package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.SummaryRemoteCallDescriptor
import io.github.ioannes78.voica.ai.SummaryRemoteCallGate

class SummaryRemoteCallBoundaryChangedException(
    message: String,
) : IllegalStateException(message)

/**
 * Room-backed send boundary for one AI summary execution generation.
 *
 * [begin] returns only after Room says the exact request is REQUEST_IN_FLIGHT, so a crash
 * cannot make a possibly-sent provider request look replay-safe. [resolve] is exact by
 * request id and is only called after the response has reached a durable checkpoint or the
 * final summary transaction has committed.
 */
class RoomSummaryRemoteCallGate(
    private val repository: AiSummaryRepository,
    private val summaryId: String,
    private val generation: Long,
) : SummaryRemoteCallGate {
    init {
        require(summaryId.isNotBlank())
        require(generation >= 1L)
    }

    override suspend fun begin(
        call: SummaryRemoteCallDescriptor,
        replacesRequestId: String?,
    ) {
        val accepted =
            if (replacesRequestId == null) {
                val prepared =
                    repository.prepareRemoteCall(
                        summaryId = summaryId,
                        generation = generation,
                        requestId = call.requestId,
                        stepKind = call.stepKind,
                        stepKey = call.stepKey,
                    )
                prepared &&
                    repository.markRemoteRequestInFlight(
                        summaryId = summaryId,
                        generation = generation,
                        requestId = call.requestId,
                        stepKind = call.stepKind,
                        stepKey = call.stepKey,
                    )
            } else {
                repository.replaceRemoteRequestInFlight(
                    summaryId = summaryId,
                    generation = generation,
                    expectedRequestId = replacesRequestId,
                    newRequestId = call.requestId,
                    stepKind = call.stepKind,
                    stepKey = call.stepKey,
                )
            }
        if (!accepted) {
            throw SummaryRemoteCallBoundaryChangedException(
                "AI summary durable remote-call boundary changed",
            )
        }
    }

    override suspend fun resolve(requestId: String) {
        if (!repository.clearRemoteDispatch(summaryId, generation, requestId)) {
            throw SummaryRemoteCallBoundaryChangedException(
                "AI summary durable remote-call boundary could not be resolved",
            )
        }
    }
}
