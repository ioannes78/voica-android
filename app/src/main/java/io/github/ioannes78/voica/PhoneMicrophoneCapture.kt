package io.github.ioannes78.voica

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import java.io.Closeable
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

internal enum class PhoneMicrophoneAudioSource(
    val platformValue: Int,
    val diagnosticsName: String,
) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
    MIC(MediaRecorder.AudioSource.MIC, "MIC"),
}

internal data class PhoneMicrophoneRoute(
    val deviceId: Int,
    val deviceType: Int,
    val productName: String,
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

internal data class PhoneMicrophoneCaptureSnapshot(
    val phase: PhoneMicrophoneCapturePhase = PhoneMicrophoneCapturePhase.IDLE,
    val sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ,
    val channelCount: Int = CanonicalPcmProfile.CHANNEL_COUNT,
    val encoding: String = "PCM16",
    val selectedAudioSource: PhoneMicrophoneAudioSource? = null,
    val minBufferBytes: Int? = null,
    val effectiveBufferBytes: Int? = null,
    val readChunkSamples: Int = PhoneMicrophoneBufferPolicy.READ_CHUNK_SAMPLES,
    val audioSessionId: Int? = null,
    val route: PhoneMicrophoneRoute? = null,
    val clientSilenced: Boolean? = null,
    val readCalls: Long = 0L,
    val samplesRead: Long = 0L,
    val shortReads: Long = 0L,
    val readErrors: Long = 0L,
    val consumerErrors: Long = 0L,
    val lastReadCode: Int? = null,
    val foregroundPhase: PhoneMicrophoneForegroundPhase = PhoneMicrophoneForegroundPhase.IDLE,
    val wakeLockHeld: Boolean = false,
    val lastFailure: String? = null,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A.1 phone microphone capture]")
        appendLine(
            "phase=$phase source=${selectedAudioSource?.diagnosticsName ?: "n/a"} " +
                "format=${sampleRateHz}Hz/${channelCount}ch/$encoding",
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
            "route=${route?.productName ?: "n/a"}/${route?.deviceType ?: -1}/${route?.deviceId ?: -1} " +
                "clientSilenced=${clientSilenced ?: "n/a"}",
        )
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
    ): PhoneMicrophoneAudioRecordHandle
}

/**
 * Single-owner Android microphone capture foundation for both Stage 16A.1 products.
 *
 * This class owns exactly one AudioRecord at a time. It has no ASR dependency and never silently
 * drops PCM: consumer failure terminates the capture as FAILED. P2 will attach the formal recording
 * sink; P3 will fan out from that recording-owned path to the bounded realtime ASR consumer.
 */
internal class PhoneMicrophoneCapture(
    context: Context,
    private val audioRecordFactory: PhoneMicrophoneAudioRecordFactory =
        AndroidPhoneMicrophoneAudioRecordFactory(),
    private val executionLease: PhoneMicrophoneExecutionLease =
        PhoneMicrophoneExecutionLease.android(context),
    private val permissionChecker: (Context) -> Boolean = { value ->
        ContextCompat.checkSelfPermission(value, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val mutableState = MutableStateFlow(PhoneMicrophoneCaptureSnapshot())
    val state: StateFlow<PhoneMicrophoneCaptureSnapshot> = mutableState.asStateFlow()

    private var activeRecord: PhoneMicrophoneAudioRecordHandle? = null
    private var captureJob: Job? = null
    private var stopRequested = false
    private var closed = false

    fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String = "正在使用手机麦克风录音",
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
                        lastFailure = "需要麦克风权限才能开始手机录音",
                    ),
                )
                return PhoneMicrophoneStartResult.Rejected("需要麦克风权限才能开始手机录音")
            }
            stopRequested = false
            updateStateLocked(
                PhoneMicrophoneCaptureSnapshot(phase = PhoneMicrophoneCapturePhase.STARTING),
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

        var selectedSource: PhoneMicrophoneAudioSource? = null
        var selectedRecord: PhoneMicrophoneAudioRecordHandle? = null
        var lastFailure: Throwable? = null
        for (source in SOURCE_FALLBACK_ORDER) {
            if (synchronized(lock) { closed || stopRequested }) break

            val candidate =
                try {
                    audioRecordFactory.create(source, effectiveBufferBytes)
                } catch (failure: Throwable) {
                    lastFailure = failure
                    null
                } ?: continue

            if (!candidate.initialized) {
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

            selectedSource = source
            selectedRecord = candidate
            break
        }

        val record = selectedRecord
        val source = selectedSource
        if (record == null || source == null) {
            executionLease.release()
            return if (synchronized(lock) { closed || stopRequested }) {
                rejectStart("手机录音已取消")
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
            executionLease.release()
            return rejectStart("手机录音前台服务未能保持运行，请重试")
        }

        synchronized(lock) {
            if (closed || stopRequested || activeRecord != null) {
                runCatching { record.stop() }
                runCatching { record.release() }
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
                    selectedAudioSource = source,
                    minBufferBytes = minBufferBytes,
                    effectiveBufferBytes = effectiveBufferBytes,
                    audioSessionId = record.audioSessionId,
                    route = record.route,
                    clientSilenced = record.clientSilenced,
                    foregroundPhase = foregroundPhaseBeforeRun,
                    wakeLockHeld = leaseSnapshot.wakeLockHeld,
                    lastFailure = leaseSnapshot.lastFailure,
                ),
            )
            captureJob = workerScope.launch { captureLoop(record, consumer) }
        }

        return PhoneMicrophoneStartResult.Started(
            audioSource = source,
            minBufferBytes = minBufferBytes,
            effectiveBufferBytes = effectiveBufferBytes,
        )
    }

    fun requestStop() {
        val record = synchronized(lock) {
            when (currentSnapshotLocked().phase) {
                PhoneMicrophoneCapturePhase.STARTING -> {
                    stopRequested = true
                    updateStateLocked(
                        currentSnapshotLocked().copy(phase = PhoneMicrophoneCapturePhase.STOPPING),
                    )
                    null
                }

                PhoneMicrophoneCapturePhase.RECORDING -> {
                    stopRequested = true
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

    suspend fun stopAndAwait(timeoutMs: Long = 2_000L): Boolean {
        require(timeoutMs > 0L)
        requestStop()
        val job = synchronized(lock) { captureJob } ?: return true
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    fun snapshot(): PhoneMicrophoneCaptureSnapshot = mutableState.value

    override fun close() {
        val record = synchronized(lock) {
            if (closed) return
            closed = true
            stopRequested = true
            activeRecord
        }
        runCatching { record?.stop() }
        if (record == null) {
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
    ) {
        val buffer = ShortArray(PhoneMicrophoneBufferPolicy.READ_CHUNK_SAMPLES)
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

                val ownedSamples = buffer.copyOf(readCount)
                val frameStart = synchronized(lock) { currentSnapshotLocked().samplesRead }
                try {
                    consumer.onPcm(
                        PhoneMicrophonePcmFrame(
                            startSampleIndex = frameStart,
                            samples = ownedSamples,
                        ),
                    )
                } catch (failure: Throwable) {
                    terminalFailure = failure
                    incrementConsumerError(readCount, buffer.size, record, failure)
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
                            route = record.route,
                            clientSilenced = record.clientSilenced,
                            foregroundPhase = PhoneMicrophoneForegroundDiagnostics.state.value.phase,
                            wakeLockHeld = executionLease.snapshot().wakeLockHeld,
                        ),
                    )
                }
            }
        } finally {
            runCatching { if (record.recording) record.stop() }
            runCatching { record.release() }
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
                    route = record.route,
                    clientSilenced = record.clientSilenced,
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
    }
}

private class AndroidPhoneMicrophoneAudioRecordFactory : PhoneMicrophoneAudioRecordFactory {
    override fun minimumBufferBytes(): Int =
        AudioRecord.getMinBufferSize(
            CanonicalPcmProfile.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

    override fun create(
        audioSource: PhoneMicrophoneAudioSource,
        bufferBytes: Int,
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
        return AndroidPhoneMicrophoneAudioRecordHandle(record)
    }
}

private class AndroidPhoneMicrophoneAudioRecordHandle(
    private val record: AudioRecord,
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
    )

private fun Throwable.describePhoneMicrophoneCaptureFailure(): String =
    "${javaClass.simpleName}:${message.orEmpty()}"
