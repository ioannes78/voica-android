package io.github.ioannes78.voica.ui.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.ioannes78.voica.audio.PlaybackState
import kotlin.math.roundToLong

@Composable
fun RecordingPlaybackCard(
    recordingId: String,
    recordingName: String,
    canonicalReady: Boolean,
    deviceRecordingActive: Boolean,
    playbackViewModel: PlaybackViewModel,
) {
    val snapshot by playbackViewModel.snapshot.collectAsState()

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
        )
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
    durationMs: Long?,
    canonicalReady: Boolean,
    deviceRecordingActive: Boolean,
    playbackViewModel: PlaybackViewModel,
    onOpenFullPlayer: () -> Unit,
) {
    if (!canonicalReady || deviceRecordingActive) return

    val snapshot by playbackViewModel.snapshot.collectAsState()
    val loaded = snapshot.recordingId == recordingId
    val totalSamples =
        if (loaded && snapshot.durationSampleCount > 0L) {
            snapshot.durationSampleCount
        } else {
            durationMs
                ?.coerceAtLeast(0L)
                ?.times(CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong())
                ?.div(1_000L)
                ?: 0L
        }
    val actualPosition =
        if (loaded) {
            snapshot.positionSampleIndex.coerceIn(0L, totalSamples.coerceAtLeast(0L))
        } else {
            0L
        }
    val actualRatio =
        if (totalSamples > 0L) {
            (actualPosition.toDouble() / totalSamples.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        } else {
            0f
        }

    var dragging by remember(recordingId) { mutableStateOf(false) }
    var previewRatio by remember(recordingId) { mutableFloatStateOf(0f) }

    LaunchedEffect(actualRatio, dragging) {
        if (!dragging) {
            previewRatio = actualRatio
        }
    }

    val previewPosition =
        if (dragging && totalSamples > 0L) {
            (previewRatio.toDouble() * totalSamples.toDouble())
                .roundToLong()
                .coerceIn(0L, totalSamples)
        } else {
            actualPosition
        }

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenFullPlayer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        when {
                            loaded && snapshot.state == PlaybackState.PLAYING ->
                                playbackViewModel.pause()
                            loaded && snapshot.state == PlaybackState.ERROR ->
                                playbackViewModel.retryCurrent()
                            loaded -> playbackViewModel.play()
                            else -> playbackViewModel.loadAndPlay(recordingId)
                        }
                    },
                ) {
                    Icon(
                        if (loaded && snapshot.state == PlaybackState.PLAYING) {
                            Icons.Outlined.Pause
                        } else {
                            Icons.Outlined.PlayArrow
                        },
                        contentDescription =
                            if (loaded && snapshot.state == PlaybackState.PLAYING) {
                                "暂停"
                            } else {
                                "播放"
                            },
                    )
                }
                Text(
                    formatPlaybackTime(previewPosition) +
                        " / " + formatPlaybackTime(totalSamples),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenFullPlayer) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "打开播放器")
                }
            }
            Slider(
                value = if (dragging) previewRatio else actualRatio,
                onValueChange = {
                    dragging = true
                    previewRatio = it.coerceIn(0f, 1f)
                },
                onValueChangeFinished = {
                    if (loaded && totalSamples > 0L) {
                        val target =
                            (previewRatio.toDouble() * totalSamples.toDouble())
                                .roundToLong()
                                .coerceIn(0L, totalSamples)
                        playbackViewModel.seekToSample(target)
                    }
                    dragging = false
                },
                enabled =
                    loaded &&
                        totalSamples > 0L &&
                        snapshot.state != PlaybackState.PREPARING &&
                        snapshot.state != PlaybackState.ERROR &&
                        snapshot.state != PlaybackState.RELEASED,
            )
        }
    }
}
