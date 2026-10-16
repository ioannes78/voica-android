package io.github.ioannes78.voica.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.FileListFreshness
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingSourceType
import java.util.Locale
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun RecordingPlaybackProductActions(
    recording: RecordingLibraryItem,
    canonicalReady: Boolean,
    onRename: () -> Unit,
    onShareCanonical: () -> Unit,
    onShareOriginal: () -> Unit,
    onExportCanonical: () -> Unit,
    onExportOriginal: () -> Unit,
    onDelete: () -> Unit,
) {
    var sharePickerOpen by remember(recording.id) { mutableStateOf(false) }
    var exportPickerOpen by remember(recording.id) { mutableStateOf(false) }
    var originalActionInFlight by remember(recording.id) { mutableStateOf(false) }
    var originalActionMessage by remember(recording.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val application = context.applicationContext as VoicaApplication
    val actionScope = rememberCoroutineScope()

    val availability = recording.localMediaAvailability()
    val effectiveCanonicalReady = canonicalReady || availability.canonicalReady
    val displayAsset = availability.displayAsset
    val recordedAt = RecordingDisplayTimeFormatter.format(recording.recordedAtLocalIso, recording.createdAtMs)
    val duration =
        (recording.mediaDurationMs ?: recording.deviceReportedDurationMs)
            ?.takeIf { it >= 0L }
            ?.let(::formatInfoDuration)
            ?: "--"
    val source =
        when {
            availability.originalReady && recording.sourceType == RecordingSourceType.DEVICE_DOWNLOAD -> "录音卡"
            effectiveCanonicalReady && availability.remoteOriginalAvailable -> "本地标准音频"
            availability.remotePending && !availability.originalReady && !effectiveCanonicalReady -> "录音卡（待下载）"
            recording.sourceType == RecordingSourceType.PHONE_MICROPHONE -> "手机麦克风"
            recording.sourceType == RecordingSourceType.LOCAL_IMPORT -> "本地导入"
            recording.sourceType == RecordingSourceType.DEVICE_DOWNLOAD -> "录音卡"
            else -> recording.sourceType
        }
    val format = displayAsset?.container.toProductAudioFormatLabel()
    val size = displayAsset?.sizeBytes?.let(::formatInfoBytes) ?: "--"
    val shareExportEnabled =
        availability.canShareOrExport || effectiveCanonicalReady || availability.remoteOriginalAvailable

    fun requestOriginal(onReady: () -> Unit) {
        if (originalActionInFlight) return
        if (availability.originalReady) {
            originalActionMessage = null
            onReady()
            return
        }
        if (!availability.remoteOriginalAvailable) {
            originalActionMessage = "没有可下载的原始 OPUS"
            return
        }
        originalActionInFlight = true
        originalActionMessage = null
        actionScope.launch {
            val error = ensureOriginalOpusDownloaded(recording, application)
            originalActionInFlight = false
            if (error == null) {
                originalActionMessage = null
                onReady()
            } else {
                originalActionMessage = error
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("录音信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ProductInfoLine(Icons.Outlined.CalendarMonth, "录制时间", recordedAt)
            ProductInfoLine(Icons.Outlined.AccessTime, "录音时长", duration)
            ProductInfoLine(Icons.Outlined.Description, "文件大小", size)
            ProductInfoLine(Icons.Outlined.MicNone, "来源 / 格式", "$source · $format")
            if (recording.sourceType == RecordingSourceType.DEVICE_DOWNLOAD) {
                ProductInfoLine(
                    Icons.Outlined.Description,
                    "标准音频",
                    if (effectiveCanonicalReady) "WAV · 已在本机" else "未生成",
                )
                ProductInfoLine(
                    Icons.Outlined.Description,
                    "原始音频",
                    when {
                        availability.originalReady -> "${availability.originalAsset?.container.toProductAudioFormatLabel()} · 已下载"
                        availability.remoteOriginalAvailable -> buildString {
                            append("OPUS · 录音卡上可下载")
                            availability.remoteOriginalSizeBytes?.let { bytes ->
                                append(" · ")
                                append(formatInfoBytes(bytes))
                            }
                        }
                        else -> "--"
                    },
                )
            }
            if (availability.remotePending && !availability.originalReady && !effectiveCanonicalReady) {
                Text(
                    "完整音频仍在录音卡，尚未下载到手机。下载原始 OPUS 后即可播放、生成标准 WAV、分享或导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductActionTile(Modifier.weight(1f), Icons.Outlined.Edit, "重命名", onClick = onRename)
                ProductActionTile(
                    Modifier.weight(1f), Icons.Outlined.Share, "分享", enabled = shareExportEnabled,
                    onClick = { originalActionMessage = null; sharePickerOpen = true },
                )
                ProductActionTile(
                    Modifier.weight(1f), Icons.Outlined.FileUpload, "导出", enabled = shareExportEnabled,
                    onClick = { originalActionMessage = null; exportPickerOpen = true },
                )
                ProductActionTile(Modifier.weight(1f), Icons.Outlined.DeleteOutline, "删除", destructive = true, onClick = onDelete)
            }
        }
    }

    if (sharePickerOpen) {
        AudioVariantDialog(
            title = "分享录音",
            canonicalReady = effectiveCanonicalReady,
            originalReady = availability.originalReady,
            remoteOriginalAvailable = availability.remoteOriginalAvailable,
            originalActionInFlight = originalActionInFlight,
            originalActionMessage = originalActionMessage,
            canonicalLabel = "标准 WAV",
            originalLabel = "原始 OPUS",
            onDismiss = { if (!originalActionInFlight) sharePickerOpen = false },
            onCanonical = { sharePickerOpen = false; onShareCanonical() },
            onOriginal = { requestOriginal { sharePickerOpen = false; onShareOriginal() } },
        )
    }
    if (exportPickerOpen) {
        AudioVariantDialog(
            title = "导出录音",
            canonicalReady = effectiveCanonicalReady,
            originalReady = availability.originalReady,
            remoteOriginalAvailable = availability.remoteOriginalAvailable,
            originalActionInFlight = originalActionInFlight,
            originalActionMessage = originalActionMessage,
            canonicalLabel = "标准 WAV",
            originalLabel = "原始 OPUS",
            onDismiss = { if (!originalActionInFlight) exportPickerOpen = false },
            onCanonical = { exportPickerOpen = false; onExportCanonical() },
            onOriginal = { requestOriginal { exportPickerOpen = false; onExportOriginal() } },
        )
    }
}

@Composable
private fun ProductInfoLine(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(label, modifier = Modifier.width(86.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ProductActionTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.outline
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val containerColor = if (destructive && enabled) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(27.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

@Composable
private fun AudioVariantDialog(
    title: String,
    canonicalReady: Boolean,
    originalReady: Boolean,
    remoteOriginalAvailable: Boolean,
    originalActionInFlight: Boolean,
    originalActionMessage: String?,
    canonicalLabel: String,
    originalLabel: String,
    onDismiss: () -> Unit,
    onCanonical: () -> Unit,
    onOriginal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCanonical, enabled = canonicalReady && !originalActionInFlight, modifier = Modifier.fillMaxWidth()) {
                    Text(canonicalLabel)
                }
                TextButton(
                    onClick = onOriginal,
                    enabled = (originalReady || remoteOriginalAvailable) && !originalActionInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (!originalReady && remoteOriginalAvailable) "$originalLabel · 下载" else originalLabel)
                }
                when {
                    originalActionInFlight -> Text(
                        "正在从录音卡下载原始 OPUS…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !originalActionMessage.isNullOrBlank() -> Text(
                        originalActionMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    canonicalReady && !originalReady && remoteOriginalAvailable -> Text(
                        "标准 WAV 已在本机；原始 OPUS 保留在录音卡，点击后按需下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !canonicalReady && !originalReady && remoteOriginalAvailable -> Text(
                        "原始 OPUS 尚未下载到手机，点击后从录音卡下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    !canonicalReady && originalReady -> Text(
                        "标准 WAV 尚未生成，可先使用原始 OPUS。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !originalActionInFlight) { Text("取消") }
        },
    )
}

private suspend fun ensureOriginalOpusDownloaded(
    recording: RecordingLibraryItem,
    application: VoicaApplication,
): String? {
    if (recording.localMediaAvailability().originalReady) return null
    val remoteIdentity = recording.sourceRemoteIdentity?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "没有可定位的原始 OPUS"
    val sourceDeviceAddress = recording.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "没有可定位的录音卡信息"
    val repository = application.container.deviceRepository
    val connected = repository.connectionState.value as? DeviceConnectionState.Ready
        ?: return "请先连接录制该音频的录音卡"
    if (!connected.address.equals(sourceDeviceAddress, ignoreCase = true)) {
        return "请连接录制该音频的对应录音卡"
    }

    val before = repository.deviceFileListState.value
    val refreshFailure = runCatching { repository.refreshDeviceFiles() }.exceptionOrNull()
    if (refreshFailure != null) return refreshFailure.message ?: "刷新录音卡文件失败"
    val refreshed = withTimeoutOrNull(DEVICE_FILE_REFRESH_TIMEOUT_MS) {
        repository.deviceFileListState
            .filter { state ->
                state.freshness in TERMINAL_FILE_LIST_STATES &&
                    (state.lastUpdatedTimeMs != before.lastUpdatedTimeMs || state.activeSessionId != before.activeSessionId)
            }
            .first()
    } ?: repository.deviceFileListState.value
    if (refreshed.freshness == FileListFreshness.FAILED) return "刷新录音卡文件失败"

    val expectedSize = recording.localMediaAvailability().remoteOriginalSizeBytes
    val remote = refreshed.files.firstOrNull { file -> file.identity == remoteIdentity }
        ?: refreshed.files.firstOrNull { file ->
            file.deviceAddress.equals(sourceDeviceAddress, ignoreCase = true) &&
                file.displayFilename.equals(recording.originalFilename, ignoreCase = true) &&
                (expectedSize == null || file.sizeBytes == expectedSize)
        }
        ?: return "录音卡中未找到对应的原始 OPUS"

    val downloadFailure = runCatching { repository.downloadDeviceFile(remote, DeviceAudioFormat.OPUS) }.exceptionOrNull()
    if (downloadFailure != null) return downloadFailure.message ?: "原始 OPUS 下载失败"

    val registered = withTimeoutOrNull(ORIGINAL_REGISTRATION_TIMEOUT_MS) {
        application.container.recordingLibraryRepository.recordings
            .map { items ->
                items.firstOrNull { item -> item.id == recording.id }
                    ?.localMediaAvailability()
                    ?.originalReady == true
            }
            .filter { it }
            .first()
    } == true
    return if (registered) null else "原始 OPUS 下载未完成，请重试"
}

internal fun String?.toProductAudioFormatLabel(): String {
    val normalized = this?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return when (normalized) {
        "RAW_OPUS", "OPUS" -> "OPUS"
        "" -> "--"
        else -> normalized
    }
}

private fun formatInfoDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatInfoBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private val TERMINAL_FILE_LIST_STATES = setOf(
    FileListFreshness.FRESH,
    FileListFreshness.EMPTY,
    FileListFreshness.FAILED,
)
private const val DEVICE_FILE_REFRESH_TIMEOUT_MS = 6_000L
private const val ORIGINAL_REGISTRATION_TIMEOUT_MS = 12_000L
