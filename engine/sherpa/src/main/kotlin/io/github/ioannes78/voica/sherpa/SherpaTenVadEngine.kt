package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.VadEngine
import kotlin.math.min

const val SHERPA_TEN_VAD_CANDIDATE_MODEL_ID = "ten-vad-fp32"

data class TenVadSettings(
    val threshold: Float = 0.5F,
    val minSilenceDurationSeconds: Float = 0.25F,
    val minSpeechDurationSeconds: Float = 0.25F,
    val windowSizeSamples: Int = 256,
    val maxSpeechDurationSeconds: Float = 5.0F,
    val numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
) {
    init {
        require(threshold in 0.0F..1.0F)
        require(minSilenceDurationSeconds >= 0.0F)
        require(minSpeechDurationSeconds >= 0.0F)
        require(windowSizeSamples > 0)
        require(maxSpeechDurationSeconds > 0.0F)
        require(numThreads > 0)
    }

    companion object {
        /**
         * Gate A control profile. It changes only the maximum inference-segment duration so
         * TEN can be compared with Voica's current 30 s Silero policy without conflating the
         * VAD model with forced-split length.
         */
        fun gateAThirtySecondControl(
            threshold: Float = 0.5F,
            minSilenceDurationSeconds: Float = 0.25F,
            minSpeechDurationSeconds: Float = 0.25F,
            numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
        ): TenVadSettings =
            TenVadSettings(
                threshold = threshold,
                minSilenceDurationSeconds = minSilenceDurationSeconds,
                minSpeechDurationSeconds = minSpeechDurationSeconds,
                windowSizeSamples = 256,
                maxSpeechDurationSeconds = 30.0F,
                numThreads = numThreads,
            )
    }
}

class SherpaTenVadEngine internal constructor(
    override val model: ModelDescriptor,
    private val modelLocation: SherpaVadModelLocation,
    private val settings: TenVadSettings,
    private val sessionFactory: TenNativeVadSessionFactory,
    private val pcmChunkSamples: Int,
) : VadEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelLocation: SherpaVadModelLocation,
        settings: TenVadSettings = TenVadSettings(),
        pcmChunkSamples: Int = DEFAULT_PCM_CHUNK_SAMPLES,
    ) : this(
        model = model,
        modelLocation = modelLocation,
        settings = settings,
        sessionFactory = SherpaTenNativeVadSessionFactory,
        pcmChunkSamples = pcmChunkSamples,
    )

    init {
        require(model.kind == ModelKind.VAD) {
            "TEN VAD engine requires a VAD model descriptor"
        }
        require(pcmChunkSamples > 0)
    }

    override suspend fun analyze(
        source: PcmSource,
        progressListener: ProgressListener?,
    ): List<SpeechSegment> {
        check(!closed) { "VAD engine is closed" }
        require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            "VAD requires 16 kHz canonical PCM"
        }
        require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "VAD requires mono canonical PCM"
        }
        require(source.totalSampleCount >= 0L)

        if (source.totalSampleCount == 0L) {
            progressListener?.onProgress(
                TranscriptionProgress(
                    phase = TranscriptionPhase.VAD,
                    processedUnits = 0L,
                    totalUnits = 0L,
                ),
            )
            return emptyList()
        }

        val output = mutableListOf<SpeechSegment>()
        val buffer = ShortArray(pcmChunkSamples)
        var processed = 0L

        sessionFactory.create(modelLocation, settings).use { vad ->
            while (true) {
                val read = source.read(buffer) ?: break
                require(read.startSampleIndex == processed) {
                    "PCM source is not sequential: expected $processed, got ${read.startSampleIndex}"
                }
                require(read.sampleCount in 1..buffer.size)
                val nextProcessed = Math.addExact(processed, read.sampleCount.toLong())
                require(nextProcessed <= source.totalSampleCount) {
                    "PCM source exceeded declared total sample count"
                }

                val samples = FloatArray(read.sampleCount) { index ->
                    buffer[index] / 32768.0F
                }
                vad.acceptWaveform(samples)
                drainSegments(vad, source.totalSampleCount, output)

                processed = nextProcessed
                progressListener?.onProgress(
                    TranscriptionProgress(
                        phase = TranscriptionPhase.VAD,
                        processedUnits = processed,
                        totalUnits = source.totalSampleCount,
                    ),
                )
            }

            require(processed == source.totalSampleCount) {
                "PCM source ended at $processed but declared ${source.totalSampleCount}"
            }

            vad.flush()
            drainSegments(vad, source.totalSampleCount, output)
        }

        return output
    }

    override fun close() {
        closed = true
    }

    private fun drainSegments(
        vad: NativeVadSession,
        totalSampleCount: Long,
        target: MutableList<SpeechSegment>,
    ) {
        while (!vad.empty()) {
            val native = vad.front()
            val start = native.startSampleIndex
            require(start >= 0L)
            val rawEnd = Math.addExact(start, native.sampleCount.toLong())
            val end = min(rawEnd, totalSampleCount)
            if (end > start && start < totalSampleCount) {
                target +=
                    SpeechSegment(
                        startSampleIndex = start,
                        endSampleIndexExclusive = end,
                    )
            }
            vad.pop()
        }
    }

    private companion object {
        const val DEFAULT_PCM_CHUNK_SAMPLES = 4_096
    }
}

internal fun interface TenNativeVadSessionFactory {
    fun create(
        location: SherpaVadModelLocation,
        settings: TenVadSettings,
    ): NativeVadSession
}

private object SherpaTenNativeVadSessionFactory : TenNativeVadSessionFactory {
    override fun create(
        location: SherpaVadModelLocation,
        settings: TenVadSettings,
    ): NativeVadSession {
        val modelPath =
            when (location) {
                is SherpaVadModelLocation.Asset -> location.assetPath
                is SherpaVadModelLocation.File -> location.absolutePath
            }
        val config =
            VadModelConfig(
                tenVadModelConfig =
                    TenVadModelConfig(
                        model = modelPath,
                        threshold = settings.threshold,
                        minSilenceDuration = settings.minSilenceDurationSeconds,
                        minSpeechDuration = settings.minSpeechDurationSeconds,
                        windowSize = settings.windowSizeSamples,
                        maxSpeechDuration = settings.maxSpeechDurationSeconds,
                    ),
                sampleRate = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                numThreads = settings.numThreads,
                provider = SherpaRuntime.PROVIDER_CPU,
            )
        val delegate =
            when (location) {
                is SherpaVadModelLocation.Asset ->
                    Vad(
                        assetManager = location.assetManager,
                        config = config,
                    )
                is SherpaVadModelLocation.File ->
                    Vad(
                        assetManager = null,
                        config = config,
                    )
            }
        return object : NativeVadSession {
            override fun acceptWaveform(samples: FloatArray) {
                delegate.acceptWaveform(samples)
            }

            override fun empty(): Boolean = delegate.empty()

            override fun front(): NativeVadSegment {
                val segment = delegate.front()
                return NativeVadSegment(
                    startSampleIndex = segment.start.toLong(),
                    sampleCount = segment.samples.size,
                )
            }

            override fun pop() {
                delegate.pop()
            }

            override fun flush() {
                delegate.flush()
            }

            override fun close() {
                delegate.release()
            }
        }
    }
}
