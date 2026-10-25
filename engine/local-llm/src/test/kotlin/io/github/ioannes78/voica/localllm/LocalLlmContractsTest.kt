package io.github.ioannes78.voica.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmContractsTest {
    @Test
    fun `runtime config defaults to auto gpu-preferred policy contract`() {
        val config = LocalLlmRuntimeConfig()

        assertEquals(LocalLlmBackendPolicy.AUTO, config.backendPolicy)
        assertEquals(4_000, config.maxNumTokens)
        assertEquals(64, config.topK)
        assertEquals(0.95, config.topP, 0.0)
        assertEquals(1.0, config.temperature, 0.0)
        assertFalse(config.speculativeDecoding)
    }

    @Test
    fun `p0 preset remains auto rather than locking test device backend`() {
        val preset = LocalLlmValidatedPresets.P0_BASELINE

        assertEquals(LocalLlmBackendPolicy.AUTO, preset.backendPolicy)
        assertEquals(4_000, preset.maxNumTokens)
        assertFalse(preset.speculativeDecoding)
    }

    @Test
    fun `auto policy can record gpu resolution without losing requested policy`() {
        val resolution = LocalLlmBackendResolution(
            requestedPolicy = LocalLlmBackendPolicy.AUTO,
            resolvedBackend = LocalLlmBackend.GPU,
            reason = "GPU_AVAILABLE",
        )

        assertEquals(LocalLlmBackendPolicy.AUTO, resolution.requestedPolicy)
        assertEquals(LocalLlmBackend.GPU, resolution.resolvedBackend)
        assertFalse(resolution.fallbackAttempted)
    }

    @Test
    fun `auto policy can record safe cpu fallback`() {
        val resolution = LocalLlmBackendResolution(
            requestedPolicy = LocalLlmBackendPolicy.AUTO,
            resolvedBackend = LocalLlmBackend.CPU,
            reason = "GPU_PREFLIGHT_BLOCKED",
            fallbackAttempted = true,
        )

        assertEquals(LocalLlmBackend.CPU, resolution.resolvedBackend)
        assertTrue(resolution.fallbackAttempted)
    }

    @Test
    fun `explicit backend policies cannot silently resolve to another backend`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmBackendResolution(
                requestedPolicy = LocalLlmBackendPolicy.GPU,
                resolvedBackend = LocalLlmBackend.CPU,
                reason = "unexpected",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmBackendResolution(
                requestedPolicy = LocalLlmBackendPolicy.CPU,
                resolvedBackend = LocalLlmBackend.GPU,
                reason = "unexpected",
            )
        }
    }

    @Test
    fun `model identity requires immutable sha256 shaped value`() {
        val identity = validIdentity()

        assertEquals("gemma-4-e2b-it-litertlm", identity.modelId)
        assertEquals(64, identity.sha256.length)

        assertThrows(IllegalArgumentException::class.java) {
            validIdentity().copy(sha256 = "not-a-sha")
        }
    }

    @Test
    fun `invalid sampler and context values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmRuntimeConfig(maxNumTokens = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmRuntimeConfig(topK = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmRuntimeConfig(topP = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlmRuntimeConfig(temperature = Double.NaN)
        }
    }

    @Test
    fun `failure preserves phase backend and fallback evidence`() {
        val failure = LocalLlmFailure(
            code = LocalLlmFailureCode.ENGINE_INIT_FAILED,
            phase = LocalLlmFailurePhase.ENGINE_INITIALIZING,
            message = "GPU initialization failed",
            requestedPolicy = LocalLlmBackendPolicy.AUTO,
            resolvedBackend = LocalLlmBackend.GPU,
            fallbackAttempted = false,
            causeClassName = "java.lang.IllegalStateException",
        )

        assertEquals(LocalLlmFailureCode.ENGINE_INIT_FAILED, failure.code)
        assertEquals(LocalLlmBackend.GPU, failure.resolvedBackend)
        assertFalse(failure.fallbackAttempted)
    }

    private fun validIdentity() = LocalLlmModelIdentity(
        modelId = "gemma-4-e2b-it-litertlm",
        modelFamily = "Gemma 4 E2B",
        artifact = "gemma-4-E2B-it.litertlm",
        revision = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
        sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
        runtimeId = "litert-lm",
        runtimeVersion = "0.18.0",
    )
}
