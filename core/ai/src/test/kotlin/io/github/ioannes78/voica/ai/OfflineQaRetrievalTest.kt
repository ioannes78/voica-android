package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQaRetrievalTest {
    private fun snapshot(vararg texts: String): OfflineQaSnapshot = OfflineQaSnapshot.from(
        StructuredTranscriptInput(
            recordingId = "recording", transcriptionId = "transcript",
            inputContentDigest = "a".repeat(64), transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = texts.size * 100L, languageConfig = "zh", alignmentId = null,
            units = texts.mapIndexed { i, text ->
                StructuredTranscriptUnit(
                    EvidenceSourceRef(
                        "S" + (i + 1).toString().padStart(5, '0'),
                        TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "segment-$i",
                        if (i % 2 == 0) "a" else "b", null, null, false, false,
                        i * 100L, (i + 1) * 100L,
                    ), text, "zh",
                )
            },
        ),
    )

    @Test
    fun chineseQuestionFindsRelevantPhraseAndNeighbor() {
        val source = snapshot("天气很好", "项目预算五十万元", "财务负责审批", "设备运行正常")
        val result = OfflineQaRetrieval().retrieve(OfflineQaRequest(source.source, "项目预算是多少？"), source)
        assertEquals(listOf("S00002", "S00001", "S00003"), result.candidates.map { it.anchor.ref })
    }

    @Test
    fun normalizedMixedTextAndNumericMatchRankFirst() {
        val source = snapshot("ERP预算100万元", "erp预算200万元", "设备200台")
        val result = OfflineQaRetrieval(OfflineQaRetrievalConfig(neighborRadius = 0))
            .retrieve(OfflineQaRequest(source.source, "ＥＲＰ预算２００万元"), source)
        assertEquals("S00002", result.candidates.first().anchor.ref)
        assertTrue("S00003" !in result.allowedRefs)
    }

    @Test
    fun noMatchAndNumberOnlyReturnEmpty() {
        val source = snapshot("预算50万元", "设备正常")
        val retrieval = OfflineQaRetrieval()
        assertTrue(retrieval.retrieve(OfflineQaRequest(source.source, "火星殖民"), source).candidates.isEmpty())
        assertTrue(retrieval.retrieve(OfflineQaRequest(source.source, "50"), source).candidates.isEmpty())
    }

    @Test
    fun scopeAppliesToSeedsAndNeighbors() {
        val source = snapshot("预算方案", "预算方案", "预算方案", "其他内容")
        val request = OfflineQaRequest(source.source, "预算", speakerId = "a", sampleRange = OfflineQaSampleRange(100, 300))
        assertEquals(listOf("S00003"), OfflineQaRetrieval().retrieve(request, source).candidates.map { it.anchor.ref })
    }

    @Test
    fun tiesAreStableAndDirectMatchesPrecedeNeighbors() {
        val source = snapshot("预算方案", "其他内容", "预算方案", "其他内容")
        val request = OfflineQaRequest(source.source, "预算")
        val retrieval = OfflineQaRetrieval(OfflineQaRetrievalConfig(topK = 2))
        repeat(5) {
            assertEquals(listOf("S00001", "S00003"), retrieval.retrieve(request, source).candidates.map { it.anchor.ref })
        }
    }

    @Test
    fun rejectsDifferentSnapshotEvenWithSamePositionalIds() {
        val source = snapshot("预算方案")
        val changed = snapshot("预算取消")
        assertTrue(runCatching {
            OfflineQaRetrieval().retrieve(OfflineQaRequest(source.source, "预算"), changed)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun defaultCapAndDedupPreserveWholeExcerpts() {
        val source = snapshot(*Array(20) { "预算方案完整内容 $it" })
        val result = OfflineQaRetrieval().retrieve(OfflineQaRequest(source.source, "预算方案"), source)
        assertEquals(12, result.candidates.size)
        assertEquals(12, result.allowedRefs.size)
        result.candidates.forEach { assertEquals(source.resolve(it.anchor.ref)?.excerpt, it.excerpt) }
    }
}
