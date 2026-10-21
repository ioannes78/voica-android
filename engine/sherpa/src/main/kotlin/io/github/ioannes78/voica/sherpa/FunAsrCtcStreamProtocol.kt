package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineStream
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import kotlin.math.roundToLong

/**
 * Revision-2 protocol layered on the existing sherpa OfflineStream option ABI.
 *
 * No new Java/JNI method is required. The patched FunASR recognizer retains the
 * pre-adaptor encoder state after the first normal decode only when ctcCompact
 * is configured. The same OfflineStream then receives one of these commands:
 *
 * align   target ids plus the validated CTC blank/vocabulary contract are
 *         supplied as stream options; a second decode runs compact CTC + forced
 *         alignment without rerunning ASR. Its result uses tokens as decimal
 *         target ids, timestamps as start seconds, and durations as acoustic
 *         duration seconds.
 * discard erase retained native state without scoring.
 */
internal object FunAsrCtcStreamProtocol {
    const val COMMAND_KEY = "voica.funasr.ctc.command"
    const val TARGET_IDS_KEY = "voica.funasr.ctc.target_ids"
    const val BLANK_ID_KEY = "voica.funasr.ctc.blank_id"
    const val VOCAB_SIZE_KEY = "voica.funasr.ctc.vocab_size"
    const val COMMAND_ALIGN = "align"
    const val COMMAND_DISCARD = "discard"

    fun requestAlignment(
        stream: OfflineStream,
        targetIds: IntArray,
        blankId: Int,
        vocabSize: Int,
    ) {
        validateAlignmentArguments(targetIds, blankId, vocabSize)
        stream.setOption(TARGET_IDS_KEY, targetIds.joinToString(separator = ","))
        stream.setOption(BLANK_ID_KEY, blankId.toString())
        stream.setOption(VOCAB_SIZE_KEY, vocabSize.toString())
        stream.setOption(COMMAND_KEY, COMMAND_ALIGN)
    }

    internal fun validateAlignmentArguments(
        targetIds: IntArray,
        blankId: Int,
        vocabSize: Int,
    ) {
        require(targetIds.isNotEmpty()) { "FunASR CTC target ids must not be empty" }
        require(vocabSize > 1) { "FunASR CTC vocabulary size must be > 1" }
        require(blankId in 0 until vocabSize) {
            "FunASR CTC blank id must be inside the declared vocabulary"
        }
        require(targetIds.all { it in 0 until vocabSize }) {
            "FunASR CTC target ids must be inside the declared vocabulary"
        }
        require(targetIds.none { it == blankId }) {
            "FunASR CTC final-text targets must not contain the blank id"
        }
    }

    fun requestDiscard(stream: OfflineStream) {
        stream.setOption(COMMAND_KEY, COMMAND_DISCARD)
        // No remove-option API exists. Clear stale alignment arguments so a
        // buggy native implementation cannot accidentally reuse an old request.
        stream.setOption(TARGET_IDS_KEY, "")
        stream.setOption(BLANK_ID_KEY, "")
        stream.setOption(VOCAB_SIZE_KEY, "")
    }

    fun parseAlignmentResult(
        result: OfflineRecognizerResult,
        expectedTargetIds: IntArray,
        acceptedSampleCount: Long,
    ): List<CtcAlignedTokenSample>? {
        require(acceptedSampleCount >= 0L)
        if (expectedTargetIds.isEmpty()) return emptyList()
        if (
            result.tokens.size != expectedTargetIds.size ||
            result.timestamps.size != expectedTargetIds.size ||
            result.durations.size != expectedTargetIds.size
        ) {
            return null
        }

        val sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()
        val aligned = ArrayList<CtcAlignedTokenSample>(expectedTargetIds.size)
        var previousStart = -1L

        for (index in expectedTargetIds.indices) {
            val tokenId = result.tokens[index].toIntOrNull() ?: return null
            if (tokenId != expectedTargetIds[index]) return null

            val startSeconds = result.timestamps[index]
            val durationSeconds = result.durations[index]
            if (
                !startSeconds.isFinite() ||
                !durationSeconds.isFinite() ||
                startSeconds < 0F ||
                durationSeconds <= 0F
            ) {
                return null
            }

            val start = (startSeconds.toDouble() * sampleRate).roundToLong()
            val end =
                ((startSeconds.toDouble() + durationSeconds.toDouble()) * sampleRate)
                    .roundToLong()
            if (
                start < 0L ||
                end <= start ||
                end > acceptedSampleCount ||
                start < previousStart
            ) {
                return null
            }

            aligned +=
                CtcAlignedTokenSample(
                    targetIndex = index,
                    tokenId = tokenId,
                    startSampleOffset = start,
                    endSampleOffsetExclusive = end,
                    // The stream-result ABI does not carry alignment confidence.
                    // Correctness is represented by a finite Viterbi path; do
                    // not invent a probability here.
                    peakLogProbability = Float.NaN,
                )
            previousStart = start
        }

        return aligned
    }
}
