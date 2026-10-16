package io.github.ioannes78.voica.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A15Qa2PresentationContractTest {
    @Test
    fun `live media presentation fields are optional and side effect free by default`() {
        val fact =
            AppActivityFact(
                logicalKey = "qa2-contract",
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                lifecycleClass = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
                title = "录音中",
            )

        assertNull(fact.elapsedMs)
        assertNull(fact.mediaDisplayName)
        assertNull(fact.mediaSizeBytes)
        assertTrue(fact.audioActivityLevels.isEmpty())
        assertFalse(fact.waveformActive)
        assertEquals(emptySet<AppActivityAction>(), fact.availableActions)
    }
}
