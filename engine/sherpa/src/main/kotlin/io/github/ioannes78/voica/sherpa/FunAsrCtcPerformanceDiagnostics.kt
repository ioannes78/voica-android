package io.github.ioannes78.voica.sherpa

/**
 * QA-only observability for revision-2 FunASR same-stream CTC alignment.
 *
 * These values are wall-clock measurements around public sherpa decode calls. They deliberately do
 * not claim to measure internal encoder invocation counts because sherpa's Android API does not
 * expose that counter. [sameStreamAlignment] records the product contract that text decode and CTC
 * alignment are issued against the same OfflineStream instance.
 */
data class FunAsrCtcPerformanceDiagnostic(
    val audioSampleCount: Int,
    val audioDurationMs: Long,
    val textDecodeMs: Long,
    val alignmentMs: Long?,
    val totalMs: Long,
    val targetTokenCount: Int,
    val alignedTokenCount: Int?,
    val alignmentSucceeded: Boolean,
    val sameStreamAlignment: Boolean,
    val failureStage: String?,
) {
    init {
        require(audioSampleCount > 0)
        require(audioDurationMs >= 0L)
        require(textDecodeMs >= 0L)
        require(alignmentMs == null || alignmentMs >= 0L)
        require(totalMs >= textDecodeMs)
        require(targetTokenCount >= 0)
        require(alignedTokenCount == null || alignedTokenCount >= 0)
        require(alignmentSucceeded || alignedTokenCount == null)
    }
}

object FunAsrCtcPerformanceDiagnostics {
    private const val MAX_RECORDS = 256
    private val lock = Any()
    private val records = ArrayDeque<FunAsrCtcPerformanceDiagnostic>()

    fun record(diagnostic: FunAsrCtcPerformanceDiagnostic) {
        synchronized(lock) {
            while (records.size >= MAX_RECORDS) records.removeFirst()
            records.addLast(diagnostic)
        }
    }

    fun snapshot(): List<FunAsrCtcPerformanceDiagnostic> =
        synchronized(lock) { records.toList() }

    fun clear() {
        synchronized(lock) { records.clear() }
    }

    fun formatForQa(): String {
        val current = snapshot()
        if (current.isEmpty()) return "No FunASR CTC performance diagnostics yet."

        val audioMs = current.sumOf { it.audioDurationMs }
        val textMs = current.sumOf { it.textDecodeMs }
        val alignmentMs = current.sumOf { it.alignmentMs ?: 0L }
        val totalMs = current.sumOf { it.totalMs }
        val textDecodeRtf = if (audioMs > 0L) textMs.toDouble() / audioMs.toDouble() else 0.0
        val measuredRtf = if (audioMs > 0L) totalMs.toDouble() / audioMs.toDouble() else 0.0
        val outputTokenCount = current.sumOf { it.targetTokenCount }

        return buildString {
            append("summary chunks=").append(current.size)
            append(" audioMs=").append(audioMs)
            append(" textDecodeMs=").append(textMs)
            append(" textDecodeRtf=").append("%.4f".format(java.util.Locale.US, textDecodeRtf))
            append(" ctcAlignmentMs=").append(alignmentMs)
            append(" totalMs=").append(totalMs)
            append(" measuredRtf=").append("%.4f".format(java.util.Locale.US, measuredRtf))
            append(" outputTokenCount=").append(outputTokenCount)
            append(" sameStream=true")
            append(" encoderInvocationCount=not_exposed")
            append('\n')
            current.forEachIndexed { index, item ->
                val chunkDecodeRtf =
                    if (item.audioDurationMs > 0L) {
                        item.textDecodeMs.toDouble() / item.audioDurationMs.toDouble()
                    } else {
                        0.0
                    }
                append('#').append(index + 1)
                append(" audioSamples=").append(item.audioSampleCount)
                append(" audioMs=").append(item.audioDurationMs)
                append(" textDecodeMs=").append(item.textDecodeMs)
                append(" textDecodeRtf=").append("%.4f".format(java.util.Locale.US, chunkDecodeRtf))
                append(" ctcAlignmentMs=").append(item.alignmentMs?.toString() ?: "n/a")
                append(" totalMs=").append(item.totalMs)
                append(" outputTokens=").append(item.targetTokenCount)
                append(" alignedTokens=").append(item.alignedTokenCount?.toString() ?: "n/a")
                append(" alignmentSucceeded=").append(item.alignmentSucceeded)
                append(" sameStream=").append(item.sameStreamAlignment)
                item.failureStage?.let { append(" failureStage=").append(it) }
                append('\n')
            }
        }.trimEnd()
    }
}
