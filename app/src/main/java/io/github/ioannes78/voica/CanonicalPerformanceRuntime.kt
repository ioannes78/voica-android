package io.github.ioannes78.voica

import android.util.Log
import io.github.ioannes78.voica.audio.CanonicalConversionTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CanonicalPerformanceSnapshot(
    val recordingId: String,
    val sourceRole: String,
    val sourceContainer: String,
    val sourceBytes: Long,
    val sourceDurationUs: Long?,
    val sourceVerificationNanos: Long,
    val conversion: CanonicalConversionTiming?,
    val roomCommitNanos: Long,
    val totalWallNanos: Long,
    val completedAtMs: Long,
) {
    val realTimeFactor: Double?
        get() {
            val duration = sourceDurationUs?.takeIf { it > 0L } ?: return null
            return (totalWallNanos / 1_000.0) / duration.toDouble()
        }

    fun compactLogLine(): String =
        buildString {
            append("recording=").append(recordingId)
            append(" role=").append(sourceRole)
            append(" container=").append(sourceContainer)
            append(" sourceBytes=").append(sourceBytes)
            append(" durationUs=").append(sourceDurationUs ?: -1L)
            append(" sourceShaMs=").append(sourceVerificationNanos.toMillis())
            conversion?.let { timing ->
                append(" decodeReadMs=").append(timing.decodeOrReadNanos.toMillis())
                append(" normalizeMs=").append(timing.normalizeNanos.toMillis())
                append(" writeMs=").append(timing.writer.writeNanos.toMillis())
                append(" syncMs=").append(timing.writer.syncNanos.toMillis())
                append(" moveMs=").append(timing.writer.moveNanos.toMillis())
                append(" outputShaMs=").append(timing.writer.outputHashNanos.toMillis())
                append(" pipelineMs=").append(timing.pipelineNanos.toMillis())
            }
            append(" roomCommitMs=").append(roomCommitNanos.toMillis())
            append(" totalMs=").append(totalWallNanos.toMillis())
            realTimeFactor?.let { rtf ->
                append(" rtf=").append(String.format(java.util.Locale.US, "%.4f", rtf))
            }
        }

    private fun Long.toMillis(): Long = this / 1_000_000L
}

class CanonicalPerformanceRuntime {
    private val mutableLatest = MutableStateFlow<CanonicalPerformanceSnapshot?>(null)
    val latest: StateFlow<CanonicalPerformanceSnapshot?> = mutableLatest.asStateFlow()

    fun publish(snapshot: CanonicalPerformanceSnapshot) {
        mutableLatest.value = snapshot
        Log.i(LOG_TAG, snapshot.compactLogLine())
    }

    private companion object {
        const val LOG_TAG = "VoicaCanonicalPerf"
    }
}
