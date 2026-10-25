package io.github.ioannes78.voica.localllm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmBackendPolicyTest {
    private val gate = LocalLlmResourceGate()
    private val resolver = LocalLlmBackendResolver()

    @Test
    fun `auto prefers gpu without locking device ram size`() {
        val decision = gate.evaluate(snapshot())
        val candidates = resolver.resolve(LocalLlmBackendPolicy.AUTO, decision)

        assertEquals(LocalLlmBackend.GPU, candidates.first().resolvedBackend)
        assertEquals(LocalLlmBackend.CPU, candidates.last().resolvedBackend)
        assertFalse(candidates.first().fallbackAttempted)
    }

    @Test
    fun `severe thermal preflight skips gpu and selects cpu`() {
        val decision = gate.evaluate(snapshot(thermal = LocalLlmThermalLevel.SEVERE))
        val candidates = resolver.resolve(LocalLlmBackendPolicy.AUTO, decision)

        assertFalse(decision.gpuAllowed)
        assertTrue(decision.cpuAllowed)
        assertEquals(1, candidates.size)
        assertEquals(LocalLlmBackend.CPU, candidates.single().resolvedBackend)
        assertTrue(candidates.single().fallbackAttempted)
    }

    @Test
    fun `critical thermal blocks both backends`() {
        val decision = gate.evaluate(snapshot(thermal = LocalLlmThermalLevel.CRITICAL))

        val failure =
            runCatching { resolver.resolve(LocalLlmBackendPolicy.AUTO, decision) }
                .exceptionOrNull()
        assertTrue(failure is LocalLlmRuntimeException)
        assertEquals(
            LocalLlmFailureCode.THERMAL_BLOCKED,
            (failure as LocalLlmRuntimeException).failure.code,
        )
    }

    @Test
    fun `system low memory blocks both without using fixed eight gigabyte rule`() {
        val decision = gate.evaluate(snapshot(lowMemory = true))

        assertFalse(decision.gpuAllowed)
        assertFalse(decision.cpuAllowed)
    }

    @Test
    fun `auto retries cpu after safe gpu initialization failure`() = runBlocking {
        val attempted = mutableListOf<LocalLlmBackend>()
        val controller =
            LocalLlmExecutionController { _, request, resolution ->
                attempted += resolution.resolvedBackend
                if (resolution.resolvedBackend == LocalLlmBackend.GPU) {
                    throw runtimeFailure(
                        code = LocalLlmFailureCode.ENGINE_INIT_FAILED,
                        phase = LocalLlmFailurePhase.ENGINE_INITIALIZING,
                        backend = LocalLlmBackend.GPU,
                    )
                }
                success(request, resolution)
            }

        val result = controller.generate("/model", request(), gate.evaluate(snapshot()))

        assertEquals(listOf(LocalLlmBackend.GPU, LocalLlmBackend.CPU), attempted)
        assertEquals(LocalLlmBackend.CPU, result.backendResolution.resolvedBackend)
        assertTrue(result.backendResolution.fallbackAttempted)
        assertTrue(result.backendResolution.reason.contains("ENGINE_INIT_FAILED"))
    }

    @Test
    fun `auto does not retry cpu after generation failure`() = runBlocking {
        val attempted = mutableListOf<LocalLlmBackend>()
        val controller =
            LocalLlmExecutionController { _, _, resolution ->
                attempted += resolution.resolvedBackend
                throw runtimeFailure(
                    code = LocalLlmFailureCode.GENERATION_FAILED,
                    phase = LocalLlmFailurePhase.GENERATING,
                    backend = resolution.resolvedBackend,
                )
            }

        val failure =
            runCatching {
                controller.generate("/model", request(), gate.evaluate(snapshot()))
            }.exceptionOrNull()

        assertTrue(failure is LocalLlmRuntimeException)
        assertEquals(listOf(LocalLlmBackend.GPU), attempted)
    }

    @Test
    fun `auto does not retry cpu after out of memory evidence`() = runBlocking {
        val attempted = mutableListOf<LocalLlmBackend>()
        val controller =
            LocalLlmExecutionController { _, _, resolution ->
                attempted += resolution.resolvedBackend
                throw LocalLlmRuntimeException(
                    LocalLlmFailure(
                        code = LocalLlmFailureCode.ENGINE_INIT_FAILED,
                        phase = LocalLlmFailurePhase.ENGINE_INITIALIZING,
                        message = "oom",
                        requestedPolicy = LocalLlmBackendPolicy.AUTO,
                        resolvedBackend = resolution.resolvedBackend,
                        causeClassName = OutOfMemoryError::class.java.name,
                    ),
                )
            }

        controller.runCatchingGenerate()
        assertEquals(listOf(LocalLlmBackend.GPU), attempted)
    }

    @Test
    fun `explicit gpu never silently changes to cpu`() = runBlocking {
        val attempted = mutableListOf<LocalLlmBackend>()
        val controller =
            LocalLlmExecutionController { _, _, resolution ->
                attempted += resolution.resolvedBackend
                throw runtimeFailure(
                    code = LocalLlmFailureCode.ENGINE_INIT_FAILED,
                    phase = LocalLlmFailurePhase.ENGINE_INITIALIZING,
                    backend = resolution.resolvedBackend,
                    policy = LocalLlmBackendPolicy.GPU,
                )
            }
        val request = request(LocalLlmBackendPolicy.GPU)

        runCatching {
            controller.generate("/model", request, gate.evaluate(snapshot()))
        }
        assertEquals(listOf(LocalLlmBackend.GPU), attempted)
    }

    private suspend fun LocalLlmExecutionController.runCatchingGenerate() =
        runCatching {
            generate("/model", request(), gate.evaluate(snapshot()))
        }

    private fun snapshot(
        lowMemory: Boolean = false,
        thermal: LocalLlmThermalLevel = LocalLlmThermalLevel.NONE,
    ) =
        LocalLlmResourceSnapshot(
            availableMemoryBytes = 2_000L,
            totalMemoryBytes = 8_000L,
            lowMemory = lowMemory,
            lowMemoryThresholdBytes = 500L,
            thermalLevel = thermal,
        )

    private fun request(
        policy: LocalLlmBackendPolicy = LocalLlmBackendPolicy.AUTO,
    ) =
        LocalLlmGenerationRequest(
            prompt = "test",
            modelIdentity = identity(),
            config = LocalLlmRuntimeConfig(backendPolicy = policy),
        )

    private fun runtimeFailure(
        code: LocalLlmFailureCode,
        phase: LocalLlmFailurePhase,
        backend: LocalLlmBackend,
        policy: LocalLlmBackendPolicy = LocalLlmBackendPolicy.AUTO,
    ) =
        LocalLlmRuntimeException(
            LocalLlmFailure(
                code = code,
                phase = phase,
                message = "failed",
                requestedPolicy = policy,
                resolvedBackend = backend,
            ),
        )

    private fun success(
        request: LocalLlmGenerationRequest,
        resolution: LocalLlmBackendResolution,
    ): LocalLlmGenerationResult =
        LocalLlmGenerationResult(
            text = "ok",
            modelIdentity = request.modelIdentity,
            backendResolution = resolution,
            diagnostics =
                LocalLlmDiagnostics(
                    runtimeVersion = "0.18.0",
                    modelIdentity = request.modelIdentity,
                    backendResolution = resolution,
                ),
        )

    private fun identity() =
        LocalLlmModelIdentity(
            modelId = "gemma-4-e2b-it-litertlm",
            modelFamily = "Gemma 4 E2B",
            artifact = "gemma-4-E2B-it.litertlm",
            revision = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
            runtimeId = "litert-lm",
            runtimeVersion = "0.18.0",
        )
}
