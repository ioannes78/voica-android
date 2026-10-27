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
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmModelIdentity
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryLongRoutingTest {
    @Test
    fun overBudgetInputRoutesToLongRunnerWithoutUsingDirectGenerateCallSites() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val persistence = FakePersistence()
            val longRunner = FakeLongRunner()
            var directGenerationCalls = 0
            val coordinator =
                OfflineSummaryCoordinator(
                    scope = scope,
                    resolveInput = { longInput() },
                    verifyModelReady = { modelIdentity() },
                    generateLocal = { _, _ ->
                        directGenerationCalls += 1
                        generation(finalJson())
                    },
                    persistence = persistence,
                    onCompleted = { _, _ -> },
                    criticalTaskReason = { null },
                    longRunner = longRunner,
                )

            assertTrue(coordinator.start(RECORDING_ID) is OfflineSummaryStartOutcome.Started)
            val terminal = awaitTerminal(coordinator)

            assertEquals(OfflineSummaryRunPhase.COMPLETED, terminal.phase)
            assertEquals(0, directGenerationCalls)
            assertEquals(1, longRunner.newExecutions)
            assertTrue(longRunner.lastDirectEstimate > 2_528)
            assertEquals(listOf("CREATED", "PREPARING"), persistence.transitions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun explicitResumeUsesLongRunnerWithoutCreatingNewSummary() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val persistence = FakePersistence()
            val longRunner = FakeLongRunner()
            val coordinator =
                OfflineSummaryCoordinator(
                    scope = scope,
                    resolveInput = { longInput() },
                    verifyModelReady = { modelIdentity() },
                    generateLocal = { _, _ -> error("direct generation must not run during resume") },
                    persistence = persistence,
                    onCompleted = { _, _ -> },
                    criticalTaskReason = { null },
                    longRunner = longRunner,
                )

            val outcome = coordinator.resume(RECORDING_ID, SUMMARY_ID)
            assertTrue(outcome is OfflineSummaryStartOutcome.Started)
            val terminal = awaitTerminal(coordinator)

            assertEquals(OfflineSummaryRunPhase.COMPLETED, terminal.phase)
            assertEquals(1, longRunner.prepareResumeCalls)
            assertEquals(1, longRunner.resumeExecutions)
            assertTrue(persistence.transitions.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun startupReconcileAddsLongAndDirectCountsWithoutAutoGeneration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val persistence = FakePersistence().apply { interruptCount = 2 }
            val longRunner = FakeLongRunner().apply { reconcileCount = 3 }
            var directGenerationCalls = 0
            val coordinator =
                OfflineSummaryCoordinator(
                    scope = scope,
                    resolveInput = { longInput() },
                    verifyModelReady = { modelIdentity() },
                    generateLocal = { _, _ ->
                        directGenerationCalls += 1
                        generation(finalJson())
                    },
                    persistence = persistence,
                    onCompleted = { _, _ -> },
                    criticalTaskReason = { null },
                    longRunner = longRunner,
                )

            assertEquals(5, coordinator.reconcileOnStartup())
            assertEquals(0, coordinator.reconcileOnStartup())
            assertEquals(0, directGenerationCalls)
            assertEquals(0, longRunner.newExecutions)
            assertEquals(0, longRunner.resumeExecutions)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitTerminal(coordinator: OfflineSummaryCoordinator): OfflineSummaryRunState =
        withTimeout(5_000L) {
            coordinator.state
                .map { it[RECORDING_ID] }
                .filterNotNull()
                .first {
                    it.phase == OfflineSummaryRunPhase.COMPLETED ||
                        it.phase == OfflineSummaryRunPhase.FAILED ||
                        it.phase == OfflineSummaryRunPhase.CANCELLED
                }
        }

    private class FakeLongRunner : OfflineSummaryLongRunnerPort {
        var newExecutions = 0
        var resumeExecutions = 0
        var prepareResumeCalls = 0
        var lastDirectEstimate = 0
        var reconcileCount = 0

        override suspend fun executeNew(
            summaryId: String,
            input: StructuredTranscriptInput,
            preparedIdentity: LocalLlmModelIdentity,
            directPromptEstimatedTokens: Int,
            onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
        ): OfflineSummaryLongRunResult {
            newExecutions += 1
            lastDirectEstimate = directPromptEstimatedTokens
            onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.ANALYZING, "long"))
            onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.VALIDATING, "final"))
            return OfflineSummaryLongRunResult(generation(finalJson()), repairAttempted = false)
        }

        override suspend fun prepareResume(
            summaryId: String,
            input: StructuredTranscriptInput,
        ): OfflineSummaryRunPhase {
            prepareResumeCalls += 1
            return OfflineSummaryRunPhase.ANALYZING
        }

        override suspend fun executeResume(
            summaryId: String,
            input: StructuredTranscriptInput,
            preparedIdentity: LocalLlmModelIdentity,
            onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
        ): OfflineSummaryLongRunResult {
            resumeExecutions += 1
            onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.ANALYZING, "resume"))
            return OfflineSummaryLongRunResult(generation(finalJson()), repairAttempted = false)
        }

        override suspend fun fail(summaryId: String, code: OfflineSummaryFailureCode, message: String?): Boolean = true

        override suspend fun cancel(summaryId: String): Boolean = true

        override suspend fun reconcileOnStartup(): Int {
            val count = reconcileCount
            reconcileCount = 0
            return count
        }
    }

    private class FakePersistence : OfflineSummaryPersistencePort {
        val transitions = mutableListOf<String>()
        var interruptCount = 0

        override suspend fun create(input: StructuredTranscriptInput, modelIdentitySnapshot: String): String {
            transitions += "CREATED"
            return SUMMARY_ID
        }

        override suspend fun markPreparing(summaryId: String) {
            transitions += "PREPARING"
        }

        override suspend fun markAnalyzing(summaryId: String) {
            transitions += "ANALYZING"
        }

        override suspend fun markValidating(summaryId: String) {
            transitions += "VALIDATING"
        }

        override suspend fun complete(
            summaryId: String,
            result: AiSummaryResult,
            evidence: List<OfflineSummaryEvidencePersistenceDraft>,
        ) {
            transitions += "COMPLETED"
        }

        override suspend fun fail(summaryId: String, code: OfflineSummaryFailureCode, sanitizedMessage: String?) {
            transitions += "FAILED"
        }

        override suspend fun cancel(summaryId: String) {
            transitions += "CANCELLED"
        }

        override suspend fun interruptAllActive(): Int {
            val count = interruptCount
            interruptCount = 0
            return count
        }
    }

    private fun longInput(): StructuredTranscriptInput =
        StructuredTranscriptInput(
            recordingId = RECORDING_ID,
            transcriptionId = "transcription-long",
            transcriptionRevisionId = "revision-long",
            inputContentDigest = "c".repeat(64),
            transcriptionMode = "HIGH_QUALITY",
            canonicalAssetId = "canonical-long",
            canonicalSha256 = "d".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 16_000L,
            languageConfig = "zh",
            alignmentId = null,
            units =
                listOf(
                    StructuredTranscriptUnit(
                        evidence =
                            EvidenceSourceRef(
                                ref = "S00001",
                                sourceKind = TranscriptEvidenceSourceKind.TRANSCRIPT_SEGMENT,
                                sourceId = "segment-long",
                                speakerId = null,
                                speakerDisplayName = null,
                                assignmentQuality = null,
                                overlap = false,
                                ambiguous = false,
                                startSampleIndex = 0L,
                                endSampleIndexExclusive = 16_000L,
                            ),
                        text = "会议内容和后续行动。".repeat(4_000),
                        detectedLanguage = "zh",
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
                resolvedBackend = LocalLlmBackend.CPU,
                reason = "TEST_CPU",
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

    private fun finalJson(): String =
        """{"schemaVersion":1,"contentType":"MEETING","classificationConfidence":0.9,"title":"t","overview":"o","sections":[]}"""

    private companion object {
        const val RECORDING_ID = "recording-long"
        const val SUMMARY_ID = "summary-long"
    }
}
