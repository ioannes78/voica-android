package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16AQaArtificialAsrSlowdownTest {
    @Test
    fun `disabled controller never arms or triggers`() {
        val controller = Stage16AArtificialAsrSlowdownController(enabled = false)

        assertFalse(controller.armOnce(1_200L))
        assertNull(controller.consumeArmedDelayMs())
        assertFalse(controller.snapshot().enabled)
        assertEquals(0, controller.snapshot().triggerCount)
    }

    @Test
    fun `armed delay is consumed exactly once`() {
        val controller = Stage16AArtificialAsrSlowdownController(enabled = true)

        assertTrue(controller.armOnce(1_200L))
        assertFalse(controller.armOnce(2_500L))
        assertEquals(1_200L, controller.consumeArmedDelayMs())
        assertNull(controller.consumeArmedDelayMs())

        val snapshot = controller.snapshot()
        assertTrue(snapshot.enabled)
        assertNull(snapshot.armedDelayMs)
        assertEquals(1, snapshot.armCount)
        assertEquals(1, snapshot.triggerCount)
        assertEquals(1_200L, snapshot.lastTriggeredDelayMs)
    }

    @Test
    fun `new run clears pending delay and prior counters`() {
        val controller = Stage16AArtificialAsrSlowdownController(enabled = true)
        assertTrue(controller.armOnce(2_500L))
        assertEquals(2_500L, controller.consumeArmedDelayMs())

        controller.beginRun()

        val snapshot = controller.snapshot()
        assertNull(snapshot.armedDelayMs)
        assertEquals(0, snapshot.armCount)
        assertEquals(0, snapshot.triggerCount)
        assertNull(snapshot.lastTriggeredDelayMs)
    }

    @Test
    fun `invalid delays are rejected`() {
        val controller = Stage16AArtificialAsrSlowdownController(enabled = true)

        assertFalse(controller.armOnce(0L))
        assertFalse(controller.armOnce(5_001L))
        assertTrue(controller.armOnce(2_500L))
    }
}
