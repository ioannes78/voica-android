package io.github.ioannes78.voica.ui.transcript

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import java.util.Locale

@Composable
fun TranscriptionStatusCard(
    state: TranscriptionRunState,
    recordingName: String?,
    notice: String?,
    onCancel: () -> Unit,
) {
    if (state is TranscriptionRunState.Idle && notice == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.transcription_status_title),
                style = MaterialTheme.typography.titleMedium,
            )

            when (state) {
                TranscriptionRunState.Idle -> Unit

                is TranscriptionRunState.Running -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(modeLabel(state.mode))
                    Text(phaseLabel(state.progress.phase))
                    val fraction = state.progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.transcription_progress_percent,
                                (fraction * 100.0).toInt().coerceIn(0, 100),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.transcription_cancel))
                    }
                }

                is TranscriptionRunState.Completed -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.transcription_completed,
                            modeLabel(state.mode),
                        ),
                    )
                    state.warning?.let { warning ->
                        Text(
                            stringResource(
                                R.string.transcription_completed_with_fallback,
                                warning,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is TranscriptionRunState.Failed -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.transcription_failed,
                            state.message,
                        ),
                    )
                    if (state.missingModelIds.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.transcription_missing_models,
                                state.missingModelIds.joinToString(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is TranscriptionRunState.Cancelled -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.transcription_cancelled))
                }
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun TranscriptDocumentHeader(
    document: TranscriptDocument,
    recordingName: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.transcript_result_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(recordingName ?: document.recordingId)
            Text(
                stringResource(
                    R.string.transcript_result_mode,
                    modeLabel(document.mode),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.transcript_result_segments,
                    document.segments.size,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun TranscriptSegmentCard(segment: TranscriptDisplaySegment) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                formatSampleRange(
                    segment.startSampleIndex,
                    segment.endSampleIndexExclusive,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                segment.text.ifBlank {
                    stringResource(R.string.transcript_empty_segment)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun modeLabel(mode: TranscriptionMode): String =
    when (mode) {
        TranscriptionMode.FAST ->
            stringResource(R.string.transcription_mode_fast)
        TranscriptionMode.HIGH_QUALITY ->
            stringResource(R.string.transcription_mode_high_quality)
    }

@Composable
private fun modeLabel(mode: String): String =
    when (mode) {
        "FAST" -> stringResource(R.string.transcription_mode_fast)
        "HIGH_QUALITY" -> stringResource(R.string.transcription_mode_high_quality)
        else -> mode
    }

@Composable
private fun phaseLabel(phase: TranscriptionPhase): String =
    when (phase) {
        TranscriptionPhase.PREPARING ->
            stringResource(R.string.transcription_phase_preparing)
        TranscriptionPhase.VAD ->
            stringResource(R.string.transcription_phase_vad)
        TranscriptionPhase.FIRST_PASS ->
            stringResource(R.string.transcription_phase_first_pass)
        TranscriptionPhase.SECOND_PASS ->
            stringResource(R.string.transcription_phase_second_pass)
        TranscriptionPhase.PUNCTUATION ->
            stringResource(R.string.transcription_phase_punctuation)
        TranscriptionPhase.PERSISTING ->
            stringResource(R.string.transcription_phase_persisting)
    }

private fun formatSampleRange(
    start: Long,
    end: Long,
): String =
    formatSampleTime(start) + " – " + formatSampleTime(end)

private fun formatSampleTime(sampleIndex: Long): String {
    val seconds = sampleIndex.toDouble() / 16_000.0
    val totalWholeSeconds = seconds.toLong()
    val minutes = totalWholeSeconds / 60L
    val remainingSeconds = seconds - minutes * 60.0
    return String.format(
        Locale.US,
        "%02d:%05.2f",
        minutes,
        remainingSeconds,
    )
}
