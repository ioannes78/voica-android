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
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.transcript.DiarizationChunkStitchInput
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.SpeakerStitchingResult
import io.github.ioannes78.voica.transcript.consumeDiarizationWindowsSequentially
import io.github.ioannes78.voica.transcript.planDiarizationWindows
import io.github.ioannes78.voica.transcript.stitchDiarizationChunks
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DiarizationSpeakerCountMetrics(
    val absoluteError: Int?,
    val extraSpeakers: Int?,
    val missingSpeakers: Int?,
)

internal fun evaluateDiarizationSpeakerCount(
    referenceSpeakerCount: Int?,
    detectedSpeakerCount: Int,
): DiarizationSpeakerCountMetrics {
    require(detectedSpeakerCount >= 0)
    if (referenceSpeakerCount == null) {
        return DiarizationSpeakerCountMetrics(null, null, null)
    }
    require(referenceSpeakerCount > 0)
    return DiarizationSpeakerCountMetrics(
        absoluteError = kotlin.math.abs(detectedSpeakerCount - referenceSpeakerCount),
        extraSpeakers = (detectedSpeakerCount - referenceSpeakerCount).coerceAtLeast(0),
        missingSpeakers = (referenceSpeakerCount - detectedSpeakerCount).coerceAtLeast(0),
    )
}

data class DiarizationBenchmarkCaseResult(
    val embeddingModelId: String,
    val displayName: String,
    val version: String,
    val revision: Long,
    val manifestDigest: String,
    val segmentationModelId: String,
    val segmentationModelVersion: String,
    val segmentationModelRevision: Long,
    val performanceProfile: String,
    val requestedThreads: Int?,
    val effectiveThreads: Int,
    val vadThreshold: Float,
    val vadMinSilenceSeconds: Float,
    val vadMinSpeechSeconds: Float,
    val vadMaxSpeechSeconds: Float,
    val chunkSizeSamples: Long,
    val chunkOverlapSamples: Long,
    val stitchingCosineThreshold: Float,
    val audioDurationMs: Long,
    val wallTimeMs: Long,
    val cpuTimeMs: Long,
    val rtf: Double,
    val peakPssKb: Long,
    val thermalStatusStart: Int?,
    val thermalStatusMax: Int?,
    val speechSegmentCount: Int,
    val windowCount: Int,
    val detectedSpeakerCount: Int,
    val referenceSpeakerCount: Int?,
    val speakerCountAbsoluteError: Int?,
    val fragmentationExtraSpeakers: Int?,
    val mergeMissingSpeakers: Int?,
    val turnCount: Int,
    val overlapTurnCount: Int,
    val error: String? = null,
)

data class DiarizationBenchmarkReport(
    val schemaVersion: Int = 1,
    val createdAt: String,
    val recordingId: String,
    val environment: SpeechBenchmarkEnvironment,
    val referenceSpeakerCount: Int?,
    val cases: List<DiarizationBenchmarkCaseResult>,
) {
    fun toJson(): String =
        JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("createdAt", createdAt)
            .put("recordingId", recordingId)
            .put(
                "environment",
                JSONObject()
                    .put("manufacturer", environment.manufacturer)
                    .put("model", environment.model)
                    .put("hardware", environment.hardware)
                    .put("socManufacturer", environment.socManufacturer ?: JSONObject.NULL)
                    .put("socModel", environment.socModel ?: JSONObject.NULL)
                    .put("sdkInt", environment.sdkInt)
                    .put("supportedAbis", JSONArray(environment.supportedAbis))
                    .put("totalRamBytes", environment.totalRamBytes)
                    .put("logicalProcessors", environment.logicalProcessors)
                    .put("appVersionCode", environment.appVersionCode)
                    .put("appVersionName", environment.appVersionName ?: JSONObject.NULL),
            )
            .put("referenceSpeakerCount", referenceSpeakerCount ?: JSONObject.NULL)
            .put(
                "cases",
                JSONArray().also { array ->
                    cases.forEach { result ->
                        array.put(
                            JSONObject()
                                .put("embeddingModelId", result.embeddingModelId)
                                .put("displayName", result.displayName)
                                .put("version", result.version)
                                .put("revision", result.revision)
                                .put("manifestDigest", result.manifestDigest)
                                .put("segmentationModelId", result.segmentationModelId)
                                .put("segmentationModelVersion", result.segmentationModelVersion)
                                .put("segmentationModelRevision", result.segmentationModelRevision)
                                .put("performanceProfile", result.performanceProfile)
                                .put("requestedThreads", result.requestedThreads ?: JSONObject.NULL)
                                .put("effectiveThreads", result.effectiveThreads)
                                .put("vadThreshold", result.vadThreshold)
                                .put("vadMinSilenceSeconds", result.vadMinSilenceSeconds)
                                .put("vadMinSpeechSeconds", result.vadMinSpeechSeconds)
                                .put("vadMaxSpeechSeconds", result.vadMaxSpeechSeconds)
                                .put("chunkSizeSamples", result.chunkSizeSamples)
                                .put("chunkOverlapSamples", result.chunkOverlapSamples)
                                .put("stitchingCosineThreshold", result.stitchingCosineThreshold)
                                .put("audioDurationMs", result.audioDurationMs)
                                .put("wallTimeMs", result.wallTimeMs)
                                .put("cpuTimeMs", result.cpuTimeMs)
                                .put("rtf", result.rtf)
                                .put("peakPssKb", result.peakPssKb)
                                .put("thermalStatusStart", result.thermalStatusStart ?: JSONObject.NULL)
                                .put("thermalStatusMax", result.thermalStatusMax ?: JSONObject.NULL)
                                .put("speechSegmentCount", result.speechSegmentCount)
                                .put("windowCount", result.windowCount)
                                .put("detectedSpeakerCount", result.detectedSpeakerCount)
                                .put("referenceSpeakerCount", result.referenceSpeakerCount ?: JSONObject.NULL)
                                .put("speakerCountAbsoluteError", result.speakerCountAbsoluteError ?: JSONObject.NULL)
                                .put("fragmentationExtraSpeakers", result.fragmentationExtraSpeakers ?: JSONObject.NULL)
                                .put("mergeMissingSpeakers", result.mergeMissingSpeakers ?: JSONObject.NULL)
                                .put("turnCount", result.turnCount)
                                .put("overlapTurnCount", result.overlapTurnCount)
                                .put("error", result.error ?: JSONObject.NULL),
                        )
                    }
                },
            )
            .toString(2)
}

class DiarizationBenchmarkRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val engineProvider: Stage9DiarizationEngineProvider,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    suspend fun runEmbeddingComparison(
        recordingId: String,
        referenceSpeakerCount: Int? = null,
        embeddingModelIds: List<String> = Stage13ASpeakerEmbeddingModelIds.ALL,
    ): DiarizationBenchmarkReport {
        require(recordingId.isNotBlank())
        require(referenceSpeakerCount == null || referenceSpeakerCount > 0)
        require(embeddingModelIds.isNotEmpty())
        require(embeddingModelIds.distinct().size == embeddingModelIds.size)

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
                ?: error("diarization benchmark requires active VAD model")
        val segmentation =
            modelManager.activeModel(Stage9ModelIds.SEGMENTATION)
                ?: error("diarization benchmark requires active segmentation model")

        require(vad.descriptor.kind == ModelKind.VAD)
        require(segmentation.descriptor.kind == ModelKind.SPEAKER)
        require(
            segmentation.descriptor.speakerRole ==
                SpeakerModelRole.DIARIZATION_SEGMENTATION,
        )

        val config = DiarizationConfig()
        val cases =
            embeddingModelIds.map { modelId ->
                val embedding = modelManager.activeModel(modelId)
                if (embedding == null) {
                    missingCase(
                        modelId = modelId,
                        segmentation = segmentation,
                        totalSampleCount = totalSampleCount,
                        referenceSpeakerCount = referenceSpeakerCount,
                        settings = settings,
                        effectiveThreads = performance.effectiveThreads,
                        config = config,
                    )
                } else {
                    runCase(
                        recordingId = recordingId,
                        totalSampleCount = totalSampleCount,
                        referenceSpeakerCount = referenceSpeakerCount,
                        vad = vad,
                        segmentation = segmentation,
                        embedding = embedding,
                        settings = settings,
                        effectiveThreads = performance.effectiveThreads,
                        config = config,
                    )
                }
            }

        return DiarizationBenchmarkReport(
            createdAt = Instant.now().toString(),
            recordingId = recordingId,
            environment = environment(),
            referenceSpeakerCount = referenceSpeakerCount,
            cases = cases,
        )
    }

    private suspend fun runCase(
        recordingId: String,
        totalSampleCount: Long,
        referenceSpeakerCount: Int?,
        vad: ActiveModel,
        segmentation: ActiveModel,
        embedding: ActiveModel,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
        config: DiarizationConfig,
    ): DiarizationBenchmarkCaseResult {
        require(embedding.descriptor.kind == ModelKind.SPEAKER)
        require(embedding.descriptor.speakerRole == SpeakerModelRole.EMBEDDING)
        val leases =
            listOf(vad, segmentation, embedding).map { model ->
                modelUseRegistry.acquire(
                    modelId = model.descriptor.modelId,
                    version = model.descriptor.version,
                    revision = model.descriptor.revision,
                )
            }
        val audioDurationMs =
            totalSampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ

        try {
            engineProvider.validateBundle(segmentation, embedding)
            val measured =
                measureResources {
                    runPipeline(
                        recordingId = recordingId,
                        totalSampleCount = totalSampleCount,
                        vad = vad,
                        segmentation = segmentation,
                        embedding = embedding,
                        settings = settings,
                        effectiveThreads = effectiveThreads,
                        config = config,
                    )
                }
            val stitched = measured.value.stitched
            val countMetrics =
                evaluateDiarizationSpeakerCount(
                    referenceSpeakerCount = referenceSpeakerCount,
                    detectedSpeakerCount = stitched.speakerCount,
                )
            return DiarizationBenchmarkCaseResult(
                embeddingModelId = embedding.descriptor.modelId,
                displayName = embedding.descriptor.displayName,
                version = embedding.descriptor.version,
                revision = embedding.descriptor.revision,
                manifestDigest = embedding.manifestDigest,
                segmentationModelId = segmentation.descriptor.modelId,
                segmentationModelVersion = segmentation.descriptor.version,
                segmentationModelRevision = segmentation.descriptor.revision,
                performanceProfile = settings.performanceProfile.name,
                requestedThreads = settings.requestedThreads,
                effectiveThreads = effectiveThreads,
                vadThreshold = settings.vad.threshold,
                vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
                vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
                vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
                chunkSizeSamples = config.chunkSizeSamples,
                chunkOverlapSamples = config.chunkOverlapSamples,
                stitchingCosineThreshold = config.stitchingCosineThreshold,
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
                speechSegmentCount = measured.value.speechSegmentCount,
                windowCount = measured.value.windowCount,
                detectedSpeakerCount = stitched.speakerCount,
                referenceSpeakerCount = referenceSpeakerCount,
                speakerCountAbsoluteError = countMetrics.absoluteError,
                fragmentationExtraSpeakers = countMetrics.extraSpeakers,
                mergeMissingSpeakers = countMetrics.missingSpeakers,
                turnCount = stitched.turns.size,
                overlapTurnCount = stitched.turns.count { it.overlap },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failedCase(
                embedding = embedding,
                segmentation = segmentation,
                totalSampleCount = totalSampleCount,
                referenceSpeakerCount = referenceSpeakerCount,
                settings = settings,
                effectiveThreads = effectiveThreads,
                config = config,
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            leases.asReversed().forEach { lease ->
                runCatching { lease.close() }
            }
        }
    }

    private suspend fun runPipeline(
        recordingId: String,
        totalSampleCount: Long,
        vad: ActiveModel,
        segmentation: ActiveModel,
        embedding: ActiveModel,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
        config: DiarizationConfig,
    ): PipelineOutput {
        val vadSource =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before benchmark VAD")
        val vadEngine =
            try {
                engineProvider.vadFactory(
                    model = vad,
                    numThreads = effectiveThreads,
                    vadSettings = settings.vad,
                ).open()
            } catch (error: Throwable) {
                vadSource.close()
                throw error
            }

        val speechSegments =
            try {
                vadSource.use { source ->
                    require(source.totalSampleCount == totalSampleCount)
                    vadEngine.analyze(source)
                }
            } finally {
                vadEngine.close()
            }

        val ranges =
            planDiarizationWindows(
                speechSegments = speechSegments,
                totalSampleCount = totalSampleCount,
                config = config,
            )
        if (ranges.isEmpty()) {
            return PipelineOutput(
                speechSegmentCount = speechSegments.size,
                windowCount = 0,
                stitched = stitchDiarizationChunks(emptyList(), config),
            )
        }

        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before benchmark diarization")
        require(source.totalSampleCount == totalSampleCount)

        val diarizationEngine =
            engineProvider.diarizationEngine(
                segmentation = segmentation,
                embedding = embedding,
                numThreads = effectiveThreads,
            )
        val embeddingEngine =
            try {
                engineProvider.embeddingEngine(
                    model = embedding,
                    numThreads = effectiveThreads,
                )
            } catch (error: Throwable) {
                diarizationEngine.close()
                source.close()
                throw error
            }

        val chunks = ArrayList<DiarizationChunkStitchInput>(ranges.size)
        try {
            source.use {
                consumeDiarizationWindowsSequentially(
                    source = it,
                    ranges = ranges,
                ) { index, window ->
                    currentCoroutineContext().ensureActive()
                    val result =
                        diarizationEngine.diarize(
                            window = window,
                            config = config,
                        )
                    val anchors =
                        buildSpeakerAnchorEmbeddings(
                            windowStartSampleIndex = window.startSampleIndex,
                            windowSamples = window.samples,
                            turns = result.turns,
                            embeddingEngine = embeddingEngine,
                            config = config,
                        )
                    chunks +=
                        DiarizationChunkStitchInput(
                            chunkIndex = index,
                            turns = result.turns,
                            anchors = anchors,
                        )
                }
            }
        } finally {
            runCatching { embeddingEngine.close() }
            runCatching { diarizationEngine.close() }
        }

        return PipelineOutput(
            speechSegmentCount = speechSegments.size,
            windowCount = ranges.size,
            stitched = stitchDiarizationChunks(chunks, config),
        )
    }

    private fun missingCase(
        modelId: String,
        segmentation: ActiveModel,
        totalSampleCount: Long,
        referenceSpeakerCount: Int?,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
        config: DiarizationConfig,
    ): DiarizationBenchmarkCaseResult =
        failedCase(
            embedding = null,
            missingModelId = modelId,
            segmentation = segmentation,
            totalSampleCount = totalSampleCount,
            referenceSpeakerCount = referenceSpeakerCount,
            settings = settings,
            effectiveThreads = effectiveThreads,
            config = config,
            error = "model is not active",
        )

    private fun failedCase(
        embedding: ActiveModel?,
        segmentation: ActiveModel,
        totalSampleCount: Long,
        referenceSpeakerCount: Int?,
        settings: LocalSpeechSettings,
        effectiveThreads: Int,
        config: DiarizationConfig,
        error: String,
        missingModelId: String? = null,
    ): DiarizationBenchmarkCaseResult =
        DiarizationBenchmarkCaseResult(
            embeddingModelId = embedding?.descriptor?.modelId ?: checkNotNull(missingModelId),
            displayName = embedding?.descriptor?.displayName ?: checkNotNull(missingModelId),
            version = embedding?.descriptor?.version.orEmpty(),
            revision = embedding?.descriptor?.revision ?: 0L,
            manifestDigest = embedding?.manifestDigest.orEmpty(),
            segmentationModelId = segmentation.descriptor.modelId,
            segmentationModelVersion = segmentation.descriptor.version,
            segmentationModelRevision = segmentation.descriptor.revision,
            performanceProfile = settings.performanceProfile.name,
            requestedThreads = settings.requestedThreads,
            effectiveThreads = effectiveThreads,
            vadThreshold = settings.vad.threshold,
            vadMinSilenceSeconds = settings.vad.minSilenceDurationSeconds,
            vadMinSpeechSeconds = settings.vad.minSpeechDurationSeconds,
            vadMaxSpeechSeconds = settings.vad.maxSpeechDurationSeconds,
            chunkSizeSamples = config.chunkSizeSamples,
            chunkOverlapSamples = config.chunkOverlapSamples,
            stitchingCosineThreshold = config.stitchingCosineThreshold,
            audioDurationMs =
                totalSampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ,
            wallTimeMs = 0L,
            cpuTimeMs = 0L,
            rtf = 0.0,
            peakPssKb = 0L,
            thermalStatusStart = currentThermalStatus(),
            thermalStatusMax = currentThermalStatus(),
            speechSegmentCount = 0,
            windowCount = 0,
            detectedSpeakerCount = 0,
            referenceSpeakerCount = referenceSpeakerCount,
            speakerCountAbsoluteError = null,
            fragmentationExtraSpeakers = null,
            mergeMissingSpeakers = null,
            turnCount = 0,
            overlapTurnCount = 0,
            error = error,
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
                    thermalStatusMax = maxThermal.get().takeIf { it >= 0 },
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

    private data class PipelineOutput(
        val speechSegmentCount: Int,
        val windowCount: Int,
        val stitched: SpeakerStitchingResult,
    )

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
    }
}
