package io.github.ioannes78.voica.localllm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmRuntimeDiagnosticObserverTest {
    @Test
    fun `coordinator emits content-free phase metadata with resolved backend and emission count`() =
        runBlocking {
            val events = mutableListOf<LocalLlmRuntimeDiagnosticPhase>()
            LocalLlmRuntimeDiagnosticObserverRegistry.install(events::add)
            try {
                val coordinator =
                    LocalLlmRuntimeCoordinator { _, request, resolution, onPhase ->
                        onPhase(LocalLlmRuntimePhase.GENERATING)
                        onPhase(LocalLlmRuntimePhase.GENERATION_PROGRESS)
                        onPhase(LocalLlmRuntimePhase.GENERATION_PROGRESS)
                        onPhase(LocalLlmRuntimePhase.GENERATED)
                        onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)
                        success(request, resolution)
                    }
                val schema = "{\"type\":\"object\"}"
                val request =
                    LocalLlmGenerationRequest(
                        prompt = "private prompt must never enter diagnostic events",
                        modelIdentity = identity(),
                        config =
                            LocalLlmRuntimeConfig(
                                backendPolicy = LocalLlmBackendPolicy.GPU,
                                responseJsonSchema = schema,
                            ),
                    )
                val resolution =
                    LocalLlmBackendResolution(
                        requestedPolicy = LocalLlmBackendPolicy.GPU,
                        resolvedBackend = LocalLlmBackend.GPU,
                        reason = "EXPLICIT_GPU",
                    )

                coordinator.generate("/model", request, resolution)

                assertEquals(
                    listOf(
                        LocalLlmRuntimePhase.GENERATING,
                        LocalLlmRuntimePhase.GENERATION_PROGRESS,
                        LocalLlmRuntimePhase.GENERATION_PROGRESS,
                        LocalLlmRuntimePhase.GENERATED,
                        LocalLlmRuntimePhase.ENGINE_CLOSED,
                    ),
                    events.map { it.phase },
                )
                assertTrue(events.all { it.requestedPolicy == LocalLlmBackendPolicy.GPU })
                assertTrue(events.all { it.resolvedBackend == LocalLlmBackend.GPU })
                assertTrue(events.all { it.schemaEnabled })
                assertTrue(events.all { it.schemaSizeChars == schema.length })
                assertEquals(0L, events.first().emissionCount)
                assertEquals(1L, events[1].emissionCount)
                assertEquals(2L, events[2].emissionCount)
                assertEquals(2L, events.last().emissionCount)
            } finally {
                LocalLlmRuntimeDiagnosticObserverRegistry.install(null)
            }
        }

    @Test
    fun `observer failure is isolated from runtime semantics`() = runBlocking {
        LocalLlmRuntimeDiagnosticObserverRegistry.install { error("diagnostic sink failed") }
        try {
            val coordinator =
                LocalLlmRuntimeCoordinator { _, request, resolution, onPhase ->
                    onPhase(LocalLlmRuntimePhase.GENERATING)
                    onPhase(LocalLlmRuntimePhase.GENERATED)
                    onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)
                    success(request, resolution)
                }

            val result = coordinator.generate("/model", request(), gpuResolution())

            assertEquals("ok", result.text)
            assertEquals(LocalLlmRuntimeState.IDLE, coordinator.state.value)
            assertFalse(coordinator.generationProgress.value.active)
        } finally {
            LocalLlmRuntimeDiagnosticObserverRegistry.install(null)
        }
    }

    private fun request() =
        LocalLlmGenerationRequest(
            prompt = "test",
            modelIdentity = identity(),
            config = LocalLlmRuntimeConfig(backendPolicy = LocalLlmBackendPolicy.AUTO),
        )

    private fun gpuResolution() =
        LocalLlmBackendResolution(
            requestedPolicy = LocalLlmBackendPolicy.AUTO,
            resolvedBackend = LocalLlmBackend.GPU,
            reason = "GPU_AVAILABLE",
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
