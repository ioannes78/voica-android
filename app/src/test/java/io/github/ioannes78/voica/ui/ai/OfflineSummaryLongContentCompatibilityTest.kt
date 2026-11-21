package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.ai.AiContentType
import io.github.ioannes78.voica.ai.AiEpistemicStatus
import io.github.ioannes78.voica.ai.AiSummaryItem
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.AiSummaryRevisionCodec
import io.github.ioannes78.voica.ai.AiSummaryRevisionEditor
import io.github.ioannes78.voica.ai.AiSummaryRevisionProvenance
import io.github.ioannes78.voica.ai.AiSummarySection
import io.github.ioannes78.voica.ai.AiSummarySectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 17.3 P6 product compatibility for the common post-persistence content surface.
 *
 * OfflineSummaryLongCompatibilityRegressionTest proves Long FINAL is persisted through the same
 * AiSummaryResult/AiSummaryEvidence entities as Direct. This test locks the downstream consumers of
 * that common result: revision provenance plus the single TextContentDocument used by Copy, Share,
 * TXT export and Markdown export.
 */
class OfflineSummaryLongContentCompatibilityTest {
    @Test
    fun longFinalShapeSupportsRevisionProvenanceAndSharedExportDocument() {
        val original = result()
        var revision = AiSummaryRevisionCodec.fromOriginal(original)

        revision =
            AiSummaryRevisionEditor.editItem(
                source = revision,
                itemId = "item-1",
                text = "人工修订：周四前完成全部测试。",
            )
        revision =
            AiSummaryRevisionEditor.addItem(
                source = revision,
                sectionId = "section-1",
                text = "人工新增：同步发布说明。",
                idFactory = { "user-item" },
            )

        val decoded = AiSummaryRevisionCodec.decode(AiSummaryRevisionCodec.encode(revision))
        val edited = decoded.sections.single().items.first { it.id == "item-1" }
        val added = decoded.sections.single().items.first { it.id == "user-item" }

        assertEquals(AiSummaryRevisionProvenance.USER_EDITED, edited.provenance)
        assertEquals("item-1", edited.sourceItemId)
        assertEquals(listOf("S00001"), edited.sourceEvidenceRefs)
        assertEquals(AiSummaryRevisionProvenance.USER_ADDED, added.provenance)
        assertEquals(null, added.sourceItemId)
        assertTrue(added.sourceEvidenceRefs.isEmpty())

        val export =
            AiSummaryContentExportFormatter.build(
                recordingName = "兼容回归录音",
                document = decoded,
                modelName = "Gemma 4 E2B · 本地",
            )

        assertEquals("长文本兼容总结", export.baseName)
        assertTrue(export.plainText.contains("模型：Gemma 4 E2B · 本地"))
        assertTrue(export.plainText.contains("确认测试与发布安排。"))
        assertTrue(export.plainText.contains("人工修订：周四前完成全部测试。 [人工修改]"))
        assertTrue(export.plainText.contains("人工新增：同步发布说明。 [人工新增]"))
        assertTrue(export.markdownText.startsWith("# 长文本兼容总结"))
        assertTrue(export.markdownText.contains("## 行动项"))
        assertTrue(export.markdownText.contains("[人工修改]"))
        assertTrue(export.markdownText.contains("[人工新增]"))
        assertFalse(export.plainText.contains("S00001"))
    }

    private fun result(): AiSummaryResult =
        AiSummaryResult(
            schemaVersion = 1,
            contentType = AiContentType.MEETING,
            classificationConfidence = 0.91,
            title = "长文本兼容总结",
            overview = "确认测试与发布安排。",
            sections =
                listOf(
                    AiSummarySection(
                        id = "section-1",
                        type = AiSummarySectionType.ACTION_ITEM,
                        label = "行动项",
                        items =
                            listOf(
                                AiSummaryItem(
                                    id = "item-1",
                                    text = "周四前完成测试。",
                                    evidenceRefs = listOf("S00001"),
                                    epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                                ),
                            ),
                    ),
                ),
        )
}
