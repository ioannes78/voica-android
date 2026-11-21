package io.github.ioannes78.voica

import android.content.Context
import android.os.SystemClock
import io.github.ioannes78.voica.localllm.LocalLlmCpuBoundaryLedger
import io.github.ioannes78.voica.localllm.LocalLlmCpuBoundaryPhase
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P2-I QA only. JNI callbacks append at most 32 scalar events to an in-memory ledger.
 * A dedicated writer persists the latest immutable snapshot OFF the callback thread.
 *
 * Best-effort durability: a native abort can happen before the queued write finishes.
 * Missing SEND_RETURNED means "return not observed", not proof of deadlock or decoding.
 */
internal class Stage17P2ICpuBoundaryStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("stage17_p2i_cpu_boundary", Context.MODE_PRIVATE)
    private val ledger = LocalLlmCpuBoundaryLedger(nanoClock = SystemClock::elapsedRealtimeNanos)
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "stage17-p2i-qa-persist").apply { isDaemon = true }
        }
    private val lock = Any()
    private var pending: String? = null
    private var scheduled = false
    private var closed = false
    private val mutableSnapshot =
        MutableStateFlow(prefs.getString("last", "P2-I: 尚无 CPU 边界诊断") ?: "P2-I: 尚无 CPU 边界诊断")

    val snapshot: StateFlow<String> = mutableSnapshot.asStateFlow()

    fun arm() {
        ledger.arm()
        enqueue("P2-I explicit CPU / boundary-only / ARM已建立（后续写入异步，可能滞后）")
    }

    fun record(phase: LocalLlmCpuBoundaryPhase) {
        val entries = ledger.record(phase) ?: return
        val text = buildString {
            append("P2-I explicit CPU / LiteRT-LM JNI wrapper (observed only)\n")
            append("lastBoundary=")
            append(entries.last().phase.name)
            append("\ncount=")
            append(entries.size)
            entries.forEach { entry ->
                append("\n+")
                append(entry.elapsedMs)
                append("ms ")
                append(entry.phase.name)
            }
        }
        enqueue(text)
    }

    fun disarm() {
        ledger.disarm()
    }

    fun close() {
        ledger.disarm()
        synchronized(lock) { closed = true }
        // Already queued writes may finish. Never block the main or JNI callback thread here.
        worker.shutdown()
    }

    private fun enqueue(value: String) {
        synchronized(lock) {
            if (closed) return
            pending = value
            if (!scheduled) {
                scheduled = true
                worker.execute { drain() }
            }
        }
    }

    private fun drain() {
        while (true) {
            val current =
                synchronized(lock) {
                    val value = pending
                    if (value == null) {
                        scheduled = false
                        return
                    }
                    pending = null
                    value
                }
            if (prefs.edit().putString("last", current).commit()) {
                mutableSnapshot.value = current
            }
        }
    }
}
