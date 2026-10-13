package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalRealtimeEventSource
import io.github.ioannes78.voica.audio.StreamingAudioBuffer
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.PunctuationEngine
import io.github.ioannes78.voica.transcript.RealtimeDurableOutputFailure
import io.github.ioannes78.voica.transcript.RealtimeDurableOutputSink
import io.github.ioannes78.voica.transcript.RealtimeHypothesisStabilizer
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioResult
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioSideSink
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioTargetFactory
import io.github.ioannes78.voica.transcript.RealtimePunctuationProcessor
import io.github.ioannes78.voica.transcript.RealtimeStablePunctuationMode
import io.github.ioannes78.voica.transcript.RealtimeStreamingAsrConfig
import io.github.ioannes78.voica.transcript.RealtimeStreamingAsrProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptCoordinator
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutputSink
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionPipeline
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionPipelineEventProcessor
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunner
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunnerState
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAudioSideSink
import io.github.ioannes78.voica.transcript.StreamingVadEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class Stage16ARealtimePipelineConfig(
    val maxBufferedPcmFrames: Int = 32,
    val maxBufferedPcmSamples: Int = 10_240,
    /** Provisional bring-up candidate; final product value is frozen only after P7 device benchmark. */
    val targetChunkSamples: Int = 1_280,
    val vadPrerollSamples: Int = 3_200,
    val localAudioQueueCapacity: Int = 128,
    val durableOutputQueueCapacity: Int = 64,
) {
    init {
        require(maxBufferedPcmFrames > 0)
        require(maxBufferedPcmSamples > 0)
        require(targetChunkSamples in CHUNK_SAMPLE_CANDIDATES) {
            "targetChunkSamples must remain inside the approved Stage 16A benchmark matrix"
        }
        require(vadPrerollSamples >= targetChunkSamples)
        require(localAudioQueueCapacity > 0)
        require(durableOutputQueueCapacity > 0)
    }

    val targetChunkDurationMs: Int
        get() = targetChunkSamples * 1_000 / 16_000

    companion object {
        /** 40 / 80 / 100 / 160 / 200 ms at canonical 16 kHz. */
        val CHUNK_SAMPLE_CANDIDATES: Set<Int> = setOf(640, 1_280, 1_600, 2_560, 3_200)
    }
}

/**
 * Fully composed local Stage 16A inference/persistence pipeline for one logical realtime run.
 *
 * This bundle owns the native ASR/VAD/punctuation engines and the model leases supplied to the
 * factory. It does not own recorder commands: START/PAUSE/RESUME/STOP and reconnect policy stay in
 * the app lifecycle controller. [release] must be called only after the session runner is no longer
 * RUNNING; accepted local-media, waveform and durable-output work is drained before native/model
 * resources are released.
 */
class Stage16ARealtimePipelineBundle internal constructor(
    val models: Stage16ARealtimeModelLeaseBundle,
    val runner: RealtimeTranscriptionSessionRunner,
    val localAudioSink: RealtimeLocalAudioSideSink,
    val durableOutputSink: RealtimeDurableOutputSink,
    val processor: RealtimeStreamingAsrProcessor,
    private val buffer: StreamingAudioBuffer,
    private val performanceProbe: Stage16ARealtimePerformanceProbe,
    private val performanceConfig: Stage16ARealtimePipelineConfig,
    private val audioActivitySink: Stage16ARealtimeAudioActivitySink?,
    private val asrEngine: StreamingAsrEngine,
    private val vadEngine: StreamingVadEngine,
    private val punctuationEngine: PunctuationEngine?,
) {
    private val released = AtomicBoolean(false)

    /**
     * Pull-only P7 telemetry snapshot. Reading it never consumes canonical media or schedules work.
     */
    internal fun performanceSnapshot(): Stage16ARealtimePipelinePerformanceSnapshot =
        Stage16ARealtimePipelinePerformanceSnapshot(
            modelId = models.models.asr.descriptor.modelId,
            modelLabel = models.models.asr.descriptor.displayName,
            effectiveThreads = models.models.performance.effectiveThreads,
            targetChunkSamples = performanceConfig.targetChunkSamples,
            targetChunkDurationMs = performanceConfig.targetChunkDurationMs,
            native = performanceProbe.snapshot(),
            buffer = buffer.snapshot(),
            runner = runner.snapshot(),
        )

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        check(runner.snapshot().state != RealtimeTranscriptionSessionRunnerState.RUNNING) {
            "Stage 16A pipeline cannot release while realtime runner is active"
        }

        try {
            audioActivitySink?.close()
            localAudioSink.close()
            durableOutputSink.close()
            audioActivitySink?.join()
            localAudioSink.join()
            durableOutputSink.join()
        } finally {
            processor.close()
            runCatching { punctuationEngine?.close() }
            runCatching { vadEngine.close() }
            runCatching { asrEngine.close() }
            models.close()
        }
    }
}

/**
 * Composes the already-approved Stage 16A seams without touching BLE/protocol internals.
 *
 * Ownership of [models] transfers to the returned bundle. If construction fails, every partially
 * opened native engine and model lease is released before the exception is rethrown.
 */
class Stage16ARealtimePipelineBundleFactory(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val engineProvider: Stage16ARealtimeEngineProvider,
    private val config: Stage16ARealtimePipelineConfig = Stage16ARealtimePipelineConfig(),
) {
    fun create(
        source: CanonicalRealtimeEventSource,
        models: Stage16ARealtimeModelLeaseBundle,
        localAudioRoot: File,
        persistDurableOutput: suspend (RealtimeTranscriptionOutput) -> Unit,
        onDurableFailure: suspend (RealtimeDurableOutputFailure) -> Unit = {},
        onLocalAudioResult: suspend (RealtimeLocalAudioResult) -> Unit,
        liveOutputSinks: List<RealtimeTranscriptionOutputSink> = emptyList(),
    ): Stage16ARealtimePipelineBundle {
        require(localAudioRoot.exists() || localAudioRoot.mkdirs()) {
            "unable to create Stage 16A realtime local-audio root"
        }
        require(localAudioRoot.isDirectory)
        require(models.models.asr.descriptor.runtimeId == SherpaRuntime.RUNTIME_ID)

        val performanceProbe = Stage16ARealtimePerformanceProbe()
        var asrEngine: StreamingAsrEngine? = null
        var vadEngine: StreamingVadEngine? = null
        var punctuationEngine: PunctuationEngine? = null
        var processor: RealtimeStreamingAsrProcessor? = null
        var localAudioSink: RealtimeLocalAudioSideSink? = null
        var durableOutputSink: RealtimeDurableOutputSink? = null
        var audioActivitySink: Stage16ARealtimeAudioActivitySink? = null

        try {
            asrEngine = performanceProbe.wrapAsr(engineProvider.openAsr(models.models))
            vadEngine = performanceProbe.wrapVad(engineProvider.openVad(models.models))
            punctuationEngine =
                engineProvider.openPunctuation(models.models)?.let(performanceProbe::wrapPunctuation)
            processor =
                RealtimeStreamingAsrProcessor(
                    asrEngine = asrEngine,
                    vadEngine = vadEngine,
                    config =
                        RealtimeStreamingAsrConfig(
                            targetChunkSamples = config.targetChunkSamples,
                            vadPrerollSamples = config.vadPrerollSamples,
                        ),
                )
            localAudioSink =
                RealtimeLocalAudioSideSink(
                    scope = scope,
                    targetFactory =
                        RealtimeLocalAudioTargetFactory { sessionId ->
                            File(localAudioRoot, "canonical-session-$sessionId.wav")
                        },
                    resultHandler = onLocalAudioResult,
                    queueCapacity = config.localAudioQueueCapacity,
                )
            durableOutputSink =
                RealtimeDurableOutputSink(
                    scope = scope,
                    persist = persistDurableOutput,
                    onFailure = onDurableFailure,
                    queueCapacity = config.durableOutputQueueCapacity,
                )

            val audioActivityConsumers =
                liveOutputSinks.filterIsInstance<Stage16ARealtimeAudioActivityConsumer>()
            if (audioActivityConsumers.isNotEmpty()) {
                audioActivitySink =
                    Stage16ARealtimeAudioActivitySink(
                        scope = scope,
                        consumers = audioActivityConsumers,
                    )
            }

            val coordinatorSideSinks = mutableListOf<StreamingAudioSideSink>(localAudioSink)
            audioActivitySink?.let(coordinatorSideSinks::add)
            val buffer =
                StreamingAudioBuffer(
                    maxQueuedPcmFrames = config.maxBufferedPcmFrames,
                    maxQueuedPcmSamples = config.maxBufferedPcmSamples,
                )
            val coordinator =
                RealtimeTranscriptCoordinator(
                    source = source,
                    outputBuffer = buffer,
                    sideSinks = coordinatorSideSinks,
                )
            val transcriptionPipeline =
                RealtimeTranscriptionPipeline(
                    processor = processor,
                    stabilizer = RealtimeHypothesisStabilizer(),
                    punctuationProcessor =
                        RealtimePunctuationProcessor(
                            punctuationEngine = punctuationEngine,
                            stableMode = RealtimeStablePunctuationMode.FINAL_ONLY,
                        ),
                )
            val runner =
                RealtimeTranscriptionSessionRunner(
                    coordinator = coordinator,
                    processor = RealtimeTranscriptionPipelineEventProcessor(transcriptionPipeline),
                    outputSinks = listOf(durableOutputSink) + liveOutputSinks,
                )

            return Stage16ARealtimePipelineBundle(
                models = models,
                runner = runner,
                localAudioSink = localAudioSink,
                durableOutputSink = durableOutputSink,
                processor = processor,
                buffer = buffer,
                performanceProbe = performanceProbe,
                performanceConfig = config,
                audioActivitySink = audioActivitySink,
                asrEngine = asrEngine,
                vadEngine = vadEngine,
                punctuationEngine = punctuationEngine,
            )
        } catch (failure: Throwable) {
            audioActivitySink?.close()
            localAudioSink?.close()
            durableOutputSink?.close()
            processor?.close()
            runCatching { punctuationEngine?.close() }
            runCatching { vadEngine?.close() }
            runCatching { asrEngine?.close() }
            models.close()
            throw failure
        }
    }
}
