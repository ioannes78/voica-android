package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A16AudioCalibrationEntryTest {
    @Test
    fun `qa application exposes calibration entry`() {
        assertTrue(stage16A16AudioCalibrationEntryVisible("io.github.ioannes78.voica.qa"))
    }

    @Test
    fun `production application hides calibration entry`() {
        assertFalse(stage16A16AudioCalibrationEntryVisible("io.github.ioannes78.voica"))
    }
}
