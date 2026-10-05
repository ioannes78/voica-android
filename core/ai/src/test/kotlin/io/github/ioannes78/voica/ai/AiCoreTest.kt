package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoreTest {
    @Test
    fun evidenceRefsAreDeterministicAndInvalidRefsAreRejected() {
        val generator = EvidenceRefGenerator()
        val sources =
            generator.assign(
                listOf(
                    TranscriptEvidenceSeed(
                        sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                        sourceId = "seg-1",
                        speakerId = null,
                        speakerDisplayName = null,
                        assignmentQuality = null,
                        overlap = false,
                        ambiguous = false,
                        startSampleIndex = 0,
                        endSampleIndexExclusive = 16_000,
                    ),
                    TranscriptEvidenceSeed(
                        sourceKind = TranscriptEvidenceSourceKind.SPEAKER_SPAN,
                        sourceId = "span-2",
                        speakerId = "speaker-1",
                        speakerDisplayName = "说话人 1",
                        assignmentQuality = "ASSIGNED",
                        overlap = false,
                        ambiguous = false,
                        startSampleIndex = 16_000,
                        endSampleIndexExclusive = 32_000,
                    ),
                ),
            )

        assertEquals(listOf("S00001", "S00002"), sources.map { it.ref })

        val validation = EvidenceResolver(sources).validate(listOf("S00002", "S99999"))
        assertEquals(listOf("S00002"), validation.validRefs)
        assertEquals(listOf("S99999"), validation.invalidRefs)
        assertFalse(validation.isValid)
    }

    @Test
    fun reduceCanOnlyPropagateKnownEvidenceRefs() {
        val sources =
            EvidenceRefGenerator().assign(
                listOf(
                    TranscriptEvidenceSeed(
                        TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                        "seg-1",
                        null,
                        null,
                        0,
                        8_000,
                    ),
                    TranscriptEvidenceSeed(
                        TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                        "seg-2",
                        null,
                        null,
                        8_000,
                        16_000,
                    ),
                ),
            )

        val propagated =
            EvidenceResolver(sources).propagateFromChildren(
                listOf(
                    listOf("S00001", "S00002"),
                    listOf("S00002", "S12345"),
                ),
            )

        assertEquals(listOf("S00001", "S00002"), propagated)
    }

    @Test
    fun tokenBudgetSwitchesToChunkingWithoutTrustingProviderMetadata() {
        val planner = TokenBudgetPlanner(fallbackContextWindowTokens = 4_000)
        val plan =
            planner.plan(
                TokenBudgetRequest(
                    contextWindowTokens = null,
                    inputEstimatedTokens = 5_000,
                    systemTokens = 300,
                    templateTokens = 200,
                    schemaTokens = 300,
                    outputReserveTokens = 800,
                    safetyMarginTokens = 400,
                ),
            )

        assertTrue(plan is TokenBudgetPlan.Chunked)
        assertEquals(2_000, plan.availableInputTokens)
        assertEquals(1_700, (plan as TokenBudgetPlan.Chunked).targetChunkTokens)
    }

    @Test
    fun summaryPromptDefaultsHumanReadableOutputToSimplifiedChinese() {
        val prompt =
            SummaryPromptFactory.taskInstruction(
                mode = AiSummaryMode.SMART,
                template = SummaryTemplateCatalog.smart(),
                partial = false,
            )

        assertEquals(3, SummaryPromptFactory.PROMPT_VERSION)
        assertTrue(
            SummaryPromptFactory.systemInstruction.contains(
                "write all human-readable summary content in Simplified Chinese",
            ),
        )
        assertTrue(prompt.contains("Default output language is Simplified Chinese"))
        assertTrue(
            SummaryPromptFactory.repairInstruction()
                .contains("human-readable summary content in Simplified Chinese"),
        )
        assertTrue(SummaryPromptFactory.systemInstruction.contains("[NO_AUDIO_EVIDENCE]"))
        assertTrue(prompt.contains("evidenceRefs=[]"))
    }

    @Test
    fun summaryCodecAcceptsJsonCodeFenceCompatibilityWrapper() {
        val raw =
            """
            ```json
            {"schemaVersion":1,"contentType":"GENERAL","classificationConfidence":null,"title":"标题","overview":"概述","sections":[]}
            ```
            """.trimIndent()

        val decoded = SummaryResultCodec.decode(raw, emptySet())

        assertEquals("标题", decoded.title)
        assertEquals(AiContentType.GENERAL, decoded.contentType)
    }

    @Test
    fun summaryCodecAcceptsSingleJsonObjectWithHarmlessWrapperText() {
        val raw =
            """
            Here is the requested JSON:
            {"schemaVersion":1,"contentType":"GENERAL","classificationConfidence":null,"title":"标题","overview":"概述","sections":[]}
            End of response.
            """.trimIndent()

        val decoded = SummaryResultCodec.decode(raw, emptySet())

        assertEquals("标题", decoded.title)
        assertEquals(AiContentType.GENERAL, decoded.contentType)
    }

    @Test
    fun summaryCodecClassifiesIncompleteObjectAsTruncated() {
        val failure =
            runCatching {
                SummaryResultCodec.decode(
                    """{"schemaVersion":1,"contentType":"GENERAL"""",
                    emptySet(),
                )
            }.exceptionOrNull() as SummaryStructuredOutputException

        assertEquals(
            SummaryStructuredOutputErrorCode.TRUNCATED_JSON,
            failure.code,
        )
    }

    @Test
    fun structuredTranscriptRejectsOutOfRangeEvidence() {
        val bad =
            runCatching {
                StructuredTranscriptInput(
                    recordingId = "rec",
                    transcriptionId = "tx",
                    transcriptionMode = "FAST",
                    canonicalAssetId = "asset",
                    canonicalSha256 = "abc",
                    canonicalProfileId = "profile",
                    totalSampleCount = 100,
                    languageConfig = "auto",
                    alignmentId = null,
                    units =
                        listOf(
                            StructuredTranscriptUnit(
                                EvidenceSourceRef(
                                    ref = "S00001",
                                    sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                    sourceId = "seg",
                                    speakerId = null,
                                    speakerDisplayName = null,
                                    assignmentQuality = null,
                                    overlap = false,
                                    ambiguous = false,
                                    startSampleIndex = 0,
                                    endSampleIndexExclusive = 101,
                                ),
                                text = "hello",
                                detectedLanguage = "en",
                            ),
                        ),
                )
            }
        assertTrue(bad.isFailure)
    }
}
