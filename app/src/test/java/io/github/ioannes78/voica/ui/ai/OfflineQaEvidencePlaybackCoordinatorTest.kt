package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.OfflineQaOutcome
import io.github.ioannes78.voica.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineQaEvidencePlaybackCoordinatorTest {
    private fun snapshot(text: String = "预算50万元") = OfflineQaSnapshot.from(StructuredTranscriptInput(
        recordingId = "r", transcriptionId = "t", inputContentDigest = "a".repeat(64),
        transcriptionMode = "HIGH_QUALITY", canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
        canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1", totalSampleCount = 100,
        languageConfig = "zh", alignmentId = null,
        units = listOf(StructuredTranscriptUnit(EvidenceSourceRef("S00001",
            TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "seg", null, null, null,
            false, false, 10, 90), text, "zh")),
    ))
    private fun turn(s: OfflineQaSnapshot): OfflineQaSessionTurn {
        val q = OfflineQaRequest(s.source, "预算")
        return OfflineQaSessionTurn(q.question, OfflineQaOutcome.Answer(OfflineQaResult(
            OfflineQaContext(q, s, listOf("S00001")), OfflineQaStatus.ANSWERED,
            listOf(OfflineQaClaim("预算50万元", listOf("S00001"))))))
    }
    private fun state(s: OfflineQaSnapshot, t: OfflineQaSessionTurn) =
        OfflineQaSessionState(OfflineQaSessionPhase.READY, s.source, listOf(t))

    @Test fun exactEvidenceUsesCanonicalIdentityAndRange() = runBlocking {
        val s = snapshot(); val t = turn(s)
        var calls = 0
        val c = OfflineQaEvidencePlaybackCoordinator({ s }, { e, check ->
            calls++
            assertEquals("asset", e.assetId); assertEquals(s.source.canonicalSha256, e.sha256)
            assertEquals(10L, e.startSampleIndex); assertEquals(90L, e.endSampleIndexExclusive)
            check()
        })
        assertEquals(QaEvidencePlayOutcome.STARTED, c.play(t, "S00001") { state(s,t) })
        assertEquals(1, calls)
        assertEquals(QaEvidencePlayOutcome.REJECTED, c.play(t, "S99999") { state(s,t) })
        assertEquals(1, calls)
    }

    @Test fun staleSnapshotOrRemovedTurnNeverStartsPlayback() = runBlocking {
        val s = snapshot(); val t = turn(s)
        val c = OfflineQaEvidencePlaybackCoordinator({ snapshot("预算60万元") }, { _, _ -> error("no play") })
        assertEquals(QaEvidencePlayOutcome.STALE, c.play(t, "S00001") { state(s,t) })
        val same = OfflineQaEvidencePlaybackCoordinator({ s }, { _, _ -> error("no play") })
        assertEquals(QaEvidencePlayOutcome.STALE, same.play(t, "S00001") { state(s,t).copy(turns = emptyList()) })
    }

    @Test fun duplicateAndLateInvalidationRejectedAndCancellationReleasesGate() = runBlocking {
        val s = snapshot(); val t = turn(s)
        var current = state(s,t)
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val c = OfflineQaEvidencePlaybackCoordinator({ s }, { _, check ->
            entered.complete(Unit); resume.await(); check()
        })
        val first = async { c.play(t, "S00001") { current } }
        entered.await()
        assertEquals(QaEvidencePlayOutcome.BUSY, c.play(t, "S00001") { current })
        current = current.copy(turns = emptyList())
        resume.complete(Unit)
        assertEquals(QaEvidencePlayOutcome.STALE, first.await())
        current = state(s,t)
        assertEquals(QaEvidencePlayOutcome.STARTED, c.play(t, "S00001") { current })
    }

    @Test fun playbackPortRejectionIsNotReportedAsSuccess() = runBlocking {
        val s = snapshot(); val t = turn(s)
        val c = OfflineQaEvidencePlaybackCoordinator({ s }, { _, _ -> false })
        assertEquals(QaEvidencePlayOutcome.REJECTED, c.play(t, "S00001") { state(s,t) })
    }
}
