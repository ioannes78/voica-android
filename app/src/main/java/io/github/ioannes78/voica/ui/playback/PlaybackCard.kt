package io.github.ioannes78.voica.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
) {
    val totalSamples = snapshot.durationSampleCount.coerceAtLeast(0L)
    var dragging by remember(snapshot.recordingId) { mutableStateOf(false) }
    var previewRatio by remember(snapshot.recordingId) { mutableFloatStateOf(0f) }
    var speedMenuExpanded by remember { mutableStateOf(false) }

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

    val displaySample =
        if (dragging && totalSamples > 0L) {
            (previewRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        } else {
            snapshot.positionSampleIndex.coerceIn(0L, totalSamples)
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                playbackStateText(snapshot.state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                formatPlaybackTime(displaySample) +
                    " / " +
                    formatPlaybackTime(totalSamples),
            )

            Slider(
                value = if (dragging) previewRatio else actualRatio,
                onValueChange = { value ->
                    dragging = true
                    previewRatio = value.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    val target =
                        (previewRatio.toDouble() * totalSamples.toDouble())
                            .roundToLong()
                            .coerceIn(0L, totalSamples)
                    dragging = false
                    onSeek(target)
                },
                enabled =
                    totalSamples > 0L &&
                        snapshot.state != PlaybackState.PREPARING &&
                        snapshot.state != PlaybackState.SEEKING &&
                        snapshot.state != PlaybackState.ERROR &&
                        snapshot.state != PlaybackState.RELEASED,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (snapshot.state) {
                    PlaybackState.PLAYING -> {
                        Button(onClick = onPause) {
                            Text(stringResource(R.string.playback_pause))
                        }
                    }
                    PlaybackState.ERROR -> {
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.playback_retry))
                        }
                    }
                    PlaybackState.PREPARING,
                    PlaybackState.SEEKING,
                    PlaybackState.RELEASED,
                    PlaybackState.IDLE,
                    -> {
                        Button(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.playback_play))
                        }
                    }
                    else -> {
                        Button(onClick = onPlay) {
                            Text(stringResource(R.string.playback_play))
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { speedMenuExpanded = true },
                        enabled =
                            snapshot.state != PlaybackState.PREPARING &&
                                snapshot.state != PlaybackState.RELEASED &&
                                snapshot.state != PlaybackState.IDLE,
                    ) {
                        Text(formatSpeed(snapshot.speed))
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false },
                    ) {
                        SUPPORTED_SPEEDS.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text(formatSpeed(speed)) },
                                onClick = {
                                    speedMenuExpanded = false
                                    onSpeed(speed)
                                },
                            )
                        }
                    }
                }
            }

            snapshot.error?.let { error ->
                Text(
                    playbackErrorText(error.code),
                    style = MaterialTheme.typography.bodySmall,
                )
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

        PlaybackErrorCode.AUDIO_FOCUS_DENIED ->
            stringResource(R.string.playback_error_focus)

        PlaybackErrorCode.PLAYBACK_SPEED_UNSUPPORTED ->
            stringResource(R.string.playback_error_speed)

        PlaybackErrorCode.SOURCE_REMOVED ->
            stringResource(R.string.playback_error_removed)

        PlaybackErrorCode.INTERNAL_STATE_ERROR ->
            stringResource(R.string.playback_error_internal)
    }

private fun formatPlaybackTime(sampleIndex: Long): String {
    val seconds =
        sampleIndexToTimeUs(
            sampleIndex.coerceAtLeast(0L),
            CanonicalPcmProfile.SAMPLE_RATE_HZ,
        ) / 1_000_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        String.format(
            Locale.US,
            "%d:%02d:%02d",
            hours,
            minutes,
            remainingSeconds,
        )
    } else {
        String.format(
            Locale.US,
            "%02d:%02d",
            minutes,
            remainingSeconds,
        )
    }
}

private fun formatSpeed(speed: Float): String =
    when (speed) {
        0.5f -> "0.5×"
        0.75f -> "0.75×"
        1.0f -> "1.0×"
        1.25f -> "1.25×"
        1.5f -> "1.5×"
        2.0f -> "2.0×"
        else -> String.format(Locale.US, "%.2f×", speed)
    }

private val SUPPORTED_SPEEDS =
    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
