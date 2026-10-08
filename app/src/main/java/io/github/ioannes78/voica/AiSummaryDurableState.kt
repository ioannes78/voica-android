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

    // Stage 13B.5 QA4 keeps failure/interruption attention on the dedicated durable Room surface.
    // Do not project those terminal rows back into the legacy process-local task notification path,
    // otherwise simply opening the detail page can hide an unresolved durable attention item.
    durableTasks.firstOrNull { it.status == AiSummaryStateValue.COMPLETED }
        ?.let { return it.toCompletedRunState() }

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

private fun AiSummaryEntity.toCompletedRunState(): AiSummaryRunState.Completed =
    AiSummaryRunState.Completed(
        summaryId = id,
        recordingId = recordingId,
        transcriptionId = transcriptionId.orEmpty(),
    )

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
