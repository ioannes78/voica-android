package io.github.ioannes78.voica.ui.realtime

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.ioannes78.voica.PhoneMicrophoneRealtimeActionOutcome
import io.github.ioannes78.voica.PhoneMicrophoneRealtimeRuntime
import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import io.github.ioannes78.voica.Stage16ARealtimeActionOutcome
import io.github.ioannes78.voica.Stage16ARealtimeRuntime
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class RealtimeTranscriptionUiActionOutcome(
    val success: Boolean,
    val code: String? = null,
    val message: String? = null,
    val degraded: Boolean = false,
)

/** UI-only contract shared by recorder-card and phone-microphone realtime products. */
internal interface RealtimeTranscriptionUiController {
    val state: StateFlow<Stage16ARealtimeUiState>

    suspend fun start(): RealtimeTranscriptionUiActionOutcome

    suspend fun pause(): RealtimeTranscriptionUiActionOutcome

    suspend fun resume(): RealtimeTranscriptionUiActionOutcome

    suspend fun stop(): RealtimeTranscriptionUiActionOutcome
}

internal class RecorderCardRealtimeUiController(
    private val runtime: Stage16ARealtimeRuntime,
) : RealtimeTranscriptionUiController {
    override val state: StateFlow<Stage16ARealtimeUiState>
        get() = runtime.state

    override suspend fun start(): RealtimeTranscriptionUiActionOutcome = runtime.start().toUiOutcome()

    override suspend fun pause(): RealtimeTranscriptionUiActionOutcome = runtime.pause().toUiOutcome()

    override suspend fun resume(): RealtimeTranscriptionUiActionOutcome = runtime.resume().toUiOutcome()

    override suspend fun stop(): RealtimeTranscriptionUiActionOutcome = runtime.stop().toUiOutcome()
}

internal class PhoneMicrophoneRealtimeUiController(
    private val runtime: PhoneMicrophoneRealtimeRuntime,
    scope: CoroutineScope,
) : RealtimeTranscriptionUiController {
    override val state: StateFlow<Stage16ARealtimeUiState> =
        combine(runtime.state, runtime.recordingState) { realtime, recording ->
            projectPhoneRealtimeUiState(
                realtime = realtime,
                recording = recording,
                runnerFailure = runtime.snapshot().runnerFailure,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue =
                projectPhoneRealtimeUiState(
                    realtime = runtime.state.value,
                    recording = runtime.recordingState.value,
                    runnerFailure = runtime.snapshot().runnerFailure,
                ),
        )

    override suspend fun start(): RealtimeTranscriptionUiActionOutcome = runtime.start().toUiOutcome()

    override suspend fun pause(): RealtimeTranscriptionUiActionOutcome = runtime.pause().toUiOutcome()

    override suspend fun resume(): RealtimeTranscriptionUiActionOutcome = runtime.resume().toUiOutcome()

    override suspend fun stop(): RealtimeTranscriptionUiActionOutcome = runtime.stop().toUiOutcome()
}

internal fun projectPhoneRealtimeUiState(
    realtime: Stage16ARealtimeUiState,
    recording: PhoneMicrophoneRecordingSnapshot,
    runnerFailure: String?,
): Stage16ARealtimeUiState {
    if (runnerFailure == null) return realtime

    val projectedPhase =
        when (recording.phase) {
            PhoneMicrophoneRecordingPhase.STARTING -> Stage16ARealtimeUiPhase.STARTING
            PhoneMicrophoneRecordingPhase.RECORDING,
            PhoneMicrophoneRecordingPhase.PAUSING,
            -> Stage16ARealtimeUiPhase.ACTIVE
            PhoneMicrophoneRecordingPhase.PAUSED -> Stage16ARealtimeUiPhase.PAUSED
            PhoneMicrophoneRecordingPhase.RESUMING -> Stage16ARealtimeUiPhase.RESUMING
            PhoneMicrophoneRecordingPhase.STOPPING -> Stage16ARealtimeUiPhase.FINALIZING
            PhoneMicrophoneRecordingPhase.IDLE,
            PhoneMicrophoneRecordingPhase.COMPLETED,
            PhoneMicrophoneRecordingPhase.EMPTY,
            PhoneMicrophoneRecordingPhase.FAILED,
            PhoneMicrophoneRecordingPhase.CLOSED,
            -> return realtime
        }

    val message =
        when (projectedPhase) {
            Stage16ARealtimeUiPhase.PAUSED -> "录音已暂停 · 实时转写已停止"
            Stage16ARealtimeUiPhase.RESUMING -> "正在继续录音 · 实时转写已停止"
            Stage16ARealtimeUiPhase.FINALIZING -> "正在保存录音 · 实时转写已停止"
            else -> "录音仍在继续 · 实时转写已停止"
        }
    return realtime.copy(
        phase = projectedPhase,
        stableText = "",
        partialText = "",
        audioActivityLevels = emptyList(),
        recordingOnlyMode = true,
        lifecycleMessage = message,
        errorMessage = null,
    )
}

/**
 * Historical Stage 16A composition seam retained as a thin adapter only. New shared reader code does
 * not depend on the recorder-card runtime type.
 */
val LocalStage16ARealtimeRuntime = staticCompositionLocalOf<Stage16ARealtimeRuntime> {
    error("Stage16ARealtimeRuntime was not provided")
}

internal val LocalRecorderCardRealtimeController = staticCompositionLocalOf<RealtimeTranscriptionUiController> {
    error("Recorder-card realtime UI controller was not provided")
}

/** Compatibility overload for the frozen recorder-card product/navigation path. */
@Composable
fun RealtimeTranscriptionScreen(
    padding: PaddingValues,
    runtime: Stage16ARealtimeRuntime,
    canStart: Boolean,
    onBack: () -> Unit,
) {
    val controller = remember(runtime) { RecorderCardRealtimeUiController(runtime) }
    RealtimeTranscriptionScreen(
        padding = padding,
        controller = controller,
        canStart = canStart,
        onBack = onBack,
    )
}

private fun Stage16ARealtimeActionOutcome.toUiOutcome() =
    RealtimeTranscriptionUiActionOutcome(
        success = success,
        code = code,
        message = message,
        degraded = degraded,
    )

private fun PhoneMicrophoneRealtimeActionOutcome.toUiOutcome() =
    RealtimeTranscriptionUiActionOutcome(
        success = success,
        code = code,
        message = message,
        degraded = degraded,
    )
