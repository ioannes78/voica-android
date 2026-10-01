package io.github.ioannes78.voica.sherpa

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
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
import java.io.Closeable
import kotlin.math.min

sealed interface SherpaVadModelLocation {
    data class Asset(
        val assetManager: AssetManager,
        val assetPath: String,
    ) : SherpaVadModelLocation

    data class File(
        val absolutePath: String,
    ) : SherpaVadModelLocation
}

data class SileroVadSettings(
    val threshold: Float = 0.5F,
    val minSilenceDurationSeconds: Float = 0.25F,
    val minSpeechDurationSeconds: Float = 0.25F,
    val windowSizeSamples: Int = 512,
    val maxSpeechDurationSeconds: Float = 30.0F,
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
}

class SherpaSileroVadEngine internal constructor(
    override val model: ModelDescriptor,
    private val modelLocation: SherpaVadModelLocation,
    private val settings: SileroVadSettings,
    private val sessionFactory: NativeVadSessionFactory,
    private val pcmChunkSamples: Int,
) : VadEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelLocation: SherpaVadModelLocation,
        settings: SileroVadSettings = SileroVadSettings(),
        pcmChunkSamples: Int = DEFAULT_PCM_CHUNK_SAMPLES,
    ) : this(
        model = model,
        modelLocation = modelLocation,
        settings = settings,
        sessionFactory = SherpaNativeVadSessionFactory,
        pcmChunkSamples = pcmChunkSamples,
    )

    init {
        require(model.kind == ModelKind.VAD) {
            "Silero VAD engine requires a VAD model descriptor"
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
                    "PCM source is not sequential: expected " + processed +
                        ", got " + read.startSampleIndex
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
                "PCM source ended at " + processed +
                    " but declared " + source.totalSampleCount
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

internal data class NativeVadSegment(
    val startSampleIndex: Long,
    val sampleCount: Int,
)

internal interface NativeVadSession : Closeable {
    fun acceptWaveform(samples: FloatArray)

    fun empty(): Boolean

    fun front(): NativeVadSegment

    fun pop()

    fun flush()
}

internal fun interface NativeVadSessionFactory {
    fun create(
        location: SherpaVadModelLocation,
        settings: SileroVadSettings,
    ): NativeVadSession
}

private object SherpaNativeVadSessionFactory : NativeVadSessionFactory {
    override fun create(
        location: SherpaVadModelLocation,
        settings: SileroVadSettings,
    ): NativeVadSession {
        val modelPath =
            when (location) {
                is SherpaVadModelLocation.Asset -> location.assetPath
                is SherpaVadModelLocation.File -> location.absolutePath
            }
        val config =
            VadModelConfig(
                sileroVadModelConfig =
                    SileroVadModelConfig(
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
        return SherpaNativeVadSession(delegate)
    }
}

private class SherpaNativeVadSession(
    private val delegate: Vad,
) : NativeVadSession {
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
