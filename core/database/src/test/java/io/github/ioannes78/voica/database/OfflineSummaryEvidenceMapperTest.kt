package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.AiContentType
import io.github.ioannes78.voica.ai.AiEpistemicStatus
import io.github.ioannes78.voica.ai.AiSummaryItem
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.AiSummarySection
import io.github.ioannes78.voica.ai.AiSummarySectionType
import io.github.ioannes78.voica.ai.EvidenceSourceRef
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryEvidenceMapperTest {
    @Test
    fun mapsOnlyApplicationOwnedEvidenceCoordinates() {
        val input = input()
        val result = result(listOf("S00001"))

        val mapped = OfflineSummaryEvidenceMapper.map(input, result)

        assertEquals(1, mapped.size)
        with(mapped.single()) {
            assertEquals("item-1", summaryItemId)
            assertEquals("S00001", sourceRef)
            assertEquals(TranscriptEvidenceSourceKind.SPEAKER_SPAN, sourceKind)
            assertEquals("span-1", sourceId)
            assertEquals(1_000L, startSampleIndex)
            assertEquals(8_000L, endSampleIndexExclusive)
            assertEquals("speaker-1", speakerId)
            assertEquals("ASSIGNED", assignmentQuality)
        }
    }

    @Test
    fun unknownOrNoEvidenceUnitCannotCreateCanonicalCoordinates() {
        val failure =
            runCatching {
                OfflineSummaryEvidenceMapper.map(input(), result(listOf("S99999")))
            }.exceptionOrNull()

        assertTrue(failure is OfflineSummaryEvidenceMappingException)
        assertEquals(setOf("S99999"), (failure as OfflineSummaryEvidenceMappingException).unknownEvidenceRefs)
    }

    @Test
    fun noEvidenceSemanticUnitDoesNotAppearInEvidenceAllowlist() {
        val input = input()

        val mapped = OfflineSummaryEvidenceMapper.map(input, result(emptyList()))

        assertTrue(mapped.isEmpty())
        assertEquals("人工补充但无可靠时间锚点", input.units.last().text)
        assertEquals("speaker-manual", input.units.last().effectiveSpeakerId)
        assertEquals(null, input.units.last().evidence)
    }

    @Test
    fun sameEvidenceMaySupportMultipleSummaryItemsWithoutInventingCoordinates() {
        val input = input()
        val result =
            AiSummaryResult(
                schemaVersion = 1,
                contentType = AiContentType.MEETING,
                classificationConfidence = null,
                title = "标题",
                overview = "概述",
                sections =
                    listOf(
                        AiSummarySection(
                            id = "section-1",
                            type = AiSummarySectionType.SUMMARY,
                            label = "总结",
                            items =
                                listOf(
                                    item("item-1", listOf("S00001")),
                                    item("item-2", listOf("S00001")),
                                ),
                        ),
                    ),
            )

        val mapped = OfflineSummaryEvidenceMapper.map(input, result)

        assertEquals(2, mapped.size)
        assertEquals(setOf("item-1", "item-2"), mapped.map { it.summaryItemId }.toSet())
        assertTrue(mapped.all { it.startSampleIndex == 1_000L && it.endSampleIndexExclusive == 8_000L })
    }

    private fun input(): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = "recording",
            transcriptionId = "transcription",
            transcriptionRevisionId = "revision",
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "profile",
            totalSampleCount = 16_000L,
            languageConfig = "zh",
            alignmentId = "alignment",
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = TranscriptEvidenceSourceKind.SPEAKER_SPAN,
                                sourceId = "span-1",
                                speakerId = "speaker-1",
                                speakerDisplayName = "甲",
                                assignmentQuality = "ASSIGNED",
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 1_000L,
                                endSampleIndexExclusive = 8_000L,
                            ),
                        text = "锚定正文",
                        detectedLanguage = "zh",
                        speakerId = "speaker-1",
                        speakerDisplayName = "甲",
                    ),
                    StructuredTranscriptUnit(
                        evidence = null,
                        text = "人工补充但无可靠时间锚点",
                        detectedLanguage = "zh",
                        speakerId = "speaker-manual",
                        speakerDisplayName = "乙",
                    ),
                ),
        )

    private fun result(refs: List<String>): AiSummaryResult =
        AiSummaryResult(
            schemaVersion = 1,
            contentType = AiContentType.MEETING,
            classificationConfidence = null,
            title = "标题",
            overview = "概述",
            sections =
                listOf(
                    AiSummarySection(
                        id = "section-1",
                        type = AiSummarySectionType.SUMMARY,
                        label = "总结",
                        items = listOf(item("item-1", refs)),
                    ),
                ),
        )

    private fun item(id: String, refs: List<String>) =
        AiSummaryItem(
            id = id,
            text = "内容-$id",
            evidenceRefs = refs,
            epistemicStatus =
                if (refs.isEmpty()) AiEpistemicStatus.AI_SYNTHESIS else AiEpistemicStatus.TRANSCRIPT_STATED,
        )
}
