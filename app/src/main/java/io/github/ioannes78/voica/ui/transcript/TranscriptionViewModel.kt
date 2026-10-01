package io.github.ioannes78.voica.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.TranscriptionCoordinator
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TranscriptDisplaySegment(
    val segmentIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val text: String,
)

data class TranscriptDocument(
    val recordingId: String,
    val transcriptionId: String,
    val mode: String,
    val segments: List<TranscriptDisplaySegment>,
)

data class TranscriptVersionSummary(
    val recordingId: String,
    val transcriptionId: String,
    val mode: String,
    val completedAtMs: Long,
    val segmentCount: Int,
    val latest: Boolean,
)

class TranscriptionViewModel(
    private val coordinator: TranscriptionCoordinator,
    private val repository: TranscriptionRepository,
) : ViewModel() {
    val runState: StateFlow<TranscriptionRunState> = coordinator.state

    private val mutableDocument = MutableStateFlow<TranscriptDocument?>(null)
    val document: StateFlow<TranscriptDocument?> = mutableDocument.asStateFlow()

    private val mutableVersions = MutableStateFlow<List<TranscriptVersionSummary>>(emptyList())
    val versions: StateFlow<List<TranscriptVersionSummary>> = mutableVersions.asStateFlow()

    private val mutableVersionsRecordingId = MutableStateFlow<String?>(null)
    val versionsRecordingId: StateFlow<String?> = mutableVersionsRecordingId.asStateFlow()

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.state.collectLatest { state ->
                if (state is TranscriptionRunState.Completed) {
                    mutableDocument.value =
                        TranscriptDocument(
                            recordingId = state.recordingId,
                            transcriptionId = state.transcriptionId,
                            mode = state.mode.name,
                            segments = state.segments.map(::toDisplay),
                        )
                    if (mutableVersionsRecordingId.value == state.recordingId) {
                        loadVersions(state.recordingId)
                    }
                }
            }
        }
    }

    fun startFast(recordingId: String) {
        start(recordingId, TranscriptionMode.FAST)
    }

    fun startHighQuality(recordingId: String) {
        start(recordingId, TranscriptionMode.HIGH_QUALITY)
    }

    fun cancel() {
        coordinator.cancel()
    }

    fun viewVersions(recordingId: String) {
        viewModelScope.launch {
            mutableDocument.value = null
            loadVersions(recordingId)
        }
    }

    fun selectVersion(transcriptionId: String) {
        viewModelScope.launch {
            val transcription = repository.find(transcriptionId)
            if (transcription == null ||
                transcription.state != TranscriptionStateValue.COMPLETED
            ) {
                mutableNotice.value = "转写版本不存在或尚未完成"
                return@launch
            }
            val segments =
                repository.loadSegments(transcription.id)
                    .map { segment ->
                        TranscriptDisplaySegment(
                            segmentIndex = segment.segmentIndex,
                            startSampleIndex = segment.startSampleIndex,
                            endSampleIndexExclusive = segment.endSampleIndexExclusive,
                            text = segment.finalText,
                        )
                    }
            mutableDocument.value =
                TranscriptDocument(
                    recordingId = transcription.recordingId,
                    transcriptionId = transcription.id,
                    mode = transcription.mode,
                    segments = segments,
                )
            mutableNotice.value = null
        }
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    private suspend fun loadVersions(recordingId: String) {
        val completed =
            repository.observeVersions(recordingId)
                .first()
                .filter { it.state == TranscriptionStateValue.COMPLETED }

        if (completed.isEmpty()) {
            mutableVersionsRecordingId.value = recordingId
            mutableVersions.value = emptyList()
            mutableNotice.value = "这条录音还没有已完成的转写结果"
            return
        }

        mutableVersionsRecordingId.value = recordingId
        mutableVersions.value =
            completed.mapIndexed { index, transcription ->
                TranscriptVersionSummary(
                    recordingId = transcription.recordingId,
                    transcriptionId = transcription.id,
                    mode = transcription.mode,
                    completedAtMs =
                        transcription.completedAtMs ?: transcription.updatedAtMs,
                    segmentCount = repository.loadSegments(transcription.id).size,
                    latest = index == 0,
                )
            }
        mutableNotice.value = null
    }

    private fun start(
        recordingId: String,
        mode: TranscriptionMode,
    ) {
        mutableNotice.value = null
        mutableDocument.value = null
        if (!coordinator.start(recordingId, mode)) {
            mutableNotice.value = "已有转写任务正在运行，请先完成或取消当前任务"
        }
    }

    private fun toDisplay(segment: TranscriptSegment) =
        TranscriptDisplaySegment(
            segmentIndex = segment.segmentIndex,
            startSampleIndex = segment.startSampleIndex,
            endSampleIndexExclusive = segment.endSampleIndexExclusive,
            text = segment.finalText,
        )

    class Factory(
        private val coordinator: TranscriptionCoordinator,
        private val repository: TranscriptionRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranscriptionViewModel(
                coordinator = coordinator,
                repository = repository,
            ) as T
    }
}
