package io.github.ioannes78.voica.ble

object ReconnectPolicy {
    private val delaysMs = longArrayOf(1_000L, 2_000L, 4_000L)

    fun delayForAttempt(attempt: Int): Long? =
        if (attempt in 1..delaysMs.size) delaysMs[attempt - 1] else null

    fun shouldRetry(error: BleError): Boolean =
        error.code == BleErrorCode.CONNECT_FAILED ||
            error.code == BleErrorCode.CONNECT_TIMEOUT ||
            error.code == BleErrorCode.REMOTE_DISCONNECTED
}
