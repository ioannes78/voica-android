package io.github.ioannes78.voica.localllm

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmRuntimeCoordinatorTest {
    @Test
    fun `single owner rejects concurrent heavy inference and returns to idle`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator =
            LocalLlmRuntimeCoordinator { _, request, resolution, onPhase ->
                onPhase(LocalLlmRuntimePhase.GENERATING)
                entered.complete(Unit)
                release.await()
                onPhase(LocalLlmRuntimePhase.GENERATED)
                onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)
                success(request, resolution)
            }

        val first = async { coordinator.generate("/model", request(), gpuResolution()) }
        entered.await()

        val secondFailure =
            runCatching {
                coordinator.generate("/model", request(), gpuResolution())
            }.exceptionOrNull()
        assertTrue(secondFailure is LocalLlmRuntimeException)
        assertEquals(
            LocalLlmFailureCode.BUSY,
            (secondFailure as LocalLlmRuntimeException).failure.code,
        )

        release.complete(Unit)
        first.await()
        assertEquals(LocalLlmRuntimeState.IDLE, coordinator.state.value)
    }

    @Test
    fun `generation progress records first and latest emission without content`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator =
            LocalLlmRuntimeCoordinator { _, request, resolution, onPhase ->
                onPhase(LocalLlmRuntimePhase.GENERATING)
                onPhase(LocalLlmRuntimePhase.GENERATION_PROGRESS)
                onPhase(LocalLlmRuntimePhase.GENERATION_PROGRESS)
                entered.complete(Unit)
                release.await()
                onPhase(LocalLlmRuntimePhase.GENERATED)
                onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)
                success(request, resolution)
            }

        val job = async { coordinator.generate("/model", request(), gpuResolution()) }
        entered.await()

        val progress = coordinator.generationProgress.value
        assertTrue(progress.active)
        assertNotNull(progress.startedAtNanos)
        assertNotNull(progress.firstEmissionAtNanos)
        assertNotNull(progress.lastEmissionAtNanos)
        assertEquals(2L, progress.emissionCount)
        assertTrue(checkNotNull(progress.lastEmissionAtNanos) >= checkNotNull(progress.firstEmissionAtNanos))

        release.complete(Unit)
        job.await()
        assertFalse(coordinator.generationProgress.value.active)
        assertEquals(2L, coordinator.generationProgress.value.emissionCount)
    }

    @Test
    fun `cancellation with confirmed native terminal releases owner and returns to idle`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val coordinator =
            LocalLlmRuntimeCoordinator { _, _, _, onPhase ->
                onPhase(LocalLlmRuntimePhase.GENERATING)
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    onPhase(LocalLlmRuntimePhase.CANCEL_REQUESTED)
                    onPhase(LocalLlmRuntimePhase.NATIVE_CANCEL_SIGNAL_SENT)
                    onPhase(LocalLlmRuntimePhase.NATIVE_CANCEL_TERMINAL_RECEIVED)
                    onPhase(LocalLlmRuntimePhase.CONVERSATION_CLOSED)
                    onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)
                    throw cancelled
                }
            }

        val job = launch {
            coordinator.generate("/model", request(), gpuResolution())
        }
        entered.await()
        job.cancelAndJoin()

        assertEquals(LocalLlmRuntimeState.IDLE, coordinator.state.value)
        assertFalse(coordinator.generationProgress.value.active)
    }

    @Test
    fun `unconfirmed native release poisons runtime and blocks a second heavy inference`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val attempts = AtomicInteger(0)
        val coordinator =
            LocalLlmRuntimeCoordinator { _, _, _, onPhase ->
                attempts.incrementAndGet()
                onPhase(LocalLlmRuntimePhase.GENERATING)
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    onPhase(LocalLlmRuntimePhase.CANCEL_REQUESTED)
                    onPhase(LocalLlmRuntimePhase.NATIVE_CANCEL_SIGNAL_SENT)
                    onPhase(LocalLlmRuntimePhase.RELEASE_UNCONFIRMED)
                    throw cancelled
                }
            }

        val job = launch {
            coordinator.generate("/model", request(), gpuResolution())
        }
        entered.await()
        job.cancelAndJoin()

        assertEquals(LocalLlmRuntimeState.POISONED, coordinator.state.value)
        assertFalse(coordinator.generationProgress.value.active)

        val secondFailure =
            runCatching {
                coordinator.generate("/model", request(), gpuResolution())
            }.exceptionOrNull()

        assertTrue(secondFailure is LocalLlmRuntimeException)
        assertEquals(
            LocalLlmFailureCode.RUNTIME_POISONED,
            (secondFailure as LocalLlmRuntimeException).failure.code,
        )
        assertEquals(1, attempts.get())
        assertEquals(LocalLlmRuntimeState.POISONED, coordinator.state.value)
    }

    @Test
    fun `conversation creation failure is classified and owner is released`() = runBlocking {
        val coordinator =
            LocalLlmRuntimeCoordinator { _, _, _, onPhase ->
                onPhase(LocalLlmRuntimePhase.CONVERSATION_CREATING)
                error("conversation failed")
            }

        val failure =
            runCatching {
                coordinator.generate("/model", request(), gpuResolution())
            }.exceptionOrNull()

        assertTrue(failure is LocalLlmRuntimeException)
        val classified = (failure as LocalLlmRuntimeException).failure
        assertEquals(LocalLlmFailureCode.CONVERSATION_CREATE_FAILED, classified.code)
        assertEquals(LocalLlmFailurePhase.CONVERSATION_CREATING, classified.phase)
        assertEquals(LocalLlmRuntimeState.IDLE, coordinator.state.value)
    }

    @Test
    fun `release failure is classified as poisoned and does not return to idle`() = runBlocking {
        val coordinator =
            LocalLlmRuntimeCoordinator { _, _, _, onPhase ->
                onPhase(LocalLlmRuntimePhase.GENERATING)
                onPhase(LocalLlmRuntimePhase.RELEASE_UNCONFIRMED)
                error("native release failed")
            }

        val failure =
            runCatching {
                coordinator.generate("/model", request(), gpuResolution())
            }.exceptionOrNull()

        assertTrue(failure is LocalLlmRuntimeException)
        val classified = (failure as LocalLlmRuntimeException).failure
        assertEquals(LocalLlmFailureCode.RUNTIME_POISONED, classified.code)
        assertEquals(LocalLlmFailurePhase.RELEASING, classified.phase)
        assertEquals(LocalLlmRuntimeState.POISONED, coordinator.state.value)
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
    ): LocalLlmGenerationResult {
        val diagnostics =
            LocalLlmDiagnostics(
                runtimeVersion = "0.18.0",
                modelIdentity = request.modelIdentity,
                backendResolution = resolution,
            )
        return LocalLlmGenerationResult(
            text = "ok",
            modelIdentity = request.modelIdentity,
            backendResolution = resolution,
            diagnostics = diagnostics,
        )
    }

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
