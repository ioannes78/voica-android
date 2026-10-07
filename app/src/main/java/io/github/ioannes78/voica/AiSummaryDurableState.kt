package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryStateValue

internal fun projectAiSummaryRunState(
    durableTasks: List<AiSummaryEntity>,
    transientState: AiSummaryRunState,
): AiSummaryRunState {
    val active = durableTasks.firstOrNull { it.status in AiSummaryStateValue.ACTIVE }
    if (active != null) {
        val transientRunning = transientState as? AiSummaryRunState.Running
        if (transientRunning?.summaryId == active.id) {
            return transientRunning
        }
        return active.toRunningState()
    }

    if (transientState is AiSummaryRunState.Running && transientState.summaryId == null) {
        return transientState
    }

    durableTasks.firstOrNull {
        it.status == AiSummaryStateValue.COMPLETED ||
            it.status == AiSummaryStateValue.FAILED ||
            it.status == AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT
    }?.let { return it.toTerminalRunState() }

    return when (transientState) {
        // A non-null summaryId means the transient state already has a durable Room owner. If that
        // row is no longer active/attention-worthy, Room wins and stale callbacks cannot revive it.
        is AiSummaryRunState.Running -> AiSummaryRunState.Idle
        is AiSummaryRunState.Cancelled -> transientState
        is AiSummaryRunState.Failed ->
            if (transientState.summaryId == null) transientState else AiSummaryRunState.Idle
        is AiSummaryRunState.Completed -> AiSummaryRunState.Idle
        AiSummaryRunState.Idle -> AiSummaryRunState.Idle
    }
}

private fun AiSummaryEntity.toRunningState(): AiSummaryRunState.Running =
    AiSummaryRunState.Running(
        summaryId = id,
        recordingId = recordingId,
        transcriptionId = transcriptionId.orEmpty(),
        phase = durableAiSummaryPhase(status),
    )

private fun AiSummaryEntity.toTerminalRunState(): AiSummaryRunState =
    when (status) {
        AiSummaryStateValue.COMPLETED ->
            AiSummaryRunState.Completed(
                summaryId = id,
                recordingId = recordingId,
                transcriptionId = transcriptionId.orEmpty(),
            )
        AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT ->
            AiSummaryRunState.Failed(
                summaryId = id,
                recordingId = recordingId,
                transcriptionId = transcriptionId.orEmpty(),
                errorCode = errorCode ?: "REMOTE_RESULT_UNKNOWN",
                message =
                    sanitizedErrorMessage
                        ?: "上一次请求状态无法确认，需要手动重试。",
            )
        else ->
            AiSummaryRunState.Failed(
                summaryId = id,
                recordingId = recordingId,
                transcriptionId = transcriptionId.orEmpty(),
                errorCode = errorCode ?: "AI_SUMMARY_FAILED",
                message = sanitizedErrorMessage ?: "AI 总结生成失败，请重试",
            )
    }

private fun durableAiSummaryPhase(status: String): AiSummaryEnginePhase =
    when (status) {
        AiSummaryStateValue.MAPPING -> AiSummaryEnginePhase.MAPPING
        AiSummaryStateValue.REDUCING -> AiSummaryEnginePhase.REDUCING
        AiSummaryStateValue.VALIDATING -> AiSummaryEnginePhase.VALIDATING
        AiSummaryStateValue.ANALYZING,
        AiSummaryStateValue.PLANNING,
        -> AiSummaryEnginePhase.ANALYZING
        else -> AiSummaryEnginePhase.PREPARING
    }
