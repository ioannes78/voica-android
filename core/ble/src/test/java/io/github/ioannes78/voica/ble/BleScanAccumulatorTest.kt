package io.github.ioannes78.voica.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanAccumulatorTest {
    @Test
    fun sameAddressIsDeduplicatedAndLatestObservationWins() {
        val accumulator = BleScanAccumulator()
        accumulator.upsert(
            BleScanDevice(
                address = "AA:BB:CC:DD:EE:FF",
                name = "QS668",
                rssi = -70,
                lastSeenElapsedMs = 1,
                advertisesAe20 = false,
                likelyQs668 = true,
            ),
        )
        val result = accumulator.upsert(
            BleScanDevice(
                address = "AA:BB:CC:DD:EE:FF",
                name = "QS668",
                rssi = -48,
                lastSeenElapsedMs = 2,
                advertisesAe20 = true,
                likelyQs668 = true,
            ),
        )

        assertEquals(1, result.size)
        assertEquals(-48, result.single().rssi)
        assertEquals(2L, result.single().lastSeenElapsedMs)
        assertTrue(result.single().advertisesAe20)
    }

    @Test
    fun candidatesAreSortedBeforeOtherBleDevices() {
        val accumulator = BleScanAccumulator()
        accumulator.upsert(
            BleScanDevice("11", "Other", -20, 1, false, false),
        )
        accumulator.upsert(
            BleScanDevice("22", "QS668", -80, 1, false, true),
        )
        val result = accumulator.upsert(
            BleScanDevice("33", "Recorder", -90, 1, true, true),
        )

        assertEquals(listOf("33", "22", "11"), result.map { it.address })
    }
}
