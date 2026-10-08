package io.github.ioannes78.voica.ai

import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPipelineTest {
    @Test
    fun cloudPayloadUsesStableRefsWithoutInternalIds() {
        val input = transcriptInput()
        val payload = TranscriptPayloadFormatter.format(input)

        assertTrue(payload.contains("[S00001]"))
        assertTrue(payload.contains("张三"))
        assertFalse(payload.contains("recording-secret-id"))
        assertFalse(payload.contains("transcription-secret-id"))
        assertFalse(payload.contains("span-internal-id"))
    }

    @Test
    fun structuredOutputRejectsUnknownEvidenceAndTranscriptStatementWithoutEvidence() {
        val unknown =
            runCatching {
                SummaryResultCodec.decode(
                    validJson("S99999"),
                    allowedEvidenceRefs = setOf("S00001"),
                )
            }
        assertTrue(unknown.isFailure)

        val noEvidence =
            runCatching {
                SummaryResultCodec.decode(
                    validJson(ref = null),
                    allowedEvidenceRefs = setOf("S00001"),
                )
            }
        assertTrue(noEvidence.isFailure)
    }

    @Test
    fun oversizedUnitSplitsWithoutInventingChildTimestampsOrRefs() {
        val input = transcriptInput(longText = "甲".repeat(200))
        val unit = input.units.single()
        val sourceEvidence = checkNotNull(unit.evidence)
        val chunks =
            SummaryChunkPlanner(CharacterEstimator()).plan(
                input = input,
                targetChunkTokens = 30,
            )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.evidenceRefs == setOf("S00001") })
        assertTrue(
            chunks.flatMap { it.units }.all {
                val childEvidence = checkNotNull(it.evidence)
                childEvidence.startSampleIndex == sourceEvidence.startSampleIndex &&
                    childEvidence.endSampleIndexExclusive == sourceEvidence.endSampleIndexExclusive
            },
        )
    }

    @Test
    fun directGenerationReturnsValidatedStructuredResult() =
        runTest {
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(
                                    LlmGenerationResponse(
                                        "r1",
                                        validJson("S00001"),
                                        LlmUsage(10, 5, 15),
                                    ),
                                ),
                            ),
                        ),
                )
            var nextId = 0
            val engine =
                AiSummaryEngine(
                    idFactory = { "req-" + (++nextId) },
                    sleeper = {},
                )
            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                    ),
                )

            assertEquals(AiContentType.MEETING, output.result.contentType)
            assertEquals(1, output.providerCallCount)
            assertEquals(15L, output.usage?.totalTokens)
            assertEquals("S00001", output.result.sections.single().items.single().evidenceRefs.single())
        }

    @Test
    fun malformedJsonGetsOneBoundedRepair() =
        runTest {
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(LlmGenerationResponse("a", "not-json", null)),
                                Result.success(
                                    LlmGenerationResponse(
                                        "b",
                                        validJson("S00001"),
                                        null,
                                    ),
                                ),
                            ),
                        ),
                )
            var nextId = 0
            val engine =
                AiSummaryEngine(
                    idFactory = { "req-" + (++nextId) },
                    sleeper = {},
                )

            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                    ),
                )

            assertEquals(2, output.providerCallCount)
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests[1].taskInstruction.contains("Repair"))
        }

    @Test
    fun invalidEvidenceUsesTargetedRepairWithAllowedRefs() =
        runTest {
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(
                                    LlmGenerationResponse(
                                        requestId = "bad-evidence",
                                        content = validJson("S99999"),
                                        usage = null,
                                        finishReason = "stop",
                                    ),
                                ),
                                Result.success(
                                    LlmGenerationResponse(
                                        requestId = "repaired",
                                        content = validJson("S00001"),
                                        usage = null,
                                        finishReason = "stop",
                                    ),
                                ),
                            ),
                        ),
                )
            val engine =
                AiSummaryEngine(
                    idFactory = { "repair-" + provider.requests.size },
                    sleeper = {},
                )

            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                    ),
                )

            assertEquals(2, output.providerCallCount)
            assertEquals(2, provider.requests.size)
            assertTrue(
                provider.requests[1].taskInstruction.contains(
                    SummaryStructuredOutputErrorCode.INVALID_EVIDENCE_REF.name,
                ),
            )
            assertTrue(provider.requests[1].taskInstruction.contains("S00001"))
            assertFalse(provider.requests[1].taskInstruction.contains("S99999,"))
        }

    @Test
    fun lengthFinishReasonRetriesWithLargerOutputBudgetBeforeRepair() =
        runTest {
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(
                                    LlmGenerationResponse(
                                        requestId = "cut-off",
                                        content = "{\"schemaVersion\":1",
                                        usage = null,
                                        finishReason = "length",
                                    ),
                                ),
                                Result.success(
                                    LlmGenerationResponse(
                                        requestId = "complete",
                                        content = validJson("S00001"),
                                        usage = null,
                                        finishReason = "stop",
                                    ),
                                ),
                            ),
                        ),
                )
            val engine =
                AiSummaryEngine(
                    idFactory = { "length-" + provider.requests.size },
                    sleeper = {},
                )

            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                    ),
                )

            assertEquals(2, output.providerCallCount)
            assertEquals(2, provider.requests.size)
            val firstBudget = provider.requests[0].maxOutputTokens ?: 0
            val secondBudget = provider.requests[1].maxOutputTokens ?: 0
            assertTrue(secondBudget > firstBudget)
            assertFalse(provider.requests[1].taskInstruction.contains("Repair"))
        }


    @Test
    fun durableRemoteGateRunsBeforeProviderAndLeavesDirectResultPendingUntilCommit() =
        runTest {
            val events = mutableListOf<String>()
            val gate = RecordingRemoteCallGate(events)
            val provider =
                object : TextLlmProvider {
                    override suspend fun capabilities(profile: ProviderProfile) =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        )

                    override suspend fun discoverModels(profile: ProviderProfile) =
                        Result.success(emptyList<ProviderModel>())

                    override suspend fun testConnection(profile: ProviderProfile) =
                        ConnectionTestResult(true, capabilities(profile))

                    override suspend fun generate(
                        profile: ProviderProfile,
                        request: LlmGenerationRequest,
                    ): Result<LlmGenerationResponse> {
                        events += "provider:${request.requestId}"
                        return Result.success(
                            LlmGenerationResponse(
                                requestId = request.requestId,
                                content = validJson("S00001"),
                                usage = null,
                            ),
                        )
                    }

                    override fun cancel(requestId: String) = Unit
                }
            val engine = AiSummaryEngine(idFactory = { "durable-1" }, sleeper = {})

            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                        remoteCallGate = gate,
                    ),
                )

            assertEquals(
                listOf(
                    "begin:durable-1:DIRECT:direct:replaces=null",
                    "provider:durable-1",
                ),
                events,
            )
            assertEquals("durable-1", output.pendingRemoteRequestId)
        }

    @Test
    fun generationFailureIsNotAutomaticallyReplayedAfterDurableDispatch() =
        runTest {
            var providerCalls = 0
            val gate = RecordingRemoteCallGate(mutableListOf())
            val timeout =
                ProviderFailure(
                    code = ProviderErrorCode.TIMEOUT,
                    sanitizedMessage = "timeout",
                    retryable = true,
                )
            val provider =
                object : TextLlmProvider {
                    override suspend fun capabilities(profile: ProviderProfile) =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        )

                    override suspend fun discoverModels(profile: ProviderProfile) =
                        Result.success(emptyList<ProviderModel>())

                    override suspend fun testConnection(profile: ProviderProfile) =
                        ConnectionTestResult(true, capabilities(profile))

                    override suspend fun generate(
                        profile: ProviderProfile,
                        request: LlmGenerationRequest,
                    ): Result<LlmGenerationResponse> {
                        providerCalls++
                        return Result.failure(TestProviderFailure(timeout))
                    }

                    override fun cancel(requestId: String) = Unit
                }
            var requestOrdinal = 0
            val engine =
                AiSummaryEngine(
                    idFactory = { "no-replay-${++requestOrdinal}" },
                    sleeper = {},
                )

            val result =
                runCatching {
                    engine.generate(
                        provider,
                        AiSummaryEngineRequest(
                            input = transcriptInput(),
                            profile = profile(),
                            mode = AiSummaryMode.SMART,
                            template = SummaryTemplateCatalog.smart(),
                            remoteCallGate = gate,
                        ),
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(1, providerCalls)
            assertEquals(1, requestOrdinal)
        }

    @Test
    fun structuredRepairReplacesPreviousInFlightRequestWithoutSafeReplayGap() =
        runTest {
            val events = mutableListOf<String>()
            val gate = RecordingRemoteCallGate(events)
            val provider =
                QueueProvider(
                    capabilities =
                        ProviderCapabilities(
                            supportsJsonSchema = true,
                            contextWindowTokens = 100_000,
                            maxOutputTokens = 4_096,
                        ),
                    responses =
                        ArrayDeque(
                            listOf(
                                Result.success(LlmGenerationResponse("first", "not-json", null)),
                                Result.success(
                                    LlmGenerationResponse(
                                        "second",
                                        validJson("S00001"),
                                        null,
                                    ),
                                ),
                            ),
                        ),
                )
            var requestOrdinal = 0
            val engine =
                AiSummaryEngine(
                    idFactory = { "repair-${++requestOrdinal}" },
                    sleeper = {},
                )

            val output =
                engine.generate(
                    provider,
                    AiSummaryEngineRequest(
                        input = transcriptInput(),
                        profile = profile(),
                        mode = AiSummaryMode.SMART,
                        template = SummaryTemplateCatalog.smart(),
                        remoteCallGate = gate,
                    ),
                )

            assertEquals(
                listOf(
                    "begin:repair-1:DIRECT:direct:replaces=null",
                    "begin:repair-2:REPAIR:direct:repair:1:replaces=repair-1",
                ),
                events,
            )
            assertEquals("repair-2", output.pendingRemoteRequestId)
            assertEquals(2, provider.requests.size)
        }

    @Test
    fun retryPolicyOnlyRetriesDeclaredTransientFailures() {
        val policy = ProviderRetryPolicy(maxAttempts = 3, baseDelayMs = 100)
        assertTrue(
            policy.shouldRetry(
                ProviderFailure(
                    ProviderErrorCode.RATE_LIMITED,
                    "429",
                    retryable = true,
                ),
                attempt = 1,
            ),
        )
        assertFalse(
            policy.shouldRetry(
                ProviderFailure(
                    ProviderErrorCode.AUTHENTICATION_FAILED,
                    "401",
                    retryable = false,
                ),
                attempt = 1,
            ),
        )
        assertFalse(
            policy.shouldRetry(
                ProviderFailure(
                    ProviderErrorCode.CONTEXT_LIMIT_EXCEEDED,
                    "context",
                    retryable = false,
                ),
                attempt = 1,
            ),
        )
    }

    private fun transcriptInput(
        longText: String = "请确认项目方案并在周五前完成。",
    ): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = "recording-secret-id",
            transcriptionId = "transcription-secret-id",
            transcriptionMode = "FAST",
            canonicalAssetId = "canonical-secret-id",
            canonicalSha256 = "a".repeat(64),
            canonicalProfileId = "canonical-profile",
            totalSampleCount = 160_000,
            languageConfig = "auto",
            alignmentId = "alignment-secret-id",
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = TranscriptEvidenceSourceKind.SPEAKER_SPAN,
                                sourceId = "span-internal-id",
                                speakerId = "speaker-internal-id",
                                speakerDisplayName = "张三",
                                assignmentQuality = "ASSIGNED",
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 16_000,
                                endSampleIndexExclusive = 80_000,
                            ),
                        text = longText,
                        detectedLanguage = "zh",
                    ),
                ),
        )

    private fun profile(): ProviderProfile =
        ProviderProfile(
            providerProfileId = "p1",
            presetId = ProviderPresetIds.CUSTOM_OPENAI_COMPATIBLE,
            displayName = "Test",
            baseUrl = "https://example.test/v1",
            credentialRef = "cred",
            defaultModel = "model",
            timeoutMs = 30_000,
            manualContextWindowTokens = 100_000,
            capabilityOverrides = null,
            enabled = true,
        )

    private fun validJson(ref: String?): String {
        val evidenceJson = if (ref == null) "[]" else "[\"" + ref + "\"]"
        return (
            "{" +
                "\"schemaVersion\":1," +
                "\"contentType\":\"MEETING\"," +
                "\"classificationConfidence\":0.9," +
                "\"title\":\"项目讨论\"," +
                "\"overview\":\"讨论了项目安排。\"," +
                "\"sections\":[{" +
                "\"id\":\"s1\"," +
                "\"type\":\"DECISION\"," +
                "\"label\":\"决策\"," +
                "\"items\":[{" +
                "\"id\":\"i1\"," +
                "\"text\":\"确认项目方案。\"," +
                "\"evidenceRefs\":" + evidenceJson + "," +
                "\"epistemicStatus\":\"TRANSCRIPT_STATED\"," +
                "\"attributes\":{}" +
                "}]" +
                "}]" +
                "}"
        )
    }

    private class CharacterEstimator : TokenEstimator {
        override fun estimate(text: String): Int = text.length
    }


    private class RecordingRemoteCallGate(
        private val events: MutableList<String>,
    ) : SummaryRemoteCallGate {
        override suspend fun begin(
            call: SummaryRemoteCallDescriptor,
            replacesRequestId: String?,
        ) {
            events +=
                "begin:${call.requestId}:${call.stepKind}:${call.stepKey}:replaces=$replacesRequestId"
        }

        override suspend fun resolve(requestId: String) {
            events += "resolve:$requestId"
        }
    }

    private class TestProviderFailure(
        override val failure: ProviderFailure,
    ) : RuntimeException(), ProviderFailureCarrier

    private class QueueProvider(
        private val capabilities: ProviderCapabilities,
        private val responses: ArrayDeque<Result<LlmGenerationResponse>>,
    ) : TextLlmProvider {
        val requests = mutableListOf<LlmGenerationRequest>()

        override suspend fun capabilities(profile: ProviderProfile): ProviderCapabilities =
            capabilities

        override suspend fun discoverModels(profile: ProviderProfile): Result<List<ProviderModel>> =
            Result.success(emptyList())

        override suspend fun testConnection(profile: ProviderProfile): ConnectionTestResult =
            ConnectionTestResult(true, capabilities)

        override suspend fun generate(
            profile: ProviderProfile,
            request: LlmGenerationRequest,
        ): Result<LlmGenerationResponse> {
            requests += request
            return responses.removeFirst()
        }

        override fun cancel(requestId: String) = Unit
    }
}
