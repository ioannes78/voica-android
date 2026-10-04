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
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.TranscriptSpeakerAlignmentStateValue
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.transcript.SpeakerAssignmentQuality
import io.github.ioannes78.voica.transcript.TimedTextCue
import io.github.ioannes78.voica.transcript.TranscriptTimeline
import io.github.ioannes78.voica.transcript.TranscriptionMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TranscriptDisplaySegment(
    val stableId: String,
    val sourceSegmentId: String,
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
    val assignmentQuality: SpeakerAssignmentQuality? = null,
    val cues: List<TimedTextCue> = emptyList(),
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
    val sourceModelId: String? = null,
    val diarizationRunId: String? = null,
    val alignmentId: String? = null,
    val speakers: List<TranscriptSpeakerDisplay> = emptyList(),
    val timeline: TranscriptTimeline? = null,
    val compatiblePlaybackAssetId: String? = null,
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
    private val diarizationCoordinator: DiarizationCoordinator,
    private val diarizationRepository: DiarizationRepository,
    private val recordingLibraryRepository: RecordingLibraryRepository,
    private val contentRepository: Stage12CContentRepository,
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

    private var documentLoadJob: Job? = null
    private var documentLoadGeneration = 0L

    init {
        viewModelScope.launch {
            coordinator.state.collectLatest { state ->
                when (state) {
                    is TranscriptionRunState.Completed -> {
                        contentRepository.setCurrentTranscriptionVersion(
                            state.recordingId,
                            state.transcriptionId,
                        )
                        requestDocumentLoad(
                            transcriptionId = state.transcriptionId,
                            clearCurrent = true,
                        )
                        if (mutableVersionsRecordingId.value == state.recordingId) {
                            loadVersions(state.recordingId)
                        }
                    }

                    is TranscriptionRunState.Failed,
                    is TranscriptionRunState.Cancelled,
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
                                requestDocumentLoad(transcriptionId)
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
                            requestDocumentLoad(state.transcriptionId)
                        }
                    }

                    is SpeakerAlignmentRunState.Failed -> {
                        if (mutableDocument.value?.transcriptionId == state.transcriptionId) {
                            mutableNotice.value = "说话人对齐失败：" + state.message
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

    /** Recording-file transcription always uses the configured offline ASR model. */
    fun startOffline(recordingId: String) {
        start(recordingId, TranscriptionMode.HIGH_QUALITY)
    }

    /** Source-compatible aliases while QA5 removes the old two-button product UI. */
    fun startFast(recordingId: String) = startOffline(recordingId)

    fun startHighQuality(recordingId: String) = startOffline(recordingId)

    fun cancel() {
        coordinator.cancel()
    }

    fun viewVersions(recordingId: String) {
        cancelDocumentLoad(clearCurrent = true)
        viewModelScope.launch {
            loadVersions(recordingId)
        }
    }

    fun selectVersion(transcriptionId: String) {
        viewModelScope.launch {
            val recordingId =
                mutableVersions.value
                    .firstOrNull { it.transcriptionId == transcriptionId }
                    ?.recordingId
                    ?: repository.find(transcriptionId)?.recordingId
                    ?: return@launch
            contentRepository.setCurrentTranscriptionVersion(
                recordingId = recordingId,
                transcriptionId = transcriptionId,
            )
            requestDocumentLoad(
                transcriptionId = transcriptionId,
                clearCurrent = true,
            )
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
                requestDocumentLoad(transcriptionId)
            }
        }
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    private fun requestDocumentLoad(
        transcriptionId: String,
        clearCurrent: Boolean = false,
    ) {
        documentLoadGeneration += 1L
        val generation = documentLoadGeneration
        documentLoadJob?.cancel()
        if (clearCurrent) {
            mutableDocument.value = null
        }
        documentLoadJob =
            viewModelScope.launch {
                loadDocument(
                    transcriptionId = transcriptionId,
                    generation = generation,
                )
            }
    }

    private fun cancelDocumentLoad(clearCurrent: Boolean) {
        documentLoadGeneration += 1L
        documentLoadJob?.cancel()
        documentLoadJob = null
        if (clearCurrent) {
            mutableDocument.value = null
        }
    }

    private fun isCurrentDocumentLoad(generation: Long): Boolean =
        generation == documentLoadGeneration

    private suspend fun loadDocument(
        transcriptionId: String,
        generation: Long,
    ) {
        val transcription = repository.find(transcriptionId)
        if (!isCurrentDocumentLoad(generation)) return
        if (transcription == null || transcription.state != TranscriptionStateValue.COMPLETED) {
            mutableNotice.value = "转写版本不存在或尚未完成"
            return
        }

        val sourceSegments = repository.loadSegments(transcription.id)
        val sourceTokens = repository.loadTokensForTranscription(transcription.id)
        if (!isCurrentDocumentLoad(generation)) return

        val baseTimeline =
            buildTranscriptTimelineFromDatabase(
                transcription = transcription,
                segments = sourceSegments,
                tokens = sourceTokens,
            )
        val currentLineage =
            recordingLibraryRepository.loadCanonicalTranscriptionLineage(
                recordingId = transcription.recordingId,
                profileId = transcription.canonicalProfileId,
            )
        if (!isCurrentDocumentLoad(generation)) return
        val compatiblePlaybackAssetId =
            currentLineage
                ?.takeIf { lineage ->
                    lineage.canonicalProfileId == transcription.canonicalProfileId &&
                        lineage.canonicalSha256.equals(
                            transcription.sourceCanonicalSha256,
                            ignoreCase = true,
                        )
                }
                ?.canonicalAssetId

        val baseDocument =
            TranscriptDocument(
                recordingId = transcription.recordingId,
                transcriptionId = transcription.id,
                mode = transcription.mode,
                segments = timelineDisplaySegments(baseTimeline, sourceSegments),
                sourceModelId =
                    transcription.secondPassAsrModelId
                        ?: transcription.firstPassAsrModelId,
                timeline = baseTimeline,
                compatiblePlaybackAssetId = compatiblePlaybackAssetId,
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
        if (!isCurrentDocumentLoad(generation)) return

        if (compatibleRun == null) {
            mutableDocument.value = baseDocument
            mutableNotice.value = null
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
        if (!isCurrentDocumentLoad(generation)) return

        if (completedAlignment == null) {
            mutableDocument.value = baseDocument.copy(diarizationRunId = compatibleRun.id)
            if (!isCurrentDocumentLoad(generation)) return
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
        val spans = diarizationRepository.loadSpans(completedAlignment.id)
        if (!isCurrentDocumentLoad(generation)) return

        val alignedTimeline =
            buildTranscriptTimelineFromDatabase(
                transcription = transcription,
                segments = sourceSegments,
                tokens = sourceTokens,
                alignmentId = completedAlignment.id,
                spans = spans,
                speakers = speakers,
            )

        mutableDocument.value =
            baseDocument.copy(
                segments = timelineDisplaySegments(alignedTimeline, sourceSegments),
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
                timeline = alignedTimeline,
            )
        mutableNotice.value = null
    }

    private suspend fun loadVersions(recordingId: String) {
        val completed =
            repository.observeVersions(recordingId)
                .first()
                .filter { it.state == TranscriptionStateValue.COMPLETED }
                .sortedByDescending { it.completedAtMs ?: it.updatedAtMs }

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
                    completedAtMs = transcription.completedAtMs ?: transcription.updatedAtMs,
                    segmentCount = repository.loadSegments(transcription.id).size,
                    latest = index == 0,
                )
            }
        val preferredId =
            contentRepository.resolveCurrentTranscriptionId(recordingId)
                ?.takeIf { id -> completed.any { it.id == id } }
                ?: completed.first().id
        val currentDocument = mutableDocument.value
        val currentStillPreferred =
            currentDocument?.recordingId == recordingId &&
                currentDocument.transcriptionId == preferredId
        if (!currentStillPreferred) {
            requestDocumentLoad(
                transcriptionId = preferredId,
                clearCurrent = true,
            )
        }
        mutableNotice.value = null
    }

    private fun start(
        recordingId: String,
        mode: TranscriptionMode,
    ) {
        mutableNotice.value = null
        cancelDocumentLoad(clearCurrent = true)
        if (!coordinator.start(recordingId, mode)) {
            mutableNotice.value = "已有转写任务正在运行，请先完成或取消当前任务"
        }
    }

    class Factory(
        private val coordinator: TranscriptionCoordinator,
        private val repository: TranscriptionRepository,
        private val diarizationCoordinator: DiarizationCoordinator,
        private val diarizationRepository: DiarizationRepository,
        private val recordingLibraryRepository: RecordingLibraryRepository,
        private val contentRepository: Stage12CContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranscriptionViewModel(
                coordinator = coordinator,
                repository = repository,
                diarizationCoordinator = diarizationCoordinator,
                diarizationRepository = diarizationRepository,
                recordingLibraryRepository = recordingLibraryRepository,
                contentRepository = contentRepository,
            ) as T
    }
}
