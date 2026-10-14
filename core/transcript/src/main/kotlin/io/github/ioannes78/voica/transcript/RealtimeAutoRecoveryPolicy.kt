package io.github.ioannes78.voica.transcript

enum class RealtimeAutoRecoveryDecision {
    RESUME,
    NO_ACTIVE_REALTIME_INTENT,
    USER_DISABLED_REALTIME,
    DEVICE_MISMATCH,
    RECORDING_NOT_ACTIVE,
    RECORDING_IDENTITY_UNCONFIRMED,
    RECORDING_IDENTITY_MISMATCH,
    TRANSPORT_GENERATION_NOT_ADVANCED,
}

data class RealtimeAutoRecoveryContext(
    val realtimeIntentActiveBeforeDisconnect: Boolean,
    val userDisabledRealtimeSinceDisconnect: Boolean,
    val previousDeviceIdentity: String?,
    val currentDeviceIdentity: String?,
    val previousRecordingIdentity: String?,
    val currentRecordingIdentity: String?,
    val currentDeviceRecordingActive: Boolean,
    val previousTransportGeneration: Long?,
    val currentTransportGeneration: Long?,
)

/**
 * Conservative Stage 16A reconnect gate.
 *
 * Realtime recognition may automatically resume only after the upper app layer has independently
 * confirmed both the same physical recorder and the same still-active device recording. Unknown
 * recording identity is intentionally not treated as a match. A reconnect always requires a new
 * transport generation so no fake continuity can be created across a transport gap.
 */
object RealtimeAutoRecoveryPolicy {
    fun evaluate(context: RealtimeAutoRecoveryContext): RealtimeAutoRecoveryDecision {
        if (!context.realtimeIntentActiveBeforeDisconnect) {
            return RealtimeAutoRecoveryDecision.NO_ACTIVE_REALTIME_INTENT
        }
        if (context.userDisabledRealtimeSinceDisconnect) {
            return RealtimeAutoRecoveryDecision.USER_DISABLED_REALTIME
        }
        if (
            context.previousDeviceIdentity == null ||
            context.currentDeviceIdentity == null ||
            context.previousDeviceIdentity != context.currentDeviceIdentity
        ) {
            return RealtimeAutoRecoveryDecision.DEVICE_MISMATCH
        }
        if (!context.currentDeviceRecordingActive) {
            return RealtimeAutoRecoveryDecision.RECORDING_NOT_ACTIVE
        }
        if (
            context.previousRecordingIdentity == null ||
            context.currentRecordingIdentity == null
        ) {
            return RealtimeAutoRecoveryDecision.RECORDING_IDENTITY_UNCONFIRMED
        }
        if (context.previousRecordingIdentity != context.currentRecordingIdentity) {
            return RealtimeAutoRecoveryDecision.RECORDING_IDENTITY_MISMATCH
        }
        if (
            context.previousTransportGeneration == null ||
            context.currentTransportGeneration == null ||
            context.currentTransportGeneration == context.previousTransportGeneration
        ) {
            return RealtimeAutoRecoveryDecision.TRANSPORT_GENERATION_NOT_ADVANCED
        }
        return RealtimeAutoRecoveryDecision.RESUME
    }
}
