package io.github.ioannes78.voica.ble

object ReconnectPolicy {
    private val stagedDelaysMs = longArrayOf(
        1_000L,
        2_000L,
        4_000L,
        8_000L,
        15_000L,
    )

    fun delayForAttempt(attempt: Int): Long? =
        when {
            attempt <= 0 -> null
            attempt <= stagedDelaysMs.size -> stagedDelaysMs[attempt - 1]
            else -> STEADY_RECOVERY_DELAY_MS
        }

    fun shouldRetry(error: BleError): Boolean =
        error.recoverable &&
            (
                error.code == BleErrorCode.CONNECT_FAILED ||
                    error.code == BleErrorCode.CONNECT_TIMEOUT ||
                    error.code == BleErrorCode.REMOTE_DISCONNECTED
            )

    private const val STEADY_RECOVERY_DELAY_MS = 30_000L
}
