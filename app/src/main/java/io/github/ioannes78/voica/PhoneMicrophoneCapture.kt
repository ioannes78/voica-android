package io.github.ioannes78.voica

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import java.io.Closeable
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PhoneMicrophoneCapturePhase {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    STOPPED,
    FAILED,
    CLOSED,
}

internal enum class PhoneMicrophoneCaptureTerminalReason {
    START_REJECTED,
    CLIENT_SILENCED,
    READ_ERROR,
    CONSUMER_ERROR,
    EXECUTION_OWNER_FAILURE,
}

internal enum class PhoneMicrophoneAudioSource(
    val platformValue: Int,
    val diagnosticsName: String,
) {
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
    MIC(MediaRecorder.AudioSource.MIC, "MIC"),
}

internal data class PhoneMicrophoneRoute(
    val deviceId: Int,
    val deviceType: Int,
    val productName: String,
    val address: String? = null,
)

internal data class PhoneMicrophonePcmFrame(
    val startSampleIndex: Long,
    val samples: ShortArray,
) {
    init {
        require(startSampleIndex >= 0L)
        require(samples.isNotEmpty())
    }

    val endSampleIndexExclusive: Long
        get() = Math.addExact(startSampleIndex, samples.size.toLong())
}

internal fun interface PhoneMicrophonePcmConsumer {
    /**
     * Called on the dedicated capture worker. The supplied samples are exclusively owned by this
     * frame. Throwing is treated as a fatal recording-path failure rather than silently dropping PCM.
     */
    fun onPcm(frame: PhoneMicrophonePcmFrame)
}

/**
 * Recording-truth guard used immediately after AudioRecord.read() and before any formal WAV or ASR
 * consumer receives the frame. Android can keep returning successful reads containing zeroed PCM
 * while another higher-priority recorder owns the microphone; those samples are not real capture
 * truth and must never be persisted as normal audio.
 */
internal fun deliverPhoneMicrophonePcmIfAudible(
    clientSilenced: Boolean?,
    frame: PhoneMicrophonePcmFrame,
    consumer: PhoneMicrophonePcmConsumer,
): Boolean {
    if (clientSilenced == true) return false
    consumer.onPcm(frame)
    return true
}

internal data class PhoneMicrophoneCaptureSnapshot(
    val phase: PhoneMicrophoneCapturePhase = PhoneMicrophoneCapturePhase.IDLE,
    val sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ,
    val channelCount: Int = CanonicalPcmProfile.CHANNEL_COUNT,
    val encoding: String = "PCM16",
    val requestedInput: PhoneMicrophoneInputRequest = PhoneMicrophoneInputRequest.Auto,
    val selectedAudioSource: PhoneMicrophoneAudioSource? = null,
    val audioSourceAttempts: Int = 0,
    val sourceFallbackUsed: Boolean = false,
    val sourceFallbackReason: String? = null,
    val minBufferBytes: Int? = null,
    val effectiveBufferBytes: Int? = null,
    val readChunkSamples: Int = PhoneMicrophoneBufferPolicy.READ_CHUNK_SAMPLES,
    val audioSessionId: Int? = null,
    val preferredDeviceAccepted: Boolean? = null,
    val route: PhoneMicrophoneRoute? = null,
    val routeGeneration: Long = 0L,
    val routeChangeCount: Long = 0L,
    val lastRouteChangeReason: PhoneMicrophoneRouteChangeReason? = null,
    val clientSilenced: Boolean? = null,
    val signal: PhoneMicrophoneSignalSnapshot? = null,
    val signalSummary: PhoneMicrophoneSignalSummary? = null,
    val readCalls: Long = 0L,
    /** Samples accepted as real microphone capture truth and forwarded to the primary consumer. */
    val samplesRead: Long = 0L,
    val shortReads: Long = 0L,
    val silencedReadCalls: Long = 0L,
    val silencedSamplesRejected: Long = 0L,
    val readErrors: Long = 0L,
    val consumerErrors: Long = 0L,
    val lastReadCode: Int? = null,
    val terminalReason: PhoneMicrophoneCaptureTerminalReason? = null,
    val foregroundPhase: PhoneMicrophoneForegroundPhase = PhoneMicrophoneForegroundPhase.IDLE,
    val wakeLockHeld: Boolean = false,
    val lastFailure: String? = null,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A.1.6 phone microphone capture]")
        appendLine(
            "phase=$phase source=${selectedAudioSource?.diagnosticsName ?: "n/a"} " +
                "format=${sampleRateHz}Hz/${channelCount}ch/$encoding",
        )
        appendLine(
            "input=${requestedInput.toDiagnosticsName()} preferredAccepted=${preferredDeviceAccepted ?: "n/a"} " +
                "sourceAttempts=$audioSourceAttempts fallbackUsed=$sourceFallbackUsed " +
                "fallbackReason=${sourceFallbackReason ?: "n/a"}",
        )
        appendLine(
            "bufferMin=${minBufferBytes ?: -1} bufferEffective=${effectiveBufferBytes ?: -1} " +
                "readChunkSamples=$readChunkSamples sessionId=${audioSessionId ?: -1}",
        )
        appendLine(
            "reads=$readCalls samples=$samplesRead shortReads=$shortReads readErrors=$readErrors " +
                "consumerErrors=$consumerErrors lastReadCode=${lastReadCode ?: 0}",
        )
        appendLine(
            "clientSilenced=${clientSilenced ?: "n/a"} silencedReads=$silencedReadCalls " +
                "silencedSamplesRejected=$silencedSamplesRejected " +
                "terminalReason=${terminalReason?.name ?: "n/a"}",
        )
        appendLine(
            "route=${route?.productName ?: "n/a"}/${route?.deviceType ?: -1}/${route?.deviceId ?: -1} " +
                "routeAddress=${route?.address ?: "n/a"} routeGeneration=$routeGeneration " +
                "routeChanges=$routeChangeCount routeReason=${lastRouteChangeReason?.name ?: "n/a"}",
        )
        val signalSnapshot = signal
        if (signalSnapshot == null) {
            appendLine("signal=n/a")
        } else {
            appendLine(
                "signal=peak:${signalSnapshot.peakSample}/${formatDbfs(signalSnapshot.peakDbfs)}dBFS " +
                    "rms:${formatOneDecimal(signalSnapshot.rms)}/${formatDbfs(signalSnapshot.rmsDbfs)}dBFS " +
                    "clip:${signalSnapshot.clippingSamples}/${formatRatio(signalSnapshot.clippingRatio)} " +
                    "zero:${signalSnapshot.zeroSamples}/${formatRatio(signalSnapshot.zeroSampleRatio)}",
            )
        }
        val summary = signalSummary
        if (summary == null) {
            appendLine("signalSummary=n/a")
        } else {
            appendLine(
                "signalSummary=frames:${summary.frameCount} samples:${summary.sampleCount} " +
                    "peak:${summary.peakSample}/${formatDbfs(summary.peakDbfs)}dBFS " +
                    "overallRms:${formatOneDecimal(summary.rms)}/${formatDbfs(summary.rmsDbfs)}dBFS " +
                    "noiseFloorCandidate:${formatDbfs(summary.noiseFloorCandidateDbfs)}dBFS " +
                    "clip:${summary.clippingSamples}/${formatRatio(summary.clippingRatio)} " +
                    "zero:${summary.zeroSamples}/${formatRatio(summary.zeroSampleRatio)}",
            )
        }
        append(
            "foreground=$foregroundPhase wakeLockHeld=$wakeLockHeld " +
                "lastFailure=${lastFailure ?: "n/a"}",
        )
    }
}

internal object PhoneMicrophoneCaptureDiagnostics {
    private val lock = Any()
    private var retainedSnapshot: PhoneMicrophoneCaptureSnapshot? = null

    fun snapshot(): PhoneMicrophoneCaptureSnapshot? =
        synchronized(lock) { retainedSnapshot }

    fun record(snapshot: PhoneMicrophoneCaptureSnapshot) {
        synchronized(lock) { retainedSnapshot = snapshot }
    }

    fun clearForTest() {
        synchronized(lock) { retainedSnapshot = null }
    }
}

internal sealed interface PhoneMicrophoneStartResult {
    data class Started(
        val audioSource: PhoneMicrophoneAudioSource,
        val minBufferBytes: Int,
        val effectiveBufferBytes: Int,
    ) : PhoneMicrophoneStartResult

    data class Rejected(
        val message: String,
        val failureClass: String? = null,
    ) : PhoneMicrophoneStartResult
}

internal object PhoneMicrophoneBufferPolicy {
    /** Keep capture frames aligned with the Stage 16A 80 ms canonical chunk size. */
    const val READ_CHUNK_SAMPLES = 1_280
    private const val HEADROOM_MULTIPLIER = 2

    fun effectiveBufferBytes(minBufferBytes: Int): Int {
        require(minBufferBytes > 0)
        val readChunkBytes = READ_CHUNK_SAMPLES * CanonicalPcmProfile.BYTES_PER_SAMPLE
        val target =
            maxOf(
                minBufferBytes.toLong() * HEADROOM_MULTIPLIER,
                readChunkBytes.toLong() * HEADROOM_MULTIPLIER,
            ).coerceAtMost(Int.MAX_VALUE.toLong())
        val alignment = CanonicalPcmProfile.BYTES_PER_SAMPLE
        return (((target + alignment - 1L) / alignment) * alignment).toInt()
    }
}

internal interface PhoneMicrophoneAudioRecordHandle {
    val initialized: Boolean
    val recording: Boolean
    val audioSessionId: Int
    val preferredDeviceAccepted: Boolean?
    val route: PhoneMicrophoneRoute?
    val clientSilenced: Boolean?

    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}

internal interface PhoneMicrophoneAudioRecordFactory {
    fun minimumBufferBytes(): Int

    fun create(
        audioSource: PhoneMicrophoneAudioSource,
        bufferBytes: Int,
        inputRequest: PhoneMicrophoneInputRequest,
    ): PhoneMicrophoneAudioRecordHandle
}

/**
 * Single-owner Android microphone capture foundation for both Stage 16A.1 products.
 *
 * This class owns exactly one AudioRecord at a time. It has no ASR dependency. Formal recording
 * truth is protected before the consumer boundary: successful platform reads are still rejected if
 * Android reports that this recording client has been silenced by a higher-priority recorder.
 */
internal class PhoneMicrophoneCapture(
    context: Context,
    private val audioRecordFactory: PhoneMicrophoneAudioRecordFactory =
        AndroidPhoneMicrophoneAudioRecordFactory(context),
    private val executionLease: PhoneMicrophoneExecutionLease =
        PhoneMicrophoneExecutionLease.android(context),
    private val permissionChecker: (Context) -> Boolean = { value ->
        ContextCompat.checkSelfPermission(value, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val bluetoothCommunicationController: PhoneMicrophoneBluetoothCommunicationController =
        AndroidPhoneMicrophoneBluetoothCommunicationController(context),
) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val mutableState = MutableStateFlow(PhoneMicrophoneCaptureSnapshot())
    val state: StateFlow<PhoneMicrophoneCaptureSnapshot> = mutableState.asStateFlow()

    private var activeRecord: PhoneMicrophoneAudioRecordHandle? = null
    private var captureJob: Job? = null
    private var stopRequested = false
    private var keepBluetoothCommunicationSessionOnStop = false
    private var closed = false

    fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String = "正在使用手机麦克风录音",
        inputRequest: PhoneMicrophoneInputRequest = PhoneMicrophoneInputSelectionRuntime.currentRequest(),
    ): PhoneMicrophoneStartResult {
        synchronized(lock) {
            if (closed) {
                return PhoneMicrophoneStartResult.Rejected("手机麦克风录音已关闭，请重新进入录音页面")
            }
            when (currentSnapshotLocked().phase) {
                PhoneMicrophoneCapturePhase.STARTING,
                PhoneMicrophoneCapturePhase.RECORDING,
                PhoneMicrophoneCapturePhase.STOPPING,
                -> return PhoneMicrophoneStartResult.Rejected("手机麦克风正在录音或正在切换状态")
                else -> Unit
            }
            if (!permissionChecker(appContext)) {
                updateStateLocked(
                    PhoneMicrophoneCaptureSnapshot(
                        phase = PhoneMicrophoneCapturePhase.FAILED,
                        requestedInput = inputRequest,
                        terminalReason = PhoneMicrophoneCaptureTerminalReason.START_REJECTED,
                        lastFailure = "需要麦克风权限才能开始手机录音",
                    ),
                )
                return PhoneMicrophoneStartResult.Rejected("需要麦克风权限才能开始手机录音")
            }
            stopRequested = false
            keepBluetoothCommunicationSessionOnStop = false
            updateStateLocked(
                PhoneMicrophoneCaptureSnapshot(
                    phase = PhoneMicrophoneCapturePhase.STARTING,
                    requestedInput = inputRequest,
                ),
            )
        }

        if (!executionLease.acquire(foregroundLabel)) {
            return rejectStart("无法启动手机录音前台服务，请保持 Voica 在前台后重试")
        }

        if (synchronized(lock) { closed || stopRequested }) {
            executionLease.release()
            return rejectStart("手机录音已取消")
        }

        val minBufferBytes =
            try {
                audioRecordFactory.minimumBufferBytes()
            } catch (failure: Throwable) {
                executionLease.release()
                return rejectStart("无法读取麦克风缓冲区配置", failure)
            }
        if (minBufferBytes <= 0) {
            executionLease.release()
            return rejectStart("当前设备不支持 16 kHz 单声道 PCM 麦克风录音")
        }
        val effectiveBufferBytes = PhoneMicrophoneBufferPolicy.effectiveBufferBytes(minBufferBytes)

        val bluetoothPreparation =
            try {
                bluetoothCommunicationController.prepare(inputRequest)
            } catch (failure: Throwable) {
                executionLease.release()
                return rejectStart("无法准备蓝牙耳机录音通道", failure)
            }
        if (bluetoothPreparation is PhoneMicrophoneBluetoothCommunicationPreparation.Rejected) {
            executionLease.release()
            return rejectStart(
                bluetoothPreparation.message,
                IllegalStateException(
                    "${bluetoothPreparation.code}:${bluetoothPreparation.message}",
                ),
            )
        }
        if (synchronized(lock) { closed || stopRequested }) {
            bluetoothCommunicationController.release()
            executionLease.release()
            return rejectStart("手机录音已取消")
        }

        val sourceOrder =
            if (bluetoothPreparation is PhoneMicrophoneBluetoothCommunicationPreparation.Active) {
                resolvePhoneMicrophoneBluetoothCommunicationSourceOrder(
                    qaSnapshot = Stage16A16QaAudioSourceOverride.snapshot(),
                    availableSources = BLUETOOTH_SOURCE_FALLBACK_ORDER,
                )
            } else {
                Stage16A16QaAudioSourceOverride.sourceOrder(SOURCE_FALLBACK_ORDER)
            }

        var selectedSource: PhoneMicrophoneAudioSource? = null
        var selectedRecord: PhoneMicrophoneAudioRecordHandle? = null
        var lastFailure: Throwable? = null
        var sourceAttempts = 0
        for (source in sourceOrder) {
            if (synchronized(lock) { closed || stopRequested }) break
            sourceAttempts += 1

            val candidate =
                try {
                    audioRecordFactory.create(source, effectiveBufferBytes, inputRequest)
                } catch (failure: Throwable) {
                    lastFailure = failure
                    null
                } ?: continue

            if (!candidate.initialized) {
                lastFailure = IllegalStateException("AudioRecord did not initialize for ${source.diagnosticsName}")
                runCatching { candidate.release() }
                continue
            }

            if (
                inputRequest is PhoneMicrophoneInputRequest.SpecificDevice &&
                candidate.preferredDeviceAccepted != true
            ) {
                lastFailure = IllegalStateException("requested input device was not accepted")
                runCatching { candidate.release() }
                continue
            }

            val started =
                try {
                    candidate.start()
                    candidate.recording
                } catch (failure: Throwable) {
                    lastFailure = failure
                    false
                }
            if (!started) {
                runCatching { candidate.stop() }
                runCatching { candidate.release() }
                continue
            }

            if (
                inputRequest is PhoneMicrophoneInputRequest.SpecificDevice &&
                candidate.route?.deviceId != inputRequest.deviceId
            ) {
                lastFailure = IllegalStateException("requested input device did not become the actual route")
                runCatching { candidate.stop() }
                runCatching { candidate.release() }
                continue
            }

            selectedSource = source
            selectedRecord = candidate
            break
        }

        val record = selectedRecord
        val source = selectedSource
        if (record == null || source == null) {
            bluetoothCommunicationController.release()
            executionLease.release()
            return if (synchronized(lock) { closed || stopRequested }) {
                rejectStart("手机录音已取消")
            } else if (inputRequest is PhoneMicrophoneInputRequest.SpecificDevice) {
                rejectStart("未能使用所选麦克风，请重新连接设备或切换为自动", lastFailure)
            } else {
                rejectStart("无法打开手机麦克风，请检查是否被其它应用占用", lastFailure)
            }
        }

        val foregroundPhaseBeforeRun = PhoneMicrophoneForegroundDiagnostics.state.value.phase
        if (
            foregroundPhaseBeforeRun == PhoneMicrophoneForegroundPhase.FAILED ||
            foregroundPhaseBeforeRun == PhoneMicrophoneForegroundPhase.IDLE
        ) {
            runCatching { record.stop() }
            runCatching { record.release() }
            bluetoothCommunicationController.release()
            executionLease.release()
            return rejectStart("手机录音前台服务未能保持运行，请重试")
        }

        val routeTracker = PhoneMicrophoneRouteTracker()
        val initialRoute = routeTracker.reset(record.route)
        synchronized(lock) {
            if (closed || stopRequested || activeRecord != null) {
                runCatching { record.stop() }
                runCatching { record.release() }
                bluetoothCommunicationController.release()
                executionLease.release()
                val terminalPhase =
                    if (closed) PhoneMicrophoneCapturePhase.CLOSED else PhoneMicrophoneCapturePhase.STOPPED
                updateStateLocked(currentSnapshotLocked().copy(phase = terminalPhase))
                return PhoneMicrophoneStartResult.Rejected("手机录音已取消")
            }
            activeRecord = record
            val leaseSnapshot = executionLease.snapshot()
            updateStateLocked(
                PhoneMicrophoneCaptureSnapshot(
                    phase = PhoneMicrophoneCapturePhase.RECORDING,
                    requestedInput = inputRequest,
                    selectedAudioSource = source,
                    audioSourceAttempts = sourceAttempts,
                    sourceFallbackUsed = source != sourceOrder.first(),
                    sourceFallbackReason =
                        if (source != sourceOrder.first()) {
                            lastFailure?.describePhoneMicrophoneCaptureFailure()
                                ?: "${sourceOrder.first().diagnosticsName} unavailable"
                        } else {
                            null
                        },
                    minBufferBytes = minBufferBytes,
                    effectiveBufferBytes = effectiveBufferBytes,
                    audioSessionId = record.audioSessionId,
                    preferredDeviceAccepted = record.preferredDeviceAccepted,
                    route = initialRoute.route,
                    routeGeneration = initialRoute.generation,
                    routeChangeCount = initialRoute.changeCount,
                    lastRouteChangeReason = initialRoute.lastChangeReason,
                    clientSilenced = record.clientSilenced,
                    foregroundPhase = foregroundPhaseBeforeRun,
                    wakeLockHeld = leaseSnapshot.wakeLockHeld,
                    lastFailure = leaseSnapshot.lastFailure,
                ),
            )
            captureJob = workerScope.launch { captureLoop(record, consumer, routeTracker) }
        }

        return PhoneMicrophoneStartResult.Started(
            audioSource = source,
            minBufferBytes = minBufferBytes,
            effectiveBufferBytes = effectiveBufferBytes,
        )
    }

    fun requestStop(keepBluetoothCommunicationSession: Boolean = false) {
        val record = synchronized(lock) {
            when (currentSnapshotLocked().phase) {
                PhoneMicrophoneCapturePhase.STARTING -> {
                    stopRequested = true
                    keepBluetoothCommunicationSessionOnStop = keepBluetoothCommunicationSession
                    updateStateLocked(
                        currentSnapshotLocked().copy(phase = PhoneMicrophoneCapturePhase.STOPPING),
                    )
                    null
                }

                PhoneMicrophoneCapturePhase.RECORDING -> {
                    stopRequested = true
                    keepBluetoothCommunicationSessionOnStop = keepBluetoothCommunicationSession
                    updateStateLocked(
                        currentSnapshotLocked().copy(phase = PhoneMicrophoneCapturePhase.STOPPING),
                    )
                    activeRecord
                }

                else -> return
            }
        }
        runCatching { record?.stop() }
    }

    suspend fun stopAndAwait(
        timeoutMs: Long = 2_000L,
        keepBluetoothCommunicationSession: Boolean = false,
    ): Boolean {
        require(timeoutMs > 0L)
        requestStop(keepBluetoothCommunicationSession)
        val job = synchronized(lock) { captureJob } ?: return true
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    fun releaseRetainedBluetoothCommunicationSession() {
        synchronized(lock) { keepBluetoothCommunicationSessionOnStop = false }
        bluetoothCommunicationController.release()
    }

    fun snapshot(): PhoneMicrophoneCaptureSnapshot = mutableState.value

    override fun close() {
        val record = synchronized(lock) {
            if (closed) return
            closed = true
            stopRequested = true
            keepBluetoothCommunicationSessionOnStop = false
            activeRecord
        }
        runCatching { record?.stop() }
        if (record == null) {
            bluetoothCommunicationController.close()
            executionLease.release()
            synchronized(lock) {
                updateStateLocked(currentSnapshotLocked().copy(phase = PhoneMicrophoneCapturePhase.CLOSED))
            }
        }
        workerScope.cancel()
    }

    private fun captureLoop(
        record: PhoneMicrophoneAudioRecordHandle,
        consumer: PhoneMicrophonePcmConsumer,
        routeTracker: PhoneMicrophoneRouteTracker,
    ) {
        val buffer = ShortArray(PhoneMicrophoneBufferPolicy.READ_CHUNK_SAMPLES)
        val signalAccumulator = PhoneMicrophoneSignalAccumulator()
        var terminalFailure: Throwable? = null
        try {
            while (true) {
                val shouldStop = synchronized(lock) { closed || stopRequested || activeRecord !== record }
                if (shouldStop) break

                val foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase
                if (
                    foregroundPhase == PhoneMicrophoneForegroundPhase.FAILED ||
                    foregroundPhase == PhoneMicrophoneForegroundPhase.IDLE
                ) {
                    terminalFailure =
                        IllegalStateException("phone microphone foreground service is not active")
                    markPlatformFailure(record, terminalFailure)
                    break
                }

                val readCount =
                    try {
                        record.read(buffer)
                    } catch (failure: Throwable) {
                        val stoppedDuringRead =
                            synchronized(lock) { closed || stopRequested || activeRecord !== record }
                        if (stoppedDuringRead) break
                        terminalFailure = failure
                        incrementReadError(null, record, failure)
                        break
                    }

                if (readCount <= 0) {
                    val stoppedDuringRead = synchronized(lock) { closed || stopRequested || activeRecord !== record }
                    if (stoppedDuringRead) break
                    val failure = IllegalStateException("AudioRecord.read returned $readCount")
                    terminalFailure = failure
                    incrementReadError(readCount, record, failure)
                    break
                }

                val actualRoute = record.route
                val routeObservation = routeTracker.observe(actualRoute)
                val requestedInput = synchronized(lock) { currentSnapshotLocked().requestedInput }
                if (
                    requestedInput is PhoneMicrophoneInputRequest.SpecificDevice &&
                    actualRoute?.deviceId != requestedInput.deviceId
                ) {
                    val failure =
                        IllegalStateException("selected microphone route changed or disappeared")
                    terminalFailure = failure
                    markRouteMismatch(record, routeObservation, failure)
                    break
                }

                val frameStart = synchronized(lock) { currentSnapshotLocked().samplesRead }
                val frame =
                    PhoneMicrophonePcmFrame(
                        startSampleIndex = frameStart,
                        samples = buffer.copyOf(readCount),
                    )
                val clientSilenced = record.clientSilenced
                if (clientSilenced == true) {
                    val failure = PhoneMicrophoneClientSilencedException()
                    terminalFailure = failure
                    markClientSilencedRead(readCount, buffer.size, record, routeObservation, failure)
                    break
                }
                val signal = PhoneMicrophoneSignalMeter.analyze(frame.samples)
                val signalSummary = signalAccumulator.add(frame.samples)
                val delivered =
                    try {
                        deliverPhoneMicrophonePcmIfAudible(clientSilenced, frame, consumer)
                    } catch (failure: Throwable) {
                        terminalFailure = failure
                        incrementConsumerError(
                            readCount,
                            buffer.size,
                            record,
                            routeObservation,
                            signal,
                            signalSummary,
                            failure,
                        )
                        break
                    }

                if (!delivered) {
                    val failure = PhoneMicrophoneClientSilencedException()
                    terminalFailure = failure
                    markClientSilencedRead(readCount, buffer.size, record, routeObservation, failure)
                    break
                }

                synchronized(lock) {
                    val current = currentSnapshotLocked()
                    updateStateLocked(
                        current.copy(
                            readCalls = current.readCalls + 1,
                            samplesRead = current.samplesRead + readCount,
                            shortReads = current.shortReads + if (readCount < buffer.size) 1 else 0,
                            lastReadCode = readCount,
                            route = routeObservation.route,
                            routeGeneration = routeObservation.generation,
                            routeChangeCount = routeObservation.changeCount,
                            lastRouteChangeReason = routeObservation.lastChangeReason,
                            clientSilenced = clientSilenced,
                            signal = signal,
                            signalSummary = signalSummary,
                            foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                            wakeLockHeld = executionLease.snapshot().wakeLockHeld,
                        ),
                    )
                }
            }
        } finally {
            runCatching { if (record.recording) record.stop() }
            runCatching { record.release() }
            val retainBluetoothCommunication =
                synchronized(lock) {
                    val retain =
                        keepBluetoothCommunicationSessionOnStop &&
                            terminalFailure == null &&
                            !closed
                    keepBluetoothCommunicationSessionOnStop = false
                    retain
                }
            if (!retainBluetoothCommunication) {
                bluetoothCommunicationController.release()
            }
            executionLease.release()

            synchronized(lock) {
                if (activeRecord === record) activeRecord = null
                captureJob = null
                val current = currentSnapshotLocked()
                val phase =
                    when {
                        closed -> PhoneMicrophoneCapturePhase.CLOSED
                        terminalFailure != null -> PhoneMicrophoneCapturePhase.FAILED
                        else -> PhoneMicrophoneCapturePhase.STOPPED
                    }
                updateStateLocked(
                    current.copy(
                        phase = phase,
                        foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                        wakeLockHeld = executionLease.snapshot().wakeLockHeld,
                        lastFailure = terminalFailure?.describePhoneMicrophoneCaptureFailure()
                            ?: current.lastFailure,
                    ),
                )
            }
            if (closed) {
                bluetoothCommunicationController.close()
            }
        }
    }

    private fun markRouteMismatch(
        record: PhoneMicrophoneAudioRecordHandle,
        routeObservation: PhoneMicrophoneRouteObservation,
        failure: Throwable,
    ) {
        Log.w(TAG, "selected phone microphone route changed or disappeared")
        synchronized(lock) {
            val current = currentSnapshotLocked()
            updateStateLocked(
                current.copy(
                    phase = PhoneMicrophoneCapturePhase.FAILED,
                    route = routeObservation.route,
                    routeGeneration = routeObservation.generation,
                    routeChangeCount = routeObservation.changeCount,
                    lastRouteChangeReason = routeObservation.lastChangeReason,
                    clientSilenced = record.clientSilenced,
                    terminalReason = PhoneMicrophoneCaptureTerminalReason.READ_ERROR,
                    foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                    wakeLockHeld = executionLease.snapshot().wakeLockHeld,
                    lastFailure = failure.describePhoneMicrophoneCaptureFailure(),
                ),
            )
        }
    }

    private fun markClientSilencedRead(
        readCount: Int,
        requestedSamples: Int,
        record: PhoneMicrophoneAudioRecordHandle,
        routeObservation: PhoneMicrophoneRouteObservation,
        failure: Throwable,
    ) {
        Log.w(TAG, "phone microphone client was silenced by the platform; rejecting silent PCM")
        synchronized(lock) {
            val current = currentSnapshotLocked()
            updateStateLocked(
                current.copy(
                    phase = PhoneMicrophoneCapturePhase.FAILED,
                    readCalls = current.readCalls + 1,
                    shortReads = current.shortReads + if (readCount < requestedSamples) 1 else 0,
                    silencedReadCalls = current.silencedReadCalls + 1,
                    silencedSamplesRejected = current.silencedSamplesRejected + readCount,
                    lastReadCode = readCount,
                    route = routeObservation.route,
                    routeGeneration = routeObservation.generation,
                    routeChangeCount = routeObservation.changeCount,
                    lastRouteChangeReason = routeObservation.lastChangeReason,
                    clientSilenced = true,
                    terminalReason = PhoneMicrophoneCaptureTerminalReason.CLIENT_SILENCED,
                    foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                    wakeLockHeld = executionLease.snapshot().wakeLockHeld,
                    lastFailure = failure.describePhoneMicrophoneCaptureFailure(),
                ),
            )
        }
    }

    private fun incrementReadError(
        readCode: Int?,
        record: PhoneMicrophoneAudioRecordHandle,
        failure: Throwable,
    ) {
        Log.e(TAG, "phone microphone read failed", failure)
        synchronized(lock) {
            val current = currentSnapshotLocked()
            updateStateLocked(
                current.copy(
                    readCalls = current.readCalls + 1,
                    readErrors = current.readErrors + 1,
                    lastReadCode = readCode,
                    route = record.route,
                    clientSilenced = record.clientSilenced,
                    terminalReason = PhoneMicrophoneCaptureTerminalReason.READ_ERROR,
                    foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                    lastFailure = failure.describePhoneMicrophoneCaptureFailure(),
                ),
            )
        }
    }

    private fun incrementConsumerError(
        readCount: Int,
        requestedSamples: Int,
        record: PhoneMicrophoneAudioRecordHandle,
        routeObservation: PhoneMicrophoneRouteObservation,
        signal: PhoneMicrophoneSignalSnapshot,
        signalSummary: PhoneMicrophoneSignalSummary,
        failure: Throwable,
    ) {
        Log.e(TAG, "phone microphone consumer failed", failure)
        synchronized(lock) {
            val current = currentSnapshotLocked()
            updateStateLocked(
                current.copy(
                    readCalls = current.readCalls + 1,
                    shortReads = current.shortReads + if (readCount < requestedSamples) 1 else 0,
                    consumerErrors = current.consumerErrors + 1,
                    lastReadCode = readCount,
                    route = routeObservation.route,
                    routeGeneration = routeObservation.generation,
                    routeChangeCount = routeObservation.changeCount,
                    lastRouteChangeReason = routeObservation.lastChangeReason,
                    clientSilenced = record.clientSilenced,
                    signal = signal,
                    signalSummary = signalSummary,
                    terminalReason = PhoneMicrophoneCaptureTerminalReason.CONSUMER_ERROR,
                    foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                    lastFailure = failure.describePhoneMicrophoneCaptureFailure(),
                ),
            )
        }
    }

    private fun markPlatformFailure(
        record: PhoneMicrophoneAudioRecordHandle,
        failure: Throwable,
    ) {
        Log.e(TAG, "phone microphone execution owner failed", failure)
        synchronized(lock) {
            val current = currentSnapshotLocked()
            updateStateLocked(
                current.copy(
                    route = record.route,
                    clientSilenced = record.clientSilenced,
                    terminalReason = PhoneMicrophoneCaptureTerminalReason.EXECUTION_OWNER_FAILURE,
                    foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                    lastFailure = failure.describePhoneMicrophoneCaptureFailure(),
                ),
            )
        }
    }

    private fun rejectStart(
        message: String,
        failure: Throwable? = null,
    ): PhoneMicrophoneStartResult.Rejected {
        synchronized(lock) {
            val phase = if (closed) PhoneMicrophoneCapturePhase.CLOSED else PhoneMicrophoneCapturePhase.FAILED
            updateStateLocked(
                currentSnapshotLocked().copy(
                    phase = phase,
                    terminalReason = PhoneMicrophoneCaptureTerminalReason.START_REJECTED,
                    foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                    wakeLockHeld = executionLease.snapshot().wakeLockHeld,
                    lastFailure = failure?.describePhoneMicrophoneCaptureFailure() ?: message,
                ),
            )
        }
        return PhoneMicrophoneStartResult.Rejected(
            message = message,
            failureClass = failure?.javaClass?.name,
        )
    }

    private fun currentSnapshotLocked(): PhoneMicrophoneCaptureSnapshot = mutableState.value

    private fun updateStateLocked(snapshot: PhoneMicrophoneCaptureSnapshot) {
        mutableState.value = snapshot
        PhoneMicrophoneCaptureDiagnostics.record(snapshot)
    }

    private companion object {
        const val TAG = "VoicaPhoneMicCapture"
        val SOURCE_FALLBACK_ORDER =
            listOf(
                PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
                PhoneMicrophoneAudioSource.MIC,
            )
        val BLUETOOTH_SOURCE_FALLBACK_ORDER =
            listOf(
                PhoneMicrophoneAudioSource.VOICE_COMMUNICATION,
                PhoneMicrophoneAudioSource.MIC,
                PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
            )
    }
}

private class AndroidPhoneMicrophoneAudioRecordFactory(
    context: Context,
) : PhoneMicrophoneAudioRecordFactory {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun minimumBufferBytes(): Int =
        AudioRecord.getMinBufferSize(
            CanonicalPcmProfile.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

    override fun create(
        audioSource: PhoneMicrophoneAudioSource,
        bufferBytes: Int,
        inputRequest: PhoneMicrophoneInputRequest,
    ): PhoneMicrophoneAudioRecordHandle {
        val format =
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CanonicalPcmProfile.SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        val record =
            AudioRecord.Builder()
                .setAudioSource(audioSource.platformValue)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        val preferredAccepted =
            when (inputRequest) {
                PhoneMicrophoneInputRequest.Auto -> null
                is PhoneMicrophoneInputRequest.SpecificDevice -> {
                    val requestedDevice =
                        audioManager
                            .getDevices(AudioManager.GET_DEVICES_INPUTS)
                            .firstOrNull { it.isSource && it.id == inputRequest.deviceId }
                    requestedDevice != null &&
                        runCatching { record.setPreferredDevice(requestedDevice) }.getOrDefault(false)
                }
            }
        return AndroidPhoneMicrophoneAudioRecordHandle(record, preferredAccepted)
    }
}

private class AndroidPhoneMicrophoneAudioRecordHandle(
    private val record: AudioRecord,
    override val preferredDeviceAccepted: Boolean?,
) : PhoneMicrophoneAudioRecordHandle {
    override val initialized: Boolean
        get() = record.state == AudioRecord.STATE_INITIALIZED

    override val recording: Boolean
        get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override val audioSessionId: Int
        get() = record.audioSessionId

    override val route: PhoneMicrophoneRoute?
        get() = runCatching { record.routedDevice?.toPhoneMicrophoneRoute() }.getOrNull()

    override val clientSilenced: Boolean?
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { record.activeRecordingConfiguration?.isClientSilenced }.getOrNull()
            } else {
                null
            }

    override fun start() {
        record.startRecording()
    }

    override fun read(buffer: ShortArray): Int =
        record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

    override fun stop() {
        record.stop()
    }

    override fun release() {
        record.release()
    }
}

private fun AudioDeviceInfo.toPhoneMicrophoneRoute(): PhoneMicrophoneRoute =
    PhoneMicrophoneRoute(
        deviceId = id,
        deviceType = type,
        productName = productName.toString().ifBlank { "未知音频设备" },
        address =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                address.trim().takeIf { it.isNotEmpty() }
            } else {
                null
            },
    )

private fun PhoneMicrophoneInputRequest.toDiagnosticsName(): String =
    when (this) {
        PhoneMicrophoneInputRequest.Auto -> "AUTO"
        is PhoneMicrophoneInputRequest.SpecificDevice -> "SPECIFIC_DEVICE:$deviceId"
    }

private fun formatDbfs(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatRatio(value: Double): String = String.format(Locale.US, "%.4f", value)

private const val PHONE_MICROPHONE_CLIENT_SILENCED_MESSAGE =
    "麦克风被其他应用占用，采集已中断；系统静音数据未写入录音"

private class PhoneMicrophoneClientSilencedException :
    IllegalStateException(PHONE_MICROPHONE_CLIENT_SILENCED_MESSAGE)

private fun Throwable.describePhoneMicrophoneCaptureFailure(): String =
    when (this) {
        is PhoneMicrophoneClientSilencedException -> PHONE_MICROPHONE_CLIENT_SILENCED_MESSAGE
        else -> "${javaClass.simpleName}:${message.orEmpty()}"
    }
