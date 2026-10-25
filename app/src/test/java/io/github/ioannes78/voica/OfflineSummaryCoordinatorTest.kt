package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.EvidenceSourceRef
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.StructuredTranscriptUnit
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import io.github.ioannes78.voica.database.OfflineSummaryEvidencePersistenceDraft
import io.github.ioannes78.voica.localllm.LiteRtLmRuntimeContract
import io.github.ioannes78.voica.localllm.LocalLlmBackend
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmBackendResolution
import io.github.ioannes78.voica.localllm.LocalLlmDiagnostics
import io.github.ioannes78.voica.localllm.LocalLlmFailure
import io.github.ioannes78.voica.localllm.LocalLlmFailureCode
import io.github.ioannes78.voica.localllm.LocalLlmFailurePhase
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmModelIdentity
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryCoordinatorTest {
    @Test
    fun successfulDirectSummaryUsesAutoAndResolvesEffectiveTranscriptExactlyOnce() = runBlocking {
        val appScope = appScope()
        try {
            val persistence = FakePersistence()
            var resolveCalls = 0
            var completionCalls = 0
            var observedConfig: LocalLlmRuntimeConfig? = null
            val coordinator =
                coordinator(
                    scope = appScope,
                    persistence = persistence,
                    resolveInput = {
                        resolveCalls += 1
                        input()
                    },
                    generateLocal = { _, config ->
                        observedConfig = config
                        generation(validJson())
                    },
                    onCompleted = { _, _ -> completionCalls += 1 },
                )

            val started = coordinator.start(RECORDING_ID)
            assertTrue(started is OfflineSummaryStartOutcome.Started)
            val terminal = awaitTerminal(coordinator)

            assertEquals(OfflineSummaryRunPhase.COMPLETED, terminal.phase)
            assertEquals(1, resolveCalls)
            assertEquals(1, completionCalls)
            assertEquals(LocalLlmBackendPolicy.AUTO, observedConfig?.backendPolicy)
            assertEquals(4_000, observedConfig?.maxNumTokens)
            assertEquals(
                listOf("CREATED", "PREPARING", "ANALYZING", "VALIDATING", "COMPLETED"),
                persistence.transitions,
            )
            assertEquals(1, persistence.completedEvidence.size)
            assertEquals("S00001", persistence.completedEvidence.single().sourceRef)
            assertTrue(persistence.modelIdentitySnapshot.contains(Stage172OfflineSummaryModelContract.ARTIFACT_SHA256))
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun missingModelFailsPersistedRunWithoutInvokingRuntime() = runBlocking {
        val appScope = appScope()
        try {
            val persistence = FakePersistence()
            var generationCalls = 0
            val coordinator =
                coordinator(
                    scope = appScope,
                    persistence = persistence,
                    verifyModelReady = {
                        throw OfflineSummaryCoordinatorException(
                            OfflineSummaryFailureCode.MODEL_NOT_INSTALLED,
                            "missing model",
                        )
                    },
                    generateLocal = { _, _ ->
                        generationCalls += 1
                        generation(validJson())
                    },
                )

            assertTrue(coordinator.start(RECORDING_ID) is OfflineSummaryStartOutcome.Started)
            val terminal = awaitTerminal(coordinator)

            assertEquals(OfflineSummaryRunPhase.FAILED, terminal.phase)
            assertEquals(OfflineSummaryFailureCode.MODEL_NOT_INSTALLED, terminal.failureCode)
            assertEquals(0, generationCalls)
            assertEquals(OfflineSummaryFailureCode.MODEL_NOT_INSTALLED, persistence.failedCode)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun duplicateStartForSameRecordingIsRejectedBeforeSecondInputResolution() = runBlocking {
        val appScope = appScope()
        try {
            val persistence = FakePersistence()
            val enteredRuntime = CompletableDeferred<Unit>()
            val releaseRuntime = CompletableDeferred<Unit>()
            var resolveCalls = 0
            val coordinator =
                coordinator(
                    scope = appScope,
                    persistence = persistence,
                    resolveInput = {
                        resolveCalls += 1
                        input()
                    },
                    generateLocal = { _, _ ->
                        enteredRuntime.complete(Unit)
                        releaseRuntime.await()
                        generation(validJson())
                    },
                )

            assertTrue(coordinator.start(RECORDING_ID) is OfflineSummaryStartOutcome.Started)
            enteredRuntime.await()
            val duplicate = coordinator.start(RECORDING_ID)

            assertTrue(duplicate is OfflineSummaryStartOutcome.Rejected)
            duplicate as OfflineSummaryStartOutcome.Rejected
            assertEquals(OfflineSummaryFailureCode.RUNTIME_BUSY, duplicate.code)
            assertEquals(1, resolveCalls)

            releaseRuntime.complete(Unit)
            assertEquals(OfflineSummaryRunPhase.COMPLETED, awaitTerminal(coordinator).phase)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun p1RuntimeBusyMapsToProductRuntimeBusy() = runBlocking {
        assertRuntimeFailureMaps(
            failureCode = LocalLlmFailureCode.BUSY,
            expected = OfflineSummaryFailureCode.RUNTIME_BUSY,
        )
    }

    @Test
    fun p1ResourceBlockMapsToProductResourceBlocked() = runBlocking {
        assertRuntimeFailureMaps(
            failureCode = LocalLlmFailureCode.INSUFFICIENT_MEMORY,
            expected = OfflineSummaryFailureCode.RESOURCE_BLOCKED,
        )
    }

    @Test
    fun criticalSpeechTaskBlocksBeforeInputOrModelWork() = runBlocking {
        val appScope = appScope()
        try {
            var resolveCalls = 0
            var modelCalls = 0
            val coordinator =
                coordinator(
                    scope = appScope,
                    persistence = FakePersistence(),
                    resolveInput = {
                        resolveCalls += 1
                        input()
                    },
                    verifyModelReady = {
                        modelCalls += 1
                        modelIdentity()
                    },
                    criticalTaskReason = { "录音正在运行" },
                )

            val result = coordinator.start(RECORDING_ID)

            assertTrue(result is OfflineSummaryStartOutcome.Rejected)
            result as OfflineSummaryStartOutcome.Rejected
            assertEquals(OfflineSummaryFailureCode.RESOURCE_BLOCKED, result.code)
            assertEquals(0, resolveCalls)
            assertEquals(0, modelCalls)
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun generationIsOwnedByApplicationScopeNotCallerScope() = runBlocking {
        val appScope = appScope()
        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
        try {
            val enteredRuntime = CompletableDeferred<Unit>()
            val releaseRuntime = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(
                    scope = appScope,
                    persistence = FakePersistence(),
                    generateLocal = { _, _ ->
                        enteredRuntime.complete(Unit)
                        releaseRuntime.await()
                        generation(validJson())
                    },
                )
            val startResult = CompletableDeferred<OfflineSummaryStartOutcome>()

            callerScope.launch {
                startResult.complete(coordinator.start(RECORDING_ID))
            }.join()
            assertTrue(startResult.await() is OfflineSummaryStartOutcome.Started)
            enteredRuntime.await()

            callerJob.cancel()
            releaseRuntime.complete(Unit)

            assertEquals(OfflineSummaryRunPhase.COMPLETED, awaitTerminal(coordinator).phase)
        } finally {
            callerJob.cancel()
            appScope.cancel()
        }
    }

    private suspend fun assertRuntimeFailureMaps(
        failureCode: LocalLlmFailureCode,
        expected: OfflineSummaryFailureCode,
    ) {
        val appScope = appScope()
        try {
            val persistence = FakePersistence()
            val coordinator =
                coordinator(
                    scope = appScope,
                    persistence = persistence,
                    generateLocal = { _, _ ->
                        throw LocalLlmRuntimeException(
                            LocalLlmFailure(
                                code = failureCode,
                                phase = LocalLlmFailurePhase.PREFLIGHT,
                                message = failureCode.name,
                                requestedPolicy = LocalLlmBackendPolicy.AUTO,
                            ),
                        )
                    },
                )

            assertTrue(coordinator.start(RECORDING_ID) is OfflineSummaryStartOutcome.Started)
            val terminal = awaitTerminal(coordinator)

            assertEquals(OfflineSummaryRunPhase.FAILED, terminal.phase)
            assertEquals(expected, terminal.failureCode)
            assertEquals(expected, persistence.failedCode)
        } finally {
            appScope.cancel()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        persistence: OfflineSummaryPersistencePort,
        resolveInput: suspend (String) -> StructuredTranscriptInput = { input() },
        verifyModelReady: suspend () -> LocalLlmModelIdentity = { modelIdentity() },
        generateLocal: suspend (String, LocalLlmRuntimeConfig) -> LocalLlmGenerationResult =
            { _, _ -> generation(validJson()) },
        onCompleted: suspend (String, String) -> Unit = { _, _ -> },
        criticalTaskReason: () -> String? = { null },
    ): OfflineSummaryCoordinator =
        OfflineSummaryCoordinator(
            scope = scope,
            resolveInput = resolveInput,
            verifyModelReady = verifyModelReady,
            generateLocal = generateLocal,
            persistence = persistence,
            onCompleted = onCompleted,
            criticalTaskReason = criticalTaskReason,
        )

    private suspend fun awaitTerminal(coordinator: OfflineSummaryCoordinator): OfflineSummaryRunState =
        withTimeout(5_000L) {
            coordinator.state
                .map { it[RECORDING_ID] }
                .filterNotNull()
                .first {
                    it.phase == OfflineSummaryRunPhase.COMPLETED ||
                        it.phase == OfflineSummaryRunPhase.FAILED
                }
        }

    private fun appScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun input(): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = RECORDING_ID,
            transcriptionId = "transcription-1",
            transcriptionRevisionId = "revision-1",
            inputContentDigest = "a".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical-1",
            canonicalSha256 = "b".repeat(64),
            canonicalProfileId = "pcm16k-mono",
            totalSampleCount = 16_000L,
            languageConfig = "zh",
            alignmentId = "alignment-1",
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                sourceId = "segment-1",
                                speakerId = "speaker-1",
                                speakerDisplayName = "甲",
                                assignmentQuality = "ASSIGNED",
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 0L,
                                endSampleIndexExclusive = 8_000L,
                            ),
                        text = "第一版计划周五发布。",
                        detectedLanguage = "zh",
                        speakerId = "speaker-1",
                        speakerDisplayName = "甲",
                    ),
                ),
        )

    private fun modelIdentity(): LocalLlmModelIdentity =
        LocalLlmModelIdentity(
            modelId = Stage172OfflineSummaryModelContract.MODEL_ID,
            modelFamily = "Gemma 4 E2B",
            artifact = Stage172OfflineSummaryModelContract.ARTIFACT,
            revision = Stage172OfflineSummaryModelContract.MODEL_REVISION,
            sha256 = Stage172OfflineSummaryModelContract.ARTIFACT_SHA256,
            runtimeId = LiteRtLmRuntimeContract.RUNTIME_ID,
            runtimeVersion = LiteRtLmRuntimeContract.RUNTIME_VERSION,
        )

    private fun generation(text: String): LocalLlmGenerationResult {
        val identity = modelIdentity()
        val resolution =
            LocalLlmBackendResolution(
                requestedPolicy = LocalLlmBackendPolicy.AUTO,
                resolvedBackend = LocalLlmBackend.GPU,
                reason = "GPU_AVAILABLE",
                fallbackAttempted = false,
            )
        return LocalLlmGenerationResult(
            text = text,
            modelIdentity = identity,
            backendResolution = resolution,
            diagnostics =
                LocalLlmDiagnostics(
                    runtimeVersion = LiteRtLmRuntimeContract.RUNTIME_VERSION,
                    modelIdentity = identity,
                    backendResolution = resolution,
                ),
        )
    }

    private fun validJson(): String =
        """
        {
          "schemaVersion":1,
          "contentType":"MEETING",
          "classificationConfidence":0.9,
          "title":"发布计划",
          "overview":"第一版计划周五发布。",
          "sections":[
            {
              "id":"section-1",
              "type":"SUMMARY",
              "label":"总结",
              "items":[
                {
                  "id":"item-1",
                  "text":"第一版计划周五发布。",
                  "evidenceRefs":["S00001"],
                  "epistemicStatus":"TRANSCRIPT_STATED",
                  "attributes":{}
                }
              ]
            }
          ]
        }
        """.trimIndent()

    private class FakePersistence : OfflineSummaryPersistencePort {
        val transitions = mutableListOf<String>()
        var modelIdentitySnapshot: String = ""
        var failedCode: OfflineSummaryFailureCode? = null
        var completedResult: AiSummaryResult? = null
        var completedEvidence: List<OfflineSummaryEvidencePersistenceDraft> = emptyList()

        override suspend fun create(
            input: StructuredTranscriptInput,
            modelIdentitySnapshot: String,
        ): String {
            assertEquals(RECORDING_ID, input.recordingId)
            this.modelIdentitySnapshot = modelIdentitySnapshot
            transitions += "CREATED"
            return "summary-1"
        }

        override suspend fun markPreparing(summaryId: String) {
            assertEquals("summary-1", summaryId)
            transitions += "PREPARING"
        }

        override suspend fun markAnalyzing(summaryId: String) {
            assertEquals("summary-1", summaryId)
            transitions += "ANALYZING"
        }

        override suspend fun markValidating(summaryId: String) {
            assertEquals("summary-1", summaryId)
            transitions += "VALIDATING"
        }

        override suspend fun complete(
            summaryId: String,
            result: AiSummaryResult,
            evidence: List<OfflineSummaryEvidencePersistenceDraft>,
        ) {
            assertEquals("summary-1", summaryId)
            completedResult = result
            completedEvidence = evidence
            transitions += "COMPLETED"
        }

        override suspend fun fail(
            summaryId: String,
            code: OfflineSummaryFailureCode,
            sanitizedMessage: String?,
        ) {
            assertEquals("summary-1", summaryId)
            failedCode = code
            transitions += "FAILED:$code"
            assertFalse(sanitizedMessage?.contains('\n') == true)
        }
    }

    private companion object {
        const val RECORDING_ID = "recording-1"
    }
}
