package io.github.ioannes78.voica.sherpa

data class FunAsrQaSessionDiagnostic(
    val effectiveThreads: Int,
    val hotwordEnabled: Boolean,
) {
    init {
        require(effectiveThreads > 0)
    }
}

object FunAsrQaSessionDiagnostics {
    private val lock = Any()
    private var session: FunAsrQaSessionDiagnostic? = null

    fun begin(
        effectiveThreads: Int,
        hotwordEnabled: Boolean,
    ) {
        synchronized(lock) {
            session =
                FunAsrQaSessionDiagnostic(
                    effectiveThreads = effectiveThreads,
                    hotwordEnabled = hotwordEnabled,
                )
        }
    }

    fun clear() {
        synchronized(lock) {
            session = null
        }
    }

    fun snapshot(): FunAsrQaSessionDiagnostic? =
        synchronized(lock) { session }

    fun formatForQa(): String {
        val currentSession = snapshot()
        val runtimeRecords = FunAsrRuntimeDiagnostics.snapshot()
        val adaptiveRetryCount = runtimeRecords.count { it.retryDepth > 0 }
        val retryOccurred = adaptiveRetryCount > 0
        return buildString {
            append("runtime effectiveThreads=")
                .append(currentSession?.effectiveThreads?.toString() ?: "n/a")
            append(" hotword=")
                .append(
                    when (currentSession?.hotwordEnabled) {
                        true -> "ON"
                        false -> "OFF"
                        null -> "n/a"
                    },
                )
            append(" adaptiveRetryCount=").append(adaptiveRetryCount)
            append(" retryOccurred=").append(retryOccurred)
        }
    }
}
