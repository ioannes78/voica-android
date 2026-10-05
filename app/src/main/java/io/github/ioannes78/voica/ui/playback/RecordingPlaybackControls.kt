package io.github.ioannes78.voica.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.audio.PlaybackState

@Composable
fun RecordingPlaybackCard(
    recordingId: String,
    recordingName: String,
    canonicalReady: Boolean,
    deviceRecordingActive: Boolean,
    playbackViewModel: PlaybackViewModel,
) {
    val snapshot by playbackViewModel.snapshot.collectAsState()
    val waveform by playbackViewModel.waveform.collectAsState()

    LaunchedEffect(recordingId, canonicalReady) {
        if (canonicalReady) playbackViewModel.loadWaveform(recordingId)
    }

    if (deviceRecordingActive) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "设备正在录音，播放已暂停。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    } else if (snapshot.recordingId == recordingId) {
        PlaybackCard(
            snapshot = snapshot,
            recordingName = recordingName,
            onPlay = playbackViewModel::play,
            onPause = playbackViewModel::pause,
            onSeek = playbackViewModel::seekToSample,
            onSpeed = playbackViewModel::setSpeed,
            onRetry = playbackViewModel::retryCurrent,
            waveform = waveform,
        )
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.playback_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Button(
                    onClick = { playbackViewModel.loadAndPlay(recordingId) },
                    enabled = canonicalReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.playback_play))
                }
                if (!canonicalReady) {
                    Text(
                        stringResource(R.string.detail_canonical_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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
        drawLine(inactive, Offset.Zero.copy(y = y), Offset(size.width, y), 3f, StrokeCap.Round)
        drawLine(active, Offset.Zero.copy(y = y), Offset(x, y), 3f, StrokeCap.Round)
    }
}