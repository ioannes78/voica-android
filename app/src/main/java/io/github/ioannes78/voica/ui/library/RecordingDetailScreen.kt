package io.github.ioannes78.voica.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ioannes78.voica.DiarizationBenchmarkRunner
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.SpeechBenchmarkRunner
import io.github.ioannes78.voica.Stage13B5Qa4PageVisibility
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.ui.ai.AiSummaryContentViewModel
import io.github.ioannes78.voica.ui.ai.AiSummaryViewModel
import io.github.ioannes78.voica.ui.diarization.DiarizationViewModel
import io.github.ioannes78.voica.ui.playback.PlaybackViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptContentViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel

enum class RecordingDetailDestination {
    PLAYBACK,
    TRANSCRIPT,
    SUMMARY,
    ANNOTATIONS,
}

@Composable
fun RecordingDetailScreen(
    padding: PaddingValues,
    recording: RecordingLibraryItem,
    playbackViewModel: PlaybackViewModel,
    transcriptionViewModel: TranscriptionViewModel,
    transcriptContentViewModel: TranscriptContentViewModel,
    diarizationViewModel: DiarizationViewModel,
    transcriptPlaybackSyncViewModel: TranscriptPlaybackSyncViewModel,
    aiSummaryViewModel: AiSummaryViewModel,
    aiSummaryContentViewModel: AiSummaryContentViewModel,
    speechBenchmarkRunner: SpeechBenchmarkRunner? = null,
    diarizationBenchmarkRunner: DiarizationBenchmarkRunner? = null,
    initialSearchTarget: SearchDocumentEntity? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onExportCanonical: (String) -> Unit,
    onExportOriginal: (String) -> Unit,
    onShareCanonical: (String) -> Unit,
    onShareOriginal: (String) -> Unit,
    onGenerateCanonical: (String) -> Unit,
    onCancelCanonical: (String) -> Unit,
    deviceRecordingActive: Boolean,
    initialDestination: RecordingDetailDestination = RecordingDetailDestination.PLAYBACK,
    navigationRequestToken: Int = 0,
    onDestinationChanged: (RecordingDetailDestination?) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var latestDestination by remember(recording.id) {
        mutableStateOf<RecordingDetailDestination?>(initialDestination)
    }

    DisposableEffect(lifecycleOwner, recording.id) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME,
                -> Stage13B5Qa4PageVisibility.update(recording.id, latestDestination)

                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> Stage13B5Qa4PageVisibility.clear(recording.id)

                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Stage13B5Qa4PageVisibility.update(recording.id, latestDestination)
        } else {
            Stage13B5Qa4PageVisibility.clear(recording.id)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            Stage13B5Qa4PageVisibility.clear(recording.id)
        }
    }

    RecordingDetailProductScreen(
        padding = padding,
        recording = recording,
        playbackViewModel = playbackViewModel,
        transcriptionViewModel = transcriptionViewModel,
        transcriptContentViewModel = transcriptContentViewModel,
        diarizationViewModel = diarizationViewModel,
        transcriptPlaybackSyncViewModel = transcriptPlaybackSyncViewModel,
        aiSummaryViewModel = aiSummaryViewModel,
        aiSummaryContentViewModel = aiSummaryContentViewModel,
        speechBenchmarkRunner = speechBenchmarkRunner,
        diarizationBenchmarkRunner = diarizationBenchmarkRunner,
        initialSearchTarget = initialSearchTarget,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onRename = onRename,
        onDelete = onDelete,
        onExportCanonical = onExportCanonical,
        onExportOriginal = onExportOriginal,
        onShareCanonical = onShareCanonical,
        onShareOriginal = onShareOriginal,
        onGenerateCanonical = onGenerateCanonical,
        onCancelCanonical = onCancelCanonical,
        deviceRecordingActive = deviceRecordingActive,
        initialDestination = initialDestination,
        navigationRequestToken = navigationRequestToken,
        onDestinationChanged = { destination ->
            latestDestination = destination
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Stage13B5Qa4PageVisibility.update(recording.id, destination)
            } else {
                Stage13B5Qa4PageVisibility.clear(recording.id)
            }
            onDestinationChanged(destination)
        },
    )
}

internal fun shouldShowTranscriptionStatus(
    state: TranscriptionRunState,
    recordingId: String,
): Boolean =
    when (state) {
        is TranscriptionRunState.Running -> state.recordingId == recordingId
        is TranscriptionRunState.Failed -> state.recordingId == recordingId
        TranscriptionRunState.Idle,
        is TranscriptionRunState.Completed,
        is TranscriptionRunState.Cancelled,
        -> false
    }

internal fun shouldShowDiarizationStatus(
    state: DiarizationRunState,
    recordingId: String,
): Boolean =
    when (state) {
        is DiarizationRunState.Running -> state.recordingId == recordingId
        is DiarizationRunState.Failed -> state.recordingId == recordingId
        DiarizationRunState.Idle,
        is DiarizationRunState.Completed,
        is DiarizationRunState.Cancelled,
        -> false
    }
