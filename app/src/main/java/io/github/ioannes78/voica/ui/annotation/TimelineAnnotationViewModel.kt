package io.github.ioannes78.voica.ui.annotation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.TimelineAnnotationAttachmentStore
import io.github.ioannes78.voica.TimelineAnnotationCameraCaptureTarget
import io.github.ioannes78.voica.RealtimeAnnotationCaptureTarget
import io.github.ioannes78.voica.TimelineAnnotationContentCoordinator
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.TimelineAnnotationAnchor
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentEntity
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentLifecycleStateValue
import io.github.ioannes78.voica.database.TimelineAnnotationEntity
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationProvenance
import io.github.ioannes78.voica.database.TimelineAnnotationRepository
import io.github.ioannes78.voica.database.TimelineAnnotationSeekResolution
import io.github.ioannes78.voica.database.TimelineAnnotationSeekUnavailableReason
import io.github.ioannes78.voica.database.TimelinePlaybackMediaIdentity
import io.github.ioannes78.voica.ui.playback.TimelineAnnotationPlaybackCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TimelineAnnotationFilter {
    ALL,
    NOTE,
    PHOTO,
    QUICK,
}

data class TimelineAnnotationUiItem(
    val annotation: TimelineAnnotationEntity,
    val attachments: List<TimelineAnnotationAttachmentEntity>,
) {
    val photoCount: Int
        get() = attachments.count {
            it.lifecycleState == TimelineAnnotationAttachmentLifecycleStateValue.READY
        }

    val isQuickMarker: Boolean
        get() = annotation.noteText.isNullOrBlank() && photoCount == 0
}

data class TimelineAnnotationUiState(
    val recordingId: String? = null,
    val items: List<TimelineAnnotationUiItem> = emptyList(),
    val filter: TimelineAnnotationFilter = TimelineAnnotationFilter.ALL,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val visibleItems: List<TimelineAnnotationUiItem>
        get() =
            items.filter { item ->
                when (filter) {
                    TimelineAnnotationFilter.ALL -> true
                    TimelineAnnotationFilter.NOTE -> !item.annotation.noteText.isNullOrBlank()
                    TimelineAnnotationFilter.PHOTO -> item.photoCount > 0
                    TimelineAnnotationFilter.QUICK -> item.isQuickMarker
                }
            }
}

class TimelineAnnotationViewModel(
    private val repository: TimelineAnnotationRepository,
    private val playbackCoordinator: TimelineAnnotationPlaybackCoordinator,
    private val attachmentStore: TimelineAnnotationAttachmentStore,
    private val contentCoordinator: TimelineAnnotationContentCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TimelineAnnotationUiState())
    val state: StateFlow<TimelineAnnotationUiState> = mutableState.asStateFlow()

    private var observationJob: Job? = null

    fun bind(recordingId: String) {
        require(recordingId.isNotBlank())
        if (mutableState.value.recordingId == recordingId && observationJob?.isActive == true) return
        observationJob?.cancel()
        mutableState.value =
            mutableState.value.copy(
                recordingId = recordingId,
                items = emptyList(),
                notice = null,
            )
        observationJob =
            viewModelScope.launch {
                repository.observeRecording(recordingId).collect { annotations ->
                    val items =
                        annotations.map { annotation ->
                            TimelineAnnotationUiItem(
                                annotation = annotation,
                                attachments = readyAttachments(annotation.id),
                            )
                        }
                    mutableState.update { current ->
                        if (current.recordingId == recordingId) current.copy(items = items) else current
                    }
                }
            }
    }

    fun setFilter(filter: TimelineAnnotationFilter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    fun createAtPlayback(
        recording: RecordingLibraryItem,
        playback: PlaybackSnapshot,
        noteText: String? = null,
        provenance: TimelineAnnotationProvenance = TimelineAnnotationProvenance(),
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        createAtSample(
            recording = recording,
            playback = playback,
            sampleIndex = playback.positionSampleIndex,
            noteText = noteText,
            provenance = provenance,
            onCreated = onCreated,
        )
    }

    fun createAtSample(
        recording: RecordingLibraryItem,
        playback: PlaybackSnapshot,
        sampleIndex: Long,
        noteText: String? = null,
        provenance: TimelineAnnotationProvenance = TimelineAnnotationProvenance(),
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        launchOperation {
            val anchor =
                captureCanonicalAnnotationAnchor(
                    recording = recording,
                    playback = playback,
                    sampleIndex = sampleIndex,
                ) ?: error("当前标准音频无法建立精确标注")
            val created =
                repository.create(
                    owner = TimelineAnnotationOwner.Recording(recording.id),
                    anchor = anchor,
                    noteText = noteText,
                    provenance = provenance,
                )
            onCreated(created)
        }
    }

    fun createAtTranscriptSample(
        recording: RecordingLibraryItem,
        canonicalAssetId: String?,
        totalSampleCount: Long?,
        sampleIndex: Long,
        provenance: TimelineAnnotationProvenance,
        noteText: String? = null,
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        launchOperation {
            val sourceAssetId =
                canonicalAssetId?.takeIf { it.isNotBlank() }
                    ?: error("当前转写时间轴与标准音频不兼容")
            val durationSampleCount =
                totalSampleCount?.takeIf { it > 0L }
                    ?: error("当前转写缺少可验证时间轴")
            val anchor =
                captureCanonicalAnnotationAnchor(
                    recording = recording,
                    sourceAssetId = sourceAssetId,
                    durationSampleCount = durationSampleCount,
                    sampleIndex = sampleIndex,
                ) ?: error("当前转写位置无法建立精确标注")
            val created =
                repository.create(
                    owner = TimelineAnnotationOwner.Recording(recording.id),
                    anchor = anchor,
                    noteText = noteText,
                    provenance = provenance,
                )
            mutableState.update { it.copy(notice = "已从转写添加标注") }
            onCreated(created)
        }
    }

    internal fun createAtCapture(
        targetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
        noteText: String? = null,
        successNotice: String = "已添加标注",
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        launchOperation {
            val target =
                targetProvider()
                    ?: error("当前暂无可安全标注的音频位置")
            val created =
                repository.create(
                    owner = target.owner,
                    anchor = target.anchor,
                    noteText = noteText,
                )
            mutableState.update { current ->
                val item = TimelineAnnotationUiItem(created, emptyList())
                current.copy(
                    items = current.items.filterNot { it.annotation.id == created.id } + item,
                    notice = successNotice,
                )
            }
            onCreated(created)
        }
    }

    internal fun createAtRealtime(
        targetProvider: suspend () -> RealtimeAnnotationCaptureTarget?,
        noteText: String? = null,
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {
        createAtCapture(
            targetProvider = targetProvider,
            noteText = noteText,
            successNotice = "已添加实时标注",
            onCreated = onCreated,
        )
    }

    internal fun resetTransientItems() {
        mutableState.update { current ->
            if (current.recordingId == null) {
                current.copy(items = emptyList(), notice = null)
            } else {
                current
            }
        }
    }

    fun updateNote(annotationId: String, noteText: String?) {
        launchOperation { repository.updateNote(annotationId, noteText) }
    }

    fun moveToPlaybackPosition(
        annotationId: String,
        recording: RecordingLibraryItem,
        playback: PlaybackSnapshot,
    ) {
        launchOperation {
            val anchor =
                captureCanonicalAnnotationAnchor(
                    recording = recording,
                    playback = playback,
                    sampleIndex = playback.positionSampleIndex,
                ) ?: error("当前播放位置无法建立精确标注")
            repository.moveAnchor(annotationId, anchor)
            mutableState.update { it.copy(notice = "标注已移动到当前播放位置") }
        }
    }

    suspend fun resolveProjectionSample(
        annotation: TimelineAnnotationEntity,
        recordingId: String,
        sourceAssetId: String?,
        durationSampleCount: Long?,
    ): Long? {
        val assetId = sourceAssetId?.takeIf { it.isNotBlank() } ?: return null
        val duration = durationSampleCount?.takeIf { it > 0L } ?: return null
        return when (
            val resolved =
                playbackCoordinator.resolve(
                    annotation = annotation,
                    media =
                        TimelinePlaybackMediaIdentity(
                            recordingId = recordingId,
                            sourceAssetId = assetId,
                            durationSampleCount = duration,
                        ),
                )
        ) {
            is TimelineAnnotationSeekResolution.Exact -> resolved.sampleIndex
            is TimelineAnnotationSeekResolution.Unavailable -> null
        }
    }

    fun seek(annotation: TimelineAnnotationEntity) {
        viewModelScope.launch {
            when (val result = playbackCoordinator.seek(annotation)) {
                is TimelineAnnotationSeekResolution.Exact -> Unit
                is TimelineAnnotationSeekResolution.Unavailable ->
                    mutableState.update {
                        it.copy(notice = seekUnavailableMessage(result.reason))
                    }
            }
        }
    }

    fun deleteAnnotation(annotationId: String, silent: Boolean = false) {
        launchOperation {
            val result = contentCoordinator.delete(annotationId)
            if (!result.deleted) {
                error(
                    if (result.failedPaths.isEmpty()) {
                        "标注删除失败"
                    } else {
                        "附件文件删除失败，请稍后重试"
                    },
                )
            }
            mutableState.update { current ->
                current.copy(
                    items = current.items.filterNot { it.annotation.id == annotationId },
                    notice = if (silent) current.notice else "标注已删除",
                )
            }
        }
    }

    fun importGallery(
        annotationId: String,
        uri: Uri,
    ) {
        launchOperation {
            attachmentStore.importGallery(annotationId, uri)
            refreshAttachments(annotationId)
        }
    }

    fun prepareCamera(
        annotationId: String,
        onPrepared: (TimelineAnnotationCameraCaptureTarget) -> Unit,
    ) {
        launchOperation {
            onPrepared(attachmentStore.createCameraCaptureTarget(annotationId))
        }
    }

    fun finishCamera(
        target: TimelineAnnotationCameraCaptureTarget,
        accepted: Boolean,
        deleteAnnotationIfCancelled: Boolean = false,
    ) {
        launchOperation {
            if (accepted) {
                attachmentStore.commitCameraCapture(target)
                refreshAttachments(target.annotationId)
            } else {
                attachmentStore.discardCameraCaptureTarget(target)
                if (deleteAnnotationIfCancelled) {
                    contentCoordinator.delete(target.annotationId)
                    mutableState.update { current ->
                        current.copy(items = current.items.filterNot { it.annotation.id == target.annotationId })
                    }
                }
            }
        }
    }

    fun deleteAttachment(attachment: TimelineAnnotationAttachmentEntity) {
        launchOperation {
            val failures = attachmentStore.deleteAttachment(attachment.id)
            if (failures.isNotEmpty()) error("图片删除失败，请稍后重试")
            refreshAttachments(attachment.annotationId)
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, notice = null) }
            try {
                block()
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(notice = error.message ?: "标注操作失败")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun readyAttachments(annotationId: String): List<TimelineAnnotationAttachmentEntity> =
        repository.getAttachments(annotationId)
            .filter { it.lifecycleState == TimelineAnnotationAttachmentLifecycleStateValue.READY }

    private suspend fun refreshAttachments(annotationId: String) {
        val attachments = readyAttachments(annotationId)
        mutableState.update { current ->
            current.copy(
                items =
                    current.items.map { item ->
                        if (item.annotation.id == annotationId) {
                            item.copy(attachments = attachments)
                        } else {
                            item
                        }
                    },
            )
        }
    }

    private fun seekUnavailableMessage(reason: TimelineAnnotationSeekUnavailableReason): String =
        when (reason) {
            TimelineAnnotationSeekUnavailableReason.PENDING_SESSION -> "录音尚未完成，暂不能跳转"
            TimelineAnnotationSeekUnavailableReason.REALTIME_SEGMENTED_CAPTURE ->
                "该标注位于分段录音，当前无法安全跳转"
            TimelineAnnotationSeekUnavailableReason.REALTIME_EPOCH_UNPROVEN ->
                "该标注缺少完整时间证据，未执行跳转"
            TimelineAnnotationSeekUnavailableReason.CANONICAL_ASSET_MISMATCH,
            TimelineAnnotationSeekUnavailableReason.CANONICAL_SHA_MISMATCH,
            TimelineAnnotationSeekUnavailableReason.CANONICAL_PROFILE_MISMATCH,
            -> "当前标准音频与标注锚点不一致，未执行跳转"
            TimelineAnnotationSeekUnavailableReason.SAMPLE_OUT_OF_RANGE -> "标注位置超出当前音频范围"
            else -> "该标注暂不能精确跳转"
        }

    class Factory(
        private val repository: TimelineAnnotationRepository,
        private val playbackCoordinator: TimelineAnnotationPlaybackCoordinator,
        private val attachmentStore: TimelineAnnotationAttachmentStore,
        private val contentCoordinator: TimelineAnnotationContentCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimelineAnnotationViewModel(
                repository = repository,
                playbackCoordinator = playbackCoordinator,
                attachmentStore = attachmentStore,
                contentCoordinator = contentCoordinator,
            ) as T
    }
}

internal fun captureCanonicalAnnotationAnchor(
    recording: RecordingLibraryItem,
    playback: PlaybackSnapshot,
    sampleIndex: Long,
): TimelineAnnotationAnchor? {
    if (playback.recordingId != recording.id) return null
    return captureCanonicalAnnotationAnchor(
        recording = recording,
        sourceAssetId = playback.sourceAssetId,
        durationSampleCount = playback.durationSampleCount,
        sampleIndex = sampleIndex,
    )
}

internal fun captureCanonicalAnnotationAnchor(
    recording: RecordingLibraryItem,
    sourceAssetId: String?,
    durationSampleCount: Long,
    sampleIndex: Long,
): TimelineAnnotationAnchor? {
    val resolvedAssetId = sourceAssetId?.takeIf { it.isNotBlank() } ?: return null
    if (durationSampleCount <= 0L) return null
    if (sampleIndex !in 0L..durationSampleCount) return null

    val canonical =
        recording.assets.firstOrNull { asset ->
            asset.assetId == resolvedAssetId &&
                asset.role == AudioAssetRole.CANONICAL_WAV &&
                asset.integrityState == AudioIntegrityState.VERIFIED &&
                asset.formatValidationState == AudioValidationState.VALID
        } ?: return null
    val profileId =
        recording.derivations.firstOrNull { derivation ->
            derivation.outputAssetId == canonical.assetId &&
                derivation.state == AudioDerivationState.READY
        }?.profileId

    return TimelineAnnotationAnchor(
        domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
        sampleIndex = sampleIndex,
        canonicalAssetIdSnapshot = canonical.assetId,
        canonicalSha256Snapshot = canonical.sha256,
        canonicalProfileIdSnapshot = profileId,
    )
}
