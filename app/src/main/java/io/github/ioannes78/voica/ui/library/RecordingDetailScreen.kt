package io.github.ioannes78.voica.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import io.github.ioannes78.voica.DiarizationBenchmarkRunner
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.SpeechBenchmarkRunner
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
        onDestinationChanged = onDestinationChanged,
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
