package io.github.ioannes78.voica.ui.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PlaybackErrorCode
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.audio.sampleIndexToTimeUs
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun PlaybackCard(
    snapshot: PlaybackSnapshot,
    recordingName: String?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onRetry: () -> Unit,
    waveform: WaveformOverviewResult = WaveformOverviewResult.Unavailable,
) {
    @Suppress("UNUSED_VARIABLE") val ignoredRecordingName = recordingName
    val totalSamples = snapshot.durationSampleCount.coerceAtLeast(0L)
    var dragging by remember(snapshot.recordingId) { mutableStateOf(false) }
    var previewRatio by remember(snapshot.recordingId) { mutableFloatStateOf(0f) }
    val actualRatio =
        if (totalSamples > 0L) {
            (snapshot.positionSampleIndex.toDouble() / totalSamples.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        } else {
            0f
        }
    LaunchedEffect(actualRatio, dragging) {
        if (!dragging) previewRatio = actualRatio
    }
    val visibleRatio = if (dragging) previewRatio else actualRatio
    val displaySample =
        if (totalSamples > 0L) {
            (visibleRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        } else {
            0L
        }
    val controlsEnabled =
        snapshot.state !in
            setOf(
                PlaybackState.PREPARING,
                PlaybackState.SEEKING,
                PlaybackState.ERROR,
                PlaybackState.RELEASED,
                PlaybackState.IDLE,
            )

    fun updatePreview(ratio: Float) {
        dragging = true
        previewRatio = ratio.coerceIn(0f, 1f)
    }

    fun commitPreview() {
        if (!dragging || totalSamples <= 0L) return
        val target =
            (previewRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        dragging = false
        onSeek(target)
    }

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                Box(
                    modifier =
                        Modifier
                            .size(9.dp)
                            .background(
                                if (snapshot.state == PlaybackState.PLAYING) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                CircleShape,
                            ),
                )
                Text(
                    playbackStateText(snapshot.state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    formatPlaybackTime(displaySample),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "/ " + formatPlaybackTime(totalSamples),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }

            val amplitudes = (waveform as? WaveformOverviewResult.Ready)?.amplitudes.orEmpty()
            if (amplitudes.isNotEmpty()) {
                WaveformSeekOverview(
                    amplitudes = amplitudes,
                    progress = visibleRatio,
                    enabled = totalSamples > 0L && controlsEnabled,
                    onPreview = ::updatePreview,
                    onCommit = ::commitPreview,
                    onTapSeek = { ratio ->
                        onSeek(
                            (ratio * totalSamples.toDouble())
                                .roundToLong()
                                .coerceIn(0L, totalSamples),
                        )
                    },
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(92.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "波形暂不可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RoundProgressTrack(
                progress = visibleRatio,
                enabled = totalSamples > 0L && controlsEnabled,
                onPreview = ::updatePreview,
                onCommit = ::commitPreview,
                onTapSeek = { ratio ->
                    onSeek(
                        (ratio * totalSamples.toDouble())
                            .roundToLong()
                            .coerceIn(0L, totalSamples),
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatPlaybackTime(displaySample),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatPlaybackTime(totalSamples),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SeekControl(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Replay10,
                    label = "-10s",
                    enabled = totalSamples > 0L && controlsEnabled,
                    onClick = {
                        onSeek((displaySample - SEEK_STEP_SAMPLES).coerceAtLeast(0L))
                    },
                )

                val transportEnabled =
                    snapshot.state !in
                        setOf(
                            PlaybackState.PREPARING,
                            PlaybackState.SEEKING,
                            PlaybackState.RELEASED,
                            PlaybackState.IDLE,
                        )
                val transportIcon =
                    when (snapshot.state) {
                        PlaybackState.PLAYING -> Icons.Outlined.Pause
                        PlaybackState.ERROR -> Icons.Outlined.Refresh
                        else -> Icons.Outlined.PlayArrow
                    }
                val transportLabel =
                    when (snapshot.state) {
                        PlaybackState.PLAYING -> stringResource(R.string.playback_pause)
                        PlaybackState.ERROR -> stringResource(R.string.playback_retry)
                        else -> stringResource(R.string.playback_play)
                    }
                val transportClick =
                    when (snapshot.state) {
                        PlaybackState.PLAYING -> onPause
                        PlaybackState.ERROR -> onRetry
                        else -> onPlay
                    }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = transportClick,
                        enabled = transportEnabled,
                        modifier = Modifier.size(86.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            imageVector = transportIcon,
                            contentDescription = transportLabel,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Text(
                        transportLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                SeekControl(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Forward10,
                    label = "+10s",
                    enabled = totalSamples > 0L && controlsEnabled,
                    onClick = {
                        onSeek((displaySample + SEEK_STEP_SAMPLES).coerceAtMost(totalSamples))
                    },
                )
            }

            Text(
                "播放速度",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            SegmentedSpeedControl(
                selectedSpeed = snapshot.speed,
                enabled = controlsEnabled,
                onSpeed = onSpeed,
            )

            snapshot.error?.let { error ->
                Text(
                    playbackErrorText(error.code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SeekControl(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier =
                Modifier
                    .size(66.dp)
                    .clickable(enabled = enabled, onClick = onClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaveformSeekOverview(
    amplitudes: List<Float>,
    progress: Float,
    enabled: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onTapSeek: (Double) -> Unit,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val remainingColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        onTapSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble())
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            onPreview((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        },
                        onDragEnd = onCommit,
                        onDragCancel = onCommit,
                    ) { change, _ ->
                        change.consume()
                        onPreview((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                },
    ) {
        if (amplitudes.isEmpty()) return@Canvas
        val centerY = size.height / 2f
        val step = size.width / amplitudes.size.toFloat()
        val progressX = progress.coerceIn(0f, 1f) * size.width
        amplitudes.forEachIndexed { index, value ->
            val x = step * (index + 0.5f)
            val halfHeight = value.coerceIn(0.05f, 1f) * size.height * 0.45f
            drawLine(
                color = if (x <= progressX) playedColor else remainingColor,
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = (step * 0.48f).coerceIn(2f, 7f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun RoundProgressTrack(
    progress: Float,
    enabled: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onTapSeek: (Double) -> Unit,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val thumbRing = MaterialTheme.colorScheme.surface
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        onTapSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble())
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            onPreview((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        },
                        onDragEnd = onCommit,
                        onDragCancel = onCommit,
                    ) { change, _ ->
                        change.consume()
                        onPreview((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                },
    ) {
        val y = size.height / 2f
        val x = progress.coerceIn(0f, 1f) * size.width
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = 6f, cap = StrokeCap.Round)
        drawLine(active, Offset(0f, y), Offset(x, y), strokeWidth = 6f, cap = StrokeCap.Round)
        drawCircle(color = thumbRing, radius = 12f, center = Offset(x, y))
        drawCircle(color = active, radius = 8f, center = Offset(x, y))
    }
}

@Composable
private fun SegmentedSpeedControl(
    selectedSpeed: Float,
    enabled: Boolean,
    onSpeed: (Float) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SUPPORTED_SPEEDS.forEach { speed ->
                val selected = kotlin.math.abs(selectedSpeed - speed) < 0.01f
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onSpeed(speed) },
                    shape = RoundedCornerShape(18.dp),
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    contentColor =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ) {
                    Text(
                        text = formatSpeed(speed),
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun playbackStateText(state: PlaybackState): String =
    when (state) {
        PlaybackState.PREPARING -> stringResource(R.string.playback_preparing)
        PlaybackState.READY -> stringResource(R.string.playback_ready)
        PlaybackState.PLAYING -> stringResource(R.string.playback_playing)
        PlaybackState.PAUSED -> stringResource(R.string.playback_paused)
        PlaybackState.SEEKING -> stringResource(R.string.playback_seeking)
        PlaybackState.COMPLETED -> stringResource(R.string.playback_completed)
        PlaybackState.ERROR -> stringResource(R.string.playback_error_output)
        PlaybackState.IDLE,
        PlaybackState.RELEASED,
        -> stringResource(R.string.playback_ready)
    }

@Composable
private fun playbackErrorText(code: PlaybackErrorCode): String =
    when (code) {
        PlaybackErrorCode.SOURCE_NOT_AVAILABLE,
        PlaybackErrorCode.SOURCE_INTEGRITY_FAILED,
        PlaybackErrorCode.INVALID_CANONICAL_WAV,
        -> stringResource(R.string.playback_error_source)
        PlaybackErrorCode.AUDIO_TRACK_INIT_FAILED,
        PlaybackErrorCode.AUDIO_READ_FAILED,
        PlaybackErrorCode.OUTPUT_ROUTE_FAILED,
        -> stringResource(R.string.playback_error_output)
        PlaybackErrorCode.AUDIO_FOCUS_DENIED -> stringResource(R.string.playback_error_focus)
        PlaybackErrorCode.PLAYBACK_SPEED_UNSUPPORTED -> stringResource(R.string.playback_error_speed)
        PlaybackErrorCode.SOURCE_REMOVED -> stringResource(R.string.playback_error_removed)
        PlaybackErrorCode.INTERNAL_STATE_ERROR -> stringResource(R.string.playback_error_internal)
    }

internal fun formatPlaybackTime(sampleIndex: Long): String {
    val seconds =
        sampleIndexToTimeUs(
            sampleIndex.coerceAtLeast(0L),
            CanonicalPcmProfile.SAMPLE_RATE_HZ,
        ) / 1_000_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, remainingSeconds)
    }
}

private fun formatSpeed(speed: Float): String =
    when (speed) {
        0.5f -> "0.5×"
        0.75f -> "0.75×"
        1.0f -> "1.0×"
        1.5f -> "1.5×"
        2.0f -> "2.0×"
        else -> String.format(Locale.US, "%.2f×", speed)
    }

internal val SUPPORTED_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
private const val SEEK_STEP_SECONDS = 10L
private const val SEEK_STEP_SAMPLES = SEEK_STEP_SECONDS * CanonicalPcmProfile.SAMPLE_RATE_HZ
