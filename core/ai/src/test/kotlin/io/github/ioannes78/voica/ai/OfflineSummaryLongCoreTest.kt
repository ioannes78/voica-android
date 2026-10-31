package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryLongCoreTest {
    @Test
    fun routerKeepsShortTranscriptDirectAndRoutesOverBudgetInputToLongPipeline() {
        val router = OfflineSummaryLongRouter()
        val short = inputWithTexts(listOf("今天确认第一版周五发布。"))
        val long = inputWithTexts(listOf("会议内容和后续行动。".repeat(4_000)))

        val shortDecision = router.decide(short)
        val longDecision = router.decide(long)

        assertEquals(OfflineSummaryRoute.DIRECT, shortDecision.route)
        assertTrue(shortDecision.directBudget.allowed)
        assertEquals(OfflineSummaryRoute.LONG, longDecision.route)
        assertFalse(longDecision.directBudget.allowed)
        assertEquals(2_528, longDecision.directBudget.maxEstimatedPromptTokens)
    }

    @Test
    fun plannerPreservesSourceOrderEvidenceAndExactTextCoverage() {
        val input =
            inputWithTexts(
                listOf(
                    "第一位说话人确认周五发布。",
                    "第二位说话人要求周四完成测试。",
                    "最后确认负责人会同步风险。",
                ),
            )

        val plan = OfflineSummaryChunkPlanner().plan(input)
        val fragments = plan.chunks.flatMap { it.fragments }

        assertEquals(OfflineSummaryLongContract.PIPELINE_VERSION, plan.pipelineVersion)
        assertEquals(OfflineSummaryLongContract.PLANNER_VERSION, plan.plannerVersion)
        assertEquals(input.inputContentDigest, plan.inputContentDigest)
        assertEquals(listOf(0, 1, 2), fragments.map { it.sourceUnitOrdinal })
        assertEquals(listOf("U00001", "U00002", "U00003"), fragments.map { it.sourceRef })
        assertEquals(listOf("S00001", "S00002", "S00003"), fragments.map { it.evidenceRef })
        assertTrue(plan.chunks.all {
            it.estimatedPromptTokens <= OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS
        })
    }

    @Test
    fun oversizedSingleUnitUsesUnicodeSafeFallbackWithoutDroppingText() {
        val original = "第一句😀。第二句包含很多内容；仍然要完整保留？".repeat(700)
        val input = inputWithTexts(listOf(original))

        val plan = OfflineSummaryChunkPlanner().plan(input)
        val fragments = plan.chunks.flatMap { it.fragments }
        val reconstructed = fragments.joinToString(separator = "") { it.text }

        assertTrue(fragments.size > 1)
        assertEquals(original, reconstructed)
        assertTrue(fragments.all { it.sourceRef.matches(Regex("U00001:F\\d{3,}")) })
        assertTrue(fragments.all { !it.text.contains('\uFFFD') })
        assertTrue(plan.chunks.all {
            it.estimatedPromptTokens <= OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS
        })
    }

    @Test
    fun intermediateCodecRoundTripsValidatedBoundedContent() {
        val raw =
            """
            {
              "schemaVersion":1,
              "synopsis":"本段确认发布与测试安排。",
              "items":[
                {
                  "id":"fact1",
                  "type":"DECISION",
                  "text":"第一版周五发布。",
                  "sourceRefs":["U00001"],
                  "evidenceRefs":["S00001"],
                  "epistemicStatus":"TRANSCRIPT_STATED"
                }
              ]
            }
            """.trimIndent()

        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                raw = raw,
                allowedSourceRefs = setOf("U00001"),
                allowedEvidenceRefs = setOf("S00001"),
            )
        val roundTrip =
            OfflineSummaryIntermediateCodec.decode(
                raw = OfflineSummaryIntermediateCodec.encode(decoded),
                allowedSourceRefs = setOf("U00001"),
                allowedEvidenceRefs = setOf("S00001"),
            )

        assertEquals(decoded, roundTrip)
        assertEquals(OfflineSummaryIntermediateItemType.DECISION, decoded.items.single().type)
    }

    @Test
    fun intermediateCodecRejectsNonsenseSynopsis() {
        val raw =
            """{"schemaVersion":1,"synopsis":[{","items":[{"id":"i1","type":"FACT","text":"有效事实","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}]}"""

        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = setOf("U00001"),
                    allowedEvidenceRefs = emptySet(),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INVALID_SYNOPSIS, failure.code)
    }

    @Test
    fun generationCodecRejectsFrontLoadedCoverageForLongInput() {
        val allowed =
            offlineSummaryGenerationSourceRefs(
                (1..9).map { "U" + it.toString().padStart(5, '0') },
            )
        val raw =
            """{"schemaVersion":1,"synopsis":"本段包含多项重要事实。","items":[{"id":"i1","type":"FACT","text":"只引用了开头内容。","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"},{"id":"i2","type":"FACT","text":"仍然只引用开头。","sourceRefs":["U00002"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}]}"""

        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = allowed,
                    allowedEvidenceRefs = emptySet(),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE, failure.code)
    }

    @Test
    fun generationCodecAcceptsDistributedCoverageForLongInput() {
        val allowed =
            offlineSummaryGenerationSourceRefs(
                (1..9).map { "U" + it.toString().padStart(5, '0') },
            )
        val raw =
            """{"schemaVersion":1,"synopsis":"本段提炼了前中后主要信息。","items":[{"id":"i1","type":"KEY_POINT","text":"开头的重要信息。","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"},{"id":"i2","type":"KEY_POINT","text":"中部的重要信息。","sourceRefs":["U00004"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"},{"id":"i3","type":"KEY_POINT","text":"结尾的重要信息。","sourceRefs":["U00007"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}]}"""

        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                raw = raw,
                allowedSourceRefs = allowed,
                allowedEvidenceRefs = emptySet(),
            )

        assertEquals(3, decoded.items.size)
    }

    @Test
    fun checkpointCodecDoesNotReapplyGenerationCoverageAgainstFullLineage() {
        val lineage = (1..9).mapTo(LinkedHashSet()) { "U" + it.toString().padStart(5, '0') }
        val raw =
            """{"schemaVersion":1,"synopsis":"检查点只保留压缩后的有效信息。","items":[{"id":"i1","type":"FACT","text":"压缩后的首项事实。","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"},{"id":"i2","type":"FACT","text":"压缩后的第二项事实。","sourceRefs":["U00002"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}]}"""

        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                raw = raw,
                allowedSourceRefs = lineage,
                allowedEvidenceRefs = emptySet(),
            )

        assertEquals(listOf("U00001", "U00002"), decoded.items.flatMap { it.sourceRefs })
    }

    @Test
    fun intermediateCodecFailsClosedOnUnknownSourceOrEvidenceRefs() {
        fun raw(sourceRef: String, evidenceRef: String) =
            """{"schemaVersion":1,"synopsis":"摘要内容","items":[{"id":"i1","type":"FACT","text":"事实","sourceRefs":["$sourceRef"],"evidenceRefs":["$evidenceRef"],"epistemicStatus":"TRANSCRIPT_STATED"}]}"""

        val sourceFailure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw("U99999", "S00001"),
                    allowedSourceRefs = setOf("U00001"),
                    allowedEvidenceRefs = setOf("S00001"),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException
        val evidenceFailure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw("U00001", "S99999"),
                    allowedSourceRefs = setOf("U00001"),
                    allowedEvidenceRefs = setOf("S00001"),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INVALID_SOURCE_REF, sourceFailure.code)
        assertEquals(OfflineSummaryIntermediateErrorCode.INVALID_EVIDENCE_REF, evidenceFailure.code)
    }

    @Test
    fun intermediateSchemaAndPromptsAreVersionedAndBounded() {
        val schema =
            OfflineSummaryIntermediateResponseFormat.jsonSchema(
                allowedSourceRefs = setOf("U00001"),
                allowedEvidenceRefs = setOf("S00001"),
            )
        val fragment =
            OfflineSummaryLongSourceFragment(
                sourceRef = "U00001",
                sourceUnitOrdinal = 0,
                fragmentOrdinal = null,
                text = "周五发布。",
                evidenceRef = "S00001",
                speakerDisplayName = "说话人 1",
                detectedLanguage = "zh",
            )
        val map = OfflineSummaryMapPromptRenderer().render(listOf(fragment))
        val node = validatedNode("N1", "U00001", "S00001")
        val reduce = OfflineSummaryReducePromptRenderer().render(listOf(node))
        val final = OfflineSummaryFinalPromptRenderer().render(listOf(node))

        assertTrue(schema.contains("\"maxItems\":${OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS}"))
        assertTrue(schema.contains("\"maxLength\":${OfflineSummaryLongContract.MAX_INTERMEDIATE_SYNOPSIS_CHARS}"))
        assertEquals(173103, map.promptVersion)
        assertEquals(173204, reduce.promptVersion)
        assertEquals(173303, final.promptVersion)
        assertEquals(OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION, map.resultSchemaVersion)
        assertEquals(OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION, final.resultSchemaVersion)
        assertTrue(map.text.contains("前、中、后三部分"))
        assertTrue(map.text.contains("error=INSUFFICIENT_SOURCE_COVERAGE"))
        assertTrue(map.text.contains("error=INVALID_SYNOPSIS"))
        assertTrue(reduce.text.contains("所有 NODE"))
        assertTrue(reduce.text.contains("error=INSUFFICIENT_SOURCE_COVERAGE"))
        assertTrue(reduce.text.contains("error=INVALID_SYNOPSIS"))
        assertTrue(final.text.contains("综合所有 NODE"))
        assertTrue(final.text.contains("不得生成时间戳"))
    }

    @Test
    fun reductionPlannerKeepsOrderAndSplitsOversizedNodeSetsIntoBoundedGroups() {
        val estimator =
            object : OfflineSummaryTokenEstimator {
                override val id = "CHAR_COUNT_TEST"
                override fun estimateTokens(text: String): Int = text.length
            }
        val nodes =
            (1..10).map { index ->
                validatedNode(
                    nodeId = "N$index",
                    sourceRef = "U" + index.toString().padStart(5, '0'),
                    evidenceRef = "S" + index.toString().padStart(5, '0'),
                    synopsis = "这是一个用于层级合并预算测试的摘要内容".repeat(3),
                )
            }

        val groups = OfflineSummaryReductionPlanner(estimator = estimator).group(nodes)

        assertTrue(groups.size > 1)
        assertEquals(nodes.map { it.nodeId }, groups.flatMap { it.nodes }.map { it.nodeId })
        assertTrue(groups.all {
            it.estimatedPromptTokens <= OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS
        })
    }

    private fun validatedNode(
        nodeId: String,
        sourceRef: String,
        evidenceRef: String,
        synopsis: String = "已验证摘要",
    ): OfflineSummaryValidatedIntermediateNode {
        val result =
            OfflineSummaryIntermediate(
                schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                synopsis = synopsis.take(OfflineSummaryLongContract.MAX_INTERMEDIATE_SYNOPSIS_CHARS),
                items =
                    listOf(
                        OfflineSummaryIntermediateItem(
                            id = "i1",
                            type = OfflineSummaryIntermediateItemType.FACT,
                            text = "已验证事实",
                            sourceRefs = listOf(sourceRef),
                            evidenceRefs = listOf(evidenceRef),
                            epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                        ),
                    ),
            )
        return OfflineSummaryValidatedIntermediateNode(
            nodeId = nodeId,
            summary = result,
            sourceRefs = setOf(sourceRef),
            evidenceRefs = setOf(evidenceRef),
        )
    }

    private fun inputWithTexts(texts: List<String>): StructuredTranscriptInput {
        val sampleCount = texts.size.coerceAtLeast(1) * 16_000L
        return StructuredTranscriptInput(
            recordingId = "recording",
            transcriptionId = "transcription",
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = sampleCount,
            languageConfig = "zh",
            alignmentId = null,
            units =
                texts.mapIndexed { index, text ->
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S" + (index + 1).toString().padStart(5, '0'),
                                sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                sourceId = "segment-$index",
                                speakerId = null,
                                speakerDisplayName = null,
                                assignmentQuality = null,
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = index * 16_000L,
                                endSampleIndexExclusive = (index + 1) * 16_000L,
                            ),
                        text = text,
                        detectedLanguage = "zh",
                    )
                },
        )
    }
}
