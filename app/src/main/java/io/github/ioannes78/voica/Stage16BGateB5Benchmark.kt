package io.github.ioannes78.voica

import android.app.Application
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory
import io.github.ioannes78.voica.transcript.SpeechSegment
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal const val STAGE16B_GATE_B_B5_MAX_ASR_INPUT_SECONDS = 25
internal const val STAGE16B_GATE_B_B5_MAX_RECORDING_SECONDS = 300
private const val STAGE16B_GATE_B_B5_PLAN_VERSION = 1
private const val STAGE16B_GATE_B_B5_PLAN_FILE = "stage16b_gate_b5_plan.txt"
private const val STAGE16B_GATE_B_B5_PSS_SAMPLE_INTERVAL_MS = 100L
private const val STAGE16B_GATE_B_B5_WARM_SETTLE_MS = 500L

internal data class Stage16BGateB5Plan(
    val recordingId: String,
    val canonicalSha256: String,
    val totalSampleCount: Long,
    val vadModelId: String,
    val vadModelVersion: String,
    val vadModelRevision: String,
    val vadWallMillis: Long,
    val numThreads: Int,
    val frozenBoundaries: List<SpeechSegment>,
) {
    init {
        require(recordingId.isNotBlank())
        require(canonicalSha256.isNotBlank())
        require(totalSampleCount > 0L)
        require(vadModelId == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
        require(vadModelVersion.isNotBlank())
        require(vadModelRevision.isNotBlank())
        require(vadWallMillis >= 0L)
        require(numThreads > 0)
        require(frozenBoundaries.isNotEmpty())
        var previousEnd = 0L
        frozenBoundaries.forEach { boundary ->
            require(boundary.startSampleIndex >= previousEnd)
            require(boundary.endSampleIndexExclusive <= totalSampleCount)
            require(
                boundary.sampleCount <=
                    CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() *
                        STAGE16B_GATE_B_B5_MAX_ASR_INPUT_SECONDS,
            )
            previousEnd = boundary.endSampleIndexExclusive
        }
    }

    val speechSampleCount: Long
        get() = frozenBoundaries.sumOf(SpeechSegment::sampleCount)

    val speechDurationSeconds: Double
        get() = speechSampleCount.toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()
}

internal object Stage16BGateB5PlanStore {
    fun write(
        application: Application,
        plan: Stage16BGateB5Plan,
    ) {
        val target = File(application.cacheDir, STAGE16B_GATE_B_B5_PLAN_FILE)
        val temporary = File(application.cacheDir, "$STAGE16B_GATE_B_B5_PLAN_FILE.tmp")
        temporary.writeText(encode(plan))
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            check(temporary.delete()) { "failed to remove temporary B5 plan" }
        }
    }

    fun read(application: Application): Stage16BGateB5Plan? {
        val file = File(application.cacheDir, STAGE16B_GATE_B_B5_PLAN_FILE)
        if (!file.isFile) return null
        return decode(file.readText())
    }

    internal fun encode(plan: Stage16BGateB5Plan): String =
        buildString {
            appendLine("version=$STAGE16B_GATE_B_B5_PLAN_VERSION")
            appendLine("recordingId=${plan.recordingId}")
            appendLine("canonicalSha256=${plan.canonicalSha256}")
            appendLine("totalSampleCount=${plan.totalSampleCount}")
            appendLine("vadModelId=${plan.vadModelId}")
            appendLine("vadModelVersion=${plan.vadModelVersion}")
            appendLine("vadModelRevision=${plan.vadModelRevision}")
            appendLine("vadWallMillis=${plan.vadWallMillis}")
            appendLine("numThreads=${plan.numThreads}")
            plan.frozenBoundaries.forEach { boundary ->
                appendLine("boundary=${boundary.startSampleIndex}:${boundary.endSampleIndexExclusive}")
            }
        }

    internal fun decode(text: String): Stage16BGateB5Plan {
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        val scalars =
            lines
                .filterNot { it.startsWith("boundary=") }
                .associate { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "invalid B5 plan line" }
                    line.substring(0, separator) to line.substring(separator + 1)
                }
        require(scalars["version"]?.toInt() == STAGE16B_GATE_B_B5_PLAN_VERSION) {
            "unsupported B5 plan version"
        }
        val boundaries =
            lines.filter { it.startsWith("boundary=") }.map { line ->
                val value = line.substringAfter('=')
                val pieces = value.split(':')
                require(pieces.size == 2)
                SpeechSegment(
                    startSampleIndex = pieces[0].toLong(),
                    endSampleIndexExclusive = pieces[1].toLong(),
                )
            }
        return Stage16BGateB5Plan(
            recordingId = scalars.getValue("recordingId"),
            canonicalSha256 = scalars.getValue("canonicalSha256"),
            totalSampleCount = scalars.getValue("totalSampleCount").toLong(),
            vadModelId = scalars.getValue("vadModelId"),
            vadModelVersion = scalars.getValue("vadModelVersion"),
            vadModelRevision = scalars.getValue("vadModelRevision"),
            vadWallMillis = scalars.getValue("vadWallMillis").toLong(),
            numThreads = scalars.getValue("numThreads").toInt(),
            frozenBoundaries = boundaries,
        )
    }
}

internal fun stage16BGateB5FrozenBoundaries(
    speechSegments: List<SpeechSegment>,
    maxChunkSamples: Long =
        CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B5_MAX_ASR_INPUT_SECONDS,
): List<SpeechSegment> {
    require(maxChunkSamples > 0L)
    val output = mutableListOf<SpeechSegment>()
    var previousEnd = 0L
    speechSegments.forEach { segment ->
        require(segment.startSampleIndex >= previousEnd)
        previousEnd = segment.endSampleIndexExclusive
        var start = segment.startSampleIndex
        while (start < segment.endSampleIndexExclusive) {
            val end = minOf(segment.endSampleIndexExclusive, start + maxChunkSamples)
            output += SpeechSegment(start, end)
            start = end
        }
    }
    return output
}

internal suspend fun prepareStage16BGateB5Plan(
    application: Application,
    recordingId: String,
    pcmSourceResolver: PcmSourceResolver,
    modelManager: ModelManager,
    modelUseRegistry: ModelUseRegistry,
    localSpeechSettings: LocalSpeechSettings,
    canonicalSha256: String,
): Stage16BGateB5Plan {
    check(VoicaModelChannel.isDebuggable(application)) {
        "Stage 16B B5 plan preparation is restricted to Debug/QA builds"
    }
    require(recordingId.isNotBlank())
    require(canonicalSha256.isNotBlank())

    val fsmn =
        modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
            ?: error("B5 formal evidence requires active FSMN; Silero fallback is invalid")
    require(fsmn.descriptor.modelId == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
    val threads = localSpeechSettings.resolvePerformance().effectiveThreads
    val provider =
        Stage16BLocalFileTranscriptionEngineProvider(
            delegate = SherpaStage8TranscriptionEngineProvider(application.assets),
            assetManager = application.assets,
        )
    val lease = acquireModel(modelUseRegistry, fsmn)
    val startedNs = SystemClock.elapsedRealtimeNanos()
    var totalSampleCount = 0L
    val speechSegments =
        try {
            val source =
                pcmSourceResolver.resolvePcmSource(recordingId)
                    ?: error("canonical PCM source is unavailable")
            source.use {
                requireStage16BGateB5CanonicalSource(it)
                totalSampleCount = it.totalSampleCount
                provider.vadFactory(fsmn, threads, localSpeechSettings.vad).open().use { engine ->
                    engine.analyze(it)
                }
            }
        } finally {
            lease.close()
        }
    val vadWallMillis = elapsedStage16BMillis(startedNs)
    val frozen = stage16BGateB5FrozenBoundaries(speechSegments)
    require(frozen.isNotEmpty()) { "FSMN produced no B5 speech input" }

    return Stage16BGateB5Plan(
        recordingId = recordingId,
        canonicalSha256 = canonicalSha256,
        totalSampleCount = totalSampleCount,
        vadModelId = fsmn.descriptor.modelId,
        vadModelVersion = fsmn.descriptor.version,
        vadModelRevision = fsmn.descriptor.revision.toString(),
        vadWallMillis = vadWallMillis,
        numThreads = threads,
        frozenBoundaries = frozen,
    ).also { Stage16BGateB5PlanStore.write(application, it) }
}

internal data class Stage16BGateB5PassObservation(
    val label: String,
    val engineOpenMillis: Long,
    val asrWallMillis: Long,
    val asrCallCount: Int,
    val blankCallCount: Int,
    val speechSampleCount: Long,
    val startPssKb: Long,
    val peakPssKb: Long,
    val endPssKb: Long,
) {
    init {
        require(engineOpenMillis >= 0L)
        require(asrWallMillis >= 0L)
        require(asrCallCount >= 0)
        require(blankCallCount in 0..asrCallCount)
        require(speechSampleCount > 0L)
        require(startPssKb >= 0L)
        require(peakPssKb >= startPssKb)
        require(endPssKb >= 0L)
    }

    val totalWallMillis: Long
        get() = engineOpenMillis + asrWallMillis

    val speechDurationMillis: Double
        get() =
            speechSampleCount.toDouble() * 1000.0 /
                CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()

    val asrRtf: Double
        get() = asrWallMillis.toDouble() / speechDurationMillis

    val totalRtf: Double
        get() = totalWallMillis.toDouble() / speechDurationMillis

    val complete: Boolean
        get() = asrCallCount > 0 && blankCallCount == 0
}

internal data class Stage16BGateB5Result(
    val processSessionId: String,
    val processId: Int,
    val modelId: String,
    val modelVersion: String,
    val modelRevision: String,
    val plan: Stage16BGateB5Plan,
    val cold: Stage16BGateB5PassObservation,
    val warm: Stage16BGateB5PassObservation,
) {
    val complete: Boolean
        get() = cold.complete && warm.complete
}

private object Stage16BGateB5ProcessGuard {
    val sessionId: String = UUID.randomUUID().toString()
    private val claimed = AtomicBoolean(false)

    fun claim() {
        check(claimed.compareAndSet(false, true)) {
            "B5 formal session already consumed in this process; restart this candidate process before another formal run"
        }
    }
}

internal class Stage16BGateB5CandidateRunner(
    private val application: Application,
    private val modelId: String,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val localSpeechSettings: () -> LocalSpeechSettings,
    private val loadCanonicalSha256: suspend (String) -> String?,
) {
    suspend fun run(): Stage16BGateB5Result {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B B5 candidate benchmark is restricted to Debug/QA builds"
        }
        Stage16BGateB5ProcessGuard.claim()
        val plan =
            Stage16BGateB5PlanStore.read(application)
                ?: error("B5 plan is missing; prepare the common FSMN-frozen input in the B5 launcher first")
        val liveSha = loadCanonicalSha256(plan.recordingId)
        require(liveSha == plan.canonicalSha256) {
            "canonical SHA changed since B5 plan preparation"
        }
        val model = modelManager.activeModel(modelId) ?: error("B5 candidate model is not active: $modelId")
        val settings = localSpeechSettings()
        val provider = SherpaStage8TranscriptionEngineProvider(application.assets)
        val factory =
            provider.secondPassFactory(
                model = model,
                numThreads = plan.numThreads,
                senseVoiceSettings = settings.senseVoice,
                qwenSettings = settings.qwen,
            )
        val frozenInputs = readFrozenInputs(plan)
        val lease = acquireModel(modelUseRegistry, model)
        return try {
            val cold = measurePass("COLD", factory, frozenInputs, plan.speechSampleCount)
            delay(STAGE16B_GATE_B_B5_WARM_SETTLE_MS)
            val warm = measurePass("WARM", factory, frozenInputs, plan.speechSampleCount)
            Stage16BGateB5Result(
                processSessionId = Stage16BGateB5ProcessGuard.sessionId,
                processId = Process.myPid(),
                modelId = model.descriptor.modelId,
                modelVersion = model.descriptor.version,
                modelRevision = model.descriptor.revision.toString(),
                plan = plan,
                cold = cold,
                warm = warm,
            )
        } finally {
            lease.close()
        }
    }

    private suspend fun measurePass(
        label: String,
        factory: SecondPassAsrEngineFactory,
        frozenInputs: List<Stage16BGateB5FrozenInput>,
        speechSampleCount: Long,
    ): Stage16BGateB5PassObservation =
        supervisorScope {
            val startPssKb = Debug.getPss().toLong()
            val peakPssKb = AtomicLong(startPssKb)
            val sampler = launchPssSampler(peakPssKb)
            var engineOpenMillis = 0L
            var asrWallMillis = 0L
            var callCount = 0
            var blankCount = 0
            try {
                val openStartedNs = SystemClock.elapsedRealtimeNanos()
                val engine = factory.open()
                engineOpenMillis = elapsedStage16BMillis(openStartedNs)
                updatePeakPss(peakPssKb, Debug.getPss().toLong())
                engine.use {
                    val asrStartedNs = SystemClock.elapsedRealtimeNanos()
                    frozenInputs.forEach { input ->
                        val hypothesis =
                            it.transcribe(
                                samples = input.samples,
                                sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                            )
                        callCount += 1
                        if (hypothesis.text.isBlank()) blankCount += 1
                        updatePeakPss(peakPssKb, Debug.getPss().toLong())
                    }
                    asrWallMillis = elapsedStage16BMillis(asrStartedNs)
                }
            } finally {
                sampler.cancelAndJoin()
            }
            updatePeakPss(peakPssKb, Debug.getPss().toLong())
            Stage16BGateB5PassObservation(
                label = label,
                engineOpenMillis = engineOpenMillis,
                asrWallMillis = asrWallMillis,
                asrCallCount = callCount,
                blankCallCount = blankCount,
                speechSampleCount = speechSampleCount,
                startPssKb = startPssKb,
                peakPssKb = peakPssKb.get(),
                endPssKb = Debug.getPss().toLong(),
            )
        }

    private suspend fun readFrozenInputs(plan: Stage16BGateB5Plan): List<Stage16BGateB5FrozenInput> {
        val source =
            pcmSourceResolver.resolvePcmSource(plan.recordingId)
                ?: error("canonical PCM source is unavailable")
        return source.use {
            requireStage16BGateB5CanonicalSource(it)
            require(it.totalSampleCount == plan.totalSampleCount) {
                "canonical sample count changed since B5 plan preparation"
            }
            val total = Math.toIntExact(it.totalSampleCount)
            val pcm = ShortArray(total)
            var offset = 0
            while (offset < total) {
                val read = it.read(pcm, offset, total - offset) ?: break
                require(read.startSampleIndex == offset.toLong())
                require(read.sampleCount > 0)
                offset += read.sampleCount
            }
            require(offset == total) { "canonical PCM ended early during B5 input freeze" }
            plan.frozenBoundaries.map { boundary ->
                Stage16BGateB5FrozenInput(
                    boundary = boundary,
                    samples =
                        pcm.copyOfRange(
                            Math.toIntExact(boundary.startSampleIndex),
                            Math.toIntExact(boundary.endSampleIndexExclusive),
                        ),
                )
            }
        }
    }

    private fun CoroutineScope.launchPssSampler(peakPssKb: AtomicLong): Job =
        launch {
            while (isActive) {
                updatePeakPss(peakPssKb, Debug.getPss().toLong())
                delay(STAGE16B_GATE_B_B5_PSS_SAMPLE_INTERVAL_MS)
            }
        }
}

internal data class Stage16BGateB5FrozenInput(
    val boundary: SpeechSegment,
    val samples: ShortArray,
) {
    init {
        require(boundary.sampleCount == samples.size.toLong())
    }
}

private fun requireStage16BGateB5CanonicalSource(source: PcmSource) {
    require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
        "B5 requires canonical 16 kHz PCM"
    }
    require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
        "B5 requires canonical mono PCM"
    }
    require(source.totalSampleCount > 0L)
    val maxSamples =
        CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B5_MAX_RECORDING_SECONDS
    require(source.totalSampleCount <= maxSamples) {
        "B5 recording is limited to ${STAGE16B_GATE_B_B5_MAX_RECORDING_SECONDS}s; use B6 for long files"
    }
    require(source.totalSampleCount <= Int.MAX_VALUE.toLong())
}

private fun acquireModel(
    modelUseRegistry: ModelUseRegistry,
    model: ActiveModel,
): ModelLease =
    modelUseRegistry.acquire(
        modelId = model.descriptor.modelId,
        version = model.descriptor.version,
        revision = model.descriptor.revision,
    )

private fun elapsedStage16BMillis(startedNs: Long): Long =
    (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) / 1_000_000L

private fun updatePeakPss(target: AtomicLong, candidate: Long) {
    while (true) {
        val current = target.get()
        if (candidate <= current || target.compareAndSet(current, candidate)) return
    }
}
