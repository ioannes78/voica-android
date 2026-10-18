package io.github.ioannes78.voica.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.CanonicalGenerationPhase
import io.github.ioannes78.voica.CanonicalGenerationProgress
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.ui.library.localMediaAvailability
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

@Composable
fun RecordingPlaybackCard(
    recordingId: String,
    recordingName: String,
    canonicalReady: Boolean,
    canonicalBusy: Boolean,
    canonicalProgress: CanonicalGenerationProgress?,
    deviceRecordingActive: Boolean,
    playbackViewModel: PlaybackViewModel,
    annotationSampleIndices: List<Long> = emptyList(),
    onWaveformLongPress: ((Long) -> Unit)? = null,
    onGenerateCanonical: () -> Unit,
    onCancelCanonical: () -> Unit,
) {
    val snapshot by playbackViewModel.snapshot.collectAsState()
    val context = LocalContext.current
    val application = context.applicationContext as? VoicaApplication
    val recordingsFlow =
        remember(application) {
            application?.container?.recordingLibraryRepository?.recordings
                ?: flowOf(emptyList<RecordingLibraryItem>())
        }
    val libraryRecordings by recordingsFlow.collectAsState(initial = emptyList())
    val mediaAvailability =
        libraryRecordings.firstOrNull { item -> item.id == recordingId }?.localMediaAvailability()
    val effectiveCanonicalReady = canonicalReady || mediaAvailability?.canonicalReady == true
    val remotePendingOnly =
        mediaAvailability?.let { availability ->
            availability.remotePending &&
                !availability.originalReady &&
                !effectiveCanonicalReady
        } == true
    val waveformRepository =
        remember(application) {
            application?.let { app ->
                WaveformOverviewRepository(
                    sourceResolver = app.container.audioSourceResolver,
                    cacheRoot = File(app.cacheDir, "waveforms"),
                )
            }
        }
    var waveform by remember(recordingId) {
        mutableStateOf<WaveformOverviewResult>(WaveformOverviewResult.Unavailable)
    }

    LaunchedEffect(recordingId, effectiveCanonicalReady, waveformRepository) {
        waveform = WaveformOverviewResult.Unavailable
        if (effectiveCanonicalReady && waveformRepository != null) {
            waveform = waveformRepository.load(recordingId)
        }
    }

    when {
        deviceRecordingActive -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Text(
                    "设备正在录音，播放已暂停。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }

        !effectiveCanonicalReady && (canonicalProgress != null || canonicalBusy) ->
            CanonicalGeneratingPlaybackCard(
                progress = canonicalProgress,
                onCancel = onCancelCanonical,
            )

        remotePendingOnly -> RemotePendingPlaybackCard()

        !effectiveCanonicalReady ->
            CanonicalRequiredPlaybackCard(onGenerateCanonical = onGenerateCanonical)

        snapshot.recordingId == recordingId ->
            PlaybackCard(
                snapshot = snapshot,
                recordingName = recordingName,
                onPlay = playbackViewModel::play,
                onPause = playbackViewModel::pause,
                onSeek = playbackViewModel::seekToSample,
                onSpeed = playbackViewModel::setSpeed,
                onRetry = playbackViewModel::retryCurrent,
                waveform = waveform,
                annotationSampleIndices = annotationSampleIndices,
                onWaveformLongPress = onWaveformLongPress,
            )

        else ->
            ReadyPlaybackLaunchCard(
                recordingId = recordingId,
                playbackViewModel = playbackViewModel,
            )
    }
}

@Composable
private fun RemotePendingPlaybackCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "完整录音待下载",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "完整音频仍在录音卡，尚未下载到手机。下载完成后可生成标准 WAV、播放、转写和导出。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CanonicalRequiredPlaybackCard(
    onGenerateCanonical: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "需要生成标准 WAV",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "生成后可播放、转写和说话人分析",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onGenerateCanonical,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "生成标准 WAV",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CanonicalGeneratingPlaybackCard(
    progress: CanonicalGenerationProgress?,
    onCancel: () -> Unit,
) {
    var nowMs by remember(progress?.recordingId, progress?.startedAtMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(progress?.recordingId, progress?.startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val fraction = progress?.fraction
    val percent = fraction?.let { (it * 100f).toInt().coerceIn(0, 100) }
    val processed = progress?.processedDurationUs?.let(::formatCanonicalWorkTime) ?: "--:--"
    val total = progress?.totalDurationUs?.let(::formatCanonicalWorkTime) ?: "--:--"
    val elapsed =
        progress?.let {
            formatElapsedMs((nowMs - it.startedAtMs).coerceAtLeast(0L))
        } ?: "--:--"
    val cancellable = progress?.cancellable != false
    val subtitle = canonicalPhaseLabel(progress?.phase)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "正在生成标准 WAV",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cancellable && progress?.phase != CanonicalGenerationPhase.CANCELLING) {
                    TextButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
            }

            if (fraction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${percent ?: 0}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "已处理 $processed / $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "已耗时 $elapsed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DisabledPlayerPreview(totalDurationUs = progress?.totalDurationUs)

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            ) {
                Text(
                    "生成完成后可播放、转写和说话人分析",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DisabledPlayerPreview(totalDurationUs: Long?) {
    val waveformColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(54.dp),
        ) {
            val bars = 48
            val spacing = size.width / bars
            repeat(bars) { index ->
                val pattern = intArrayOf(4, 7, 11, 15, 10, 6, 13, 8)[index % 8]
                val halfHeight = size.height * (pattern / 36f)
                val x = spacing * index + spacing / 2f
                drawLine(
                    color = waveformColor,
                    start = Offset(x, size.height / 2f - halfHeight),
                    end = Offset(x, size.height / 2f + halfHeight),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "00:00",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                totalDurationUs?.let(::formatCanonicalWorkTime) ?: "--:--",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.size(76.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
        ) {
            Row(
                modifier = Modifier.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf("0.75x", "1.0x", "1.5x", "2.0x").forEach { speed ->
                    Text(
                        speed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyPlaybackLaunchCard(
    recordingId: String,
    playbackViewModel: PlaybackViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(R.string.playback_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = { playbackViewModel.loadAndPlay(recordingId) },
                modifier = Modifier.size(86.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.playback_play),
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                stringResource(R.string.playback_play),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun canonicalPhaseLabel(phase: CanonicalGenerationPhase?): String =
    when (phase) {
        CanonicalGenerationPhase.PREPARING -> "正在准备"
        CanonicalGenerationPhase.DECODING,
        CanonicalGenerationPhase.NORMALIZING,
        CanonicalGenerationPhase.WRITING,
        -> "正在解码与转换"
        CanonicalGenerationPhase.VERIFYING -> "正在校验生成结果"
        CanonicalGenerationPhase.COMMITTING -> "正在保存生成结果"
        CanonicalGenerationPhase.CANCELLING -> "正在取消"
        null -> "正在处理"
    }

private fun formatCanonicalWorkTime(durationUs: Long): String {
    val totalSeconds = (durationUs / 1_000_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatElapsedMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@Composable
fun MiniPlaybackBar(
    recordingId: String,
    recordingName: String = "",
    durationMs: Long?,
    canonicalReady: Boolean,
    deviceRecordingActive: Boolean,
    playbackViewModel: PlaybackViewModel,
    onOpenFullPlayer: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val ignoredDurationMs = durationMs
    if (!canonicalReady || deviceRecordingActive) return

    val snapshot by playbackViewModel.snapshot.collectAsState()
    val loaded = snapshot.recordingId == recordingId
    if (
        !loaded ||
        snapshot.state !in setOf(PlaybackState.PLAYING, PlaybackState.PAUSED)
    ) {
        return
    }

    val totalSamples = snapshot.durationSampleCount.coerceAtLeast(0L)
    val position = snapshot.positionSampleIndex.coerceIn(0L, totalSamples)
    val ratio =
        if (totalSamples > 0L) {
            (position.toDouble() / totalSamples.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    Surface(
        tonalElevation = 3.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenFullPlayer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        if (snapshot.state == PlaybackState.PLAYING) {
                            playbackViewModel.pause()
                        } else {
                            playbackViewModel.play()
                        }
                    },
                ) {
                    Icon(
                        if (snapshot.state == PlaybackState.PLAYING) {
                            Icons.Outlined.Pause
                        } else {
                            Icons.Outlined.PlayArrow
                        },
                        contentDescription =
                            if (snapshot.state == PlaybackState.PLAYING) "暂停" else "播放",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recordingName.ifBlank { "正在播放" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatPlaybackTime(position) + " / " + formatPlaybackTime(totalSamples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = playbackViewModel::closePlayer) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭播放器")
                }
            }
            MiniProgress(ratio)
        }
    }
}

@Composable
private fun MiniProgress(progress: Float) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(4.dp),
    ) {
        val y = size.height / 2f
        val x = size.width * progress.coerceIn(0f, 1f)
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), 3f, StrokeCap.Round)
        drawLine(active, Offset(0f, y), Offset(x, y), 3f, StrokeCap.Round)
    }
}
