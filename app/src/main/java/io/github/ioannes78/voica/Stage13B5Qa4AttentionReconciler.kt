package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Resolves superseded FAILED AI-summary attention only after a newer durable summary row exists.
 * Ambiguous remote results are deliberately excluded: they remain frozen/manual until explicit
 * retry, ignore, or adoption of a newer completed result.
 */
class Stage13B5Qa4AttentionReconciler(
    scope: CoroutineScope,
    private val aiSummaryRepository: AiSummaryRepository,
) {
    init {
        scope.launch {
            aiSummaryRepository.observeDurableTasks().collectLatest { tasks ->
                tasks.asSequence()
                    .filter { it.status == AiSummaryStateValue.FAILED }
                    .filter { it.terminalAcknowledgedAtMs == null }
                    .filter { failed ->
                        tasks.any { newer ->
                            newer.recordingId == failed.recordingId &&
                                newer.id != failed.id &&
                                newer.createdAtMs > failed.createdAtMs
                        }
                    }
                    .toList()
                    .forEach { failed ->
                        aiSummaryRepository.acknowledgeTerminal(failed.id)
                    }
            }
        }
    }
}
