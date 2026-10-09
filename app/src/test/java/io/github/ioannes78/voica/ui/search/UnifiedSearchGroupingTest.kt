package io.github.ioannes78.voica.ui.search

import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.database.SearchDocumentTypeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchGroupingTest {
    @Test
    fun `same recording filename transcript and summary collapse into one group`() {
        val results =
            listOf(
                document(
                    id = "recording:r1",
                    type = SearchDocumentTypeValue.RECORDING,
                    recordingId = "r1",
                    title = "数字化转型会议",
                ),
                document(
                    id = "transcription:t1:s1",
                    type = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                    recordingId = "r1",
                    title = "转写",
                    text = "讨论数字化转型计划",
                ),
                document(
                    id = "summary:a1:item:i1",
                    type = SearchDocumentTypeValue.SUMMARY_ITEM,
                    recordingId = "r1",
                    title = "行动项",
                    text = "确认数字化转型预算",
                ),
            )

        val groups =
            buildUnifiedSearchRecordingGroups(
                results = results,
                recordingTitles = mapOf("r1" to "数字化转型会议"),
                query = "数字化转型",
            )

        assertEquals(1, groups.size)
        assertEquals("r1", groups.single().recordingId)
        assertTrue(groups.single().fileNameMatched)
        assertEquals(1, groups.single().transcriptHits.size)
        assertEquals(1, groups.single().summaryHits.size)
    }

    @Test
    fun `recording title is preserved when only transcript matched`() {
        val groups =
            buildUnifiedSearchRecordingGroups(
                results =
                    listOf(
                        document(
                            id = "transcription:t2:s1",
                            type = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                            recordingId = "r2",
                            title = "转写",
                            text = "项目预算需要确认",
                        ),
                    ),
                recordingTitles = mapOf("r2" to "周例会 10-09"),
                query = "预算",
            )

        assertEquals("周例会 10-09", groups.single().recordingTitle)
        assertFalse(groups.single().fileNameMatched)
    }

    @Test
    fun `filename and multi-source match ranks ahead of transcript-only match`() {
        val results =
            listOf(
                document(
                    id = "recording:r1",
                    type = SearchDocumentTypeValue.RECORDING,
                    recordingId = "r1",
                    title = "数字化转型会议",
                ),
                document(
                    id = "transcription:t1:s1",
                    type = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                    recordingId = "r1",
                    text = "数字化转型",
                ),
                document(
                    id = "summary:a1:overview",
                    type = SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
                    recordingId = "r1",
                    text = "数字化转型",
                ),
                document(
                    id = "transcription:t2:s1",
                    type = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                    recordingId = "r2",
                    text = "数字化转型",
                    updatedAtMs = 9_999L,
                ),
            )

        val groups =
            buildUnifiedSearchRecordingGroups(
                results = results,
                recordingTitles =
                    mapOf(
                        "r1" to "数字化转型会议",
                        "r2" to "另一场会议",
                    ),
                query = "数字化转型",
            )

        assertEquals(listOf("r1", "r2"), groups.map { it.recordingId })
        assertTrue(groups[0].score > groups[1].score)
    }

    private fun document(
        id: String,
        type: String,
        recordingId: String,
        title: String = "内容",
        text: String = "",
        updatedAtMs: Long = 1_000L,
    ): SearchDocumentEntity =
        SearchDocumentEntity(
            rowId = 0L,
            documentId = id,
            documentType = type,
            recordingId = recordingId,
            transcriptionId = null,
            revisionId = null,
            sourceAnchorId = null,
            aiSummaryId = null,
            sectionId = null,
            itemId = null,
            folderId = null,
            tagId = null,
            displayTitle = title,
            displayText = text,
            indexTitle = title,
            indexBody = text,
            updatedAtMs = updatedAtMs,
        )
}
