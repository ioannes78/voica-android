package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.OfflineQaOutcome
import io.github.ioannes78.voica.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineQaSessionTest {
    private fun snapshot(text: String = "预算50万元") = OfflineQaSnapshot.from(StructuredTranscriptInput(
        recordingId = "r", transcriptionId = "t", inputContentDigest = "a".repeat(64),
        transcriptionMode = "HIGH_QUALITY", canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
        canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1", totalSampleCount = 100,
        languageConfig = "zh", alignmentId = null,
        units = listOf(StructuredTranscriptUnit(EvidenceSourceRef("S00001",
            TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "seg", null, null, null,
            false, false, 0, 100), text, "zh")),
    ))

    private fun answer(request: OfflineQaRequest, source: OfflineQaSnapshot) =
        OfflineQaOutcome.Answer(OfflineQaResult(OfflineQaContext(request, source, listOf("S00001")),
            OfflineQaStatus.ANSWERED, listOf(OfflineQaClaim("预算", listOf("S00001")))))

    @Test fun boundedHistoryAndDisplayAreMemoryOnly() = runBlocking {
        val s = snapshot()
        val counts = mutableListOf<Int>()
        val session = OfflineQaSession("r", CoroutineScope(coroutineContext + Dispatchers.Unconfined), { s }, { q, h ->
            counts.add(h.size)
            answer(q, s)
        })
        try {
            assertTrue(session.refresh())
            repeat(22) { assertTrue(session.submit("预算$it")) }
            assertEquals(listOf(0, 1) + List(20) { 2 }, counts)
            assertEquals(20, session.state.value.turns.size)
            assertEquals("预算2", session.state.value.turns.first().question)
            session.clear()
            assertTrue(session.state.value.turns.isEmpty())
        } finally { session.close() }
    }

    @Test fun cancelWaitsForCleanupAndRejectsLateResult() = runBlocking {
        val s = snapshot()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = OfflineQaSession("r", CoroutineScope(coroutineContext + Dispatchers.Unconfined), { s }, { q, _ ->
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        })
        try {
            assertTrue(session.refresh())
            assertTrue(session.submit("预算"))
            entered.await()
            assertFalse(session.submit("另一个问题"))
            session.cancel()
            cleaning.await()
            assertEquals(OfflineQaSessionPhase.CANCELLING, session.state.value.phase)
            assertFalse(session.submit("预算"))
            session.cancel()
            assertFalse(session.refresh())
            release.complete(Unit)
            withTimeout(5000) { while (session.state.value.phase != OfflineQaSessionPhase.READY) yield() }
            assertTrue(session.state.value.turns.isEmpty())
        } finally { release.complete(Unit); session.close() }
    }

    @Test fun changedSourceClearsOldAnswersAndRequiresRefresh() = runBlocking {
        var s = snapshot()
        val session = OfflineQaSession("r", CoroutineScope(coroutineContext + Dispatchers.Unconfined), { s }, { q, _ -> answer(q, s) })
        try {
            assertTrue(session.refresh())
            assertTrue(session.submit("预算"))
            assertEquals(1, session.state.value.turns.size)
            s = snapshot("预算60万元")
            assertTrue(session.submit("预算"))
            assertEquals(OfflineQaSessionPhase.UNAVAILABLE, session.state.value.phase)
            assertTrue(session.state.value.turns.isEmpty())
            assertTrue(session.refresh())
            assertEquals(s.source, session.state.value.source)
        } finally { session.close() }
    }

    @Test fun invalidationAndCloseDropAnswers() = runBlocking {
        val s = snapshot()
        val session = OfflineQaSession("r", CoroutineScope(coroutineContext + Dispatchers.Unconfined), { s }, { q, _ -> answer(q, s) })
        try {
            assertTrue(session.refresh())
            assertTrue(session.submit("预算"))
            session.invalidate()
            assertTrue(session.state.value.turns.isEmpty())
            assertFalse(session.submit("预算"))
            session.close()
            assertFalse(session.refresh())
            assertEquals(OfflineQaSessionPhase.CLOSED, session.state.value.phase)
        } finally { session.close() }
    }

    @Test fun invalidInputAndFailedLatestTurnDoNotReuseOldHistory() = runBlocking {
        val s = snapshot()
        val counts = mutableListOf<Int>()
        val session = OfflineQaSession("r", CoroutineScope(coroutineContext + Dispatchers.Unconfined), { s }, { q, h ->
            counts.add(h.size)
            if (q.question == "失败") OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_BUSY) else answer(q, s)
        })
        try {
            assertTrue(session.refresh())
            assertFalse(session.submit(" "))
            assertFalse(session.submit("字".repeat(513)))
            assertTrue(session.submit("预算"))
            assertTrue(session.submit("失败"))
            assertTrue(session.submit("多少钱？"))
            assertEquals(listOf(0,1,0), counts)
        } finally { session.close() }
    }
}
