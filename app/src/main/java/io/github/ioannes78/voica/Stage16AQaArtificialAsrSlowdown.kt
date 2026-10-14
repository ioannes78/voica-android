package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionEventProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import kotlinx.coroutines.delay

internal data class Stage16AQaArtificialAsrSlowdownSnapshot(
    val enabled: Boolean,
    val armedDelayMs: Long? = null,
    val armCount: Int = 0,
    val triggerCount: Int = 0,
    val lastTriggeredDelayMs: Long? = null,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A QA artificial ASR slowdown]")
        append(
            "enabled=$enabled armed=${armedDelayMs?.let { "${it}ms" } ?: "n/a"} " +
                "armCount=$armCount triggerCount=$triggerCount " +
                "lastTriggered=${lastTriggeredDelayMs?.let { "${it}ms" } ?: "n/a"}",
        )
    }
}

/**
 * One-shot, QA-only delay controller used by S10 to stall the Stage 16 ASR consumer without
 * delaying BLE ingest, the Stage 15 canonical source, or local-audio side sinks.
 */
internal class Stage16AArtificialAsrSlowdownController(
    private val enabled: Boolean,
) {
    private val lock = Any()
    private var armedDelayMs: Long? = null
    private var armCount = 0
    private var triggerCount = 0
    private var lastTriggeredDelayMs: Long? = null

    fun beginRun() {
        synchronized(lock) {
            armedDelayMs = null
            armCount = 0
            triggerCount = 0
            lastTriggeredDelayMs = null
        }
    }

    fun armOnce(delayMs: Long): Boolean = synchronized(lock) {
        if (!enabled || delayMs !in MIN_DELAY_MS..MAX_DELAY_MS || armedDelayMs != null) {
            return@synchronized false
        }
        armedDelayMs = delayMs
        armCount += 1
        true
    }

    fun consumeArmedDelayMs(): Long? = synchronized(lock) {
        if (!enabled) return@synchronized null
        val delayMs = armedDelayMs ?: return@synchronized null
        armedDelayMs = null
        triggerCount += 1
        lastTriggeredDelayMs = delayMs
        delayMs
    }

    fun snapshot(): Stage16AQaArtificialAsrSlowdownSnapshot = synchronized(lock) {
        Stage16AQaArtificialAsrSlowdownSnapshot(
            enabled = enabled,
            armedDelayMs = armedDelayMs,
            armCount = armCount,
            triggerCount = triggerCount,
            lastTriggeredDelayMs = lastTriggeredDelayMs,
        )
    }

    private companion object {
        const val MIN_DELAY_MS = 1L
        const val MAX_DELAY_MS = 5_000L
    }
}

internal object Stage16AQaArtificialAsrSlowdown {
    private val controller =
        Stage16AArtificialAsrSlowdownController(
            enabled = BuildConfig.APPLICATION_ID.endsWith(".qa"),
        )

    val isEnabled: Boolean
        get() = controller.snapshot().enabled

    fun beginRun() = controller.beginRun()

    fun armOnce(delayMs: Long): Boolean = controller.armOnce(delayMs)

    fun consumeArmedDelayMs(): Long? = controller.consumeArmedDelayMs()

    fun snapshot(): Stage16AQaArtificialAsrSlowdownSnapshot = controller.snapshot()

    internal fun clearForTest() = controller.beginRun()
}

/**
 * Delays only the runner-side event processor after a PCM event has been dequeued from the bounded
 * Stage 16 buffer. The source pump therefore keeps filling the buffer during the artificial stall,
 * which makes S10 a deterministic consumer-backpressure test rather than a BLE/source delay test.
 */
internal class Stage16AQaArtificialSlowdownEventProcessor(
    private val delegate: RealtimeTranscriptionEventProcessor,
    private val consumeDelayMs: () -> Long? = Stage16AQaArtificialAsrSlowdown::consumeArmedDelayMs,
) : RealtimeTranscriptionEventProcessor {
    override suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput> {
        if (event is StreamingAudioEvent.PcmFrame) {
            consumeDelayMs()?.let { delayMs -> delay(delayMs) }
        }
        return delegate.accept(event)
    }

    override suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput> =
        delegate.onPauseCommitted()
}
