package io.github.ioannes78.voica

internal enum class PhoneMicrophoneCaptureRecoveryTrigger {
    READ_ERROR,
    ROUTE_LOST,
}

/**
 * P4 bounded recovery policy. Automatic recreation is intentionally narrow:
 *
 * - only AUTO input may recover without user action;
 * - platform read failure may recover once;
 * - an observed route loss may recover once after the old capture has fully stopped;
 * - manual-route mismatch, client silencing and formal consumer failures are never hidden here.
 */
internal object PhoneMicrophoneCaptureAutoRecoveryPolicy {
    fun triggerFor(snapshot: PhoneMicrophoneCaptureSnapshot): PhoneMicrophoneCaptureRecoveryTrigger? {
        if (snapshot.requestedInput !is PhoneMicrophoneInputRequest.Auto) return null
        if (snapshot.phase != PhoneMicrophoneCapturePhase.FAILED) return null
        return when (snapshot.terminalReason) {
            PhoneMicrophoneCaptureTerminalReason.READ_ERROR ->
                PhoneMicrophoneCaptureRecoveryTrigger.READ_ERROR
            else -> null
        }
    }

    fun allowsRouteLossRecovery(request: PhoneMicrophoneInputRequest): Boolean =
        request is PhoneMicrophoneInputRequest.Auto
}
