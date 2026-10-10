package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalAudioWorkProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CanonicalGenerationPhase {
    PREPARING,
    DECODING,
    NORMALIZING,
    WRITING,
    VERIFYING,
    COMMITTING,
    CANCELLING,
}

data class CanonicalGenerationProgress(
    val recordingId: String,
    val phase: CanonicalGenerationPhase,
    val processedDurationUs: Long = 0L,
    val totalDurationUs: Long? = null,
    val processedUnits: Long = 0L,
    val totalUnits: Long? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val cancellable: Boolean = true,
) {
    val fraction: Float?
        get() {
            val durationTotal = totalDurationUs?.takeIf { it > 0L }
            if (durationTotal != null) {
                return (processedDurationUs.toDouble() / durationTotal.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }
            val unitTotal = totalUnits?.takeIf { it > 0L } ?: return null
            return (processedUnits.toDouble() / unitTotal.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
}

/**
 * Process-local, throttled presentation state for canonical generation.
 *
 * Durable lifecycle truth remains in Room. High-frequency work progress deliberately stays out of
 * the database so long recordings do not turn UI feedback into write amplification.
 */
class CanonicalGenerationProgressRuntime(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableProgress =
        MutableStateFlow<Map<String, CanonicalGenerationProgress>>(emptyMap())
    val progress: StateFlow<Map<String, CanonicalGenerationProgress>> =
        mutableProgress.asStateFlow()

    private val lastWorkPublishMs = mutableMapOf<String, Long>()

    @Synchronized
    fun begin(
        recordingId: String,
        totalDurationUs: Long? = null,
    ) {
        val now = nowMs()
        lastWorkPublishMs.remove(recordingId)
        publish(
            CanonicalGenerationProgress(
                recordingId = recordingId,
                phase = CanonicalGenerationPhase.PREPARING,
                totalDurationUs = totalDurationUs?.takeIf { it > 0L },
                startedAtMs = now,
                updatedAtMs = now,
            ),
        )
    }

    @Synchronized
    fun stage(
        recordingId: String,
        phase: CanonicalGenerationPhase,
        totalDurationUs: Long? = null,
        cancellable: Boolean = true,
    ) {
        val now = nowMs()
        val previous = mutableProgress.value[recordingId]
        val startedAt = previous?.startedAtMs ?: now
        publish(
            (previous
                ?: CanonicalGenerationProgress(
                    recordingId = recordingId,
                    phase = phase,
                    startedAtMs = startedAt,
                    updatedAtMs = now,
                )).copy(
                phase = phase,
                totalDurationUs =
                    totalDurationUs?.takeIf { it > 0L }
                        ?: previous?.totalDurationUs,
                updatedAtMs = now,
                cancellable = cancellable,
            ),
        )
    }

    @Synchronized
    fun work(
        recordingId: String,
        work: CanonicalAudioWorkProgress,
        force: Boolean = false,
    ) {
        val now = nowMs()
        val previous = mutableProgress.value[recordingId] ?: return
        val last = lastWorkPublishMs[recordingId] ?: Long.MIN_VALUE
        val terminalSample =
            work.totalDurationUs?.takeIf { it > 0L }
                ?.let { work.processedDurationUs >= it } == true ||
                work.totalUnits?.takeIf { it > 0L }
                    ?.let { work.processedUnits >= it } == true
        if (!force && !terminalSample && now - last < MIN_WORK_PUBLISH_INTERVAL_MS) return

        lastWorkPublishMs[recordingId] = now
        publish(
            previous.copy(
                processedDurationUs =
                    maxOf(previous.processedDurationUs, work.processedDurationUs),
                totalDurationUs =
                    work.totalDurationUs?.takeIf { it > 0L }
                        ?: previous.totalDurationUs,
                processedUnits = maxOf(previous.processedUnits, work.processedUnits),
                totalUnits = work.totalUnits ?: previous.totalUnits,
                updatedAtMs = now,
            ),
        )
    }

    @Synchronized
    fun cancelling(recordingId: String) {
        stage(
            recordingId = recordingId,
            phase = CanonicalGenerationPhase.CANCELLING,
            cancellable = false,
        )
    }

    @Synchronized
    fun finish(recordingId: String) {
        lastWorkPublishMs.remove(recordingId)
        if (recordingId !in mutableProgress.value) return
        mutableProgress.value = mutableProgress.value - recordingId
    }

    @Synchronized
    private fun publish(value: CanonicalGenerationProgress) {
        mutableProgress.value = mutableProgress.value + (value.recordingId to value)
    }

    private companion object {
        const val MIN_WORK_PUBLISH_INTERVAL_MS = 250L
    }
}
