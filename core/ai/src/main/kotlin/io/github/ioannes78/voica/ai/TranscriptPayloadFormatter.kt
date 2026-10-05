package io.github.ioannes78.voica.ai

object TranscriptPayloadFormatter {
    private const val CANONICAL_SAMPLE_RATE_HZ = 16_000L
    private const val MAX_SPEAKER_LABEL_CHARS = 80

    fun format(input: StructuredTranscriptInput): String =
        formatUnits(input.units)

    fun formatUnits(units: List<StructuredTranscriptUnit>): String =
        units.joinToString(separator = "\n") { formatUnit(it) }

    fun formatUnit(unit: StructuredTranscriptUnit): String {
        val evidence = unit.evidence
        if (evidence == null) {
            return "[NO_AUDIO_EVIDENCE] " + unit.text
        }
        val time = formatElapsed(evidence.startSampleIndex)
        val speaker =
            evidence.speakerDisplayName
                ?.let(::sanitizeInline)
                ?.take(MAX_SPEAKER_LABEL_CHARS)
                ?.takeIf { it.isNotBlank() }
                ?: when {
                    evidence.ambiguous -> "说话人不确定"
                    else -> null
                }
        return buildString {
            append("[")
            append(evidence.ref)
            append("][")
            append(time)
            append("]")
            if (speaker != null) {
                append("[")
                append(speaker)
                append("]")
            }
            if (evidence.overlap) {
                append("[重叠语音]")
            }
            append(" ")
            append(unit.text)
        }
    }

    private fun sanitizeInline(value: String): String =
        value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun formatElapsed(sampleIndex: Long): String {
        require(sampleIndex >= 0L)
        val totalMillis = sampleIndex * 1_000L / CANONICAL_SAMPLE_RATE_HZ
        val hours = totalMillis / 3_600_000L
        val minutes = (totalMillis / 60_000L) % 60L
        val seconds = (totalMillis / 1_000L) % 60L
        val millis = totalMillis % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }
}