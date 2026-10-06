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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState

@Composable
fun GlobalMiniPlaybackBar(
    snapshot: PlaybackSnapshot,
    recordingName: String?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    if (!shouldRenderGlobalMiniPlayer(snapshot)) return

    val totalSamples = snapshot.durationSampleCount.coerceAtLeast(0L)
    val position = snapshot.positionSampleIndex.coerceIn(0L, totalSamples)
    val progress = globalMiniPlayerProgress(snapshot)

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = {
                        if (snapshot.state == PlaybackState.PLAYING) onPause() else onPlay()
                    },
                ) {
                    Icon(
                        imageVector =
                            if (snapshot.state == PlaybackState.PLAYING) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Outlined.PlayArrow
                            },
                        contentDescription =
                            if (snapshot.state == PlaybackState.PLAYING) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onOpen)
                            .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = recordingName?.takeIf { it.isNotBlank() } ?: "正在播放",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatPlaybackTime(position) + " / " + formatPlaybackTime(totalSamples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Close, contentDescription = "停止播放")
                }
            }

            MiniPlaybackProgress(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun shouldRenderGlobalMiniPlayer(snapshot: PlaybackSnapshot): Boolean =
    snapshot.recordingId != null &&
        snapshot.state in setOf(PlaybackState.PLAYING, PlaybackState.PAUSED)

internal fun globalMiniPlayerProgress(snapshot: PlaybackSnapshot): Float {
    val total = snapshot.durationSampleCount
    if (total <= 0L) return 0f
    return (snapshot.positionSampleIndex.toDouble() / total.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
}

@Composable
private fun MiniPlaybackProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.height(3.dp)) {
        val y = size.height / 2f
        val endX = size.width * progress.coerceIn(0f, 1f)
        drawLine(
            color = inactive,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height,
            cap = StrokeCap.Butt,
        )
        drawLine(
            color = active,
            start = Offset(0f, y),
            end = Offset(endX, y),
            strokeWidth = size.height,
            cap = StrokeCap.Butt,
        )
    }
}
