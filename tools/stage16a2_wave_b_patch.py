from pathlib import Path

ROOT = Path('.')


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    target = ROOT / path
    text = target.read_text()
    actual = text.count(old)
    if actual < count:
        raise RuntimeError(f'{path}: expected at least {count} occurrences, found {actual}: {old[:160]!r}')
    target.write_text(text.replace(old, new, count))


def replace_all(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text()
    if old not in text:
        raise RuntimeError(f'{path}: anchor not found: {old[:160]!r}')
    target.write_text(text.replace(old, new))


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)


# ---------------------------------------------------------------------------
# A. Fix ordinary recorder-card pending identity: exact device + filename,
#    never confuse an active filename with RemoteDeviceFile.identity.
# ---------------------------------------------------------------------------
entities = 'core/database/src/main/java/io/github/ioannes78/voica/database/TimelineAnnotationEntities.kt'
replace(
    entities,
    '''object TimelineAnnotationPendingOwnerKindValue {
    const val PHONE_CAPTURE = "PHONE_CAPTURE"
    const val RECORDER_CAPTURE = "RECORDER_CAPTURE"
    const val REALTIME_RUN = "REALTIME_RUN"
}
''',
    '''object TimelineAnnotationPendingOwnerKindValue {
    const val PHONE_CAPTURE = "PHONE_CAPTURE"
    const val RECORDER_CAPTURE = "RECORDER_CAPTURE"
    const val REALTIME_RUN = "REALTIME_RUN"
}

data class RecorderCapturePendingIdentity(
    val deviceAddress: String,
    val filename: String,
)

object RecorderCapturePendingOwnerId {
    private const val SEPARATOR = "\\u001f"

    fun encode(deviceAddress: String, filename: String): String {
        require(deviceAddress.isNotBlank())
        require(filename.isNotBlank())
        require(SEPARATOR !in deviceAddress && SEPARATOR !in filename)
        return deviceAddress.trim() + SEPARATOR + filename.trim()
    }

    fun decode(value: String): RecorderCapturePendingIdentity? {
        val index = value.indexOf(SEPARATOR)
        if (index <= 0 || index >= value.lastIndex) return null
        val address = value.substring(0, index).trim()
        val filename = value.substring(index + SEPARATOR.length).trim()
        if (address.isBlank() || filename.isBlank()) return null
        return RecorderCapturePendingIdentity(address, filename)
    }
}
''',
)

dao = 'core/database/src/main/java/io/github/ioannes78/voica/database/RecordingDao.kt'
replace(
    dao,
    '''    @Query("SELECT * FROM recordings WHERE sourceRemoteIdentity = :remoteIdentity LIMIT 1")
    suspend fun findByRemoteIdentity(remoteIdentity: String): RecordingEntity?

    @Query("SELECT * FROM recordings")''',
    '''    @Query("SELECT * FROM recordings WHERE sourceRemoteIdentity = :remoteIdentity LIMIT 1")
    suspend fun findByRemoteIdentity(remoteIdentity: String): RecordingEntity?

    @Query(
        """
        SELECT * FROM recordings
        WHERE state = 'ACTIVE'
          AND sourceDeviceAddress = :deviceAddress
          AND originalFilename = :filename
        ORDER BY updatedAtMs DESC
        LIMIT 2
        """,
    )
    suspend fun findActiveByDeviceAndOriginalFilename(
        deviceAddress: String,
        filename: String,
    ): List<RecordingEntity>

    @Query("SELECT * FROM recordings")''',
)

repo = 'core/database/src/main/java/io/github/ioannes78/voica/database/TimelineAnnotationRepository.kt'
replace(
    repo,
    '''                TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE -> {
                    val recording = recordingDao.findByRemoteIdentity(ownerId)
                    require(recording?.id == recordingId) {
                        "recorder capture pending owner must promote to its exact remote recording identity"
                    }
                }
''',
    '''                TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE -> {
                    val identity =
                        RecorderCapturePendingOwnerId.decode(ownerId)
                            ?: error("invalid recorder capture pending owner identity")
                    val matches =
                        recordingDao.findActiveByDeviceAndOriginalFilename(
                            deviceAddress = identity.deviceAddress,
                            filename = identity.filename,
                        )
                    require(matches.singleOrNull()?.id == recordingId) {
                        "recorder capture pending owner must promote to one exact device recording"
                    }
                }
''',
)
replace(
    repo,
    '''                        kind == TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE ->
                            recordingDao.findByRemoteIdentity(ownerId)?.id?.takeIf {
                                recordingDao.isRecordingActive(it)
                            }
''',
    '''                        kind == TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE -> {
                            val identity = RecorderCapturePendingOwnerId.decode(ownerId)
                            if (identity == null) {
                                null
                            } else {
                                recordingDao.findActiveByDeviceAndOriginalFilename(
                                    deviceAddress = identity.deviceAddress,
                                    filename = identity.filename,
                                ).singleOrNull()?.id
                            }
                        }
''',
)

active_capture = 'app/src/main/java/io/github/ioannes78/voica/ActiveTimelineAnnotationCapture.kt'
replace(
    active_capture,
    'import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue\n',
    'import io.github.ioannes78.voica.database.RecorderCapturePendingOwnerId\nimport io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue\n',
)
replace(
    active_capture,
    '''internal fun recorderRecordingAnnotationTarget(
    recording: RecordingDeviceState,
): RealtimeAnnotationCaptureTarget? {''',
    '''internal fun recorderRecordingAnnotationTarget(
    recording: RecordingDeviceState,
    deviceAddress: String?,
): RealtimeAnnotationCaptureTarget? {''',
)
replace(
    active_capture,
    '''    val remoteIdentity = recording.filename?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val durationSeconds = recording.durationSeconds?.takeIf { it >= 0 } ?: return null''',
    '''    val filename = recording.filename?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolvedDeviceAddress = deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val durationSeconds = recording.durationSeconds?.takeIf { it >= 0 } ?: return null''',
)
replace(
    active_capture,
    '''                kind = TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE,
                ownerId = remoteIdentity,
            ),''',
    '''                kind = TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE,
                ownerId = RecorderCapturePendingOwnerId.encode(resolvedDeviceAddress, filename),
            ),''',
)

device_screen = 'app/src/main/java/io/github/ioannes78/voica/ui/device/DeviceProductScreen.kt'
replace(
    device_screen,
    '                recorderRecordingAnnotationTarget(viewModel.recordingState.value)',
    '''                recorderRecordingAnnotationTarget(
                    recording = viewModel.recordingState.value,
                    deviceAddress =
                        (viewModel.connectionState.value as? DeviceConnectionState.Ready)?.address,
                )''',
)

active_test = 'app/src/test/java/io/github/ioannes78/voica/ActiveTimelineAnnotationCaptureTest.kt'
replace_all(
    active_test,
    '''            recorderRecordingAnnotationTarget(
                RecordingDeviceState(''',
    '''            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(''',
)
# Add the device address after each RecordingDeviceState close used in this test.
replace(
    active_test,
    '''                    freshness = RecordingFreshness.FRESH,
                ),
            )
        requireNotNull(target)''',
    '''                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            )
        requireNotNull(target)''',
)
replace(
    active_test,
    '        assertEquals("note20261018-120000.wav", target.owner.ownerId)',
    '        assertEquals("AA:BB:CC:DD:EE:FF\\u001fnote20261018-120000.wav", target.owner.ownerId)',
)
replace_all(
    active_test,
    '''                    freshness = RecordingFreshness.STALE,
                ),
            ),''',
    '''                    freshness = RecordingFreshness.STALE,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            ),''',
)
replace_all(
    active_test,
    '''                    freshness = RecordingFreshness.FRESH,
                ),
            ),
        )''',
    '''                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )''',
)

# ---------------------------------------------------------------------------
# B. Projection-capable playback coordinator + annotation view model.
# ---------------------------------------------------------------------------
playback_coordinator = 'app/src/main/java/io/github/ioannes78/voica/ui/playback/TimelineAnnotationPlaybackCoordinator.kt'
replace(
    playback_coordinator,
    '''    suspend fun seek(annotation: TimelineAnnotationEntity): TimelineAnnotationSeekResolution {''',
    '''    suspend fun resolve(
        annotation: TimelineAnnotationEntity,
        media: TimelinePlaybackMediaIdentity,
    ): TimelineAnnotationSeekResolution = resolveAnchor(annotation, media)

    suspend fun seek(annotation: TimelineAnnotationEntity): TimelineAnnotationSeekResolution {''',
)

vm = 'app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationViewModel.kt'
replace(
    vm,
    'import io.github.ioannes78.voica.database.TimelineAnnotationSeekUnavailableReason\n',
    'import io.github.ioannes78.voica.database.TimelineAnnotationSeekUnavailableReason\nimport io.github.ioannes78.voica.database.TimelinePlaybackMediaIdentity\n',
)
replace(
    vm,
    '''    fun createAtTranscriptSample(
        recording: RecordingLibraryItem,
        canonicalAssetId: String?,
        totalSampleCount: Long?,
        sampleIndex: Long,
        provenance: TimelineAnnotationProvenance,
    ) {''',
    '''    fun createAtTranscriptSample(
        recording: RecordingLibraryItem,
        canonicalAssetId: String?,
        totalSampleCount: Long?,
        sampleIndex: Long,
        provenance: TimelineAnnotationProvenance,
        noteText: String? = null,
        onCreated: (TimelineAnnotationEntity) -> Unit = {},
    ) {''',
)
replace(
    vm,
    '''            repository.create(
                owner = TimelineAnnotationOwner.Recording(recording.id),
                anchor = anchor,
                provenance = provenance,
            )
            mutableState.update { it.copy(notice = "已从转写添加标注") }''',
    '''            val created =
                repository.create(
                    owner = TimelineAnnotationOwner.Recording(recording.id),
                    anchor = anchor,
                    noteText = noteText,
                    provenance = provenance,
                )
            mutableState.update { it.copy(notice = "已从转写添加标注") }
            onCreated(created)''',
)
replace(
    vm,
    '''    fun seek(annotation: TimelineAnnotationEntity) {
        viewModelScope.launch {''',
    '''    suspend fun resolveProjectionSample(
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
        viewModelScope.launch {''',
)
# transient cancellation/delete must disappear immediately even without a recording observer
replace(
    vm,
    '''            if (!silent) mutableState.update { it.copy(notice = "标注已删除") }
        }
    }''',
    '''            mutableState.update { current ->
                current.copy(
                    items = current.items.filterNot { it.annotation.id == annotationId },
                    notice = if (silent) current.notice else "标注已删除",
                )
            }
        }
    }''',
)
replace(
    vm,
    '''                if (deleteAnnotationIfCancelled) {
                    contentCoordinator.delete(target.annotationId)
                }
            }
        }
    }''',
    '''                if (deleteAnnotationIfCancelled) {
                    contentCoordinator.delete(target.annotationId)
                    mutableState.update { current ->
                        current.copy(items = current.items.filterNot { it.annotation.id == target.annotationId })
                    }
                }
            }
        }
    }''',
)

# ---------------------------------------------------------------------------
# C. Unified composer gets a compact mode suitable for transcript rows.
# ---------------------------------------------------------------------------
composer = 'app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationComposerButton.kt'
replace(
    composer,
    'import androidx.compose.foundation.layout.Box\n',
    'import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.PaddingValues\n',
)
replace(
    composer,
    'import androidx.compose.ui.Modifier\n',
    'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp\n',
)
replace(
    composer,
    '''    enabled: Boolean,
    label: String = "＋",
    viewModel: TimelineAnnotationViewModel,''',
    '''    enabled: Boolean,
    label: String = "＋",
    compact: Boolean = false,
    viewModel: TimelineAnnotationViewModel,''',
)
replace(
    composer,
    '''    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { menuOpen = true },
            enabled = enabled,
        ) {
            Text(label)
        }
        DropdownMenu(''',
    '''    Box(modifier = modifier) {
        if (compact) {
            TextButton(
                onClick = { menuOpen = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(label)
            }
        } else {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = enabled,
            ) {
                Text(label)
            }
        }
        DropdownMenu(''',
)

# ---------------------------------------------------------------------------
# D. Transcript annotation projection model + compact UI.
# ---------------------------------------------------------------------------
write(
    'app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptAnnotationProjection.kt',
    '''package io.github.ioannes78.voica.ui.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationUiItem
import java.util.Locale

internal data class TranscriptAnnotationProjection(
    val item: TimelineAnnotationUiItem,
    val sampleIndex: Long,
)

internal data class TranscriptAnnotationRange(
    val id: String,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
)

internal data class TranscriptAnnotationAssignment(
    val byRangeId: Map<String, List<TranscriptAnnotationProjection>>,
    val unassigned: List<TranscriptAnnotationProjection>,
)

internal fun assignTranscriptAnnotations(
    projections: List<TranscriptAnnotationProjection>,
    ranges: List<TranscriptAnnotationRange>,
): TranscriptAnnotationAssignment {
    val validRanges =
        ranges.filter { range ->
            range.id.isNotBlank() && range.startSampleIndex >= 0L &&
                range.endSampleIndexExclusive > range.startSampleIndex
        }
    val assigned = linkedMapOf<String, MutableList<TranscriptAnnotationProjection>>()
    val unassigned = mutableListOf<TranscriptAnnotationProjection>()
    projections.sortedBy { it.sampleIndex }.forEach { projection ->
        val range =
            validRanges.firstOrNull { candidate ->
                projection.sampleIndex >= candidate.startSampleIndex &&
                    projection.sampleIndex < candidate.endSampleIndexExclusive
            }
        if (range == null) {
            unassigned += projection
        } else {
            assigned.getOrPut(range.id) { mutableListOf() } += projection
        }
    }
    return TranscriptAnnotationAssignment(
        byRangeId = assigned.mapValues { it.value.toList() },
        unassigned = unassigned,
    )
}

@Composable
internal fun TranscriptAnnotationStrip(
    projections: List<TranscriptAnnotationProjection>,
    onClick: (TranscriptAnnotationProjection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (projections.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        projections.forEach { projection ->
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onClick(projection) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        formatProjectionTime(projection.sampleIndex),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        annotationProjectionSummary(projection.item),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TranscriptUnprojectedAnnotationPanel(
    projectedOutsideText: List<TranscriptAnnotationProjection>,
    unresolved: List<TimelineAnnotationUiItem>,
    onProjectedClick: (TranscriptAnnotationProjection) -> Unit,
    onUnresolvedClick: (TimelineAnnotationUiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (projectedOutsideText.isEmpty() && unresolved.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                "其他标注",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (projectedOutsideText.isNotEmpty()) {
                TranscriptAnnotationStrip(projectedOutsideText, onProjectedClick)
            }
            unresolved.forEach { item ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onUnresolvedClick(item) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "未定位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        annotationProjectionSummary(item),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (unresolved.isNotEmpty()) {
                Text(
                    "以上标注缺少可验证的当前转写位置；未按文字长度或断连空档猜测位置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TranscriptAnnotatedReading(
    paragraphs: List<TranscriptReadingParagraph>,
    assignment: TranscriptAnnotationAssignment,
    unresolved: List<TimelineAnnotationUiItem>,
    showAnnotations: Boolean,
    onAnnotationClick: (TranscriptAnnotationProjection) -> Unit,
    onUnresolvedClick: (TimelineAnnotationUiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                paragraphs.forEach { paragraph ->
                    Text(
                        paragraph.text.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (showAnnotations) {
                        DisableSelection {
                            TranscriptAnnotationStrip(
                                projections = assignment.byRangeId[paragraph.stableId].orEmpty(),
                                onClick = onAnnotationClick,
                            )
                        }
                    }
                }
                if (showAnnotations) {
                    DisableSelection {
                        TranscriptUnprojectedAnnotationPanel(
                            projectedOutsideText = assignment.unassigned,
                            unresolved = unresolved,
                            onProjectedClick = onAnnotationClick,
                            onUnresolvedClick = onUnresolvedClick,
                        )
                    }
                }
            }
        }
    }
}

internal fun annotationProjectionSummary(item: TimelineAnnotationUiItem): String {
    val note = item.annotation.noteText?.trim().orEmpty()
    return buildString {
        if (note.isNotBlank()) append(note) else append("快速标记")
        if (item.photoCount > 0) {
            if (isNotEmpty()) append(" · ")
            append("图片 ")
            append(item.photoCount)
            append(" 张")
        }
    }
}

private fun formatProjectionTime(sampleIndex: Long): String {
    val seconds = sampleIndex.coerceAtLeast(0L) / 16_000L
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}
''',
)

# ---------------------------------------------------------------------------
# E. Transcript cards/action bar expose composable full composer + annotation toggle.
# ---------------------------------------------------------------------------
transcription_cards = 'app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt'
replace(
    transcription_cards,
    '''    onSeek: (Long) -> Unit,
    onAddAnnotation: ((Long) -> Unit)? = null,
) {''',
    '''    onSeek: (Long) -> Unit,
    onAddAnnotation: ((Long) -> Unit)? = null,
    annotationAction: (@Composable () -> Unit)? = null,
) {''',
)
replace(
    transcription_cards,
    '''                onAddAnnotation?.let { addAnnotation ->
                    Text(
                        "＋标注",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.clickable(enabled = syncEnabled) {
                                addAnnotation(segment.startSampleIndex)
                            },
                    )
                }''',
    '''                if (annotationAction != null) {
                    annotationAction()
                } else {
                    onAddAnnotation?.let { addAnnotation ->
                        Text(
                            "＋标注",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.clickable(enabled = syncEnabled) {
                                    addAnnotation(segment.startSampleIndex)
                                },
                        )
                    }
                }''',
)

product_content = 'app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptProductContent.kt'
replace(
    product_content,
    '''    onModeChange: (TranscriptViewMode) -> Unit,
    onSpeakerModeClick: () -> Unit,
    onRetranscribe: () -> Unit,
) {''',
    '''    onModeChange: (TranscriptViewMode) -> Unit,
    onSpeakerModeClick: () -> Unit,
    onRetranscribe: () -> Unit,
    annotationCount: Int = 0,
    showAnnotations: Boolean = true,
    onToggleAnnotations: () -> Unit = {},
) {''',
)
replace(
    product_content,
    '''        if (state.currentRevisionId != null) {
            Text(
                "已编辑",''',
    '''        if (annotationCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onToggleAnnotations) {
                    Text("标注 $annotationCount · " + if (showAnnotations) "隐藏" else "显示")
                }
            }
        }
        if (state.currentRevisionId != null) {
            Text(
                "已编辑",''',
)

# ---------------------------------------------------------------------------
# F. Recording detail: resolve Recording-owned annotations to current media,
#    full composer in timeline + reading, dynamic projection after re-ASR.
# ---------------------------------------------------------------------------
detail = 'app/src/main/java/io/github/ioannes78/voica/ui/library/RecordingDetailProductScreen.kt'
replace(
    detail,
    'import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationPanel\n',
    'import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton\nimport io.github.ioannes78.voica.ui.annotation.TimelineAnnotationPanel\n',
)
replace(
    detail,
    'import io.github.ioannes78.voica.ui.transcript.TranscriptContinuousReading\n',
    '''import io.github.ioannes78.voica.ui.transcript.TranscriptAnnotatedReading
import io.github.ioannes78.voica.ui.transcript.TranscriptAnnotationProjection
import io.github.ioannes78.voica.ui.transcript.TranscriptAnnotationRange
import io.github.ioannes78.voica.ui.transcript.TranscriptAnnotationStrip
import io.github.ioannes78.voica.ui.transcript.TranscriptContentViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptDisplaySegment
import io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader
import io.github.ioannes78.voica.ui.transcript.TranscriptFollowMode
import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel
import io.github.ioannes78.voica.ui.transcript.TranscriptProductActionBar
import io.github.ioannes78.voica.ui.transcript.TranscriptReadingParagraph
import io.github.ioannes78.voica.ui.transcript.TranscriptSegmentCard
import io.github.ioannes78.voica.ui.transcript.TranscriptUnprojectedAnnotationPanel
import io.github.ioannes78.voica.ui.transcript.TranscriptViewMode
import io.github.ioannes78.voica.ui.transcript.TranscriptionStatusCard
import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel
import io.github.ioannes78.voica.ui.transcript.assignTranscriptAnnotations
''',
)
# Remove the duplicate old transcript imports left after replacing just the first line.
for duplicate in [
    'import io.github.ioannes78.voica.ui.transcript.TranscriptContentViewModel\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptDisplaySegment\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptFollowMode\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptPlaybackSyncViewModel\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptProductActionBar\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptReadingParagraph\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptSegmentCard\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptViewMode\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptionStatusCard\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptionViewModel\n',
]:
    # remove only the second occurrence if duplicated
    target = ROOT / detail
    txt = target.read_text()
    first = txt.find(duplicate)
    second = txt.find(duplicate, first + len(duplicate)) if first >= 0 else -1
    if second >= 0:
        txt = txt[:second] + txt[second + len(duplicate):]
        target.write_text(txt)

replace(
    detail,
    '''    var transcriptViewMode by rememberSaveable(recording.id) {
        mutableStateOf(TranscriptViewMode.TIMELINE)
    }
    var renameOpen''',
    '''    var transcriptViewMode by rememberSaveable(recording.id) {
        mutableStateOf(TranscriptViewMode.TIMELINE)
    }
    var showTranscriptAnnotations by rememberSaveable(recording.id) { mutableStateOf(true) }
    var annotationProjectionSamples by remember(recording.id) {
        mutableStateOf<Map<String, Long>>(emptyMap())
    }
    var renameOpen''',
)
replace(
    detail,
    '''    val recordingDocument = document?.takeIf { it.recordingId == recording.id }
    val candidatePreview =''',
    '''    val recordingDocument = document?.takeIf { it.recordingId == recording.id }
    LaunchedEffect(
        annotationUiState.items,
        recordingDocument?.compatiblePlaybackAssetId,
        recordingDocument?.timeline?.totalSampleCount,
    ) {
        val assetId = recordingDocument?.compatiblePlaybackAssetId
        val totalSamples = recordingDocument?.timeline?.totalSampleCount
        annotationProjectionSamples =
            if (assetId == null || totalSamples == null || totalSamples <= 0L) {
                emptyMap()
            } else {
                buildMap {
                    annotationUiState.items.forEach { item ->
                        annotationViewModel.resolveProjectionSample(
                            annotation = item.annotation,
                            recordingId = recording.id,
                            sourceAssetId = assetId,
                            durationSampleCount = totalSamples,
                        )?.let { sample -> put(item.annotation.id, sample) }
                    }
                }
            }
    }
    val projectedAnnotations =
        annotationUiState.items.mapNotNull { item ->
            annotationProjectionSamples[item.annotation.id]?.let { sample ->
                TranscriptAnnotationProjection(item = item, sampleIndex = sample)
            }
        }.sortedBy { it.sampleIndex }
    val unresolvedAnnotations =
        annotationUiState.items.filterNot { annotationProjectionSamples.containsKey(it.annotation.id) }
    val candidatePreview =''',
)
replace(
    detail,
    '''                                        onSpeakerModeClick = {
                                            pendingSpeakerModeName = displayedSpeakerMode.name
                                            speakerSheetOpen = true
                                        },
                                        onRetranscribe = startRetranscription,
                                    )''',
    '''                                        onSpeakerModeClick = {
                                            pendingSpeakerModeName = displayedSpeakerMode.name
                                            speakerSheetOpen = true
                                        },
                                        onRetranscribe = startRetranscription,
                                        annotationCount = annotationUiState.items.size,
                                        showAnnotations = showTranscriptAnnotations,
                                        onToggleAnnotations = {
                                            showTranscriptAnnotations = !showTranscriptAnnotations
                                        },
                                    )''',
)
# Replace reading branch with annotated reading + current-playback composer.
replace(
    detail,
    '''                            if (transcriptViewMode == TranscriptViewMode.READING) {
                                item(key = "continuous-reading") {
                                    if (transcriptContentState.loading) {
                                        Text(
                                            "正在准备阅读稿…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        TranscriptContinuousReading(
                                            paragraphs = transcriptContentState.paragraphs,
                                        )
                                    }
                                }
                            } else {''',
    '''                            if (transcriptViewMode == TranscriptViewMode.READING) {
                                item(key = "continuous-reading") {
                                    if (transcriptContentState.loading) {
                                        Text(
                                            "正在准备阅读稿…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        val ranges =
                                            transcriptContentState.paragraphs.mapNotNull { paragraph ->
                                                val start = paragraph.anchorStartSampleIndex
                                                val end = paragraph.anchorEndSampleIndexExclusive
                                                if (start != null && end != null && end > start) {
                                                    TranscriptAnnotationRange(paragraph.stableId, start, end)
                                                } else {
                                                    null
                                                }
                                            }
                                        val assignment = assignTranscriptAnnotations(projectedAnnotations, ranges)
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                            ) {
                                                TimelineAnnotationComposerButton(
                                                    enabled =
                                                        playbackSnapshot.recordingId == recording.id &&
                                                            playbackSnapshot.durationSampleCount > 0L,
                                                    label = "＋标注",
                                                    compact = true,
                                                    viewModel = annotationViewModel,
                                                    onCreate = { noteText, onCreated ->
                                                        annotationViewModel.createAtPlayback(
                                                            recording = recording,
                                                            playback = playbackSnapshot,
                                                            noteText = noteText,
                                                            provenance =
                                                                TimelineAnnotationProvenance(
                                                                    sourceTranscriptionIdSnapshot = recordingDocument.transcriptionId,
                                                                    sourceRevisionIdSnapshot = transcriptContentState.currentRevisionId,
                                                                    sourceTimingQualitySnapshot = "PLAYBACK_FALLBACK",
                                                                ),
                                                            onCreated = onCreated,
                                                        )
                                                    },
                                                )
                                            }
                                            TranscriptAnnotatedReading(
                                                paragraphs = transcriptContentState.paragraphs,
                                                assignment = assignment,
                                                unresolved = unresolvedAnnotations,
                                                showAnnotations = showTranscriptAnnotations,
                                                onAnnotationClick = { projection ->
                                                    annotationViewModel.seek(projection.item.annotation)
                                                },
                                                onUnresolvedClick = { item ->
                                                    annotationViewModel.seek(item.annotation)
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {''',
)
# Revision timeline: compute assignment before items, pass full composer + render strips, then other panel.
replace(
    detail,
    '''                                if (transcriptContentState.currentRevisionId != null) {
                                    items(
                                        items = transcriptContentState.paragraphs,
                                        key = { "revision-timeline:" + it.stableId },
                                    ) { paragraph ->''',
    '''                                if (transcriptContentState.currentRevisionId != null) {
                                    val revisionRanges =
                                        transcriptContentState.paragraphs.mapNotNull { paragraph ->
                                            val start = paragraph.anchorStartSampleIndex
                                            val end = paragraph.anchorEndSampleIndexExclusive
                                            if (start != null && end != null && end > start) {
                                                TranscriptAnnotationRange(paragraph.stableId, start, end)
                                            } else {
                                                null
                                            }
                                        }
                                    val revisionAssignment =
                                        assignTranscriptAnnotations(projectedAnnotations, revisionRanges)
                                    items(
                                        items = transcriptContentState.paragraphs,
                                        key = { "revision-timeline:" + it.stableId },
                                    ) { paragraph ->''',
)
replace(
    detail,
    '''                                            onAddAnnotation = { sample ->
                                                val provenance =
                                                    revisionAnnotationProvenance(
                                                        transcriptionId = recordingDocument.transcriptionId,
                                                        revisionId = transcriptContentState.currentRevisionId,
                                                        paragraph = paragraph,
                                                    )
                                                if (sample != null) {
                                                    annotationViewModel.createAtTranscriptSample(
                                                        recording = recording,
                                                        canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                        totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                        sampleIndex = sample,
                                                        provenance = provenance,
                                                    )
                                                } else {
                                                    annotationViewModel.createAtPlayback(
                                                        recording = recording,
                                                        playback = playbackSnapshot,
                                                        provenance = provenance,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                } else {''',
    '''                                            onAddAnnotation = { sample ->
                                                val provenance =
                                                    revisionAnnotationProvenance(
                                                        transcriptionId = recordingDocument.transcriptionId,
                                                        revisionId = transcriptContentState.currentRevisionId,
                                                        paragraph = paragraph,
                                                    )
                                                if (sample != null) {
                                                    annotationViewModel.createAtTranscriptSample(
                                                        recording = recording,
                                                        canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                        totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                        sampleIndex = sample,
                                                        provenance = provenance,
                                                    )
                                                } else {
                                                    annotationViewModel.createAtPlayback(
                                                        recording = recording,
                                                        playback = playbackSnapshot,
                                                        provenance = provenance,
                                                    )
                                                }
                                            },
                                            annotationAction = {
                                                val provenance =
                                                    revisionAnnotationProvenance(
                                                        transcriptionId = recordingDocument.transcriptionId,
                                                        revisionId = transcriptContentState.currentRevisionId,
                                                        paragraph = paragraph,
                                                    )
                                                TimelineAnnotationComposerButton(
                                                    enabled =
                                                        paragraph.anchorStartSampleIndex != null ||
                                                            playbackSnapshot.recordingId == recording.id,
                                                    label = "＋标注",
                                                    compact = true,
                                                    viewModel = annotationViewModel,
                                                    onCreate = { noteText, onCreated ->
                                                        val sample = paragraph.anchorStartSampleIndex
                                                        if (sample != null) {
                                                            annotationViewModel.createAtTranscriptSample(
                                                                recording = recording,
                                                                canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                                totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                                sampleIndex = sample,
                                                                provenance = provenance,
                                                                noteText = noteText,
                                                                onCreated = onCreated,
                                                            )
                                                        } else {
                                                            annotationViewModel.createAtPlayback(
                                                                recording = recording,
                                                                playback = playbackSnapshot,
                                                                noteText = noteText,
                                                                provenance = provenance,
                                                                onCreated = onCreated,
                                                            )
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                        if (showTranscriptAnnotations) {
                                            TranscriptAnnotationStrip(
                                                projections = revisionAssignment.byRangeId[paragraph.stableId].orEmpty(),
                                                onClick = { projection ->
                                                    annotationViewModel.seek(projection.item.annotation)
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                            )
                                        }
                                    }
                                    if (showTranscriptAnnotations &&
                                        (revisionAssignment.unassigned.isNotEmpty() || unresolvedAnnotations.isNotEmpty())
                                    ) {
                                        item(key = "revision-other-annotations") {
                                            TranscriptUnprojectedAnnotationPanel(
                                                projectedOutsideText = revisionAssignment.unassigned,
                                                unresolved = unresolvedAnnotations,
                                                onProjectedClick = { projection ->
                                                    annotationViewModel.seek(projection.item.annotation)
                                                },
                                                onUnresolvedClick = { item -> annotationViewModel.seek(item.annotation) },
                                            )
                                        }
                                    }
                                } else {''',
)
# Raw timeline assignment around items.
replace(
    detail,
    '''                                } else {
                                    items(
                                        items = recordingDocument.segments,
                                        key = { recordingDocument.transcriptionId + ":" + it.stableId },
                                    ) { segment ->''',
    '''                                } else {
                                    val segmentRanges =
                                        recordingDocument.segments.map { segment ->
                                            TranscriptAnnotationRange(
                                                id = segment.stableId,
                                                startSampleIndex = segment.startSampleIndex,
                                                endSampleIndexExclusive = segment.endSampleIndexExclusive,
                                            )
                                        }
                                    val segmentAssignment =
                                        assignTranscriptAnnotations(projectedAnnotations, segmentRanges)
                                    items(
                                        items = recordingDocument.segments,
                                        key = { recordingDocument.transcriptionId + ":" + it.stableId },
                                    ) { segment ->''',
)
replace(
    detail,
    '''                                            onAddAnnotation =
                                                if (syncEnabled) {
                                                    { sampleIndex ->
                                                        annotationViewModel.createAtTranscriptSample(
                                                            recording = recording,
                                                            canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                            totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                            sampleIndex = sampleIndex,
                                                            provenance =
                                                                segmentAnnotationProvenance(
                                                                    transcriptionId = recordingDocument.transcriptionId,
                                                                    segment = segment,
                                                                ),
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }''',
    '''                                            onAddAnnotation =
                                                if (syncEnabled) {
                                                    { sampleIndex ->
                                                        annotationViewModel.createAtTranscriptSample(
                                                            recording = recording,
                                                            canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                            totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                            sampleIndex = sampleIndex,
                                                            provenance =
                                                                segmentAnnotationProvenance(
                                                                    transcriptionId = recordingDocument.transcriptionId,
                                                                    segment = segment,
                                                                ),
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                            annotationAction = {
                                                TimelineAnnotationComposerButton(
                                                    enabled = syncEnabled,
                                                    label = "＋标注",
                                                    compact = true,
                                                    viewModel = annotationViewModel,
                                                    onCreate = { noteText, onCreated ->
                                                        annotationViewModel.createAtTranscriptSample(
                                                            recording = recording,
                                                            canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                            totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                            sampleIndex = segment.startSampleIndex,
                                                            provenance =
                                                                segmentAnnotationProvenance(
                                                                    transcriptionId = recordingDocument.transcriptionId,
                                                                    segment = segment,
                                                                ),
                                                            noteText = noteText,
                                                            onCreated = onCreated,
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                        if (showTranscriptAnnotations) {
                                            TranscriptAnnotationStrip(
                                                projections = segmentAssignment.byRangeId[segment.stableId].orEmpty(),
                                                onClick = { projection ->
                                                    annotationViewModel.seek(projection.item.annotation)
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                            )
                                        }
                                    }
                                    if (showTranscriptAnnotations &&
                                        (segmentAssignment.unassigned.isNotEmpty() || unresolvedAnnotations.isNotEmpty())
                                    ) {
                                        item(key = "segment-other-annotations") {
                                            TranscriptUnprojectedAnnotationPanel(
                                                projectedOutsideText = segmentAssignment.unassigned,
                                                unresolved = unresolvedAnnotations,
                                                onProjectedClick = { projection ->
                                                    annotationViewModel.seek(projection.item.annotation)
                                                },
                                                onUnresolvedClick = { item -> annotationViewModel.seek(item.annotation) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }''',
)
# RevisionTimelineRow supports composable action in header.
replace(
    detail,
    '''    onSeek: (Long) -> Unit,
    onAddAnnotation: (Long?) -> Unit,
) {''',
    '''    onSeek: (Long) -> Unit,
    onAddAnnotation: (Long?) -> Unit,
    annotationAction: (@Composable () -> Unit)? = null,
) {''',
)
replace(
    detail,
    '''                Text(
                    "＋标注",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onAddAnnotation(start) },
                )''',
    '''                if (annotationAction != null) {
                    annotationAction()
                } else {
                    Text(
                        "＋标注",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onAddAnnotation(start) },
                    )
                }''',
)

# ---------------------------------------------------------------------------
# G. Waveform long-press: coarse anchor -> hold/drag fine adjustment -> release create.
# ---------------------------------------------------------------------------
playback = 'app/src/main/java/io/github/ioannes78/voica/ui/playback/PlaybackCard.kt'
replace(
    playback,
    'import androidx.compose.foundation.gestures.detectDragGestures\n',
    'import androidx.compose.foundation.gestures.detectDragGestures\nimport androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress\n',
)
replace(
    playback,
    'import androidx.compose.ui.input.pointer.pointerInput\n',
    '''import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
''',
)
replace(
    playback,
    '''    var dragging by remember(snapshot.recordingId) { mutableStateOf(false) }
    var previewRatio by remember(snapshot.recordingId) { mutableFloatStateOf(0f) }
    val actualRatio =''',
    '''    var dragging by remember(snapshot.recordingId) { mutableStateOf(false) }
    var previewRatio by remember(snapshot.recordingId) { mutableFloatStateOf(0f) }
    var annotationFineAdjusting by remember(snapshot.recordingId) { mutableStateOf(false) }
    var annotationFineCenterRatio by remember(snapshot.recordingId) { mutableFloatStateOf(0f) }
    var annotationFineRatio by remember(snapshot.recordingId) { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val actualRatio =''',
)
replace(
    playback,
    '''    LaunchedEffect(actualRatio, dragging) {
        if (!dragging) previewRatio = actualRatio
    }
    val visibleRatio = if (dragging) previewRatio else actualRatio''',
    '''    LaunchedEffect(actualRatio, dragging, annotationFineAdjusting) {
        if (!dragging && !annotationFineAdjusting) previewRatio = actualRatio
    }
    val visibleRatio =
        when {
            annotationFineAdjusting -> annotationFineRatio
            dragging -> previewRatio
            else -> actualRatio
        }
    val annotationFineHalfSpanRatio =
        if (totalSamples > 0L) {
            (fineAnnotationHalfSpanSamples(totalSamples).toDouble() / totalSamples.toDouble())
                .coerceIn(0.0, 0.5)
                .toFloat()
        } else {
            0f
        }''',
)
replace(
    playback,
    '''    fun commitPreview() {
        if (!dragging || totalSamples <= 0L) return
        val target =
            (previewRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        dragging = false
        onSeek(target)
    }

    Card(''',
    '''    fun commitPreview() {
        if (!dragging || totalSamples <= 0L) return
        val target =
            (previewRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        dragging = false
        onSeek(target)
    }

    fun startFineAnnotation(ratio: Double) {
        if (onWaveformLongPress == null || totalSamples <= 0L) return
        annotationFineCenterRatio = ratio.toFloat().coerceIn(0f, 1f)
        annotationFineRatio = annotationFineCenterRatio
        annotationFineAdjusting = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun adjustFineAnnotation(normalizedHorizontalDelta: Double) {
        if (!annotationFineAdjusting) return
        annotationFineRatio =
            fineAnnotationAdjustedRatio(
                currentRatio = annotationFineRatio,
                centerRatio = annotationFineCenterRatio,
                normalizedHorizontalDelta = normalizedHorizontalDelta.toFloat(),
                halfSpanRatio = annotationFineHalfSpanRatio,
            )
    }

    fun commitFineAnnotation() {
        if (!annotationFineAdjusting || totalSamples <= 0L) return
        val target =
            (annotationFineRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        annotationFineAdjusting = false
        onWaveformLongPress?.invoke(target)
    }

    fun cancelFineAnnotation() {
        annotationFineAdjusting = false
    }

    Card(''',
)
replace(
    playback,
    '''                    Text(
                        formatPlaybackTime(displaySample),
                        style = MaterialTheme.typography.headlineMedium,''',
    '''                    Text(
                        if (annotationFineAdjusting) formatFineAnnotationTime(displaySample) else formatPlaybackTime(displaySample),
                        style = MaterialTheme.typography.headlineMedium,''',
)
replace(
    playback,
    '''                    markerProgress = annotationMarkerProgress,
                    onLongPress =
                        onWaveformLongPress?.let { callback ->
                            { ratio ->
                                callback(
                                    (ratio * totalSamples.toDouble())
                                        .roundToLong()
                                        .coerceIn(0L, totalSamples),
                                )
                            }
                        },
                )
            } else {''',
    '''                    markerProgress = annotationMarkerProgress,
                    fineMarkerProgress = annotationFineRatio.takeIf { annotationFineAdjusting },
                    onFineLongPressStart = if (onWaveformLongPress != null) ::startFineAnnotation else null,
                    onFineLongPressDrag = if (onWaveformLongPress != null) ::adjustFineAnnotation else null,
                    onFineLongPressEnd = if (onWaveformLongPress != null) ::commitFineAnnotation else null,
                    onFineLongPressCancel = if (onWaveformLongPress != null) ::cancelFineAnnotation else null,
                    annotationFineAdjusting = annotationFineAdjusting,
                )
                if (annotationFineAdjusting) {
                    Text(
                        "精确标注 · 左右移动微调，松手创建",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {''',
)
replace(
    playback,
    '''    onTapSeek: (Double) -> Unit,
    markerProgress: List<Float> = emptyList(),
    onLongPress: ((Double) -> Unit)? = null,
) {''',
    '''    onTapSeek: (Double) -> Unit,
    markerProgress: List<Float> = emptyList(),
    fineMarkerProgress: Float? = null,
    onFineLongPressStart: ((Double) -> Unit)? = null,
    onFineLongPressDrag: ((Double) -> Unit)? = null,
    onFineLongPressEnd: (() -> Unit)? = null,
    onFineLongPressCancel: (() -> Unit)? = null,
    annotationFineAdjusting: Boolean = false,
) {''',
)
replace(
    playback,
    '''                    detectTapGestures(
                        onTap = { offset ->
                            if (size.width > 0) {
                                onTapSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble())
                            }
                        },
                        onLongPress = { offset ->
                            if (size.width > 0) {
                                onLongPress?.invoke(
                                    (offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble(),
                                )
                            }
                        },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(''',
    '''                    detectTapGestures(
                        onTap = { offset ->
                            if (size.width > 0) {
                                onTapSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble())
                            }
                        },
                    )
                }
                .pointerInput(enabled, onFineLongPressStart != null) {
                    if (!enabled || onFineLongPressStart == null) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            if (size.width > 0) {
                                onFineLongPressStart(
                                    (offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble(),
                                )
                            }
                        },
                        onDragEnd = { onFineLongPressEnd?.invoke() },
                        onDragCancel = { onFineLongPressCancel?.invoke() },
                    ) { change, dragAmount ->
                        change.consume()
                        if (size.width > 0) {
                            onFineLongPressDrag?.invoke(
                                (dragAmount.x / size.width.toFloat()).toDouble(),
                            )
                        }
                    }
                }
                .pointerInput(enabled, annotationFineAdjusting) {
                    if (!enabled || annotationFineAdjusting) return@pointerInput
                    detectDragGestures(''',
)
replace(
    playback,
    '''        markerProgress.forEach { ratio ->
            val x = ratio.coerceIn(0f, 1f) * size.width
            drawLine(
                color = markerColor.copy(alpha = 0.78f),''',
    '''        markerProgress.forEach { ratio ->
            val x = ratio.coerceIn(0f, 1f) * size.width
            drawLine(
                color = markerColor.copy(alpha = 0.78f),''',
)
# Append fine locator after regular marker loop.
replace(
    playback,
    '''            drawCircle(
                color = markerColor,
                radius = 4f,
                center = Offset(x, 6f),
            )
        }
    }
}

@Composable
private fun RoundProgressTrack(''',
    '''            drawCircle(
                color = markerColor,
                radius = 4f,
                center = Offset(x, 6f),
            )
        }
        fineMarkerProgress?.let { ratio ->
            val x = ratio.coerceIn(0f, 1f) * size.width
            drawLine(
                color = playedColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = playedColor,
                radius = 5f,
                center = Offset(x, size.height / 2f),
            )
        }
    }
}

@Composable
private fun RoundProgressTrack(''',
)
# Add helper functions before private format or EOF; use known formatPlaybackTime declaration anchor.
replace(
    playback,
    '''internal fun formatPlaybackTime(sampleIndex: Long): String {''',
    '''internal fun fineAnnotationHalfSpanSamples(totalSamples: Long): Long {
    val safe = totalSamples.coerceAtLeast(0L)
    val minute = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * 60L
    val requested =
        when {
            safe <= minute -> CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * 5L
            safe <= minute * 10L -> CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * 15L
            else -> CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * 30L
        }
    return requested.coerceAtMost((safe / 2L).coerceAtLeast(1L))
}

internal fun fineAnnotationAdjustedRatio(
    currentRatio: Float,
    centerRatio: Float,
    normalizedHorizontalDelta: Float,
    halfSpanRatio: Float,
): Float {
    val half = halfSpanRatio.coerceIn(0f, 0.5f)
    val minimum = (centerRatio - half).coerceAtLeast(0f)
    val maximum = (centerRatio + half).coerceAtMost(1f)
    val delta = normalizedHorizontalDelta * half * 2f
    return (currentRatio + delta).coerceIn(minimum, maximum)
}

internal fun formatFineAnnotationTime(sampleIndex: Long): String {
    val totalSeconds = sampleIndex.coerceAtLeast(0L).toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()
    val minutes = (totalSeconds / 60.0).toLong()
    val seconds = totalSeconds - minutes * 60.0
    return String.format(Locale.US, "%02d:%04.1f", minutes, seconds)
}

internal fun formatPlaybackTime(sampleIndex: Long): String {''',
)

# ---------------------------------------------------------------------------
# H. Pure tests for projection and waveform fine adjustment.
# ---------------------------------------------------------------------------
write(
    'app/src/test/java/io/github/ioannes78/voica/ui/transcript/TranscriptAnnotationProjectionTest.kt',
    '''package io.github.ioannes78.voica.ui.transcript

import io.github.ioannes78.voica.database.TimelineAnnotationEntity
import io.github.ioannes78.voica.database.TimelineAnnotationOwnershipStateValue
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationUiItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAnnotationProjectionTest {
    @Test
    fun assignsRecordingOwnedSamplesToCurrentTranscriptRangesWithoutTextInference() {
        val first = projection("a", 10_000L)
        val second = projection("b", 30_000L)
        val outside = projection("c", 90_000L)
        val result =
            assignTranscriptAnnotations(
                projections = listOf(outside, second, first),
                ranges =
                    listOf(
                        TranscriptAnnotationRange("p1", 0L, 20_000L),
                        TranscriptAnnotationRange("p2", 20_000L, 40_000L),
                    ),
            )
        assertEquals(listOf("a"), result.byRangeId["p1"]?.map { it.item.annotation.id })
        assertEquals(listOf("b"), result.byRangeId["p2"]?.map { it.item.annotation.id })
        assertEquals(listOf("c"), result.unassigned.map { it.item.annotation.id })
    }

    @Test
    fun boundaryUsesEndExclusiveAndNeverGuessesIntoMissingRange() {
        val atBoundary = projection("x", 20_000L)
        val result =
            assignTranscriptAnnotations(
                projections = listOf(atBoundary),
                ranges = listOf(TranscriptAnnotationRange("p1", 0L, 20_000L)),
            )
        assertEquals(emptyMap<String, List<TranscriptAnnotationProjection>>(), result.byRangeId)
        assertEquals(listOf("x"), result.unassigned.map { it.item.annotation.id })
    }

    private fun projection(id: String, sample: Long): TranscriptAnnotationProjection =
        TranscriptAnnotationProjection(
            item =
                TimelineAnnotationUiItem(
                    annotation =
                        TimelineAnnotationEntity(
                            id = id,
                            ownershipState = TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING,
                            recordingId = "recording",
                            pendingOwnerKind = null,
                            pendingOwnerId = null,
                            anchorDomain = "CANONICAL_RECORDING",
                            anchorSampleIndex = sample,
                            createdAtMs = 1L,
                            updatedAtMs = 1L,
                        ),
                    attachments = emptyList(),
                ),
            sampleIndex = sample,
        )
}
''',
)

write(
    'app/src/test/java/io/github/ioannes78/voica/ui/playback/TimelineAnnotationFineAdjustTest.kt',
    '''package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAnnotationFineAdjustTest {
    @Test
    fun fineWindowGrowsWithLongRecordingButRemainsLocal() {
        val sr = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong()
        assertEquals(5L * sr, fineAnnotationHalfSpanSamples(30L * sr))
        assertEquals(15L * sr, fineAnnotationHalfSpanSamples(5L * 60L * sr))
        assertEquals(30L * sr, fineAnnotationHalfSpanSamples(30L * 60L * sr))
    }

    @Test
    fun horizontalDragMovesWithinFineWindowInsteadOfWholeRecordingWidth() {
        val adjusted =
            fineAnnotationAdjustedRatio(
                currentRatio = 0.5f,
                centerRatio = 0.5f,
                normalizedHorizontalDelta = 0.25f,
                halfSpanRatio = 0.10f,
            )
        assertEquals(0.55f, adjusted, 0.0001f)
        assertTrue(adjusted < 0.75f)
    }
}
''',
)

# Fix recorder test missing device-address rejection case explicitly.
# (The replace_all above handles stale/missing filename; add missing address safety assertion.)
replace(
    active_test,
    '''        assertNull(
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = null,
                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
    }
}''',
    '''        assertNull(
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = null,
                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertNull(
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = "note.wav",
                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = null,
            ),
        )
    }
}''',
)

print('Stage 16A.2 revision wave B patch applied')
