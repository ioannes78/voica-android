package io.github.ioannes78.voica

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

data class Stage16ARealtimeSystemMetricsSnapshot(
    val elapsedMs: Long,
    val processCpuMs: Long,
    val processCpuPercentOfOneCore: Double?,
    val startPssKb: Int?,
    val currentPssKb: Int?,
    val pssDeltaKb: Int?,
    val startThermalStatus: Int?,
    val currentThermalStatus: Int?,
    val startBatteryCapacityPercent: Int?,
    val currentBatteryCapacityPercent: Int?,
    val batteryCapacityDeltaPercent: Int?,
    val startBatteryChargeCounterUah: Int?,
    val currentBatteryChargeCounterUah: Int?,
    val batteryChargeDeltaUah: Int?,
)

/**
 * Low-overhead Stage 16A P7 system metrics probe.
 *
 * The probe records one baseline at construction and reads current process/device counters only when
 * diagnostics are requested or the run is finalized. It starts no timer/thread and does no media IO.
 */
class Stage16ARealtimeSystemMetricsProbe internal constructor(
    private val elapsedRealtimeMs: () -> Long,
    private val processCpuTimeMs: () -> Long,
    private val pssKb: () -> Int?,
    private val thermalStatus: () -> Int?,
    private val batteryCapacityPercent: () -> Int?,
    private val batteryChargeCounterUah: () -> Int?,
) {
    private val startElapsedMs = elapsedRealtimeMs()
    private val startProcessCpuMs = processCpuTimeMs()
    private val startPssKb = pssKb()
    private val startThermalStatus = thermalStatus()
    private val startBatteryCapacityPercent = batteryCapacityPercent()
    private val startBatteryChargeCounterUah = batteryChargeCounterUah()

    fun snapshot(): Stage16ARealtimeSystemMetricsSnapshot {
        val elapsedMs = (elapsedRealtimeMs() - startElapsedMs).coerceAtLeast(0L)
        val processCpuMs = (processCpuTimeMs() - startProcessCpuMs).coerceAtLeast(0L)
        val currentPssKb = pssKb()
        val currentThermalStatus = thermalStatus()
        val currentBatteryCapacityPercent = batteryCapacityPercent()
        val currentBatteryChargeCounterUah = batteryChargeCounterUah()
        return Stage16ARealtimeSystemMetricsSnapshot(
            elapsedMs = elapsedMs,
            processCpuMs = processCpuMs,
            processCpuPercentOfOneCore =
                if (elapsedMs > 0L) processCpuMs.toDouble() * 100.0 / elapsedMs.toDouble() else null,
            startPssKb = startPssKb,
            currentPssKb = currentPssKb,
            pssDeltaKb = delta(startPssKb, currentPssKb),
            startThermalStatus = startThermalStatus,
            currentThermalStatus = currentThermalStatus,
            startBatteryCapacityPercent = startBatteryCapacityPercent,
            currentBatteryCapacityPercent = currentBatteryCapacityPercent,
            batteryCapacityDeltaPercent =
                delta(startBatteryCapacityPercent, currentBatteryCapacityPercent),
            startBatteryChargeCounterUah = startBatteryChargeCounterUah,
            currentBatteryChargeCounterUah = currentBatteryChargeCounterUah,
            batteryChargeDeltaUah =
                delta(startBatteryChargeCounterUah, currentBatteryChargeCounterUah),
        )
    }

    companion object {
        fun android(context: Context): Stage16ARealtimeSystemMetricsProbe {
            val appContext = context.applicationContext
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            val batteryManager = appContext.getSystemService(BatteryManager::class.java)
            return Stage16ARealtimeSystemMetricsProbe(
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                processCpuTimeMs = Process::getElapsedCpuTime,
                pssKb = {
                    runCatching {
                        val memoryInfo = Debug.MemoryInfo()
                        Debug.getMemoryInfo(memoryInfo)
                        memoryInfo.totalPss
                    }.getOrNull()
                },
                thermalStatus = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { powerManager?.currentThermalStatus }.getOrNull()
                    } else {
                        null
                    }
                },
                batteryCapacityPercent = {
                    batteryManager
                        ?.let { manager ->
                            runCatching {
                                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            }.getOrNull()
                        }
                        ?.takeIf { it in 0..100 }
                },
                batteryChargeCounterUah = {
                    batteryManager
                        ?.let { manager ->
                            runCatching {
                                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                            }.getOrNull()
                        }
                        ?.takeIf { it != Int.MIN_VALUE }
                },
            )
        }

        private fun delta(start: Int?, current: Int?): Int? =
            if (start != null && current != null) current - start else null
    }
}
