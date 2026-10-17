package io.github.ioannes78.voica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class PhoneMicrophoneBluetoothCompatPath {
    NONE,
    COMMUNICATION_DEVICE,
    LEGACY_SCO,
}

internal enum class PhoneMicrophoneBluetoothCommunicationPhase {
    IDLE,
    PREPARING,
    ACTIVE,
    FAILED,
    RELEASING,
    RELEASED,
}

internal data class PhoneMicrophoneBluetoothCommunicationSnapshot(
    val phase: PhoneMicrophoneBluetoothCommunicationPhase =
        PhoneMicrophoneBluetoothCommunicationPhase.IDLE,
    val path: PhoneMicrophoneBluetoothCompatPath = PhoneMicrophoneBluetoothCompatPath.NONE,
    val requestedInputDeviceId: Int? = null,
    val requestedInputType: Int? = null,
    val requestedInputProductName: String? = null,
    val requestedInputAddress: String? = null,
    val modeBefore: Int? = null,
    val modeActive: Int? = null,
    val modeRestored: Int? = null,
    val requestedCommunicationDeviceId: Int? = null,
    val requestedCommunicationDeviceType: Int? = null,
    val requestedCommunicationDeviceProductName: String? = null,
    val requestedCommunicationDeviceAddress: String? = null,
    val actualCommunicationDeviceId: Int? = null,
    val actualCommunicationDeviceType: Int? = null,
    val actualCommunicationDeviceProductName: String? = null,
    val actualCommunicationDeviceAddress: String? = null,
    val setCommunicationDeviceAccepted: Boolean? = null,
    val legacyScoState: Int? = null,
    val setupLatencyMs: Long? = null,
    val cleanupSucceeded: Boolean? = null,
    val failureCode: String? = null,
    val failureDetail: String? = null,
)

internal object PhoneMicrophoneBluetoothCommunicationDiagnostics {
    private val lock = Any()
    private var retainedSnapshot = PhoneMicrophoneBluetoothCommunicationSnapshot()

    fun snapshot(): PhoneMicrophoneBluetoothCommunicationSnapshot =
        synchronized(lock) { retainedSnapshot }

    fun record(snapshot: PhoneMicrophoneBluetoothCommunicationSnapshot) {
        synchronized(lock) { retainedSnapshot = snapshot }
    }

    fun clearForTest() {
        synchronized(lock) {
            retainedSnapshot = PhoneMicrophoneBluetoothCommunicationSnapshot()
        }
    }
}

internal sealed interface PhoneMicrophoneBluetoothCommunicationPreparation {
    data object NotRequired : PhoneMicrophoneBluetoothCommunicationPreparation

    data class Active(
        val path: PhoneMicrophoneBluetoothCompatPath,
    ) : PhoneMicrophoneBluetoothCommunicationPreparation

    data class Rejected(
        val code: String,
        val message: String,
    ) : PhoneMicrophoneBluetoothCommunicationPreparation
}

internal interface PhoneMicrophoneBluetoothCommunicationController : Closeable {
    fun prepare(inputRequest: PhoneMicrophoneInputRequest): PhoneMicrophoneBluetoothCommunicationPreparation

    fun release()

    fun snapshot(): PhoneMicrophoneBluetoothCommunicationSnapshot
}

/**
 * P6-5 Bluetooth HFP/SCO compatibility adapter.
 *
 * This object owns no AudioRecord. It only establishes and tears down the Android communication
 * audio session that must exist before the existing single AudioRecord is started. API 31+ uses
 * setCommunicationDevice(); API 26-30 keeps the legacy startBluetoothSco() path isolated.
 */
internal class AndroidPhoneMicrophoneBluetoothCommunicationController(
    context: Context,
) : PhoneMicrophoneBluetoothCommunicationController {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lock = Any()

    private var activeInputDeviceId: Int? = null
    private var activePath = PhoneMicrophoneBluetoothCompatPath.NONE
    private var modeBeforeActiveSession: Int? = null
    private var legacyScoRequested = false
    private var closed = false
    private var snapshot = PhoneMicrophoneBluetoothCommunicationSnapshot()

    override fun prepare(
        inputRequest: PhoneMicrophoneInputRequest,
    ): PhoneMicrophoneBluetoothCommunicationPreparation {
        synchronized(lock) {
            if (closed) {
                return rejectLocked(
                    code = "BLUETOOTH_COMMUNICATION_CLOSED",
                    message = "蓝牙耳机录音通道已关闭，请重新进入录音页面",
                    startedAtMs = SystemClock.elapsedRealtime(),
                )
            }
        }

        val requestedDevice = requestedBluetoothScoInput(inputRequest)
        if (requestedDevice == null) {
            if (synchronized(lock) { activePath != PhoneMicrophoneBluetoothCompatPath.NONE }) {
                release()
            }
            return PhoneMicrophoneBluetoothCommunicationPreparation.NotRequired
        }

        synchronized(lock) {
            if (
                activeInputDeviceId == requestedDevice.id &&
                activePath != PhoneMicrophoneBluetoothCompatPath.NONE &&
                isSessionStillActiveLocked(requestedDevice)
            ) {
                val current = snapshot.copy(
                    phase = PhoneMicrophoneBluetoothCommunicationPhase.ACTIVE,
                    failureCode = null,
                    failureDetail = null,
                )
                updateLocked(current)
                return PhoneMicrophoneBluetoothCommunicationPreparation.Active(activePath)
            }
        }

        if (synchronized(lock) { activePath != PhoneMicrophoneBluetoothCompatPath.NONE }) {
            release()
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        val modeBefore = runCatching { audioManager.mode }.getOrNull()
        val path =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PhoneMicrophoneBluetoothCompatPath.COMMUNICATION_DEVICE
            } else {
                PhoneMicrophoneBluetoothCompatPath.LEGACY_SCO
            }
        synchronized(lock) {
            modeBeforeActiveSession = modeBefore
            updateLocked(
                PhoneMicrophoneBluetoothCommunicationSnapshot(
                    phase = PhoneMicrophoneBluetoothCommunicationPhase.PREPARING,
                    path = path,
                    requestedInputDeviceId = requestedDevice.id,
                    requestedInputType = requestedDevice.type,
                    requestedInputProductName = requestedDevice.productName.toString(),
                    requestedInputAddress = requestedDevice.normalizedAddress(),
                    modeBefore = modeBefore,
                ),
            )
        }

        val modeActivated =
            runCatching {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.mode
            }.getOrElse { failure ->
                return failAndCleanup(
                    code = "COMM_MODE_REJECTED",
                    message = "无法进入蓝牙耳机录音通信模式",
                    detail = failure.describeBluetoothCommunicationFailure(),
                    startedAtMs = startedAtMs,
                )
            }
        synchronized(lock) {
            updateLocked(snapshot.copy(modeActive = modeActivated))
        }
        if (modeActivated != AudioManager.MODE_IN_COMMUNICATION) {
            return failAndCleanup(
                code = "COMM_MODE_REJECTED",
                message = "系统未进入蓝牙耳机录音通信模式",
                detail = "mode=$modeActivated",
                startedAtMs = startedAtMs,
            )
        }

        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                prepareCommunicationDevice(requestedDevice, startedAtMs)
            } else {
                prepareLegacySco(requestedDevice, startedAtMs)
            }
        if (result is PhoneMicrophoneBluetoothCommunicationPreparation.Active) {
            synchronized(lock) {
                activeInputDeviceId = requestedDevice.id
                activePath = result.path
            }
        }
        return result
    }

    override fun release() {
        val releaseSnapshot = synchronized(lock) {
            if (activePath == PhoneMicrophoneBluetoothCompatPath.NONE) return
            updateLocked(snapshot.copy(phase = PhoneMicrophoneBluetoothCommunicationPhase.RELEASING))
            snapshot
        }
        var cleanupSucceeded = true
        when (releaseSnapshot.path) {
            PhoneMicrophoneBluetoothCompatPath.COMMUNICATION_DEVICE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    cleanupSucceeded =
                        runCatching {
                            audioManager.clearCommunicationDevice()
                            true
                        }.getOrDefault(false) && cleanupSucceeded
                }
            }

            PhoneMicrophoneBluetoothCompatPath.LEGACY_SCO -> {
                if (legacyScoRequested) {
                    @Suppress("DEPRECATION")
                    val stopped = runCatching {
                        audioManager.stopBluetoothSco()
                        true
                    }.getOrDefault(false)
                    cleanupSucceeded = stopped && cleanupSucceeded
                }
            }

            PhoneMicrophoneBluetoothCompatPath.NONE -> Unit
        }

        val restoredMode = restorePreviousMode()
        synchronized(lock) {
            legacyScoRequested = false
            activeInputDeviceId = null
            activePath = PhoneMicrophoneBluetoothCompatPath.NONE
            modeBeforeActiveSession = null
            updateLocked(
                releaseSnapshot.copy(
                    phase = PhoneMicrophoneBluetoothCommunicationPhase.RELEASED,
                    modeRestored = restoredMode,
                    cleanupSucceeded = cleanupSucceeded,
                ),
            )
        }
    }

    override fun snapshot(): PhoneMicrophoneBluetoothCommunicationSnapshot =
        synchronized(lock) { snapshot }

    override fun close() {
        release()
        synchronized(lock) { closed = true }
    }

    private fun requestedBluetoothScoInput(
        inputRequest: PhoneMicrophoneInputRequest,
    ): AudioDeviceInfo? {
        val request = inputRequest as? PhoneMicrophoneInputRequest.SpecificDevice ?: return null
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.isSource &&
                    it.id == request.deviceId &&
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
    }

    private fun prepareCommunicationDevice(
        requestedInput: AudioDeviceInfo,
        startedAtMs: Long,
    ): PhoneMicrophoneBluetoothCommunicationPreparation {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val available =
            runCatching { audioManager.availableCommunicationDevices }.getOrElse { failure ->
                return failAndCleanup(
                    code = "COMM_DEVICE_QUERY_FAILED",
                    message = "无法读取蓝牙通信设备",
                    detail = failure.describeBluetoothCommunicationFailure(),
                    startedAtMs = startedAtMs,
                )
            }
        val target = selectCommunicationDevice(requestedInput, available)
            ?: return failAndCleanup(
                code = "COMM_DEVICE_NOT_AVAILABLE",
                message = "系统尚未提供可用的蓝牙通话音频设备",
                detail = "available=${available.joinToString { it.type.toString() }}",
                startedAtMs = startedAtMs,
            )

        synchronized(lock) {
            updateLocked(
                snapshot.copy(
                    requestedCommunicationDeviceId = target.id,
                    requestedCommunicationDeviceType = target.type,
                    requestedCommunicationDeviceProductName = target.productName.toString(),
                    requestedCommunicationDeviceAddress = target.normalizedAddress(),
                ),
            )
        }

        val accepted = runCatching { audioManager.setCommunicationDevice(target) }.getOrDefault(false)
        synchronized(lock) {
            updateLocked(snapshot.copy(setCommunicationDeviceAccepted = accepted))
        }
        if (!accepted) {
            return failAndCleanup(
                code = "COMM_DEVICE_REJECTED",
                message = "系统拒绝启用蓝牙耳机录音通道",
                detail = "setCommunicationDevice=false",
                startedAtMs = startedAtMs,
            )
        }

        val deadline = SystemClock.elapsedRealtime() + COMMUNICATION_DEVICE_TIMEOUT_MS
        var actual = runCatching { audioManager.communicationDevice }.getOrNull()
        while (!sameCommunicationDevice(target, actual) && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(COMMUNICATION_DEVICE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            actual = runCatching { audioManager.communicationDevice }.getOrNull()
        }
        if (!sameCommunicationDevice(target, actual)) {
            return failAndCleanup(
                code = "COMM_DEVICE_TIMEOUT",
                message = "蓝牙耳机录音通道连接超时，请重新连接耳机后重试",
                detail = actual?.let(::communicationDeviceDescription) ?: "actual=n/a",
                startedAtMs = startedAtMs,
            )
        }

        val completedAtMs = SystemClock.elapsedRealtime()
        synchronized(lock) {
            updateLocked(
                snapshot.copy(
                    phase = PhoneMicrophoneBluetoothCommunicationPhase.ACTIVE,
                    actualCommunicationDeviceId = actual?.id,
                    actualCommunicationDeviceType = actual?.type,
                    actualCommunicationDeviceProductName = actual?.productName?.toString(),
                    actualCommunicationDeviceAddress = actual?.normalizedAddress(),
                    setupLatencyMs = completedAtMs - startedAtMs,
                    failureCode = null,
                    failureDetail = null,
                ),
            )
        }
        return PhoneMicrophoneBluetoothCommunicationPreparation.Active(
            PhoneMicrophoneBluetoothCompatPath.COMMUNICATION_DEVICE,
        )
    }

    @Suppress("DEPRECATION")
    private fun prepareLegacySco(
        requestedInput: AudioDeviceInfo,
        startedAtMs: Long,
    ): PhoneMicrophoneBluetoothCommunicationPreparation {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            return failAndCleanup(
                code = "SCO_UNAVAILABLE",
                message = "当前系统不支持通话外蓝牙 SCO 录音",
                detail = null,
                startedAtMs = startedAtMs,
            )
        }

        val state = AtomicInteger(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
        val latch = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val value =
                        intent?.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR,
                        ) ?: AudioManager.SCO_AUDIO_STATE_ERROR
                    state.set(value)
                    if (
                        value == AudioManager.SCO_AUDIO_STATE_CONNECTED ||
                        value == AudioManager.SCO_AUDIO_STATE_DISCONNECTED ||
                        value == AudioManager.SCO_AUDIO_STATE_ERROR
                    ) {
                        latch.countDown()
                    }
                }
            }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        val sticky =
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.getOrNull()
        val stickyState =
            sticky?.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            )
        if (stickyState != null) state.set(stickyState)

        val startFailure =
            runCatching {
                audioManager.startBluetoothSco()
                legacyScoRequested = true
            }.exceptionOrNull()
        if (startFailure != null) {
            runCatching { appContext.unregisterReceiver(receiver) }
            return failAndCleanup(
                code = "SCO_START_FAILED",
                message = "无法启动蓝牙 SCO 录音通道",
                detail = startFailure.describeBluetoothCommunicationFailure(),
                startedAtMs = startedAtMs,
            )
        }

        if (state.get() != AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            runCatching { latch.await(LEGACY_SCO_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        runCatching { appContext.unregisterReceiver(receiver) }
        val finalState = state.get()
        synchronized(lock) {
            updateLocked(
                snapshot.copy(
                    requestedCommunicationDeviceId = requestedInput.id,
                    requestedCommunicationDeviceType = requestedInput.type,
                    requestedCommunicationDeviceProductName = requestedInput.productName.toString(),
                    requestedCommunicationDeviceAddress = requestedInput.normalizedAddress(),
                    legacyScoState = finalState,
                ),
            )
        }
        if (finalState != AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            return failAndCleanup(
                code = "SCO_CONNECT_TIMEOUT",
                message = "蓝牙 SCO 录音通道连接失败或超时",
                detail = "scoState=$finalState",
                startedAtMs = startedAtMs,
            )
        }

        synchronized(lock) {
            updateLocked(
                snapshot.copy(
                    phase = PhoneMicrophoneBluetoothCommunicationPhase.ACTIVE,
                    setupLatencyMs = SystemClock.elapsedRealtime() - startedAtMs,
                    failureCode = null,
                    failureDetail = null,
                ),
            )
        }
        return PhoneMicrophoneBluetoothCommunicationPreparation.Active(
            PhoneMicrophoneBluetoothCompatPath.LEGACY_SCO,
        )
    }

    private fun failAndCleanup(
        code: String,
        message: String,
        detail: String?,
        startedAtMs: Long,
    ): PhoneMicrophoneBluetoothCommunicationPreparation.Rejected {
        var cleanupSucceeded = true
        val currentPath = synchronized(lock) { snapshot.path }
        if (currentPath == PhoneMicrophoneBluetoothCompatPath.COMMUNICATION_DEVICE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            cleanupSucceeded =
                runCatching {
                    audioManager.clearCommunicationDevice()
                    true
                }.getOrDefault(false) && cleanupSucceeded
        }
        if (currentPath == PhoneMicrophoneBluetoothCompatPath.LEGACY_SCO && legacyScoRequested) {
            @Suppress("DEPRECATION")
            val stopped = runCatching {
                audioManager.stopBluetoothSco()
                true
            }.getOrDefault(false)
            cleanupSucceeded = stopped && cleanupSucceeded
        }
        legacyScoRequested = false
        val restoredMode = restorePreviousMode()
        synchronized(lock) {
            activeInputDeviceId = null
            activePath = PhoneMicrophoneBluetoothCompatPath.NONE
            modeBeforeActiveSession = null
            updateLocked(
                snapshot.copy(
                    phase = PhoneMicrophoneBluetoothCommunicationPhase.FAILED,
                    modeRestored = restoredMode,
                    setupLatencyMs = SystemClock.elapsedRealtime() - startedAtMs,
                    cleanupSucceeded = cleanupSucceeded,
                    failureCode = code,
                    failureDetail = detail,
                ),
            )
        }
        return PhoneMicrophoneBluetoothCommunicationPreparation.Rejected(code, message)
    }

    private fun rejectLocked(
        code: String,
        message: String,
        startedAtMs: Long,
    ): PhoneMicrophoneBluetoothCommunicationPreparation.Rejected {
        updateLocked(
            snapshot.copy(
                phase = PhoneMicrophoneBluetoothCommunicationPhase.FAILED,
                setupLatencyMs = SystemClock.elapsedRealtime() - startedAtMs,
                failureCode = code,
                failureDetail = message,
            ),
        )
        return PhoneMicrophoneBluetoothCommunicationPreparation.Rejected(code, message)
    }

    private fun restorePreviousMode(): Int? {
        val previous = synchronized(lock) { modeBeforeActiveSession } ?: return runCatching { audioManager.mode }.getOrNull()
        return runCatching {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = previous
            }
            audioManager.mode
        }.getOrNull()
    }

    private fun isSessionStillActiveLocked(requestedInput: AudioDeviceInfo): Boolean =
        when (activePath) {
            PhoneMicrophoneBluetoothCompatPath.COMMUNICATION_DEVICE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val actual = runCatching { audioManager.communicationDevice }.getOrNull()
                    actual?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                        requestedInput.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } else {
                    false
                }

            PhoneMicrophoneBluetoothCompatPath.LEGACY_SCO -> legacyScoRequested
            PhoneMicrophoneBluetoothCompatPath.NONE -> false
        }

    private fun updateLocked(value: PhoneMicrophoneBluetoothCommunicationSnapshot) {
        snapshot = value
        PhoneMicrophoneBluetoothCommunicationDiagnostics.record(value)
    }

    private companion object {
        const val COMMUNICATION_DEVICE_TIMEOUT_MS = 10_000L
        const val COMMUNICATION_DEVICE_POLL_MS = 50L
        const val LEGACY_SCO_TIMEOUT_MS = 10_000L
    }
}

internal fun resolvePhoneMicrophoneBluetoothCommunicationSourceOrder(
    qaSnapshot: Stage16A16QaAudioSourceOverrideSnapshot,
    availableSources: List<PhoneMicrophoneAudioSource>,
): List<PhoneMicrophoneAudioSource> {
    require(availableSources.isNotEmpty())
    val productOrder =
        listOf(
            PhoneMicrophoneAudioSource.VOICE_COMMUNICATION,
            PhoneMicrophoneAudioSource.MIC,
            PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
        ).filter { it in availableSources } +
            availableSources.distinct().filter {
                it != PhoneMicrophoneAudioSource.VOICE_COMMUNICATION &&
                    it != PhoneMicrophoneAudioSource.MIC &&
                    it != PhoneMicrophoneAudioSource.VOICE_RECOGNITION
            }
    if (!qaSnapshot.enabled || qaSnapshot.mode == Stage16A16QaAudioSourceMode.AUTO) return productOrder
    return when (qaSnapshot.mode) {
        Stage16A16QaAudioSourceMode.AUTO -> productOrder
        Stage16A16QaAudioSourceMode.FORCE_VOICE_RECOGNITION ->
            listOf(PhoneMicrophoneAudioSource.VOICE_RECOGNITION)
        Stage16A16QaAudioSourceMode.FORCE_MIC -> listOf(PhoneMicrophoneAudioSource.MIC)
    }
}

private fun selectCommunicationDevice(
    requestedInput: AudioDeviceInfo,
    availableDevices: List<AudioDeviceInfo>,
): AudioDeviceInfo? =
    availableDevices
        .asSequence()
        .filter { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        .maxByOrNull { candidate -> bluetoothEndpointScore(requestedInput, candidate) }

private fun bluetoothEndpointScore(
    source: AudioDeviceInfo,
    sink: AudioDeviceInfo,
): Int {
    var score = 1
    val sourceAddress = source.normalizedAddress()
    val sinkAddress = sink.normalizedAddress()
    if (sourceAddress != null && sinkAddress != null && sourceAddress.equals(sinkAddress, ignoreCase = true)) {
        score += 100
    }
    if (source.productName.toString() == sink.productName.toString()) score += 10
    return score
}

private fun sameCommunicationDevice(
    requested: AudioDeviceInfo,
    actual: AudioDeviceInfo?,
): Boolean {
    if (actual == null) return false
    if (requested.id == actual.id) return true
    if (requested.type != actual.type) return false
    val requestedAddress = requested.normalizedAddress()
    val actualAddress = actual.normalizedAddress()
    if (requestedAddress != null && actualAddress != null) {
        return requestedAddress.equals(actualAddress, ignoreCase = true)
    }
    return requested.productName.toString() == actual.productName.toString()
}

private fun AudioDeviceInfo.normalizedAddress(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        address.trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }

private fun communicationDeviceDescription(device: AudioDeviceInfo): String =
    "id=${device.id} type=${device.type} product=${device.productName} address=${device.normalizedAddress() ?: "n/a"}"

private fun Throwable.describeBluetoothCommunicationFailure(): String =
    "${javaClass.simpleName}:${message.orEmpty()}"