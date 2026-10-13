package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage16ARealtimeSystemMetricsTest {
    @Test
    fun `snapshot reports cpu pss thermal and battery deltas from run baseline`() {
        var elapsedMs = 1_000L
        var cpuMs = 500L
        var pssKb: Int? = 100_000
        var thermal: Int? = 1
        var batteryPercent: Int? = 80
        var chargeUah: Int? = 3_000_000
        val probe =
            Stage16ARealtimeSystemMetricsProbe(
                elapsedRealtimeMs = { elapsedMs },
                processCpuTimeMs = { cpuMs },
                pssKb = { pssKb },
                thermalStatus = { thermal },
                batteryCapacityPercent = { batteryPercent },
                batteryChargeCounterUah = { chargeUah },
            )

        elapsedMs = 11_000L
        cpuMs = 3_000L
        pssKb = 112_000
        thermal = 2
        batteryPercent = 78
        chargeUah = 2_920_000

        val snapshot = probe.snapshot()
        assertEquals(10_000L, snapshot.elapsedMs)
        assertEquals(2_500L, snapshot.processCpuMs)
        assertEquals(25.0, snapshot.processCpuPercentOfOneCore!!, 0.0001)
        assertEquals(100_000, snapshot.startPssKb)
        assertEquals(112_000, snapshot.currentPssKb)
        assertEquals(12_000, snapshot.pssDeltaKb)
        assertEquals(1, snapshot.startThermalStatus)
        assertEquals(2, snapshot.currentThermalStatus)
        assertEquals(80, snapshot.startBatteryCapacityPercent)
        assertEquals(78, snapshot.currentBatteryCapacityPercent)
        assertEquals(-2, snapshot.batteryCapacityDeltaPercent)
        assertEquals(3_000_000, snapshot.startBatteryChargeCounterUah)
        assertEquals(2_920_000, snapshot.currentBatteryChargeCounterUah)
        assertEquals(-80_000, snapshot.batteryChargeDeltaUah)
    }

    @Test
    fun `unsupported optional metrics remain unavailable without fabricated values`() {
        var elapsedMs = 50L
        var cpuMs = 100L
        val probe =
            Stage16ARealtimeSystemMetricsProbe(
                elapsedRealtimeMs = { elapsedMs },
                processCpuTimeMs = { cpuMs },
                pssKb = { null },
                thermalStatus = { null },
                batteryCapacityPercent = { null },
                batteryChargeCounterUah = { null },
            )

        val initial = probe.snapshot()
        assertEquals(0L, initial.elapsedMs)
        assertEquals(0L, initial.processCpuMs)
        assertNull(initial.processCpuPercentOfOneCore)
        assertNull(initial.currentPssKb)
        assertNull(initial.currentThermalStatus)
        assertNull(initial.currentBatteryCapacityPercent)
        assertNull(initial.currentBatteryChargeCounterUah)

        elapsedMs = 1_050L
        cpuMs = 350L
        val later = probe.snapshot()
        assertEquals(1_000L, later.elapsedMs)
        assertEquals(250L, later.processCpuMs)
        assertEquals(25.0, later.processCpuPercentOfOneCore!!, 0.0001)
        assertNull(later.pssDeltaKb)
        assertNull(later.batteryCapacityDeltaPercent)
        assertNull(later.batteryChargeDeltaUah)
    }
}
