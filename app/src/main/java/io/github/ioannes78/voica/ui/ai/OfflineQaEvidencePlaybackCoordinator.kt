package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.OfflineQaOutcome
import io.github.ioannes78.voica.ai.*
import io.github.ioannes78.voica.audio.EvidencePlaybackExpectation
import io.github.ioannes78.voica.database.OfflineQaSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal enum class QaEvidencePlayOutcome { STARTED, BUSY, STALE, REJECTED }

/** No independent player; only current, cited evidence may enter the verified playback port. */
internal class OfflineQaEvidencePlaybackCoordinator(
    private val requireCurrent: suspend (OfflineQaSourceIdentity) -> OfflineQaSnapshot,
    private val playVerified: suspend (EvidencePlaybackExpectation, suspend () -> Boolean) -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun play(
        turn: OfflineQaSessionTurn,
        ref: String,
        currentState: () -> OfflineQaSessionState,
    ): QaEvidencePlayOutcome {
        if (!mutex.tryLock()) return QaEvidencePlayOutcome.BUSY
        try {
            val result = (turn.outcome as? OfflineQaOutcome.Answer)?.result ?: return QaEvidencePlayOutcome.REJECTED
            val source = result.context.request.source
            if (result.claims.none { ref in it.evidenceIds }) return QaEvidencePlayOutcome.REJECTED
            val cited = result.context.candidates.singleOrNull { it.anchor.ref == ref }
                ?: return QaEvidencePlayOutcome.REJECTED
            fun isVisible(): Boolean {
                val state = currentState()
                return state.phase == OfflineQaSessionPhase.READY && state.source == source &&
                    state.turns.any { it === turn }
            }
            suspend fun current(): Boolean {
                currentCoroutineContext().ensureActive()
                if (!isVisible()) return false
                val fresh = requireCurrent(source)
                currentCoroutineContext().ensureActive()
                return isVisible() && fresh.source == source && fresh.resolve(ref) == cited
            }
            if (!current()) return QaEvidencePlayOutcome.STALE
            val expected = EvidencePlaybackExpectation(source.recordingId, source.canonicalAssetId,
                source.canonicalSha256, source.canonicalProfileId, source.totalSampleCount,
                cited.anchor.startSampleIndex, cited.anchor.endSampleIndexExclusive)
            var stale = false
            val played = playVerified(expected) {
                val valid = try { current() } catch (_: OfflineQaSourceException) { false }
                if (!valid) stale = true
                valid
            }
            return when {
                played -> QaEvidencePlayOutcome.STARTED
                stale -> QaEvidencePlayOutcome.STALE
                else -> QaEvidencePlayOutcome.REJECTED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OfflineQaSourceException) {
            return QaEvidencePlayOutcome.STALE
        } catch (_: Exception) {
            return QaEvidencePlayOutcome.REJECTED
        } finally { mutex.unlock() }
    }
}
