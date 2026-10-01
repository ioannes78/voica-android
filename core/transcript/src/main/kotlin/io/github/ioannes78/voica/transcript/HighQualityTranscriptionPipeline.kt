package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmSourceResolver
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

data class HighQualityTranscriptionResult(
    val totalSampleCount: Long,
    val speechSegments: List<SpeechSegment>,
    val transcriptSegments: List<TranscriptSegment>,
    val secondPassApplied: Boolean,
    val secondPassFallbackError: String? = null,
    val punctuationFallbackError: String? = null,
)

class HighQualityTranscriptionPipeline(
    private val pcmSourceResolver: PcmSourceResolver,
    vadEngineFactory: VadEngineFactory,
    asrEngineFactory: StreamingAsrEngineFactory,
    private val secondPassAsrEngineFactory: SecondPassAsrEngineFactory,
    private val punctuationEngineFactory: PunctuationEngineFactory,
    readChunkSamples: Int = DEFAULT_READ_CHUNK_SAMPLES,
    private val maxSecondPassSegmentSamples: Long = DEFAULT_MAX_SECOND_PASS_SEGMENT_SAMPLES,
) {
    private val firstPassRunner =
        FirstPassTranscriptionRunner(
            pcmSourceResolver = pcmSourceResolver,
            vadEngineFactory = vadEngineFactory,
            asrEngineFactory = asrEngineFactory,
            readChunkSamples = readChunkSamples,
        )
    private val readChunkSamples = readChunkSamples

    init {
        require(readChunkSamples > 0)
        require(maxSecondPassSegmentSamples > 0L)
    }

    suspend fun transcribe(
        recordingId: String,
        progressListener: ProgressListener? = null,
    ): HighQualityTranscriptionResult {
        val firstPass =
            firstPassRunner.run(
                recordingId = recordingId,
                progressListener = progressListener,
            )

        if (firstPass.segments.isEmpty()) {
            return HighQualityTranscriptionResult(
                totalSampleCount = firstPass.totalSampleCount,
                speechSegments = firstPass.speechSegments,
                transcriptSegments = emptyList(),
                secondPassApplied = false,
            )
        }

        val secondPassAttempt =
            try {
                SecondPassAttempt.Success(
                    runSecondPass(
                        recordingId = recordingId,
                        firstPass = firstPass,
                        progressListener = progressListener,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SecondPassAttempt.Fallback(
                    error.message ?: error::class.java.simpleName,
                )
            }

        val bases =
            when (secondPassAttempt) {
                is SecondPassAttempt.Success ->
                    firstPass.segments.zip(secondPassAttempt.segments).map { (first, second) ->
                        require(first.segmentIndex == second.segmentIndex)
                        FinalizationBase(
                            firstPass = first,
                            text = second.hypothesis.text,
                            secondPassRawText = second.hypothesis.text,
                            punctuationCapability = second.hypothesis.punctuationCapability,
                            detectedLanguage =
                                second.hypothesis.detectedLanguage
                                    ?: first.hypothesis.detectedLanguage,
                            confidence =
                                second.hypothesis.confidence
                                    ?: first.hypothesis.confidence,
                            tokens =
                                second.absoluteTokens.ifEmpty {
                                    first.absoluteTokens
                                },
                        )
                    }

                is SecondPassAttempt.Fallback ->
                    firstPass.segments.map { first ->
                        FinalizationBase(
                            firstPass = first,
                            text = first.hypothesis.text,
                            secondPassRawText = null,
                            punctuationCapability = first.hypothesis.punctuationCapability,
                            detectedLanguage = first.hypothesis.detectedLanguage,
                            confidence = first.hypothesis.confidence,
                            tokens = first.absoluteTokens,
                        )
                    }
            }

        val finalized = finalizeText(bases, progressListener)

        return HighQualityTranscriptionResult(
            totalSampleCount = firstPass.totalSampleCount,
            speechSegments = firstPass.speechSegments,
            transcriptSegments = finalized.segments,
            secondPassApplied = secondPassAttempt is SecondPassAttempt.Success,
            secondPassFallbackError =
                (secondPassAttempt as? SecondPassAttempt.Fallback)?.error,
            punctuationFallbackError = finalized.fallbackError,
        )
    }

    private suspend fun runSecondPass(
        recordingId: String,
        firstPass: FirstPassComputation,
        progressListener: ProgressListener?,
    ): List<SecondPassSegment> {
        firstPass.speechSegments.forEach { segment ->
            require(segment.sampleCount <= maxSecondPassSegmentSamples) {
                "speech segment exceeds second-pass safety bound"
            }
            require(segment.sampleCount <= Int.MAX_VALUE.toLong())
        }

        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before second-pass ASR")
        require(source.totalSampleCount == firstPass.totalSampleCount) {
            "canonical PCM sample count changed before second pass"
        }

        val engine = secondPassAsrEngineFactory.open()
        require(engine.capabilities.supportsSecondPass) {
            "high-quality transcription requires a second-pass ASR engine"
        }

        try {
            val output = ArrayList<SecondPassSegment>(firstPass.speechSegments.size)
            val readBuffer = ShortArray(readChunkSamples)
            var sourceCursor = 0L
            var segmentIndex = 0
            var segmentPcm: ShortArray? = null
            var writtenForSegment = 0
            var processedSpeechSamples = 0L

            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.SECOND_PASS,
                    processedUnits = 0L,
                    totalUnits = firstPass.speechSampleCount,
                ),
            )

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(readBuffer) ?: break
                require(read.startSampleIndex == sourceCursor) {
                    "PCM source is not sequential during second pass"
                }
                require(read.sampleCount in 1..readBuffer.size)
                val readEnd = Math.addExact(read.startSampleIndex, read.sampleCount.toLong())
                require(readEnd <= firstPass.totalSampleCount)

                while (
                    segmentIndex < firstPass.speechSegments.size &&
                    firstPass.speechSegments[segmentIndex].startSampleIndex < readEnd
                ) {
                    currentCoroutineContext().ensureActive()
                    val segment = firstPass.speechSegments[segmentIndex]
                    require(segment.endSampleIndexExclusive > read.startSampleIndex) {
                        "speech segment was skipped during second pass"
                    }

                    if (segmentPcm == null) {
                        segmentPcm = ShortArray(Math.toIntExact(segment.sampleCount))
                        writtenForSegment = 0
                    }

                    val overlapStart = max(segment.startSampleIndex, read.startSampleIndex)
                    val overlapEnd = min(segment.endSampleIndexExclusive, readEnd)
                    if (overlapEnd > overlapStart) {
                        val sourceOffset =
                            Math.toIntExact(overlapStart - read.startSampleIndex)
                        val count = Math.toIntExact(overlapEnd - overlapStart)
                        val destinationOffset =
                            Math.toIntExact(overlapStart - segment.startSampleIndex)
                        readBuffer.copyInto(
                            destination = segmentPcm,
                            destinationOffset = destinationOffset,
                            startIndex = sourceOffset,
                            endIndex = sourceOffset + count,
                        )
                        writtenForSegment += count
                    }

                    if (segment.endSampleIndexExclusive <= readEnd) {
                        val pcm =
                            segmentPcm
                                ?: error("second-pass segment completed without PCM")
                        require(writtenForSegment == pcm.size) {
                            "second-pass segment PCM is incomplete"
                        }

                        val result =
                            engine.transcribe(
                                samples = pcm,
                                sampleRateHz = source.sampleRateHz,
                            )
                        require(result.isFinal) {
                            "second-pass ASR did not return a final hypothesis"
                        }

                        output +=
                            SecondPassSegment(
                                segmentIndex = segmentIndex,
                                hypothesis = result,
                                absoluteTokens =
                                    safeMapRelativeTokens(
                                        tokens = result.tokens,
                                        segment = segment,
                                        source = TokenSource.SECOND_PASS,
                                    ),
                            )

                        processedSpeechSamples =
                            Math.addExact(processedSpeechSamples, segment.sampleCount)
                        progressListener?.onProgress(
                            TranscriptionProgress(
                                phase = TranscriptionPhase.SECOND_PASS,
                                processedUnits = processedSpeechSamples,
                                totalUnits = firstPass.speechSampleCount,
                            ),
                        )

                        segmentPcm = null
                        writtenForSegment = 0
                        segmentIndex += 1
                    } else {
                        break
                    }
                }

                sourceCursor = readEnd
            }

            require(sourceCursor == firstPass.totalSampleCount)
            require(segmentIndex == firstPass.speechSegments.size) {
                "PCM source ended before all second-pass segments"
            }
            require(segmentPcm == null)
            require(processedSpeechSamples == firstPass.speechSampleCount)
            return output
        } finally {
            engine.close()
            source.close()
        }
    }

    private suspend fun finalizeText(
        bases: List<FinalizationBase>,
        progressListener: ProgressListener?,
    ): FinalizationResult {
        val requiresPunctuation =
            bases.any { base ->
                needsPunctuationFallback(
                    text = base.text,
                    capability = base.punctuationCapability,
                )
            }

        var engine: PunctuationEngine? = null
        var fallbackError: String? = null
        if (requiresPunctuation) {
            try {
                engine = punctuationEngineFactory.open()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fallbackError = error.message ?: error::class.java.simpleName
            }
        }

        try {
            val total = bases.size.toLong()
            val output = ArrayList<TranscriptSegment>(bases.size)
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.PUNCTUATION,
                    processedUnits = 0L,
                    totalUnits = total,
                ),
            )

            bases.forEachIndexed { index, base ->
                currentCoroutineContext().ensureActive()
                val needsPunctuation =
                    needsPunctuationFallback(
                        text = base.text,
                        capability = base.punctuationCapability,
                    )

                val finalText =
                    if (!needsPunctuation || base.text.isBlank() || engine == null) {
                        base.text
                    } else {
                        try {
                            engine.addPunctuation(base.text)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (fallbackError == null) {
                                fallbackError =
                                    error.message ?: error::class.java.simpleName
                            }
                            base.text
                        }
                    }

                val first = base.firstPass
                output +=
                    TranscriptSegment(
                        segmentIndex = first.segmentIndex,
                        startSampleIndex = first.speechSegment.startSampleIndex,
                        endSampleIndexExclusive = first.speechSegment.endSampleIndexExclusive,
                        firstPassRawText = first.hypothesis.text,
                        secondPassRawText = base.secondPassRawText,
                        finalText = finalText,
                        detectedLanguage = base.detectedLanguage,
                        confidence = base.confidence,
                        tokens = base.tokens,
                    )

                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.PUNCTUATION,
                        processedUnits = (index + 1).toLong(),
                        totalUnits = total,
                    ),
                )
            }
            return FinalizationResult(output, fallbackError)
        } finally {
            engine?.close()
        }
    }

    private sealed interface SecondPassAttempt {
        data class Success(
            val segments: List<SecondPassSegment>,
        ) : SecondPassAttempt

        data class Fallback(
            val error: String,
        ) : SecondPassAttempt
    }

    private data class SecondPassSegment(
        val segmentIndex: Int,
        val hypothesis: AsrHypothesis,
        val absoluteTokens: List<TranscriptToken>,
    )

    private data class FinalizationBase(
        val firstPass: FirstPassSegment,
        val text: String,
        val secondPassRawText: String?,
        val punctuationCapability: PunctuationCapability,
        val detectedLanguage: String?,
        val confidence: Float?,
        val tokens: List<TranscriptToken>,
    )

    private data class FinalizationResult(
        val segments: List<TranscriptSegment>,
        val fallbackError: String?,
    )

    private companion object {
        const val DEFAULT_READ_CHUNK_SAMPLES = 4_096
        const val DEFAULT_MAX_SECOND_PASS_SEGMENT_SAMPLES = 30L * 16_000L
    }
}

internal fun needsPunctuationFallback(
    text: String,
    capability: PunctuationCapability,
): Boolean {
    if (text.isBlank()) return false
    return when (capability) {
        PunctuationCapability.NONE -> true
        PunctuationCapability.RELIABLE -> false
        PunctuationCapability.PARTIAL -> {
            val last = text.trimEnd().lastOrNull() ?: return false
            last !in TERMINAL_PUNCTUATION
        }
    }
}

private val TERMINAL_PUNCTUATION =
    setOf('。', '！', '？', '.', '!', '?', '…')
