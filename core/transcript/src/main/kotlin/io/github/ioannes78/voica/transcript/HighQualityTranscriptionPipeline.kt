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
    val timelineAlignmentFallbackError: String? = null,
)

class HighQualityTranscriptionPipeline(
    private val pcmSourceResolver: PcmSourceResolver,
    private val vadEngineFactory: VadEngineFactory,
    private val secondPassAsrEngineFactory: SecondPassAsrEngineFactory,
    private val punctuationEngineFactory: PunctuationEngineFactory?,
    private val timelineAlignmentAsrEngineFactory: StreamingAsrEngineFactory? = null,
    readChunkSamples: Int = DEFAULT_READ_CHUNK_SAMPLES,
    private val maxSecondPassSegmentSamples: Long = DEFAULT_MAX_SECOND_PASS_SEGMENT_SAMPLES,
) {
    private val readChunkSamples = readChunkSamples

    init {
        require(readChunkSamples > 0)
        require(maxSecondPassSegmentSamples > 0L)
    }

    suspend fun transcribe(
        recordingId: String,
        progressListener: ProgressListener? = null,
    ): HighQualityTranscriptionResult {
        require(recordingId.isNotBlank())
        progressListener?.onProgress(
            TranscriptionProgress(phase = TranscriptionPhase.PREPARING),
        )

        val vadSource =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source is unavailable for recording $recordingId")
        val totalSampleCount = vadSource.totalSampleCount
        val speechSegments =
            try {
                val vad = vadEngineFactory.open()
                try {
                    vad.analyze(vadSource, progressListener)
                } finally {
                    vad.close()
                }
            } finally {
                vadSource.close()
            }

        validateSpeechSegments(speechSegments, totalSampleCount)
        val speechSampleCount =
            speechSegments.fold(0L) { total, segment ->
                Math.addExact(total, segment.sampleCount)
            }

        if (speechSegments.isEmpty()) {
            return HighQualityTranscriptionResult(
                totalSampleCount = totalSampleCount,
                speechSegments = emptyList(),
                transcriptSegments = emptyList(),
                secondPassApplied = false,
            )
        }

        val offlineSegments =
            runOfflineAsr(
                recordingId = recordingId,
                totalSampleCount = totalSampleCount,
                speechSegments = speechSegments,
                speechSampleCount = speechSampleCount,
                progressListener = progressListener,
            )

        var timelineFallbackError: String? = null
        val timelineReferences =
            if (
                timelineAlignmentAsrEngineFactory != null &&
                offlineSegments.any { it.absoluteTokens.isEmpty() }
            ) {
                try {
                    TimelineReferenceRunner(
                        pcmSourceResolver = pcmSourceResolver,
                        asrEngineFactory = timelineAlignmentAsrEngineFactory,
                        readChunkSamples = readChunkSamples,
                    ).run(
                        recordingId = recordingId,
                        expectedTotalSampleCount = totalSampleCount,
                        speechSegments = speechSegments,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    timelineFallbackError =
                        "精确时间轴对齐失败：" +
                            (error.message ?: error::class.java.simpleName)
                    emptyList()
                }
            } else {
                emptyList()
            }

        val referenceByIndex = timelineReferences.associateBy { it.segmentIndex }
        val bases =
            offlineSegments.map { offline ->
                val alignedTokens =
                    if (offline.absoluteTokens.isNotEmpty()) {
                        offline.absoluteTokens
                    } else {
                        referenceByIndex[offline.segmentIndex]?.let { reference ->
                            val aligned =
                                alignFinalTextToReferenceTiming(
                                    finalText = offline.hypothesis.text,
                                    referenceText = reference.text,
                                    referenceTokens = reference.absoluteTokens,
                                    segment = offline.speechSegment,
                                )
                            if (aligned.quality == TimelineAlignmentQuality.FAILED) {
                                emptyList()
                            } else {
                                aligned.tokens
                            }
                        }.orEmpty()
                    }

                FinalizationBase(
                    segmentIndex = offline.segmentIndex,
                    speechSegment = offline.speechSegment,
                    text = offline.hypothesis.text,
                    punctuationCapability = offline.hypothesis.punctuationCapability,
                    detectedLanguage = offline.hypothesis.detectedLanguage,
                    confidence = offline.hypothesis.confidence,
                    tokens = alignedTokens,
                )
            }

        val finalized = finalizeText(bases, progressListener)

        return HighQualityTranscriptionResult(
            totalSampleCount = totalSampleCount,
            speechSegments = speechSegments,
            transcriptSegments = finalized.segments,
            secondPassApplied = true,
            punctuationFallbackError = finalized.fallbackError,
            timelineAlignmentFallbackError = timelineFallbackError,
        )
    }

    private suspend fun runOfflineAsr(
        recordingId: String,
        totalSampleCount: Long,
        speechSegments: List<SpeechSegment>,
        speechSampleCount: Long,
        progressListener: ProgressListener?,
    ): List<OfflineSegment> {
        speechSegments.forEach { segment ->
            require(segment.sampleCount <= maxSecondPassSegmentSamples) {
                "speech segment exceeds offline ASR safety bound"
            }
            require(segment.sampleCount <= Int.MAX_VALUE.toLong())
        }

        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before offline ASR")
        require(source.totalSampleCount == totalSampleCount) {
            "canonical PCM sample count changed before offline ASR"
        }

        val engine =
            try {
                secondPassAsrEngineFactory.open()
            } catch (error: Throwable) {
                source.close()
                throw error
            }
        require(engine.capabilities.supportsSecondPass) {
            "high-quality transcription requires an offline ASR engine"
        }

        try {
            val output = ArrayList<OfflineSegment>(speechSegments.size)
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
                    totalUnits = speechSampleCount,
                ),
            )

            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(readBuffer) ?: break
                require(read.startSampleIndex == sourceCursor) {
                    "PCM source is not sequential during offline ASR"
                }
                require(read.sampleCount in 1..readBuffer.size)
                val readEnd = Math.addExact(read.startSampleIndex, read.sampleCount.toLong())
                require(readEnd <= totalSampleCount)

                while (
                    segmentIndex < speechSegments.size &&
                    speechSegments[segmentIndex].startSampleIndex < readEnd
                ) {
                    currentCoroutineContext().ensureActive()
                    val segment = speechSegments[segmentIndex]
                    require(segment.endSampleIndexExclusive > read.startSampleIndex) {
                        "speech segment was skipped during offline ASR"
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
                        val pcm =
                            segmentPcm
                                ?: error("offline ASR segment buffer is missing")
                        readBuffer.copyInto(
                            destination = pcm,
                            destinationOffset = destinationOffset,
                            startIndex = sourceOffset,
                            endIndex = sourceOffset + count,
                        )
                        writtenForSegment += count
                    }

                    if (segment.endSampleIndexExclusive <= readEnd) {
                        val pcm =
                            segmentPcm
                                ?: error("offline ASR segment completed without PCM")
                        require(writtenForSegment == pcm.size) {
                            "offline ASR segment PCM is incomplete"
                        }

                        val result =
                            engine.transcribe(
                                samples = pcm,
                                sampleRateHz = source.sampleRateHz,
                            )
                        require(result.isFinal) {
                            "offline ASR did not return a final hypothesis"
                        }

                        output +=
                            OfflineSegment(
                                segmentIndex = segmentIndex,
                                speechSegment = segment,
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
                                totalUnits = speechSampleCount,
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

            require(sourceCursor == totalSampleCount)
            require(segmentIndex == speechSegments.size) {
                "PCM source ended before all offline ASR segments"
            }
            require(segmentPcm == null)
            require(processedSpeechSamples == speechSampleCount)
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
            val factory = punctuationEngineFactory
            if (factory == null) {
                fallbackError = "当前离线模型需要外部标点模型，但标点模型不可用"
            } else {
                try {
                    engine = factory.open()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fallbackError = error.message ?: error::class.java.simpleName
                }
            }
        }

        try {
            val total = bases.size.toLong()
            val output = ArrayList<TranscriptSegment>(bases.size)
            if (requiresPunctuation) {
                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.PUNCTUATION,
                        processedUnits = 0L,
                        totalUnits = total,
                    ),
                )
            }

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

                output +=
                    TranscriptSegment(
                        segmentIndex = base.segmentIndex,
                        startSampleIndex = base.speechSegment.startSampleIndex,
                        endSampleIndexExclusive = base.speechSegment.endSampleIndexExclusive,
                        firstPassRawText = "",
                        secondPassRawText = base.text,
                        finalText = finalText,
                        detectedLanguage = base.detectedLanguage,
                        confidence = base.confidence,
                        tokens = base.tokens,
                    )

                if (requiresPunctuation) {
                    progressListener?.onProgress(
                        TranscriptionProgress(
                            phase = TranscriptionPhase.PUNCTUATION,
                            processedUnits = (index + 1).toLong(),
                            totalUnits = total,
                        ),
                    )
                }
            }
            return FinalizationResult(output, fallbackError)
        } finally {
            engine?.close()
        }
    }

    private data class OfflineSegment(
        val segmentIndex: Int,
        val speechSegment: SpeechSegment,
        val hypothesis: AsrHypothesis,
        val absoluteTokens: List<TranscriptToken>,
    )

    private data class FinalizationBase(
        val segmentIndex: Int,
        val speechSegment: SpeechSegment,
        val text: String,
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
        PunctuationCapability.PARTIAL -> true
    }
}
