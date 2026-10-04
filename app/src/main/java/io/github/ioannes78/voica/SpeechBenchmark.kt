package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.transcript.FastTranscriptionPipeline
import io.github.ioannes78.voica.transcript.HighQualityTranscriptionPipeline
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SpeechBenchmarkEnvironment(
    val manufacturer: String,
    val model: String,
    val hardware: String,
    val socManufacturer: String?,
    val socModel: String?,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val logicalProcessors: Int,
    val appVersionCode: Long,
    val appVersionName: String?,
)

enum class SpeechBenchmarkTrack {
    REALTIME,
    OFFLINE,
}

data class SpeechBenchmarkCaseResult(
    val benchmarkTrack: SpeechBenchmarkTrack,
    val modelId: String,
    val displayName: String,
    val version: String,
    val revision: Long,
    val manifestDigest: String,
    val runtimeModelType: String?,
    val quantization: String?,
    val pipelineFirstPassModelId: String,
    val pipelinePunctuationModelId: String,
    val performanceProfile: String,
    val requestedThreads: Int?,
    val effectiveThreads: Int,
    val vadThreshold: Float,
    val vadMinSilenceSeconds: Float,
    val vadMinSpeechSeconds: Float,
    val vadMaxSpeechSeconds: Float,
    val audioDurationMs: Long,
    val wallTimeMs: Long,
    val cpuTimeMs: Long,
    val rtf: Double,
    val peakPssKb: Long,
    val thermalStatusStart: Int?,
    val thermalStatusMax: Int?,
    val firstPartialAudioMs: Long?,
    val firstPartialComputeMs: Long?,
    val finalizationLatencyMs: Long?,
    val streamingProbeSpeechMs: Long?,
    val streamingProbeError: String? = null,
    val outputCharacterCount: Int,
    val outputText: String,
    val cer: Double?,
    val wer: Double?,
    val error: String? = null,
)

data class SpeechBenchmarkReport(
    val schemaVersion: Int = 3,
    val track: SpeechBenchmarkTrack,
    val createdAt: String,
    val recordingId: String,
    val environment: SpeechBenchmarkEnvironment,
    val referenceText: String?,
    val cases: List<SpeechBenchmarkCaseResult>,
) {
    fun toJson(): String =
        JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("track", track.name)
            .put("createdAt", createdAt)
            .put("recordingId", recordingId)
            .put(
                "environment",
                JSONObject()
                    .put("manufacturer", environment.manufacturer)
                    .put("model", environment.model)
                    .put("hardware", environment.hardware)
                    .put("socManufacturer", jsonNullable(environment.socManufacturer))
                    .put("socModel", jsonNullable(environment.socModel))
                    .put("sdkInt", environment.sdkInt)
                    .put("supportedAbis", JSONArray(environment.supportedAbis))
                    .put("totalRamBytes", environment.totalRamBytes)
                    .put("logicalProcessors", environment.logicalProcessors)
                    .put("appVersionCode", environment.appVersionCode)
                    .put("appVersionName", jsonNullable(environment.appVersionName)),
            )
            .put("referenceText", jsonNullable(referenceText))
            .put(
                "cases",
                JSONArray().also { array ->
                    cases.forEach { result ->
                        array.put(
                            JSONObject()
                                .put("benchmarkTrack", result.benchmarkTrack.name)
                                .put("modelId", result.modelId)
                                .put("displayName", result.displayName)
                                .put("version", result.version)
                                .put("revision", result.revision)
                                .put("manifestDigest", result.manifestDigest)
                                .put("runtimeModelType", jsonNullable(result.runtimeModelType))
                                .put("quantization", jsonNullable(result.quantization))
                                .put("pipelineFirstPassModelId", result.pipelineFirstPassModelId)
                                .put("pipelinePunctuationModelId", result.pipelinePunctuationModelId)
                                .put("performanceProfile", result.performanceProfile)
                                .put("requestedThreads", jsonNullable(result.requestedThreads))
                                .put("effectiveThreads", result.effectiveThreads)
                                .put("vadThreshold", result.vadThreshold)
                                .put("vadMinSilenceSeconds", result.vadMinSilenceSeconds)
                                .put("vadMinSpeechSeconds", result.vadMinSpeechSeconds)
                                .put("vadMaxSpeechSeconds", result.vadMaxSpeechSeconds)
                                .put("audioDurationMs", result.audioDurationMs)
                                .put("wallTimeMs", result.wallTimeMs)
                                .put("cpuTimeMs", result.cpuTimeMs)
                                .put("rtf", result.rtf)
                                .put("peakPssKb", result.peakPssKb)
                                .put("thermalStatusStart", jsonNullable(result.thermalStatusStart))
                                .put("thermalStatusMax", jsonNullable(result.thermalStatusMax))
                                .put("firstPartialAudioMs", jsonNullable(result.firstPartialAudioMs))
                                .put("firstPartialComputeMs", jsonNullable(result.firstPartialComputeMs))
                                .put("finalizationLatencyMs", jsonNullable(result.finalizationLatencyMs))
                                .put("streamingProbeSpeechMs", jsonNullable(result.streamingProbeSpeechMs))
                                .put("streamingProbeError", jsonNullable(result.streamingProbeError))
                                .put("outputCharacterCount", result.outputCharacterCount)
                                .put("outputText", result.outputText)
                                .put("cer", jsonNullable(result.cer))
                                .put("wer", jsonNullable(result.wer))
                                .put("error", jsonNullable(result.error)),
                        )
                    }
                },
            )
            .toString(2)
}

private fun jsonNullable(value: Any?): Any = value ?: JSONObject.NULL

class SpeechBenchmarkRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val engineProvider: Stage8TranscriptionEngineProvider,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    suspend fun runRealtimeComparison(
        recordingId: String,
        referenceText: String? = null,
        modelIds: List<String> = Stage13ARealtimeModelIds.ALL,
    ): SpeechBenchmarkReport {
        require(recordingId.isNotBlank())
        require(modelIds.isNotEmpty())
        require(modelIds.distinct().size == modelIds.size)

        val totalSampleCount =
            pcmSourceResolver.resolvePcmSource(recordingId)?.use { source ->
                require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
                require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
                source.totalSampleCount
            } ?: error("canonical PCM source is unavailable")

        val settings = localSpeechSettings()
        val performance = settings.resolvePerformance()
        val vad =
            modelManager.activeModel(Stage8ModelIds.VAD)
                ?: error("benchmark requires active VAD model")
        val punctuation =
            modelManager.activeModel(Stage8ModelIds.PUNCTUATION)
                ?: error("benchmark requires active punctuation model")

        require(vad.descriptor.kind == ModelKind.VAD)
        require(punctuation.descriptor.kind == ModelKind.PUNCTUATION)

        val results =
            modelIds.map { modelId ->
                val model = modelManager.activeModel(modelId)
                if (model == null) {
                    missingModelResult(
                        track = SpeechBenchmarkTrack.REALTIME,
                        modelId = modelId,
                        totalSampleCount = totalSampleCount,
                        settings = settings,
                        effectiveThreads = performance.effectiveThreads,
                        pipelineFirstPassModelId = modelId,
                        pipelinePunctuationModelId = punctuation.descriptor.modelId,
                    )
                } else {
                    runModelCase(
                        recordingId = recordingId,
                        totalSampleCount = totalSampleCount,
                        referenceText = referenceText,
                        vad = vad,
                        asr = model,
                        punctuation = punctuation,
                        settings = settings,
                        effectiveThreads = performance.effectiveThreads,
                    )
                }
            }

        return SpeechBenchmarkReport(
            track = SpeechBenchmarkTrack.REALTIME,
            createdAt = Instant.now().toString(),
            recordingId = recordingId,
            environment = environment(),
            referenceText = referenceText?.takeIf { it.isNotBlank() },
            cases = results,
        )
    }

    suspend fun runOfflineComparison(
        recordingId: String,
        referenceText: String? = null,
        modelIds: List<String> = Stage13AOfflineModelIds.ALL,
    ): SpeechBenchmarkReport {
        require(recordingId.isNotBlank())
        require(modelIds.isNotEmpty())
        require(modelIds.distinct().size == modelIds.size)

        val totalSampleCount =
            pcmSourceResolver.resolvePcmSource(recordingId)?.use { source ->
                require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
                require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
                source.totalSampleCount
            } ?: error("canonical PCM source is unavailable")

        val settings = localSpeechSettings()
        val performance = settings.resolvePerformance()
        val vad =
            modelManager.activeModel(Stage8ModelIds.VAD)
                ?: error("offline benchmark requires active VAD model")
        val punctuation =
            modelManager.activeModel(Stage8ModelIds.PUNCTUATION)
                ?: error("offline benchmark requires active punctuation model")
        val firstPass =
            settings.realtimeAsrModel.preferredModelIds()
                .firstNotNullOfOrNull { modelManager.activeModel(it) }
                ?: error("offline benchmark requires an active realtime first-pass ASR")

        require(vad.descriptor.kind == ModelKind.VAD)
        require(firstPass.descriptor.kind == ModelKind.ASR_STREAMING)
        require(punctuation.descriptor.kind == ModelKind.PUNCTUATION)

        val results =
            modelIds.map { modelId ->
                val secondPass = modelManager.activeModel(modelId)
                if (secondPass == null) {
                    missingModelResult(
                        track = SpeechBenchmarkTrack.OFFLINE,
                        modelId = modelId,
                        totalSampleCount = totalSampleCount,
                        settings = settings,
                        effectiveThreads = performance.effectiveThreads,
                        pipelineFirstPassModelId = firstPass.descriptor.modelId,
                        pipelinePunctuationModelId = punctuation.descriptor.modelId,
                    )
                } else {
                    runOfflineModelCase(
                        recordingId = recordingId,
                        totalSampleCount = totalSampleCount,
                        referenceText = referenceText,
                        vad = vad,
                        firstPass = firstPass,
                        secondPass = secondPass,
                        punctuation = punctuation,
                        settings = settings,
                        effectiveThreads = performance.effectiveThreads,
                    )
                }
            }

        return SpeechBenchmarkReport(
            track = SpeechBenchmarkTrack.OFFLINE,
            createdAt = Instant.now().toString(),
            recordingId = recordingId,
            environment = environment(),
            referenceText = referenceText?.takeIf { it.isNotBlank() },
            cases = results,
        )
    }

    private suspend fun runOfflineModelCase(
        recordingId: String,
        totalSampleCount: Long,
        referenceText: String?,
        vad: ActiveModel,
        firstPass: ActiveModel,
        secondPass: ActiveModel,
        punctuation: ActiveModel,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
    ): SpeechBenchmarkCaseResult {
        require(
            secondPass.descriptor.kind == ModelKind.ASR_SECOND_PASS ||
                secondPass.descriptor.kind == ModelKind.ASR_LARGE,
        ) {
            "offline benchmark model must support second-pass ASR"
        }
        val leasedModels = listOf(vad, firstPass, secondPass, punctuation)
        val leases =
            leasedModels.map { model ->
                modelUseRegistry.acquire(
                    modelId = model.descriptor.modelId,
                    version = model.descriptor.version,
                    revision = model.descriptor.revision,
                )
            }
        val audioDurationMs =
            totalSampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ

        try {
            val measured =
                measureResources {
                    HighQualityTranscriptionPipeline(
                        pcmSourceResolver = pcmSourceResolver,
                        vadEngineFactory =
                            engineProvider.vadFactory(
                                model = vad,
                                numThreads = effectiveThreads,
                                vadSettings = settings.vad,
                            ),
                        asrEngineFactory =
                            engineProvider.firstPassFactory(
                                model = firstPass,
                                numThreads = effectiveThreads,
                            ),
                        secondPassAsrEngineFactory =
                            engineProvider.secondPassFactory(
                                model = secondPass,
                                numThreads = effectiveThreads,
                                qwenSettings = settings.qwen,
                            ),
                        punctuationEngineFactory =
                            engineProvider.punctuationFactory(
                                model = punctuation,
                                numThreads = effectiveThreads,
                            ),
                    ).transcribe(recordingId)
                }
            check(measured.value.secondPassApplied) {
                "offline benchmark second pass fell back: " +
                    (measured.value.secondPassFallbackError ?: "unknown error")
            }

            val text =
                measured.value.transcriptSegments.joinToString(separator = "") {
                    it.finalText
                }
            val normalizedReference = referenceText?.takeIf { it.isNotBlank() }

            return SpeechBenchmarkCaseResult(
                benchmarkTrack = SpeechBenchmarkTrack.OFFLINE,
                modelId = secondPass.descriptor.modelId,
                displayName = secondPass.descriptor.displayName,
                version = secondPass.descriptor.version,
                revision = secondPass.descriptor.revision,
                manifestDigest = secondPass.manifestDigest,
                runtimeModelType = secondPass.descriptor.runtimeModelType,
                quantization = secondPass.descriptor.quantization,
                pipelineFirstPassModelId = firstPass.descriptor.modelId,
                pipelinePunctuationModelId = punctuation.descriptor.modelId,
                performanceProfile = settings.performanceProfile.name,
                requestedThreads = settings.requestedThreads,
                effectiveThreads = effectiveThreads,
                vadThreshold = settings.vad.threshold,
                vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
                vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
                vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
                audioDurationMs = audioDurationMs,
                wallTimeMs = measured.wallTimeMs,
                cpuTimeMs = measured.cpuTimeMs,
                rtf =
                    if (audioDurationMs > 0L) {
                        measured.wallTimeMs.toDouble() / audioDurationMs.toDouble()
                    } else {
                        0.0
                    },
                peakPssKb = measured.peakPssKb,
                thermalStatusStart = measured.thermalStatusStart,
                thermalStatusMax = measured.thermalStatusMax,
                firstPartialAudioMs = null,
                firstPartialComputeMs = null,
                finalizationLatencyMs = null,
                streamingProbeSpeechMs = null,
                streamingProbeError = null,
                outputCharacterCount = text.codePointCount(0, text.length),
                outputText = text,
                cer = normalizedReference?.let { speechBenchmarkCer(it, text) },
                wer = normalizedReference?.let { speechBenchmarkWer(it, text) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return SpeechBenchmarkCaseResult(
                benchmarkTrack = SpeechBenchmarkTrack.OFFLINE,
                modelId = secondPass.descriptor.modelId,
                displayName = secondPass.descriptor.displayName,
                version = secondPass.descriptor.version,
                revision = secondPass.descriptor.revision,
                manifestDigest = secondPass.manifestDigest,
                runtimeModelType = secondPass.descriptor.runtimeModelType,
                quantization = secondPass.descriptor.quantization,
                pipelineFirstPassModelId = firstPass.descriptor.modelId,
                pipelinePunctuationModelId = punctuation.descriptor.modelId,
                performanceProfile = settings.performanceProfile.name,
                requestedThreads = settings.requestedThreads,
                effectiveThreads = effectiveThreads,
                vadThreshold = settings.vad.threshold,
                vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
                vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
                vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
                audioDurationMs = audioDurationMs,
                wallTimeMs = 0L,
                cpuTimeMs = 0L,
                rtf = 0.0,
                peakPssKb = 0L,
                thermalStatusStart = currentThermalStatus(),
                thermalStatusMax = currentThermalStatus(),
                firstPartialAudioMs = null,
                firstPartialComputeMs = null,
                finalizationLatencyMs = null,
                streamingProbeSpeechMs = null,
                streamingProbeError = null,
                outputCharacterCount = 0,
                outputText = "",
                cer = null,
                wer = null,
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            leases.asReversed().forEach { lease ->
                runCatching { lease.close() }
            }
        }
    }

    private suspend fun runModelCase(
        recordingId: String,
        totalSampleCount: Long,
        referenceText: String?,
        vad: ActiveModel,
        asr: ActiveModel,
        punctuation: ActiveModel,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
    ): SpeechBenchmarkCaseResult {
        require(asr.descriptor.kind == ModelKind.ASR_STREAMING)
        val leases =
            listOf(vad, asr, punctuation).map { model ->
                modelUseRegistry.acquire(
                    modelId = model.descriptor.modelId,
                    version = model.descriptor.version,
                    revision = model.descriptor.revision,
                )
            }

        val audioDurationMs =
            totalSampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ

        try {
            val measured =
                measureResources {
                    FastTranscriptionPipeline(
                        pcmSourceResolver = pcmSourceResolver,
                        vadEngineFactory =
                            engineProvider.vadFactory(
                                model = vad,
                                numThreads = effectiveThreads,
                                vadSettings = settings.vad,
                            ),
                        asrEngineFactory =
                            engineProvider.firstPassFactory(
                                model = asr,
                                numThreads = effectiveThreads,
                            ),
                        punctuationEngineFactory =
                            engineProvider.punctuationFactory(
                                model = punctuation,
                                numThreads = effectiveThreads,
                            ),
                    ).transcribe(recordingId)
                }
            val text =
                measured.value.transcriptSegments.joinToString(separator = "") {
                    it.finalText
                }
            val streamingProbe =
                measured.value.speechSegments.firstOrNull()?.let { firstSpeechSegment ->
                    try {
                        runStreamingLatencyProbe(
                            recordingId = recordingId,
                            asr = asr,
                            effectiveThreads = effectiveThreads,
                            speechSegment = firstSpeechSegment,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        StreamingLatencyProbe(
                            speechDurationMs =
                                samplesToBenchmarkMs(firstSpeechSegment.sampleCount),
                            firstPartialAudioMs = null,
                            firstPartialComputeMs = null,
                            finalizationLatencyMs = null,
                            error = error.message ?: error::class.java.simpleName,
                        )
                    }
                }
            val normalizedReference = referenceText?.takeIf { it.isNotBlank() }
            return SpeechBenchmarkCaseResult(
                benchmarkTrack = SpeechBenchmarkTrack.REALTIME,
                modelId = asr.descriptor.modelId,
                displayName = asr.descriptor.displayName,
                version = asr.descriptor.version,
                revision = asr.descriptor.revision,
                manifestDigest = asr.manifestDigest,
                runtimeModelType = asr.descriptor.runtimeModelType,
                quantization = asr.descriptor.quantization,
                pipelineFirstPassModelId = asr.descriptor.modelId,
                pipelinePunctuationModelId = punctuation.descriptor.modelId,
                performanceProfile = settings.performanceProfile.name,
                requestedThreads = settings.requestedThreads,
                effectiveThreads = effectiveThreads,
                vadThreshold = settings.vad.threshold,
                vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
                vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
                vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
                audioDurationMs = audioDurationMs,
                wallTimeMs = measured.wallTimeMs,
                cpuTimeMs = measured.cpuTimeMs,
                rtf =
                    if (audioDurationMs > 0L) {
                        measured.wallTimeMs.toDouble() / audioDurationMs.toDouble()
                    } else {
                        0.0
                    },
                peakPssKb = measured.peakPssKb,
                thermalStatusStart = measured.thermalStatusStart,
                thermalStatusMax = measured.thermalStatusMax,
                firstPartialAudioMs = streamingProbe?.firstPartialAudioMs,
                firstPartialComputeMs = streamingProbe?.firstPartialComputeMs,
                finalizationLatencyMs = streamingProbe?.finalizationLatencyMs,
                streamingProbeSpeechMs = streamingProbe?.speechDurationMs,
                streamingProbeError = streamingProbe?.error,
                outputCharacterCount = text.codePointCount(0, text.length),
                outputText = text,
                cer =
                    normalizedReference?.let {
                        speechBenchmarkCer(it, text)
                    },
                wer =
                    normalizedReference?.let {
                        speechBenchmarkWer(it, text)
                    },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return SpeechBenchmarkCaseResult(
                benchmarkTrack = SpeechBenchmarkTrack.REALTIME,
                modelId = asr.descriptor.modelId,
                displayName = asr.descriptor.displayName,
                version = asr.descriptor.version,
                revision = asr.descriptor.revision,
                manifestDigest = asr.manifestDigest,
                runtimeModelType = asr.descriptor.runtimeModelType,
                quantization = asr.descriptor.quantization,
                pipelineFirstPassModelId = asr.descriptor.modelId,
                pipelinePunctuationModelId = punctuation.descriptor.modelId,
                performanceProfile = settings.performanceProfile.name,
                requestedThreads = settings.requestedThreads,
                effectiveThreads = effectiveThreads,
                vadThreshold = settings.vad.threshold,
                vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
                vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
                vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
                audioDurationMs = audioDurationMs,
                wallTimeMs = 0L,
                cpuTimeMs = 0L,
                rtf = 0.0,
                peakPssKb = 0L,
                thermalStatusStart = currentThermalStatus(),
                thermalStatusMax = currentThermalStatus(),
                firstPartialAudioMs = null,
                firstPartialComputeMs = null,
                finalizationLatencyMs = null,
                streamingProbeSpeechMs = null,
                streamingProbeError = null,
                outputCharacterCount = 0,
                outputText = "",
                cer = null,
                wer = null,
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            leases.asReversed().forEach { lease ->
                runCatching { lease.close() }
            }
        }
    }

    private fun missingModelResult(
        track: SpeechBenchmarkTrack,
        modelId: String,
        totalSampleCount: Long,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
        pipelineFirstPassModelId: String,
        pipelinePunctuationModelId: String,
    ): SpeechBenchmarkCaseResult =
        SpeechBenchmarkCaseResult(
            benchmarkTrack = track,
            modelId = modelId,
            displayName = modelId,
            version = "",
            revision = 0L,
            manifestDigest = "",
            runtimeModelType = null,
            quantization = null,
            pipelineFirstPassModelId = pipelineFirstPassModelId,
            pipelinePunctuationModelId = pipelinePunctuationModelId,
            performanceProfile = settings.performanceProfile.name,
            requestedThreads = settings.requestedThreads,
            effectiveThreads = effectiveThreads,
            vadThreshold = settings.vad.threshold,
            vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
            vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
            vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
            audioDurationMs =
                totalSampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ,
            wallTimeMs = 0L,
            cpuTimeMs = 0L,
            rtf = 0.0,
            peakPssKb = 0L,
            thermalStatusStart = currentThermalStatus(),
            thermalStatusMax = currentThermalStatus(),
            firstPartialAudioMs = null,
            firstPartialComputeMs = null,
            finalizationLatencyMs = null,
            streamingProbeSpeechMs = null,
            streamingProbeError = null,
            outputCharacterCount = 0,
            outputText = "",
            cer = null,
            wer = null,
            error = "model is not active",
        )

    private suspend fun runStreamingLatencyProbe(
        recordingId: String,
        asr: ActiveModel,
        effectiveThreads: Int,
        speechSegment: io.github.ioannes78.voica.transcript.SpeechSegment,
    ): StreamingLatencyProbe {
        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source is unavailable for streaming latency probe")
        val engine =
            try {
                engineProvider.firstPassFactory(
                    model = asr,
                    numThreads = effectiveThreads,
                ).open()
            } catch (error: Throwable) {
                source.close()
                throw error
            }

        try {
            require(engine.capabilities.supportsStreaming) {
                "streaming latency probe requires a streaming ASR engine"
            }
            require(engine.capabilities.supportsPartial) {
                "streaming latency probe requires partial hypotheses"
            }

            val session = engine.openSession()
            try {
                val buffer = ShortArray(STREAMING_PROBE_CHUNK_SAMPLES)
                var sourceCursor = 0L
                var acceptedSpeechSamples = 0L
                var probeStartNs: Long? = null
                var firstPartialAudioMs: Long? = null
                var firstPartialComputeMs: Long? = null

                while (sourceCursor < speechSegment.endSampleIndexExclusive) {
                    val read = source.read(buffer) ?: break
                    require(read.startSampleIndex == sourceCursor) {
                        "PCM source is not sequential during streaming latency probe"
                    }
                    val readEnd =
                        Math.addExact(
                            read.startSampleIndex,
                            read.sampleCount.toLong(),
                        )

                    val overlapStart =
                        maxOf(speechSegment.startSampleIndex, read.startSampleIndex)
                    val overlapEnd =
                        minOf(speechSegment.endSampleIndexExclusive, readEnd)
                    if (overlapEnd > overlapStart) {
                        if (probeStartNs == null) {
                            probeStartNs = SystemClock.elapsedRealtimeNanos()
                        }
                        val targetOffset =
                            Math.toIntExact(overlapStart - read.startSampleIndex)
                        val count = Math.toIntExact(overlapEnd - overlapStart)
                        session.acceptSamples(
                            samples = buffer,
                            offset = targetOffset,
                            count = count,
                        )
                        acceptedSpeechSamples =
                            Math.addExact(acceptedSpeechSamples, count.toLong())
                        val partial = session.decode()
                        if (firstPartialAudioMs == null && partial.text.isNotBlank()) {
                            firstPartialAudioMs =
                                samplesToBenchmarkMs(acceptedSpeechSamples)
                            firstPartialComputeMs =
                                (SystemClock.elapsedRealtimeNanos() -
                                    checkNotNull(probeStartNs)) / 1_000_000L
                        }
                    }

                    sourceCursor = readEnd
                }

                require(
                    acceptedSpeechSamples == speechSegment.sampleCount,
                ) {
                    "streaming latency probe did not consume the complete first speech segment"
                }

                val finalizeStartNs = SystemClock.elapsedRealtimeNanos()
                val final = session.finishInput()
                val finalizationLatencyMs =
                    (SystemClock.elapsedRealtimeNanos() - finalizeStartNs) / 1_000_000L
                check(final.isFinal) {
                    "streaming latency probe did not produce a final hypothesis"
                }

                return StreamingLatencyProbe(
                    speechDurationMs = samplesToBenchmarkMs(acceptedSpeechSamples),
                    firstPartialAudioMs = firstPartialAudioMs,
                    firstPartialComputeMs = firstPartialComputeMs,
                    finalizationLatencyMs = finalizationLatencyMs,
                    error = null,
                )
            } finally {
                session.close()
            }
        } finally {
            engine.close()
            source.close()
        }
    }

    private fun samplesToBenchmarkMs(sampleCount: Long): Long =
        sampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ

    private data class StreamingLatencyProbe(
        val speechDurationMs: Long,
        val firstPartialAudioMs: Long?,
        val firstPartialComputeMs: Long?,
        val finalizationLatencyMs: Long?,
        val error: String?,
    )

    private suspend fun <T> measureResources(
        block: suspend () -> T,
    ): MeasuredValue<T> =
        coroutineScope {
            val peakPssKb = AtomicLong(Debug.getPss())
            val startThermal = currentThermalStatus()
            val maxThermal = AtomicInteger(startThermal ?: -1)
            val sampler =
                launch(Dispatchers.Default) {
                    while (isActive) {
                        peakPssKb.accumulateAndGet(Debug.getPss(), ::maxOf)
                        currentThermalStatus()?.let { thermal ->
                            maxThermal.accumulateAndGet(thermal, ::maxOf)
                        }
                        delay(RESOURCE_SAMPLE_INTERVAL_MS)
                    }
                }

            val wallStart = SystemClock.elapsedRealtimeNanos()
            val cpuStart = android.os.Process.getElapsedCpuTime()
            try {
                val value = block()
                val wallTimeMs =
                    (SystemClock.elapsedRealtimeNanos() - wallStart) / 1_000_000L
                val cpuTimeMs =
                    android.os.Process.getElapsedCpuTime() - cpuStart
                peakPssKb.accumulateAndGet(Debug.getPss(), ::maxOf)
                currentThermalStatus()?.let { thermal ->
                    maxThermal.accumulateAndGet(thermal, ::maxOf)
                }
                MeasuredValue(
                    value = value,
                    wallTimeMs = wallTimeMs,
                    cpuTimeMs = cpuTimeMs,
                    peakPssKb = peakPssKb.get(),
                    thermalStatusStart = startThermal,
                    thermalStatusMax =
                        maxThermal.get().takeIf { it >= 0 },
                )
            } finally {
                sampler.cancelAndJoin()
            }
        }

    private fun environment(): SpeechBenchmarkEnvironment {
        val activityManager =
            application.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val packageInfo =
            application.packageManager.getPackageInfo(
                application.packageName,
                0,
            )

        return SpeechBenchmarkEnvironment(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            hardware = Build.HARDWARE,
            socManufacturer =
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null,
            socModel =
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = memoryInfo.totalMem,
            logicalProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            appVersionName = packageInfo.versionName,
        )
    }

    private fun currentThermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= 29) {
            application.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else {
            null
        }

    private data class MeasuredValue<T>(
        val value: T,
        val wallTimeMs: Long,
        val cpuTimeMs: Long,
        val peakPssKb: Long,
        val thermalStatusStart: Int?,
        val thermalStatusMax: Int?,
    )

    private companion object {
        const val RESOURCE_SAMPLE_INTERVAL_MS = 250L
        const val STREAMING_PROBE_CHUNK_SAMPLES = 1_600
    }
}

internal fun speechBenchmarkCer(
    reference: String,
    hypothesis: String,
): Double? {
    val referenceUnits = normalizeCerUnits(reference)
    if (referenceUnits.isEmpty()) return null
    val hypothesisUnits = normalizeCerUnits(hypothesis)
    return editDistance(referenceUnits, hypothesisUnits).toDouble() /
        referenceUnits.size.toDouble()
}

internal fun speechBenchmarkWer(
    reference: String,
    hypothesis: String,
): Double? {
    val referenceWords = normalizeWerWords(reference)
    if (referenceWords.isEmpty()) return null
    val hypothesisWords = normalizeWerWords(hypothesis)
    return editDistance(referenceWords, hypothesisWords).toDouble() /
        referenceWords.size.toDouble()
}

private fun normalizeCerUnits(value: String): List<Int> =
    value.lowercase(Locale.ROOT)
        .codePoints()
        .filter { codePoint ->
            !Character.isWhitespace(codePoint) &&
                !isPunctuation(codePoint)
        }
        .toArray()
        .toList()

private fun normalizeWerWords(value: String): List<String> =
    buildString {
        value.lowercase(Locale.ROOT).codePoints().forEach { codePoint ->
            if (Character.isWhitespace(codePoint) || isPunctuation(codePoint)) {
                append(' ')
            } else {
                appendCodePoint(codePoint)
            }
        }
    }
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

private fun isPunctuation(codePoint: Int): Boolean =
    when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }

private fun <T> editDistance(
    reference: List<T>,
    hypothesis: List<T>,
): Int {
    if (reference.isEmpty()) return hypothesis.size
    if (hypothesis.isEmpty()) return reference.size

    var previous = IntArray(hypothesis.size + 1) { it }
    var current = IntArray(hypothesis.size + 1)
    reference.forEachIndexed { referenceIndex, referenceValue ->
        current[0] = referenceIndex + 1
        hypothesis.forEachIndexed { hypothesisIndex, hypothesisValue ->
            val substitutionCost =
                if (referenceValue == hypothesisValue) 0 else 1
            current[hypothesisIndex + 1] =
                minOf(
                    previous[hypothesisIndex + 1] + 1,
                    current[hypothesisIndex] + 1,
                    previous[hypothesisIndex] + substitutionCost,
                )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[hypothesis.size]
}
