package io.github.ioannes78.voica

import android.app.Application
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.transcript.AsrBenchmarkScoring
import io.github.ioannes78.voica.transcript.SpeechSegment
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal const val STAGE16B_GATE_B_B6_MIN_RECORDING_SECONDS = 540
internal const val STAGE16B_GATE_B_B6_MAX_RECORDING_SECONDS = 2700
internal const val STAGE16B_GATE_B_B6_MAX_ASR_INPUT_SECONDS = 25
private const val STAGE16B_GATE_B_B6_PLAN_VERSION = 1
private const val STAGE16B_GATE_B_B6_PLAN_FILE = "stage16b_gate_b6_plan.txt"
private const val STAGE16B_GATE_B_B6_PSS_SAMPLE_INTERVAL_MS = 100L
private const val STAGE16B_GATE_B_B6_STREAM_BUFFER_SAMPLES = 8192

internal data class Stage16BGateB6Plan(
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
        require(stage16BGateB6DurationEligible(totalSampleCount))
        var previousEnd = 0L
        frozenBoundaries.forEach { boundary ->
            require(boundary.startSampleIndex >= previousEnd)
            require(boundary.endSampleIndexExclusive <= totalSampleCount)
            require(
                boundary.sampleCount <=
                    CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B6_MAX_ASR_INPUT_SECONDS,
            )
            previousEnd = boundary.endSampleIndexExclusive
        }
    }

    val speechSampleCount: Long
        get() = frozenBoundaries.sumOf(SpeechSegment::sampleCount)

    val speechDurationSeconds: Double
        get() = speechSampleCount.toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()

    val recordingDurationSeconds: Double
        get() = totalSampleCount.toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()
}

internal fun stage16BGateB6DurationEligible(totalSampleCount: Long): Boolean {
    if (totalSampleCount <= 0L) return false
    val minimum = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B6_MIN_RECORDING_SECONDS
    val maximum = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B6_MAX_RECORDING_SECONDS
    return totalSampleCount in minimum..maximum
}

internal object Stage16BGateB6PlanStore {
    fun write(application: Application, plan: Stage16BGateB6Plan) {
        val target = File(application.cacheDir, STAGE16B_GATE_B_B6_PLAN_FILE)
        val temporary = File(application.cacheDir, "$STAGE16B_GATE_B_B6_PLAN_FILE.tmp")
        temporary.writeText(encode(plan))
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            check(temporary.delete()) { "failed to remove temporary B6 plan" }
        }
    }

    fun read(application: Application): Stage16BGateB6Plan? {
        val file = File(application.cacheDir, STAGE16B_GATE_B_B6_PLAN_FILE)
        if (!file.isFile) return null
        return decode(file.readText())
    }

    internal fun encode(plan: Stage16BGateB6Plan): String = buildString {
        appendLine("version=$STAGE16B_GATE_B_B6_PLAN_VERSION")
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

    internal fun decode(text: String): Stage16BGateB6Plan {
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        val scalars = lines
            .filterNot { it.startsWith("boundary=") }
            .associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "invalid B6 plan line" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
        require(scalars["version"]?.toInt() == STAGE16B_GATE_B_B6_PLAN_VERSION) {
            "unsupported B6 plan version"
        }
        val boundaries = lines.filter { it.startsWith("boundary=") }.map { line ->
            val pieces = line.substringAfter('=').split(':')
            require(pieces.size == 2)
            SpeechSegment(pieces[0].toLong(), pieces[1].toLong())
        }
        return Stage16BGateB6Plan(
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

internal suspend fun prepareStage16BGateB6Plan(
    application: Application,
    recordingId: String,
    pcmSourceResolver: PcmSourceResolver,
    modelManager: ModelManager,
    modelUseRegistry: ModelUseRegistry,
    localSpeechSettings: LocalSpeechSettings,
    canonicalSha256: String,
): Stage16BGateB6Plan {
    check(VoicaModelChannel.isDebuggable(application)) {
        "Stage 16B B6 plan preparation is restricted to Debug/QA builds"
    }
    require(recordingId.isNotBlank())
    require(canonicalSha256.isNotBlank())

    val fsmn = modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
        ?: error("B6 formal evidence requires active FSMN; Silero fallback is invalid")
    val threads = localSpeechSettings.resolvePerformance().effectiveThreads
    val provider = Stage16BLocalFileTranscriptionEngineProvider(
        delegate = SherpaStage8TranscriptionEngineProvider(application.assets),
        assetManager = application.assets,
    )
    val lease = modelUseRegistry.acquire(
        modelId = fsmn.descriptor.modelId,
        version = fsmn.descriptor.version,
        revision = fsmn.descriptor.revision,
    )
    val startedNs = SystemClock.elapsedRealtimeNanos()
    var totalSampleCount = 0L
    val speechSegments = try {
        val source = pcmSourceResolver.resolvePcmSource(recordingId)
            ?: error("canonical PCM source is unavailable")
        source.use {
            requireStage16BGateB6CanonicalSource(it)
            totalSampleCount = it.totalSampleCount
            provider.vadFactory(fsmn, threads, localSpeechSettings.vad).open().use { engine ->
                engine.analyze(it)
            }
        }
    } finally {
        lease.close()
    }
    val vadWallMillis = stage16BGateB6ElapsedMillis(startedNs)
    val frozen = stage16BGateB5FrozenBoundaries(
        speechSegments = speechSegments,
        maxChunkSamples = CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B6_MAX_ASR_INPUT_SECONDS,
    )
    require(frozen.isNotEmpty()) { "FSMN produced no B6 speech input" }

    return Stage16BGateB6Plan(
        recordingId = recordingId,
        canonicalSha256 = canonicalSha256,
        totalSampleCount = totalSampleCount,
        vadModelId = fsmn.descriptor.modelId,
        vadModelVersion = fsmn.descriptor.version,
        vadModelRevision = fsmn.descriptor.revision.toString(),
        vadWallMillis = vadWallMillis,
        numThreads = threads,
        frozenBoundaries = frozen,
    ).also { Stage16BGateB6PlanStore.write(application, it) }
}

internal data class Stage16BGateB6ChunkTiming(
    val speechSampleCount: Long,
    val wallMillis: Long,
    val blank: Boolean,
) {
    init {
        require(speechSampleCount > 0L)
        require(wallMillis >= 0L)
    }
}

internal fun stage16BGateB6TimingRtf(timings: List<Stage16BGateB6ChunkTiming>): Double? {
    if (timings.isEmpty()) return null
    val samples = timings.sumOf(Stage16BGateB6ChunkTiming::speechSampleCount)
    if (samples <= 0L) return null
    val audioMillis = samples.toDouble() * 1000.0 / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()
    return timings.sumOf(Stage16BGateB6ChunkTiming::wallMillis).toDouble() / audioMillis
}

internal fun stage16BGateB6QuarterRtfs(
    timings: List<Stage16BGateB6ChunkTiming>,
): Pair<Double?, Double?> {
    if (timings.isEmpty()) return null to null
    val quarter = maxOf(1, timings.size / 4)
    return stage16BGateB6TimingRtf(timings.take(quarter)) to
        stage16BGateB6TimingRtf(timings.takeLast(quarter))
}

internal data class Stage16BGateB6Result(
    val processSessionId: String,
    val processId: Int,
    val modelId: String,
    val modelVersion: String,
    val modelRevision: String,
    val plan: Stage16BGateB6Plan,
    val engineOpenMillis: Long,
    val asrWallMillis: Long,
    val asrCallCount: Int,
    val blankCallCount: Int,
    val normalizedCharacterCount: Int,
    val firstQuarterAsrRtf: Double?,
    val lastQuarterAsrRtf: Double?,
    val startPssKb: Long,
    val peakPssKb: Long,
    val endPssKb: Long,
    val thermalStatusStart: Int?,
    val thermalStatusMax: Int?,
    val thermalStatusEnd: Int?,
) {
    val speechDurationMillis: Double
        get() = plan.speechSampleCount.toDouble() * 1000.0 / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()

    val asrRtf: Double
        get() = asrWallMillis.toDouble() / speechDurationMillis

    val totalWallMillis: Long
        get() = engineOpenMillis + asrWallMillis

    val totalRtf: Double
        get() = totalWallMillis.toDouble() / speechDurationMillis

    val thermalSlowdownRatio: Double?
        get() {
            val first = firstQuarterAsrRtf ?: return null
            val last = lastQuarterAsrRtf ?: return null
            return if (first > 0.0) last / first else null
        }

    val complete: Boolean
        get() =
            asrCallCount == plan.frozenBoundaries.size &&
                blankCallCount == 0 &&
                normalizedCharacterCount > 0
}

private object Stage16BGateB6ProcessGuard {
    val sessionId: String = UUID.randomUUID().toString()
    private val claimed = AtomicBoolean(false)

    fun claim() {
        check(claimed.compareAndSet(false, true)) {
            "B6 formal session already consumed in this process; restart the candidate process"
        }
    }
}

internal class Stage16BGateB6CandidateRunner(
    private val application: Application,
    private val modelId: String,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val localSpeechSettings: () -> LocalSpeechSettings,
    private val loadCanonicalSha256: suspend (String) -> String?,
) {
    suspend fun run(): Stage16BGateB6Result {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B B6 candidate benchmark is restricted to Debug/QA builds"
        }
        Stage16BGateB6ProcessGuard.claim()
        val plan = Stage16BGateB6PlanStore.read(application)
            ?: error("B6 plan is missing; prepare the common exact-FSMN input first")
        require(loadCanonicalSha256(plan.recordingId) == plan.canonicalSha256) {
            "canonical SHA changed since B6 plan preparation"
        }
        require(
            modelId == STAGE16B_FUNASR_NANO_CANDIDATE_ID ||
                modelId == Stage13AOfflineModelIds.QWEN3_ASR,
        ) { "B6 is restricted to the two high-quality finalists" }

        val model = modelManager.activeModel(modelId)
            ?: error("B6 finalist model is not active: $modelId")
        val settings = localSpeechSettings()
        val factory = SherpaStage8TranscriptionEngineProvider(application.assets).secondPassFactory(
            model = model,
            numThreads = plan.numThreads,
            senseVoiceSettings = settings.senseVoice,
            qwenSettings = settings.qwen,
        )
        val lease = modelUseRegistry.acquire(
            modelId = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision,
        )
        return try {
            measure(plan, model.descriptor.modelId, model.descriptor.version, model.descriptor.revision.toString(), factory)
        } finally {
            lease.close()
        }
    }

    private suspend fun measure(
        plan: Stage16BGateB6Plan,
        resolvedModelId: String,
        modelVersion: String,
        modelRevision: String,
        factory: io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory,
    ): Stage16BGateB6Result = supervisorScope {
        val powerManager = application.getSystemService(PowerManager::class.java)
        val thermalStart = currentThermalStatus(powerManager)
        val thermalMax = AtomicInteger(thermalStart ?: Int.MIN_VALUE)
        val startPssKb = Debug.getPss().toLong()
        val peakPssKb = AtomicLong(startPssKb)
        val sampler = launchResourceSampler(peakPssKb, thermalMax, powerManager)
        var engineOpenMillis = 0L
        var asrWallMillis = 0L
        var calls = 0
        var blanks = 0
        val timings = mutableListOf<Stage16BGateB6ChunkTiming>()
        val normalizedText = StringBuilder()
        try {
            val openStartedNs = SystemClock.elapsedRealtimeNanos()
            val engine = factory.open()
            engineOpenMillis = stage16BGateB6ElapsedMillis(openStartedNs)
            stage16BGateB6UpdatePeak(peakPssKb, Debug.getPss().toLong())
            engine.use { asr ->
                val source = pcmSourceResolver.resolvePcmSource(plan.recordingId)
                    ?: error("canonical PCM source is unavailable")
                source.use {
                    requireStage16BGateB6CanonicalSource(it)
                    require(it.totalSampleCount == plan.totalSampleCount) {
                        "canonical sample count changed since B6 plan preparation"
                    }
                    var sourcePosition = 0L
                    val asrStartedNs = SystemClock.elapsedRealtimeNanos()
                    plan.frozenBoundaries.forEach { boundary ->
                        val samples = readStage16BGateB6Boundary(it, boundary, sourcePosition)
                        sourcePosition = boundary.endSampleIndexExclusive
                        val chunkStartedNs = SystemClock.elapsedRealtimeNanos()
                        val hypothesis = asr.transcribe(
                            samples = samples,
                            sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                        )
                        val chunkWall = stage16BGateB6ElapsedMillis(chunkStartedNs)
                        calls += 1
                        val blank = hypothesis.text.isBlank()
                        if (blank) blanks += 1
                        val normalized = AsrBenchmarkScoring.normalizeForCharacterErrorRate(hypothesis.text)
                        if (normalized.isNotEmpty()) normalizedText.append(normalized)
                        timings += Stage16BGateB6ChunkTiming(boundary.sampleCount, chunkWall, blank)
                        stage16BGateB6UpdatePeak(peakPssKb, Debug.getPss().toLong())
                        currentThermalStatus(powerManager)?.let { updateThermalMax(thermalMax, it) }
                    }
                    asrWallMillis = stage16BGateB6ElapsedMillis(asrStartedNs)
                }
            }
        } finally {
            sampler.cancelAndJoin()
        }
        stage16BGateB6UpdatePeak(peakPssKb, Debug.getPss().toLong())
        val (firstQuarterRtf, lastQuarterRtf) = stage16BGateB6QuarterRtfs(timings)
        val thermalEnd = currentThermalStatus(powerManager)
        val thermalMaxValue = thermalMax.get().takeIf { it != Int.MIN_VALUE }
        Stage16BGateB6Result(
            processSessionId = Stage16BGateB6ProcessGuard.sessionId,
            processId = Process.myPid(),
            modelId = resolvedModelId,
            modelVersion = modelVersion,
            modelRevision = modelRevision,
            plan = plan,
            engineOpenMillis = engineOpenMillis,
            asrWallMillis = asrWallMillis,
            asrCallCount = calls,
            blankCallCount = blanks,
            normalizedCharacterCount = normalizedText.codePointCount(0, normalizedText.length),
            firstQuarterAsrRtf = firstQuarterRtf,
            lastQuarterAsrRtf = lastQuarterRtf,
            startPssKb = startPssKb,
            peakPssKb = peakPssKb.get(),
            endPssKb = Debug.getPss().toLong(),
            thermalStatusStart = thermalStart,
            thermalStatusMax = thermalMaxValue,
            thermalStatusEnd = thermalEnd,
        )
    }

    private fun CoroutineScope.launchResourceSampler(
        peakPssKb: AtomicLong,
        thermalMax: AtomicInteger,
        powerManager: PowerManager?,
    ): Job = launch {
        while (isActive) {
            stage16BGateB6UpdatePeak(peakPssKb, Debug.getPss().toLong())
            currentThermalStatus(powerManager)?.let { updateThermalMax(thermalMax, it) }
            delay(STAGE16B_GATE_B_B6_PSS_SAMPLE_INTERVAL_MS)
        }
    }
}

private suspend fun readStage16BGateB6Boundary(
    source: PcmSource,
    boundary: SpeechSegment,
    startingSourcePosition: Long,
): ShortArray {
    var sourcePosition = startingSourcePosition
    require(boundary.startSampleIndex >= sourcePosition)
    val discard = ShortArray(STAGE16B_GATE_B_B6_STREAM_BUFFER_SAMPLES)
    while (sourcePosition < boundary.startSampleIndex) {
        val request = minOf(discard.size.toLong(), boundary.startSampleIndex - sourcePosition).toInt()
        val read = source.read(discard, 0, request) ?: error("canonical PCM ended before B6 boundary")
        require(read.startSampleIndex == sourcePosition)
        require(read.sampleCount > 0)
        sourcePosition += read.sampleCount
    }

    val samples = ShortArray(Math.toIntExact(boundary.sampleCount))
    var filled = 0
    while (filled < samples.size) {
        val read = source.read(samples, filled, samples.size - filled)
            ?: error("canonical PCM ended during B6 boundary")
        require(read.startSampleIndex == sourcePosition)
        require(read.sampleCount > 0)
        filled += read.sampleCount
        sourcePosition += read.sampleCount
    }
    require(sourcePosition == boundary.endSampleIndexExclusive)
    return samples
}

private fun requireStage16BGateB6CanonicalSource(source: PcmSource) {
    require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) { "B6 requires canonical 16 kHz PCM" }
    require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT) { "B6 requires canonical mono PCM" }
    require(stage16BGateB6DurationEligible(source.totalSampleCount)) {
        "B6 requires a 9–45 minute recording; use a representative 10–30 minute real-speech file"
    }
}

private fun stage16BGateB6ElapsedMillis(startedNs: Long): Long =
    (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) / 1_000_000L

private fun stage16BGateB6UpdatePeak(target: AtomicLong, candidate: Long) {
    while (true) {
        val current = target.get()
        if (candidate <= current || target.compareAndSet(current, candidate)) return
    }
}

private fun currentThermalStatus(powerManager: PowerManager?): Int? =
    if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        powerManager.currentThermalStatus
    } else {
        null
    }

private fun updateThermalMax(target: AtomicInteger, candidate: Int) {
    while (true) {
        val current = target.get()
        if (candidate <= current || target.compareAndSet(current, candidate)) return
    }
}
