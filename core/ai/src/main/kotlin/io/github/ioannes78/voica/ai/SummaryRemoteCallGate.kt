package io.github.ioannes78.voica.ai

data class SummaryRemoteCallDescriptor(
    val requestId: String,
    val stepKind: String,
    val stepKey: String,
) {
    init {
        require(requestId.isNotBlank())
        require(stepKind.isNotBlank())
        require(stepKey.isNotBlank())
    }
}

/**
 * Durable boundary around every real provider request.
 *
 * Implementations must persist REQUEST_IN_FLIGHT before [begin] returns. A non-null
 * [replacesRequestId] means a follow-up request is replacing an earlier request whose
 * response existed only in process memory; implementations must keep the durable state
 * conservative (still in-flight) so process death cannot make the previous request look
 * safe to replay.
 */
interface SummaryRemoteCallGate {
    suspend fun begin(
        call: SummaryRemoteCallDescriptor,
        replacesRequestId: String? = null,
    )

    /** Marks a provider call durably resolved after its result/checkpoint has been persisted. */
    suspend fun resolve(requestId: String)
}
