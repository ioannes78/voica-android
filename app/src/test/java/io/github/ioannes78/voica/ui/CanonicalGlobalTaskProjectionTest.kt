package io.github.ioannes78.voica.ui

import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.CanonicalGenerationPhase
import io.github.ioannes78.voica.CanonicalGenerationProgress
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.RecordingDerivation
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.ui.status.AppActivityLifecycleClass
import io.github.ioannes78.voica.ui.status.AppActivityProjectionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalGlobalTaskProjectionTest {
    @Test
    fun activeCanonicalFactResolvesAsBackgroundTask() {
        val progress =
            CanonicalGenerationProgress(
                recordingId = "recording-a",
                phase = CanonicalGenerationPhase.WRITING,
                processedDurationUs = 5_000_000L,
                totalDurationUs = 10_000_000L,
                startedAtMs = 10L,
                updatedAtMs = 20L,
            )

        val projection =
            AppActivityProjectionResolver.resolve(
                buildCanonicalActivityFacts(
                    progressByRecording = mapOf("recording-a" to progress),
                    recordings = listOf(recording("recording-a", "A")),
                ),
            )

        assertEquals(1, projection.backgroundTasks.size)
        assertEquals(0.5f, projection.backgroundTasks.single().progressFraction)
    }

    @Test
    fun cancellingUsesExplicitStateInsteadOfStalePercent() {
        val progress =
            CanonicalGenerationProgress(
                recordingId = "recording-a",
                phase = CanonicalGenerationPhase.CANCELLING,
                processedDurationUs = 5_000_000L,
                totalDurationUs = 10_000_000L,
                startedAtMs = 10L,
                updatedAtMs = 20L,
                cancellable = false,
            )

        assertEquals("正在取消", canonicalProgressText(progress))
    }

    @Test
    fun durableFailureProjectsAsAttentionWhenNoActiveProgressExists() {
        val failed =
            RecordingDerivation(
                profileId = CanonicalAudioCoordinator.PROFILE_ID,
                sourceSha256 = "a".repeat(64),
                sourceAssetId = "source-a",
                outputAssetId = null,
                pipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
                state = AudioDerivationState.FAILED_RECOVERABLE,
                errorCode = "LOCAL_WRITE_FAILED",
                errorDetail = "failed",
            )

        val attention =
            AppActivityProjectionResolver.resolve(
                buildCanonicalActivityFacts(
                    progressByRecording = emptyMap(),
                    recordings = listOf(recording("recording-a", "A", listOf(failed))),
                ),
            ).attentionItems.single()

        assertEquals(AppActivityLifecycleClass.INTERRUPTED_ATTENTION, attention.lifecycleClass)
    }

    @Test
    fun readyAndCancelledDerivationsDoNotCreateActivityFacts() {
        val ready = derivation(AudioDerivationState.READY)
        val cancelled = derivation(AudioDerivationState.CANCELLED)

        assertTrue(
            buildCanonicalActivityFacts(
                progressByRecording = emptyMap(),
                recordings =
                    listOf(
                        recording("recording-ready", "Ready", listOf(ready)),
                        recording("recording-cancelled", "Cancelled", listOf(cancelled)),
                    ),
            ).isEmpty(),
        )
    }

    private fun recording(
        id: String,
        name: String,
        derivations: List<RecordingDerivation> = emptyList(),
    ) =
        RecordingLibraryItem(
            id = id,
            sourceRemoteIdentity = null,
            sourceDeviceAddress = null,
            originalFilename = "$id.wav",
            displayName = name,
            recordedAtLocalIso = null,
            deviceReportedDurationMs = null,
            downloadedAtMs = null,
            state = "ACTIVE",
            assets = emptyList(),
            derivations = derivations,
        )

    private fun derivation(state: String) =
        RecordingDerivation(
            profileId = CanonicalAudioCoordinator.PROFILE_ID,
            sourceSha256 = "b".repeat(64),
            sourceAssetId = "source",
            outputAssetId = null,
            pipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
            state = state,
            errorCode = null,
            errorDetail = null,
        )
}
