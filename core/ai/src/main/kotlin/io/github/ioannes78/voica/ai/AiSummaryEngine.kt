package io.github.ioannes78.voica.ai

import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay

data class AiSummaryEngineConfig(
    val outputReserveTokens: Int = 2_048,
    val safetyMarginTokens: Int = 1_024,
    val maxOutputTokens: Int = 4_096,
    val maxRepairAttempts: Int = 2,
) {
    init {
        require(outputReserveTokens > 0)
        require(safetyMarginTokens > 0)
        require(maxOutputTokens > 0)
        require(maxRepairAttempts in 0..2)
    }
}

enum class AiSummaryEnginePhase {
    PREPARING,
    ANALYZING,
    MAPPING,
    REDUCING,
    VALIDATING,
}

data class AiSummaryEngineProgress(
    val phase: AiSummaryEnginePhase,
    val completedUnits: Int = 0,
    val totalUnits: Int = 0,
)

data class AiSummaryEngineRequest(
    val input: StructuredTranscriptInput,
    val profile: ProviderProfile,
    val mode: AiSummaryMode,
    val template: SummaryTemplateSpec,
    val checkpointStore: SummaryCheckpointStore? = null,
    val remoteCallGate: SummaryRemoteCallGate? = null,
)

data class AiSummaryEngineOutput(
    val result: AiSummaryResult,
    val structuredPayloadJson: String,
    val usage: LlmUsage?,
    val providerCallCount: Int,
    val mapChunkCount: Int,
    val pendingRemoteRequestId: String? = null,
)

class AiSummaryEngine(
    private val estimator: TokenEstimator = ConservativeTokenEstimator(),
    private val tokenBudgetPlanner: TokenBudgetPlanner = TokenBudgetPlanner(),
    private val chunkPlanner: SummaryChunkPlanner = SummaryChunkPlanner(estimator),
    private val retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(),
    private val config: AiSummaryEngineConfig = AiSummaryEngineConfig(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun generate(
        provider: TextLlmProvider,
        request: AiSummaryEngineRequest,
        onProgress: suspend (AiSummaryEngineProgress) -> Unit = {},
    ): AiSummaryEngineOutput {
        onProgress(AiSummaryEngineProgress(AiSummaryEnginePhase.PREPARING))
        val capabilities = provider.capabilities(request.profile)
        val fullPayload = TranscriptPayloadFormatter.format(request.input)
        val taskInstruction =
            SummaryPromptFactory.taskInstruction(
                mode = request.mode,
                template = request.template,
                partial = false,
            )
        val manualContextWindowTokens = request.profile.manualContextWindowTokens
        val budget =
            tokenBudgetPlanner.plan(
                TokenBudgetRequest(
                    contextWindowTokens =
                        manualContextWindowTokens
                            ?: capabilities.contextWindowTokens,
                    inputEstimatedTokens = estimator.estimate(fullPayload),
                    systemTokens = estimator.estimate(SummaryPromptFactory.systemInstruction),
                    templateTokens = estimator.estimate(taskInstruction),
                    schemaTokens = estimator.estimate(SummaryPromptFactory.resultSchemaJson),
                    outputReserveTokens = config.outputReserveTokens,
                    safetyMarginTokens = config.safetyMarginTokens,
                ),
            )
        onProgress(AiSummaryEngineProgress(AiSummaryEnginePhase.ANALYZING))

        val accumulator = UsageAccumulator()
        var providerCalls = 0
        var activeRequestId: String? = null

        fun outputLimitFor(payload: String, repairAttempt: Int = 0): Int {
            val estimatedInput = estimator.estimate(payload)
            val desired =
                when {
                    estimatedInput >= 12_000 -> 4_096
                    estimatedInput >= 4_000 -> 3_072
                    else -> 2_048
                } + (repairAttempt * 512)
            return minOf(
                config.maxOutputTokens,
                capabilities.maxOutputTokens ?: config.maxOutputTokens,
                desired,
            ).coerceAtLeast(512)
        }

        fun LlmGenerationResponse.isLengthTruncated(): Boolean =
            finishReason
                ?.lowercase()
                ?.let { it == "length" || it == "max_tokens" || it == "max_output_tokens" }
                ?: false

        var unresolvedRemoteRequestId: String? = null

        suspend fun providerCall(
            task: String,
            dataPayload: String,
            maxOutputTokens: Int,
            stepKind: String,
            stepKey: String,
        ): Pair<LlmGenerationResponse, String> {
            val requestId = newRequestId()
            request.remoteCallGate?.begin(
                call =
                    SummaryRemoteCallDescriptor(
                        requestId = requestId,
                        stepKind = stepKind,
                        stepKey = stepKey,
                    ),
                replacesRequestId = unresolvedRemoteRequestId,
            )
            unresolvedRemoteRequestId = requestId
            activeRequestId = requestId
            val response =
                try {
                    provider.generate(
                        request.profile,
                        LlmGenerationRequest(
                            requestId = requestId,
                            model = request.profile.defaultModel,
                            systemInstruction = SummaryPromptFactory.systemInstruction,
                            taskInstruction = task,
                            transcriptPayload = dataPayload,
                            structuredOutputSchema = SummaryPromptFactory.resultSchemaJson,
                            maxOutputTokens = maxOutputTokens,
                        ),
                    ).getOrThrow()
                } catch (cancelled: CancellationException) {
                    provider.cancel(requestId)
                    throw cancelled
                } finally {
                    activeRequestId = null
                }
            providerCalls++
            accumulator.add(response.usage)
            return response to requestId
        }

        suspend fun call(
            task: String,
            dataPayload: String,
            allowedEvidenceRefs: Set<String>,
            stepKind: String,
            stepKey: String,
        ): ParsedCall {
            var nextTask = task
            var nextPayload = dataPayload
            var repairAttempt = 0
            var followUpReason: String? = null
            while (true) {
                val effectiveKind = if (followUpReason == null) stepKind else "REPAIR"
                val effectiveKey =
                    followUpReason?.let { "$stepKey:$it:$repairAttempt" } ?: stepKey
                val (response, requestId) =
                    providerCall(
                        task = nextTask,
                        dataPayload = nextPayload,
                        maxOutputTokens = outputLimitFor(dataPayload, repairAttempt),
                        stepKind = effectiveKind,
                        stepKey = effectiveKey,
                    )

                if (response.isLengthTruncated()) {
                    val currentLimit = outputLimitFor(dataPayload, repairAttempt)
                    val hardLimit =
                        minOf(
                            config.maxOutputTokens,
                            capabilities.maxOutputTokens ?: config.maxOutputTokens,
                        )
                    if (
                        repairAttempt < config.maxRepairAttempts &&
                        currentLimit < hardLimit
                    ) {
                        repairAttempt++
                        followUpReason = "length"
                        continue
                    }
                    throw SummaryStructuredOutputException(
                        SummaryStructuredOutputErrorCode.TRUNCATED_JSON,
                        "provider stopped because the output token limit was reached",
                    )
                }

                onProgress(AiSummaryEngineProgress(AiSummaryEnginePhase.VALIDATING))
                val parsed =
                    runCatching {
                        SummaryResultCodec.decode(
                            response.content,
                            allowedEvidenceRefs,
                        )
                    }
                if (parsed.isSuccess) {
                    return ParsedCall(
                        result = parsed.getOrThrow(),
                        remoteRequestId = requestId,
                    )
                }

                val failure =
                    (parsed.exceptionOrNull() as? SummaryStructuredOutputException)
                        ?: SummaryStructuredOutputException(
                            SummaryStructuredOutputErrorCode.INVALID_VALUE,
                            parsed.exceptionOrNull()?.message
                                ?: "summary result failed validation",
                        )
                if (repairAttempt >= config.maxRepairAttempts) {
                    throw failure
                }

                repairAttempt++
                followUpReason = "repair"
                nextTask =
                    SummaryPromptFactory.repairInstruction(
                        failure = failure,
                        allowedEvidenceRefs = allowedEvidenceRefs,
                    )
                nextPayload = response.content
            }
        }

        suspend fun loadCheckpoint(
            level: Int,
            chunkIndex: Int,
            digest: String,
            allowedEvidenceRefs: Set<String>,
        ): AiSummaryResult? =
            request.checkpointStore
                ?.load(level, chunkIndex, digest)
                ?.let { raw ->
                    runCatching {
                        SummaryResultCodec.decode(raw, allowedEvidenceRefs)
                    }.getOrNull()
                }

        suspend fun saveCheckpoint(
            level: Int,
            chunkIndex: Int,
            digest: String,
            result: AiSummaryResult,
            sourceStartOrdinal: Int,
            sourceEndOrdinalExclusive: Int,
            startSampleIndex: Long?,
            endSampleIndexExclusive: Long?,
            remoteRequestId: String?,
        ) {
            if (startSampleIndex == null || endSampleIndexExclusive == null) {
                // An all-unanchored revision chunk has no truthful audio range. It may be sent to
                // the LLM, but it must not acquire a synthetic 00:00/0..1 evidence/checkpoint span.
                return
            }
            request.checkpointStore?.save(
                SummaryCheckpointRecord(
                    level = level,
                    chunkIndex = chunkIndex,
                    sourceStartOrdinal = sourceStartOrdinal,
                    sourceEndOrdinalExclusive = sourceEndOrdinalExclusive,
                    startSampleIndex = startSampleIndex,
                    endSampleIndexExclusive = endSampleIndexExclusive,
                    inputDigest = digest,
                    structuredResultJson = SummaryResultCodec.encode(result),
                ),
            )
            remoteRequestId?.let { resolvedRequestId ->
                request.remoteCallGate?.resolve(resolvedRequestId)
                if (unresolvedRemoteRequestId == resolvedRequestId) {
                    unresolvedRemoteRequestId = null
                }
            }
        }

        try {
            if (budget is TokenBudgetPlan.Direct) {
                val allRefs =
                    request.input.units.mapNotNullTo(LinkedHashSet()) { it.evidence?.ref }
                val direct =
                    try {
                        call(
                            task = taskInstruction,
                            dataPayload = fullPayload,
                            allowedEvidenceRefs = allRefs,
                            stepKind = "DIRECT",
                            stepKey = "direct",
                        )
                    } catch (error: Throwable) {
                        val providerFailure = (error as? ProviderFailureCarrier)?.failure
                        val structuredFailure = error as? SummaryStructuredOutputException
                        if (
                            providerFailure?.code != ProviderErrorCode.CONTEXT_LIMIT_EXCEEDED &&
                            structuredFailure?.code !=
                            SummaryStructuredOutputErrorCode.TRUNCATED_JSON
                        ) {
                            throw error
                        }
                        null
                    }
                if (direct != null) {
                    return AiSummaryEngineOutput(
                        result = direct.result,
                        structuredPayloadJson = SummaryResultCodec.encode(direct.result),
                        usage = accumulator.toUsage(),
                        providerCallCount = providerCalls,
                        mapChunkCount = 1,
                        pendingRemoteRequestId = direct.remoteRequestId,
                    )
                }
            }

            val targetChunkTokens =
                when (budget) {
                    is TokenBudgetPlan.Chunked -> budget.targetChunkTokens
                    is TokenBudgetPlan.Direct ->
                        (budget.availableInputTokens * 0.65).toInt().coerceAtLeast(1)
                }
            val chunks =
                chunkPlanner.plan(
                    input = request.input,
                    targetChunkTokens = targetChunkTokens,
                )
            val nodes = mutableListOf<SummaryNode>()
            val mapTask =
                SummaryPromptFactory.taskInstruction(
                    mode = request.mode,
                    template = request.template,
                    partial = true,
                )
            onProgress(
                AiSummaryEngineProgress(
                    AiSummaryEnginePhase.MAPPING,
                    completedUnits = 0,
                    totalUnits = chunks.size,
                ),
            )
            chunks.forEachIndexed { index, chunk ->
                val digest =
                    SummaryCheckpointDigest.compute(
                        model = request.profile.defaultModel,
                        taskInstruction = mapTask,
                        payload = chunk.payload,
                    )
                val result =
                    loadCheckpoint(
                        level = 0,
                        chunkIndex = index,
                        digest = digest,
                        allowedEvidenceRefs = chunk.evidenceRefs,
                    ) ?: call(
                        task = mapTask,
                        dataPayload = chunk.payload,
                        allowedEvidenceRefs = chunk.evidenceRefs,
                        stepKind = "MAP",
                        stepKey = "map:$index",
                    ).let { generated ->
                        saveCheckpoint(
                            level = 0,
                            chunkIndex = index,
                            digest = digest,
                            result = generated.result,
                            sourceStartOrdinal = chunk.sourceStartOrdinal,
                            sourceEndOrdinalExclusive = chunk.sourceEndOrdinalExclusive,
                            startSampleIndex = chunk.startSampleIndex,
                            endSampleIndexExclusive = chunk.endSampleIndexExclusive,
                            remoteRequestId = generated.remoteRequestId,
                        )
                        generated.result
                    }
                nodes +=
                    SummaryNode(
                        result = result,
                        sourceStartOrdinal = chunk.sourceStartOrdinal,
                        sourceEndOrdinalExclusive = chunk.sourceEndOrdinalExclusive,
                        startSampleIndex = chunk.startSampleIndex,
                        endSampleIndexExclusive = chunk.endSampleIndexExclusive,
                    )
                onProgress(
                    AiSummaryEngineProgress(
                        AiSummaryEnginePhase.MAPPING,
                        completedUnits = index + 1,
                        totalUnits = chunks.size,
                    ),
                )
            }

            var level = nodes.toList()
            var reductionLevel = 1
            while (level.size > 1) {
                onProgress(
                    AiSummaryEngineProgress(
                        AiSummaryEnginePhase.REDUCING,
                        completedUnits = 0,
                        totalUnits = level.size,
                    ),
                )
                val groups = groupForReduction(level, targetChunkTokens)
                val next = mutableListOf<SummaryNode>()
                var completed = 0
                groups.forEachIndexed { groupIndex, group ->
                    val node =
                        if (group.size == 1) {
                            group.single()
                        } else {
                            val allowed =
                                group.asSequence()
                                    .flatMap {
                                        SummaryResultCodec.evidenceRefs(it.result).asSequence()
                                    }
                                    .toCollection(LinkedHashSet())
                            val reductionTask =
                                SummaryPromptFactory.reduceInstruction(
                                    mode = request.mode,
                                    template = request.template,
                                )
                            val reductionPayload =
                                group.mapIndexed { index, child ->
                                    "CHILD_SUMMARY_" + (index + 1) + "\n" +
                                        SummaryResultCodec.encode(child.result)
                                }.joinToString("\n\n")
                            val digest =
                                SummaryCheckpointDigest.compute(
                                    model = request.profile.defaultModel,
                                    taskInstruction = reductionTask,
                                    payload = reductionPayload,
                                )
                            val sourceStart = group.minOf { it.sourceStartOrdinal }
                            val sourceEnd = group.maxOf { it.sourceEndOrdinalExclusive }
                            val sampleStarts = group.mapNotNull { it.startSampleIndex }
                            val sampleEnds = group.mapNotNull { it.endSampleIndexExclusive }
                            val sampleStart = sampleStarts.minOrNull()
                            val sampleEnd = sampleEnds.maxOrNull()
                            val result =
                                loadCheckpoint(
                                    level = reductionLevel,
                                    chunkIndex = groupIndex,
                                    digest = digest,
                                    allowedEvidenceRefs = allowed,
                                ) ?: call(
                                    task = reductionTask,
                                    dataPayload = reductionPayload,
                                    allowedEvidenceRefs = allowed,
                                    stepKind = "REDUCE",
                                    stepKey = "reduce:$reductionLevel:$groupIndex",
                                ).let { generated ->
                                    saveCheckpoint(
                                        level = reductionLevel,
                                        chunkIndex = groupIndex,
                                        digest = digest,
                                        result = generated.result,
                                        sourceStartOrdinal = sourceStart,
                                        sourceEndOrdinalExclusive = sourceEnd,
                                        startSampleIndex = sampleStart,
                                        endSampleIndexExclusive = sampleEnd,
                                        remoteRequestId = generated.remoteRequestId,
                                    )
                                    generated.result
                                }
                            SummaryNode(
                                result = result,
                                sourceStartOrdinal = sourceStart,
                                sourceEndOrdinalExclusive = sourceEnd,
                                startSampleIndex = sampleStart,
                                endSampleIndexExclusive = sampleEnd,
                            )
                        }
                    next += node
                    completed += group.size
                    onProgress(
                        AiSummaryEngineProgress(
                            AiSummaryEnginePhase.REDUCING,
                            completedUnits = completed,
                            totalUnits = level.size,
                        ),
                    )
                }
                level = next
                reductionLevel++
            }

            val result = level.single().result
            return AiSummaryEngineOutput(
                result = result,
                structuredPayloadJson = SummaryResultCodec.encode(result),
                usage = accumulator.toUsage(),
                providerCallCount = providerCalls,
                mapChunkCount = chunks.size,
                pendingRemoteRequestId = unresolvedRemoteRequestId,
            )
        } catch (cancelled: CancellationException) {
            activeRequestId?.let(provider::cancel)
            throw cancelled
        }
    }

    private fun groupForReduction(
        nodes: List<SummaryNode>,
        targetTokens: Int,
    ): List<List<SummaryNode>> {
        if (nodes.size <= 1) return listOf(nodes)
        val groups = mutableListOf<MutableList<SummaryNode>>()
        var current = mutableListOf<SummaryNode>()
        var currentTokens = 0
        nodes.forEach { node ->
            val tokens = estimator.estimate(SummaryResultCodec.encode(node.result))
            if (current.isNotEmpty() && currentTokens + tokens > targetTokens) {
                groups += current
                current = mutableListOf()
                currentTokens = 0
            }
            current += node
            currentTokens += tokens
        }
        if (current.isNotEmpty()) groups += current

        if (groups.size == nodes.size) {
            return nodes.chunked(2)
        }
        return groups
    }

    private fun newRequestId(): String =
        idFactory().also { require(it.isNotBlank()) }

    private data class ParsedCall(
        val result: AiSummaryResult,
        val remoteRequestId: String,
    )

    private data class SummaryNode(
        val result: AiSummaryResult,
        val sourceStartOrdinal: Int,
        val sourceEndOrdinalExclusive: Int,
        val startSampleIndex: Long?,
        val endSampleIndexExclusive: Long?,
    )

    private class UsageAccumulator {
        private var input: Long? = null
        private var output: Long? = null
        private var total: Long? = null
        private var seen = false

        fun add(usage: LlmUsage?) {
            if (usage == null) return
            seen = true
            input = sumNullable(input, usage.inputTokens)
            output = sumNullable(output, usage.outputTokens)
            total = sumNullable(total, usage.totalTokens)
        }

        fun toUsage(): LlmUsage? =
            if (seen) LlmUsage(input, output, total) else null

        private fun sumNullable(
            current: Long?,
            next: Long?,
        ): Long? =
            when {
                current == null && next == null -> null
                else -> (current ?: 0L) + (next ?: 0L)
            }
    }
}