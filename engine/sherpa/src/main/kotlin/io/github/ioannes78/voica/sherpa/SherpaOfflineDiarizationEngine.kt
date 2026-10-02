package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.transcript.DiarizationChunkResult
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationEngine
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.DiarizationProgress
import io.github.ioannes78.voica.transcript.DiarizationProgressListener
import io.github.ioannes78.voica.transcript.DiarizationSpeakerTurn
import io.github.ioannes78.voica.transcript.DiarizationWindow
import java.io.File
import kotlin.math.roundToLong

data class SherpaDiarizationSettings(
    val numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
    val pyannoteWindowShiftRatio: Float = 0.1F,
    val minDurationOnSeconds: Float = 0.2F,
    val minDurationOffSeconds: Float = 0.5F,
    val defaultClusteringThreshold: Float = 0.5F,
    val computeConfidence: Boolean = true,
) {
    init {
        require(numThreads > 0)
        require(pyannoteWindowShiftRatio > 0F)
        require(minDurationOnSeconds >= 0F)
        require(minDurationOffSeconds >= 0F)
        require(defaultClusteringThreshold.isFinite())
    }
}

class SherpaOfflineDiarizationEngine internal constructor(
    override val segmentationModel: ModelDescriptor,
    override val embeddingModel: ModelDescriptor,
    private val native: NativeOfflineDiarizationSession,
) : DiarizationEngine {
    private var closed = false

    constructor(
        segmentationModel: ModelDescriptor,
        segmentationModelDirectory: File,
        embeddingModel: ModelDescriptor,
        embeddingModelDirectory: File,
        settings: SherpaDiarizationSettings = SherpaDiarizationSettings(),
    ) : this(
        segmentationModel = segmentationModel,
        embeddingModel = embeddingModel,
        native =
            SherpaNativeOfflineDiarizationSession(
                segmentationModelFile =
                    resolveSingleOnnx(
                        descriptor = segmentationModel,
                        directory = segmentationModelDirectory,
                        role = SpeakerModelRole.DIARIZATION_SEGMENTATION,
                    ),
                embeddingModelFile =
                    resolveSingleOnnx(
                        descriptor = embeddingModel,
                        directory = embeddingModelDirectory,
                        role = SpeakerModelRole.EMBEDDING,
                    ),
                settings = settings,
            ),
    )

    init {
        require(segmentationModel.kind == ModelKind.SPEAKER)
        require(segmentationModel.speakerRole == SpeakerModelRole.DIARIZATION_SEGMENTATION) {
            "segmentation model must declare DIARIZATION_SEGMENTATION"
        }
        require(embeddingModel.kind == ModelKind.SPEAKER)
        require(embeddingModel.speakerRole == SpeakerModelRole.EMBEDDING) {
            "embedding model must declare EMBEDDING"
        }
    }

    override suspend fun diarize(
        window: DiarizationWindow,
        config: DiarizationConfig,
        progressListener: DiarizationProgressListener?,
    ): DiarizationChunkResult {
        check(!closed) { "diarization engine is closed" }
        require(window.sampleRateHz == config.sampleRateHz)

        progressListener?.onProgress(
            DiarizationProgress(
                phase = DiarizationPhase.DIARIZATION,
                processedSamples = 0L,
                totalSamples = window.samples.size.toLong(),
            ),
        )

        val normalized =
            FloatArray(window.samples.size) { index ->
                window.samples[index] / 32768.0F
            }

        val nativeSegments =
            native.process(
                samples = normalized,
                sampleRateHz = window.sampleRateHz,
                expectedSpeakerCount = config.expectedSpeakerCount,
                clusteringThreshold = config.clusteringThreshold,
            )

        val mapped =
            nativeSegments
                .mapNotNull { segment ->
                    mapNativeSegment(
                        segment = segment,
                        window = window,
                    )
                }
                .sortedWith(
                    compareBy<DiarizationSpeakerTurn> { it.startSampleIndex }
                        .thenBy { it.endSampleIndexExclusive }
                        .thenBy { it.speakerIndex },
                )

        val withOverlap =
            mapped.mapIndexed { index, turn ->
                turn.copy(
                    overlap =
                        mapped.indices.any { otherIndex ->
                            if (index == otherIndex) {
                                false
                            } else {
                                val other = mapped[otherIndex]
                                other.speakerIndex != turn.speakerIndex &&
                                    rangesOverlap(turn, other)
                            }
                        },
                )
            }

        progressListener?.onProgress(
            DiarizationProgress(
                phase = DiarizationPhase.DIARIZATION,
                processedSamples = window.samples.size.toLong(),
                totalSamples = window.samples.size.toLong(),
            ),
        )

        return DiarizationChunkResult(
            speakerCount =
                withOverlap.maxOfOrNull { it.speakerIndex + 1 } ?: 0,
            turns = withOverlap,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private fun mapNativeSegment(
        segment: NativeDiarizationSegment,
        window: DiarizationWindow,
    ): DiarizationSpeakerTurn? {
        require(segment.speakerIndex >= 0)
        require(segment.startSeconds.isFinite())
        require(segment.endSeconds.isFinite())

        val localStart =
            secondsToSampleOffset(
                seconds = segment.startSeconds,
                sampleRateHz = window.sampleRateHz,
                maxSampleCount = window.samples.size.toLong(),
            )
        val localEnd =
            secondsToSampleOffset(
                seconds = segment.endSeconds,
                sampleRateHz = window.sampleRateHz,
                maxSampleCount = window.samples.size.toLong(),
            )
        if (localEnd <= localStart) return null

        return DiarizationSpeakerTurn(
            speakerIndex = segment.speakerIndex,
            startSampleIndex = Math.addExact(window.startSampleIndex, localStart),
            endSampleIndexExclusive = Math.addExact(window.startSampleIndex, localEnd),
            confidence =
                segment.confidence.takeIf {
                    it.isFinite() && it in -1F..1F
                },
            overlap = false,
        )
    }

    private companion object {
        fun rangesOverlap(
            left: DiarizationSpeakerTurn,
            right: DiarizationSpeakerTurn,
        ): Boolean =
            left.startSampleIndex < right.endSampleIndexExclusive &&
                right.startSampleIndex < left.endSampleIndexExclusive

        fun secondsToSampleOffset(
            seconds: Float,
            sampleRateHz: Int,
            maxSampleCount: Long,
        ): Long {
            val raw =
                (seconds.coerceAtLeast(0F).toDouble() * sampleRateHz.toDouble())
                    .roundToLong()
            return raw.coerceIn(0L, maxSampleCount)
        }

        fun resolveSingleOnnx(
            descriptor: ModelDescriptor,
            directory: File,
            role: SpeakerModelRole,
        ): File {
            require(descriptor.kind == ModelKind.SPEAKER)
            require(descriptor.speakerRole == role)
            require(directory.isDirectory) { "speaker model directory is missing" }

            val relativePath =
                descriptor.files
                    .map { it.relativePath }
                    .singleOrNull { it.endsWith(".onnx", ignoreCase = true) }
                    ?: error("speaker model must contain exactly one ONNX file")

            val canonicalRoot = directory.canonicalFile
            val candidate = File(canonicalRoot, relativePath).canonicalFile
            require(candidate.path.startsWith(canonicalRoot.path + File.separator)) {
                "speaker model file escapes model directory"
            }
            require(candidate.isFile) { "speaker model file is missing: $relativePath" }
            return candidate
        }
    }
}

internal data class NativeDiarizationSegment(
    val startSeconds: Float,
    val endSeconds: Float,
    val speakerIndex: Int,
    val confidence: Float,
)

internal interface NativeOfflineDiarizationSession : AutoCloseable {
    fun process(
        samples: FloatArray,
        sampleRateHz: Int,
        expectedSpeakerCount: Int?,
        clusteringThreshold: Float?,
    ): List<NativeDiarizationSegment>
}

private class SherpaNativeOfflineDiarizationSession(
    segmentationModelFile: File,
    embeddingModelFile: File,
    private val settings: SherpaDiarizationSettings,
) : NativeOfflineDiarizationSession {
    private val delegate =
        OfflineSpeakerDiarization(
            assetManager = null,
            config =
                OfflineSpeakerDiarizationConfig(
                    segmentation =
                        OfflineSpeakerSegmentationModelConfig(
                            pyannote =
                                OfflineSpeakerSegmentationPyannoteModelConfig(
                                    model = segmentationModelFile.absolutePath,
                                    windowShiftRatio = settings.pyannoteWindowShiftRatio,
                                ),
                            numThreads = settings.numThreads,
                            debug = false,
                            provider = SherpaRuntime.PROVIDER_CPU,
                        ),
                    embedding =
                        SpeakerEmbeddingExtractorConfig(
                            model = embeddingModelFile.absolutePath,
                            numThreads = settings.numThreads,
                            debug = false,
                            provider = SherpaRuntime.PROVIDER_CPU,
                        ),
                    clustering =
                        FastClusteringConfig(
                            numClusters = -1,
                            threshold = settings.defaultClusteringThreshold,
                            computeConfidence = settings.computeConfidence,
                        ),
                    minDurationOn = settings.minDurationOnSeconds,
                    minDurationOff = settings.minDurationOffSeconds,
                ),
        )
    private var closed = false

    init {
        check(delegate.sampleRate() == DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ) {
            "speaker diarization model must use 16 kHz audio"
        }
    }

    override fun process(
        samples: FloatArray,
        sampleRateHz: Int,
        expectedSpeakerCount: Int?,
        clusteringThreshold: Float?,
    ): List<NativeDiarizationSegment> {
        check(!closed)
        require(sampleRateHz == DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ)

        delegate.setConfig(
            OfflineSpeakerDiarizationConfig(
                clustering =
                    FastClusteringConfig(
                        numClusters = expectedSpeakerCount ?: -1,
                        threshold = clusteringThreshold ?: settings.defaultClusteringThreshold,
                        computeConfidence = settings.computeConfidence,
                    ),
            ),
        )

        return delegate.process(samples).map { segment ->
            NativeDiarizationSegment(
                startSeconds = segment.start,
                endSeconds = segment.end,
                speakerIndex = segment.speaker,
                confidence = segment.confidence,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.release()
    }
}
