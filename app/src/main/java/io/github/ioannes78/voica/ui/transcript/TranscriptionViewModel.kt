package io.github.ioannes78.voica.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.DiarizationCoordinator
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.SpeakerAlignmentRunState
import io.github.ioannes78.voica.TranscriptionCoordinator
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.TranscriptSpeakerAlignmentStateValue
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TranscriptDisplaySegment(
    val displayIndex: Int,
    val segmentIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val text: String,
    val speakerId: String? = null,
    val speakerOrdinal: Int? = null,
    val speakerDisplayName: String? = null,
    val speakerAssignmentAvailable: Boolean = false,
    val overlap: Boolean = false,
    val ambiguous: Boolean = false,
)

data class TranscriptSpeakerDisplay(
    val speakerId: String,
    val speakerOrdinal: Int,
    val displayName: String?,
)

data class TranscriptDocument(
    val recordingId: String,
    val transcriptionId: String,
    val mode: String,
    val segments: List<TranscriptDisplaySegment>,
    val diarizationRunId: String? = null,
    val alignmentId: String? = null,
    val speakers: List<TranscriptSpeakerDisplay> = emptyList(),
)

data class TranscriptVersionSummary(
    val recordingId: String,
    val transcriptionId: String,
    val mode: String,
    val completedAtMs: Long,
    val segmentCount: Int,
    val latest: Boolean,
)

internal class AutoDiarizationRequestTracker {
    private var requestedRecordingId: String? = null

    fun markStarted(recordingId: String) {
        requestedRecordingId = recordingId
    }

    fun consumeCompleted(recordingId: String): Boolean {
        if (requestedRecordingId != recordingId) return false
        requestedRecordingId = null
        return true
    }

    fun clearTerminal(recordingId: String) {
        if (requestedRecordingId == recordingId) {
            requestedRecordingId = null
        }
    }
}

class TranscriptionViewModel(
    private val coordinator: TranscriptionCoordinator,
    private val repository: TranscriptionRepository,
    private val diarizationCoordinator: DiarizationCoordinator,
    private val diarizationRepository: DiarizationRepository,
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

    private val autoDiarizationRequests = AutoDiarizationRequestTracker()

    init {
        viewModelScope.launch {
            coordinator.state.collectLatest { state ->
                when (state) {
                    is TranscriptionRunState.Completed -> {
                        val autoStartDiarization =
                            autoDiarizationRequests.consumeCompleted(state.recordingId)
                        loadDocument(
                            transcriptionId = state.transcriptionId,
                            autoStartDiarization = autoStartDiarization,
                        )
                        if (mutableVersionsRecordingId.value == state.recordingId) {
                            loadVersions(state.recordingId)
                        }
                    }

                    is TranscriptionRunState.Failed -> {
                        autoDiarizationRequests.clearTerminal(state.recordingId)
                    }

                    is TranscriptionRunState.Cancelled -> {
                        autoDiarizationRequests.clearTerminal(state.recordingId)
                    }

                    TranscriptionRunState.Idle,
                    is TranscriptionRunState.Running,
                    -> Unit
                }
            }
        }
        viewModelScope.launch {
            diarizationCoordinator.state.collectLatest { state ->
                when (state) {
                    is DiarizationRunState.Completed -> {
                        if (mutableDocument.value?.recordingId == state.recordingId) {
                            mutableDocument.value?.transcriptionId?.let { transcriptionId ->
                                loadDocument(transcriptionId)
                            }
                        }
                    }

                    is DiarizationRunState.Failed -> {
                        if (mutableDocument.value?.recordingId == state.recordingId) {
                            mutableNotice.value =
                                "转写已完成；自动说话人分离失败，可在说话人分离卡片中重试"
                        }
                    }

                    is DiarizationRunState.Cancelled -> {
                        if (mutableDocument.value?.recordingId == state.recordingId) {
                            mutableNotice.value =
                                "转写已完成；自动说话人分离已取消，可单独重新执行"
                        }
                    }

                    DiarizationRunState.Idle,
                    is DiarizationRunState.Running,
                    -> Unit
                }
            }
        }
        viewModelScope.launch {
            diarizationCoordinator.alignmentState.collectLatest { state ->
                when (state) {
                    is SpeakerAlignmentRunState.Completed -> {
                        if (mutableDocument.value?.transcriptionId == state.transcriptionId) {
                            loadDocument(state.transcriptionId)
                        }
                    }

                    is SpeakerAlignmentRunState.Failed -> {
                        if (mutableDocument.value?.transcriptionId == state.transcriptionId) {
                            mutableNotice.value =
                                "说话人对齐失败：" + state.message
                        }
                    }

                    is SpeakerAlignmentRunState.Cancelled -> {
                        if (mutableDocument.value?.transcriptionId == state.transcriptionId) {
                            mutableNotice.value = "说话人对齐已取消"
                        }
                    }

                    SpeakerAlignmentRunState.Idle,
                    is SpeakerAlignmentRunState.Running,
                    -> Unit
                }
            }
        }
    }

    fun startFast(recordingId: String) {
        start(recordingId, io.github.ioannes78.voica.transcript.TranscriptionMode.FAST)
    }

    fun startHighQuality(recordingId: String) {
        start(recordingId, io.github.ioannes78.voica.transcript.TranscriptionMode.HIGH_QUALITY)
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
            loadDocument(transcriptionId)
        }
    }

    fun renameSpeaker(
        speakerId: String,
        requestedName: String?,
    ) {
        viewModelScope.launch {
            val renamed =
                diarizationRepository.renameSpeaker(
                    speakerId = speakerId,
                    requestedName = requestedName,
                )
            if (!renamed) {
                mutableNotice.value = "说话人名称更新失败"
                return@launch
            }
            mutableDocument.value?.transcriptionId?.let { transcriptionId ->
                loadDocument(transcriptionId)
            }
        }
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    private suspend fun loadDocument(
        transcriptionId: String,
        autoStartDiarization: Boolean = false,
    ) {
        val transcription = repository.find(transcriptionId)
        if (transcription == null ||
            transcription.state != TranscriptionStateValue.COMPLETED
        ) {
            mutableNotice.value = "转写版本不存在或尚未完成"
            return
        }

        val sourceSegments = repository.loadSegments(transcription.id)
        val baseDocument =
            TranscriptDocument(
                recordingId = transcription.recordingId,
                transcriptionId = transcription.id,
                mode = transcription.mode,
                segments =
                    sourceSegments.mapIndexed { displayIndex, segment ->
                        TranscriptDisplaySegment(
                            displayIndex = displayIndex,
                            segmentIndex = segment.segmentIndex,
                            startSampleIndex = segment.startSampleIndex,
                            endSampleIndexExclusive = segment.endSampleIndexExclusive,
                            text = segment.finalText,
                        )
                    },
            )

        val compatibleRun =
            diarizationRepository
                .observeRuns(transcription.recordingId)
                .first()
                .firstOrNull { run ->
                    run.state == DiarizationStateValue.COMPLETED &&
                        run.sourceCanonicalSha256 == transcription.sourceCanonicalSha256 &&
                        run.canonicalProfileId == transcription.canonicalProfileId &&
                        run.totalSampleCount == transcription.totalSampleCount
                }

        if (compatibleRun == null) {
            mutableDocument.value = baseDocument
            if (autoStartDiarization) {
                val started =
                    diarizationCoordinator.start(transcription.recordingId)
                mutableNotice.value =
                    if (started) {
                        "转写已完成，正在自动进行说话人分离…"
                    } else {
                        "转写已完成；当前已有说话人分离或对齐任务，请稍后单独重试"
                    }
            } else {
                mutableNotice.value = null
            }
            return
        }

        val completedAlignment =
            diarizationRepository
                .observeAlignments(transcription.id)
                .first()
                .firstOrNull { alignment ->
                    alignment.diarizationRunId == compatibleRun.id &&
                        alignment.state == TranscriptSpeakerAlignmentStateValue.COMPLETED
                }

        if (completedAlignment == null) {
            mutableDocument.value =
                baseDocument.copy(
                    diarizationRunId = compatibleRun.id,
                )
            val started =
                diarizationCoordinator.alignTranscription(
                    transcriptionId = transcription.id,
                    diarizationRunId = compatibleRun.id,
                )
            mutableNotice.value =
                if (started) {
                    "正在把说话人分离结果应用到当前转写版本…"
                } else {
                    "已有说话人分离或对齐任务正在运行"
                }
            return
        }

        val speakers =
            diarizationRepository
                .loadSpeakers(compatibleRun.id)
                .sortedBy { it.speakerOrdinal }
        val speakersById = speakers.associateBy { it.id }
        val segmentsById = sourceSegments.associateBy { it.id }
        val spans = diarizationRepository.loadSpans(completedAlignment.id)

        val displaySegments =
            spans.mapIndexed { displayIndex, span ->
                val segment =
                    checkNotNull(segmentsById[span.sourceTranscriptSegmentId]) {
                        "speaker span references missing transcript segment"
                    }
                val speaker = span.speakerId?.let(speakersById::get)
                TranscriptDisplaySegment(
                    displayIndex = displayIndex,
                    segmentIndex = segment.segmentIndex,
                    startSampleIndex = span.startSampleIndex,
                    endSampleIndexExclusive = span.endSampleIndexExclusive,
                    text =
                        segment.finalText.substring(
                            span.finalTextStartOffset,
                            span.finalTextEndOffsetExclusive,
                        ),
                    speakerId = speaker?.id,
                    speakerOrdinal = speaker?.speakerOrdinal,
                    speakerDisplayName = speaker?.displayName,
                    speakerAssignmentAvailable = true,
                    overlap = span.overlap,
                    ambiguous = span.ambiguous,
                )
            }

        mutableDocument.value =
            baseDocument.copy(
                segments = displaySegments,
                diarizationRunId = compatibleRun.id,
                alignmentId = completedAlignment.id,
                speakers =
                    speakers.map { speaker ->
                        TranscriptSpeakerDisplay(
                            speakerId = speaker.id,
                            speakerOrdinal = speaker.speakerOrdinal,
                            displayName = speaker.displayName,
                        )
                    },
            )
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
        mode: io.github.ioannes78.voica.transcript.TranscriptionMode,
    ) {
        mutableNotice.value = null
        mutableDocument.value = null
        if (coordinator.start(recordingId, mode)) {
            autoDiarizationRequests.markStarted(recordingId)
        } else {
            mutableNotice.value = "已有转写任务正在运行，请先完成或取消当前任务"
        }
    }

    class Factory(
        private val coordinator: TranscriptionCoordinator,
        private val repository: TranscriptionRepository,
        private val diarizationCoordinator: DiarizationCoordinator,
        private val diarizationRepository: DiarizationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranscriptionViewModel(
                coordinator = coordinator,
                repository = repository,
                diarizationCoordinator = diarizationCoordinator,
                diarizationRepository = diarizationRepository,
            ) as T
    }
}
