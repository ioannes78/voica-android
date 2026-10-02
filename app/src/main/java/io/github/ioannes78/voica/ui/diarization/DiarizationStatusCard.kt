package io.github.ioannes78.voica.ui.diarization

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
import io.github.ioannes78.voica.DiarizationRunState
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.transcript.DiarizationPhase

@Composable
fun DiarizationStatusCard(
    state: DiarizationRunState,
    recordingName: String?,
    notice: String?,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state is DiarizationRunState.Idle && notice == null) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.diarization_status_title),
                style = MaterialTheme.typography.titleMedium,
            )

            when (state) {
                DiarizationRunState.Idle -> Unit

                is DiarizationRunState.Running -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(diarizationPhaseLabel(state.progress.phase))
                    val fraction = state.progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.diarization_progress_percent,
                                (fraction * 100.0).toInt().coerceIn(0, 100),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.diarization_cancel))
                    }
                }

                is DiarizationRunState.Completed -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.diarization_completed,
                            state.speakerCount,
                        ),
                    )
                }

                is DiarizationRunState.Failed -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.diarization_failed,
                            state.message,
                        ),
                    )
                    if (state.missingModelIds.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.diarization_missing_models,
                                state.missingModelIds.joinToString(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenSettings) {
                            Text(stringResource(R.string.diarization_open_model_settings))
                        }
                    }
                    OutlinedButton(onClick = { onRetry(state.recordingId) }) {
                        Text(stringResource(R.string.diarization_retry))
                    }
                }

                is DiarizationRunState.Cancelled -> {
                    Text(
                        recordingName ?: state.recordingId,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.diarization_cancelled))
                    OutlinedButton(onClick = { onRetry(state.recordingId) }) {
                        Text(stringResource(R.string.diarization_retry))
                    }
                }
            }

            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun diarizationPhaseLabel(phase: DiarizationPhase): String =
    when (phase) {
        DiarizationPhase.PREPARING ->
            stringResource(R.string.diarization_phase_preparing)
        DiarizationPhase.VAD ->
            stringResource(R.string.diarization_phase_vad)
        DiarizationPhase.DIARIZATION ->
            stringResource(R.string.diarization_phase_diarization)
        DiarizationPhase.STITCHING ->
            stringResource(R.string.diarization_phase_stitching)
        DiarizationPhase.ALIGNMENT ->
            stringResource(R.string.diarization_phase_alignment)
        DiarizationPhase.PERSISTING ->
            stringResource(R.string.diarization_phase_persisting)
    }
