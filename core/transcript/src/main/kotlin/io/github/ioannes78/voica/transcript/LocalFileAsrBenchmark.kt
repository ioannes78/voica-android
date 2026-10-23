package io.github.ioannes78.voica.transcript

import java.text.Normalizer

/**
 * Deterministic benchmark identity for a model/runtime component.
 *
 * This is intentionally storage-agnostic. Stage 16B candidate benchmarks can use it without
 * changing Room or production model selection.
 */
data class BenchmarkComponentIdentity(
    val id: String,
    val version: String? = null,
    val revision: String? = null,
    val configDigest: String? = null,
) {
    init {
        require(id.isNotBlank())
    }
}

/** Edit-distance breakdown for CER/WER style metrics. */
data class EditDistanceMetrics(
    val substitutions: Int,
    val insertions: Int,
    val deletions: Int,
    val referenceUnits: Int,
    val hypothesisUnits: Int,
) {
    init {
        require(substitutions >= 0)
        require(insertions >= 0)
        require(deletions >= 0)
        require(referenceUnits >= 0)
        require(hypothesisUnits >= 0)
    }

    val errors: Int
        get() = substitutions + insertions + deletions

    /** Undefined when the normalized reference is empty. */
    val errorRate: Double?
        get() = if (referenceUnits == 0) null else errors.toDouble() / referenceUnits.toDouble()
}

data class AsrAccuracyMetrics(
    val character: EditDistanceMetrics? = null,
    val word: EditDistanceMetrics? = null,
)

/**
 * Model/VAD-neutral observation contract used by Stage 16B benchmark tooling.
 *
 * Android-specific samplers can populate PSS/CPU/thermal/battery fields later without making the
 * core transcript module depend on Android APIs.
 */
data class LocalFileAsrBenchmarkObservation(
    val recordingId: String,
    val canonicalSha256: String? = null,
    val sampleRateHz: Int,
    val totalSampleCount: Long,
    val speechSampleCount: Long? = null,
    val vad: BenchmarkComponentIdentity? = null,
    val asr: BenchmarkComponentIdentity,
    val wallTimeMillis: Long,
    val vadTimeMillis: Long? = null,
    val asrTimeMillis: Long? = null,
    val timingAlignmentTimeMillis: Long? = null,
    val punctuationTimeMillis: Long? = null,
    val segmentCount: Int? = null,
    val tokenCount: Int? = null,
    val peakPssKb: Long? = null,
    val cpuTimeMillis: Long? = null,
    val thermalStatus: String? = null,
    val batteryDeltaPercent: Double? = null,
    val accuracy: AsrAccuracyMetrics? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(recordingId.isNotBlank())
        require(sampleRateHz > 0)
        require(totalSampleCount >= 0L)
        require(speechSampleCount == null || speechSampleCount in 0L..totalSampleCount)
        require(wallTimeMillis >= 0L)
        require(vadTimeMillis == null || vadTimeMillis >= 0L)
        require(asrTimeMillis == null || asrTimeMillis >= 0L)
        require(timingAlignmentTimeMillis == null || timingAlignmentTimeMillis >= 0L)
        require(punctuationTimeMillis == null || punctuationTimeMillis >= 0L)
        require(segmentCount == null || segmentCount >= 0)
        require(tokenCount == null || tokenCount >= 0)
        require(peakPssKb == null || peakPssKb >= 0L)
        require(cpuTimeMillis == null || cpuTimeMillis >= 0L)
    }

    val audioDurationSeconds: Double
        get() = totalSampleCount.toDouble() / sampleRateHz.toDouble()

    /** Total wall-time real-time factor. Undefined for zero-duration input. */
    val realTimeFactor: Double?
        get() =
            if (totalSampleCount == 0L) {
                null
            } else {
                (wallTimeMillis / 1000.0) / audioDurationSeconds
            }
}

object AsrBenchmarkScoring {
    /**
     * CER content normalization used by the Stage 16B benchmark harness.
     *
     * NFKC-normalizes text, lowercases case-bearing scripts, and removes whitespace/punctuation.
     * Punctuation quality is benchmarked separately so CER does not double-count punctuation.
     */
    fun normalizeForCharacterErrorRate(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val builder = StringBuilder(normalized.length)
        normalized.codePoints().forEach { codePoint ->
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(codePoint)
            }
        }
        return builder.toString()
    }

    fun characterErrorRate(
        reference: String,
        hypothesis: String,
    ): EditDistanceMetrics {
        val referenceUnits = normalizeForCharacterErrorRate(reference).codePoints().toArray().toList()
        val hypothesisUnits = normalizeForCharacterErrorRate(hypothesis).codePoints().toArray().toList()
        return editDistance(referenceUnits, hypothesisUnits)
    }

    /**
     * WER expects caller-provided word tokens.
     *
     * Voica does not silently invent Chinese word segmentation inside the benchmark layer. A future
     * Stage 16B benchmark dataset may provide reference/hypothesis word tokens when WER is meaningful.
     */
    fun wordErrorRate(
        referenceWords: List<String>,
        hypothesisWords: List<String>,
    ): EditDistanceMetrics =
        editDistance(
            reference = referenceWords.map(::normalizeWord).filter(String::isNotEmpty),
            hypothesis = hypothesisWords.map(::normalizeWord).filter(String::isNotEmpty),
        )

    private fun normalizeWord(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFKC).trim().lowercase()

    internal fun <T> editDistance(
        reference: List<T>,
        hypothesis: List<T>,
    ): EditDistanceMetrics {
        val rows = reference.size + 1
        val columns = hypothesis.size + 1
        val distance = Array(rows) { IntArray(columns) }

        for (row in 0 until rows) {
            distance[row][0] = row
        }
        for (column in 0 until columns) {
            distance[0][column] = column
        }

        for (row in 1 until rows) {
            for (column in 1 until columns) {
                val substitutionCost = if (reference[row - 1] == hypothesis[column - 1]) 0 else 1
                distance[row][column] =
                    minOf(
                        distance[row - 1][column] + 1,
                        distance[row][column - 1] + 1,
                        distance[row - 1][column - 1] + substitutionCost,
                    )
            }
        }

        var row = reference.size
        var column = hypothesis.size
        var substitutions = 0
        var insertions = 0
        var deletions = 0

        while (row > 0 || column > 0) {
            if (
                row > 0 &&
                column > 0 &&
                reference[row - 1] == hypothesis[column - 1] &&
                distance[row][column] == distance[row - 1][column - 1]
            ) {
                row -= 1
                column -= 1
                continue
            }

            // Deterministic tie-breaking: substitution -> insertion -> deletion.
            if (
                row > 0 &&
                column > 0 &&
                distance[row][column] == distance[row - 1][column - 1] + 1
            ) {
                substitutions += 1
                row -= 1
                column -= 1
                continue
            }

            if (
                column > 0 &&
                distance[row][column] == distance[row][column - 1] + 1
            ) {
                insertions += 1
                column -= 1
                continue
            }

            check(row > 0 && distance[row][column] == distance[row - 1][column] + 1) {
                "edit-distance backtrace is inconsistent"
            }
            deletions += 1
            row -= 1
        }

        return EditDistanceMetrics(
            substitutions = substitutions,
            insertions = insertions,
            deletions = deletions,
            referenceUnits = reference.size,
            hypothesisUnits = hypothesis.size,
        )
    }
}
