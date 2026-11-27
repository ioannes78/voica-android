package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.*
import io.github.ioannes78.voica.localllm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OfflineQaCoordinatorTest {
    private val source = OfflineQaSnapshot.from(StructuredTranscriptInput(
        recordingId = "r", transcriptionId = "t", inputContentDigest = "a".repeat(64),
        transcriptionMode = "HIGH_QUALITY", canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
        canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1", totalSampleCount = 100,
        languageConfig = "zh", alignmentId = null,
        units = listOf(StructuredTranscriptUnit(EvidenceSourceRef("S00001",
            TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "seg", null, null, null,
            false, false, 0, 100), "项目预算50万元", "zh")),
    ))
    private val model = LocalLlmModelIdentity(
        Stage172OfflineSummaryModelContract.MODEL_ID, "Gemma",
        Stage172OfflineSummaryModelContract.ARTIFACT, Stage172OfflineSummaryModelContract.MODEL_REVISION,
        Stage172OfflineSummaryModelContract.ARTIFACT_SHA256, LiteRtLmRuntimeContract.RUNTIME_ID,
        LiteRtLmRuntimeContract.RUNTIME_VERSION,
    )
    private val gate = OfflineAiTaskAdmission()
    private val request = OfflineQaRequest(source.source, "项目预算是多少")
    private val valid = """{"schemaVersion":1,"status":"ANSWERED","claims":[{"text":"预算50万元","evidenceIds":["S00001"]}]}"""
    private fun generation(text: String = valid): LocalLlmGenerationResult {
        val backend = LocalLlmBackendResolution(LocalLlmBackendPolicy.CPU, LocalLlmBackend.CPU, "test")
        return LocalLlmGenerationResult(text, model, backend,
            LocalLlmDiagnostics(LiteRtLmRuntimeContract.RUNTIME_VERSION, model, backend))
    }
    private fun coordinator(
        read: suspend (OfflineQaSourceIdentity) -> OfflineQaSnapshot = { source },
        state: () -> LocalLlmRuntimeState = { LocalLlmRuntimeState.IDLE },
        critical: () -> String? = { null },
        generate: suspend (String, LocalLlmRuntimeConfig) -> LocalLlmGenerationResult = { _, _ -> generation() },
    ) = OfflineQaCoordinator(gate, read, { model }, generate, state, critical)

    @Test fun successUsesScopedSchemaAndRevalidatesBeforeAndAfterGeneration() = runBlocking {
        var reads = 0
        val c = coordinator(read = { reads++; source }, generate = { _, config ->
            assertEquals(2, reads)
            assertEquals(LocalLlmBackendPolicy.AUTO, config.backendPolicy)
            assertTrue(config.responseJsonSchema!!.contains("S00001"))
            assertFalse(config.speculativeDecoding)
            assertEquals(4000, config.maxNumTokens)
            assertEquals(1024, config.maxOutputTokens)
            assertEquals(OfflineAiTaskAdmission.Kind.QA, gate.current()?.kind)
            generation()
        })
        assertTrue(c.ask(request) is OfflineQaOutcome.Answer)
        assertEquals(3, reads)
        assertNull(gate.current())
    }

    @Test fun busyPoisonAndProtectedTaskRejectWithoutGeneration() = runBlocking {
        val lease = checkNotNull(gate.tryAcquire(OfflineAiTaskAdmission.Kind.SUMMARY, "other"))
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_BUSY), coordinator().ask(request))
        assertTrue(gate.release(lease))
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_POISONED),
            coordinator(state = { LocalLlmRuntimeState.POISONED }).ask(request))
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RESOURCE_BLOCKED),
            coordinator(critical = { "recording" }).ask(request))
        assertNull(gate.current())
    }

    @Test fun noCandidatesDoesNotInvokeRuntime() = runBlocking {
        val c = coordinator(generate = { _, _ -> error("must not generate") })
        val result = c.ask(request.copy(question = "火星殖民")) as OfflineQaOutcome.Answer
        assertEquals(OfflineQaStatus.INSUFFICIENT_EVIDENCE, result.result.status)
        assertNull(gate.current())
    }

    @Test fun invalidOutputAndChangedSourceAreNotPublished() = runBlocking {
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.INVALID_OUTPUT),
            coordinator(generate = { _, _ -> generation("{}") }).ask(request))
        var reads = 0
        val c = coordinator(read = {
            if (++reads == 3) throw io.github.ioannes78.voica.database.OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
            source
        })
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.SOURCE_CHANGED), c.ask(request))
        assertNull(gate.current())
    }

    @Test fun cancellationPropagatesAndReleasesBusinessLease() = runBlocking {
        val c = coordinator(generate = { _, _ -> throw CancellationException("test") })
        assertTrue(runCatching { c.ask(request) }.exceptionOrNull() is CancellationException)
        assertNull(gate.current())
    }

    @Test fun structuralRepairRunsOnlyOnceWithOriginalEvidenceAndLease() = runBlocking {
        var calls = 0
        var reads = 0
        val c = coordinator(read = { reads++; source }, generate = { prompt, config ->
            calls++
            assertEquals(OfflineAiTaskAdmission.Kind.QA, gate.current()?.kind)
            assertTrue(config.responseJsonSchema!!.contains("S00001"))
            if (calls == 1) generation("{}") else {
                assertTrue(prompt.startsWith(OfflineQaRepairPolicy.INSTRUCTION))
                assertTrue(prompt.contains("项目预算50万元"))
                generation()
            }
        })
        assertTrue(c.ask(request) is OfflineQaOutcome.Answer)
        assertEquals(2, calls)
        assertEquals(4, reads)
        assertNull(gate.current())
    }

    @Test fun repeatedInvalidStructureAndBadEvidenceDoNotRetryIndefinitely() = runBlocking {
        var calls = 0
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.INVALID_OUTPUT),
            coordinator(generate = { _, _ -> calls++; generation("{}") }).ask(request))
        assertEquals(2, calls)
        calls = 0
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.INVALID_EVIDENCE),
            coordinator(generate = { _, _ -> calls++; generation(valid.replace("S00001", "S99999")) }).ask(request))
        assertEquals(1, calls)
        assertNull(gate.current())
    }

    @Test fun protectedTaskOrPoisonAfterGenerationPreventsRepairAndPublication() = runBlocking {
        var calls = 0
        var blocked = false
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RESOURCE_BLOCKED),
            coordinator(critical = { if (blocked) "recording" else null }, generate = { _, _ ->
                calls++; blocked = true; generation()
            }).ask(request))
        assertEquals(1, calls)
        var state = LocalLlmRuntimeState.IDLE
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_POISONED),
            coordinator(state = { state }, generate = { _, _ ->
                calls++; state = LocalLlmRuntimeState.POISONED; generation("{}")
            }).ask(request))
        assertEquals(2, calls)
        assertNull(gate.current())
    }

    @Test fun staleSourceBeforeRepairPreventsSecondInference() = runBlocking {
        var calls = 0
        var reads = 0
        val c = coordinator(read = {
            if (++reads == 3) throw io.github.ioannes78.voica.database.OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
            source
        }, generate = { _, _ -> calls++; generation("{}") })
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.SOURCE_CHANGED), c.ask(request))
        assertEquals(1, calls)
        assertNull(gate.current())
    }

    @Test fun cancellationRetainsLeaseUntilNonCancellableCleanupFinishes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = coordinator(generate = { _, _ ->
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                }
            }
        })
        val job = launch { c.ask(request) }
        try {
            withTimeout(5000) { entered.await() }
            job.cancel()
            withTimeout(5000) { cleaning.await() }
            assertNull(gate.tryAcquire(OfflineAiTaskAdmission.Kind.SUMMARY, "other"))
            assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_BUSY), coordinator().ask(request))
        } finally {
            release.complete(Unit)
            job.cancelAndJoin()
        }
        assertNull(gate.current())
    }

    @Test fun unconfirmedReleaseBlocksRepair() = runBlocking {
        var state = LocalLlmRuntimeState.IDLE
        var calls = 0
        val c = coordinator(state = { state }, generate = { _, _ ->
            calls++
            state = LocalLlmRuntimeState.RELEASING
            generation("{}")
        })
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_BUSY), c.ask(request))
        assertEquals(1, calls)
        assertNull(gate.current())
    }

    @Test fun repairBudgetOverflowDoesNotInvokeSecondGeneration() = runBlocking {
        // Find a real packed prompt that fits initially but not with the fixed repair prefix.
        val estimator = ConservativeOfflineSummaryTokenEstimator
        val candidate = (1000..2400).asSequence().mapNotNull { n ->
            val s = OfflineQaSnapshot.from(StructuredTranscriptInput(
                recordingId = "r", transcriptionId = "t", inputContentDigest = "a".repeat(64),
                transcriptionMode = "HIGH_QUALITY", canonicalAssetId = "asset", canonicalSha256 = "b".repeat(64),
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1", totalSampleCount = 100,
                languageConfig = "zh", alignmentId = null,
                units = listOf(StructuredTranscriptUnit(EvidenceSourceRef("S00001",
                    TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT, "seg", null, null, null,
                    false, false, 0, 100), "项目预算50万元" + "字".repeat(n), "zh")),
            ))
            val q = OfflineQaRequest(s.source, request.question)
            val packed = OfflineQaContextPacker().pack(s, OfflineQaRetrieval().retrieve(q, s)) as? OfflineQaPackingResult.Ready
                ?: return@mapNotNull null
            val repairCost = estimator.estimateTokens(OfflineQaRepairPolicy.INSTRUCTION + "\n" + packed.prompt) +
                estimator.estimateTokens(packed.responseJsonSchema)
            if (repairCost > OfflineQaPromptContract.MAX_ESTIMATED_INPUT) s to q else null
        }.first()
        var calls = 0
        val c = coordinator(read = { candidate.first }, generate = { _, _ -> calls++; generation("{}") })
        assertEquals(OfflineQaOutcome.Failed(OfflineQaFailureCode.INVALID_OUTPUT), c.ask(candidate.second))
        assertEquals(1, calls)
        assertNull(gate.current())
    }
}
