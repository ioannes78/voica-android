package io.github.ioannes78.voica.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaRuntimeTest {
    @Test
    fun freezesStage8RuntimeIdentityAndDefaults() {
        assertEquals("sherpa-onnx", SherpaRuntime.RUNTIME_ID)
        assertEquals("1.13.8", SherpaRuntime.RUNTIME_VERSION)
        assertEquals("cpu", SherpaRuntime.PROVIDER_CPU)
        assertEquals(2, SherpaRuntime.DEFAULT_NUM_THREADS)
    }
}
