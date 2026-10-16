package io.github.ioannes78.voica

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class PhoneMicrophoneInputSelectionSnapshot(
    val request: PhoneMicrophoneInputRequest = PhoneMicrophoneInputRequest.Auto,
    val inventoryGeneration: Long = 0L,
    val devices: List<PhoneMicrophoneAudioDeviceDescriptor> = emptyList(),
) {
    val selectedDevice: PhoneMicrophoneAudioDeviceDescriptor?
        get() =
            (request as? PhoneMicrophoneInputRequest.SpecificDevice)?.let { selected ->
                devices.firstOrNull { it.isSource && it.deviceId == selected.deviceId }
            }

    val selectedDeviceMissing: Boolean
        get() = request is PhoneMicrophoneInputRequest.SpecificDevice && selectedDevice == null
}

internal fun applyPhoneMicrophoneInputInventory(
    current: PhoneMicrophoneInputSelectionSnapshot,
    inventory: PhoneMicrophoneAudioDeviceInventorySnapshot,
): PhoneMicrophoneInputSelectionSnapshot =
    current.copy(
        inventoryGeneration = inventory.generation,
        devices = inventory.devices.filter { it.isSource },
    )

internal fun selectPhoneMicrophoneInputDevice(
    current: PhoneMicrophoneInputSelectionSnapshot,
    deviceId: Int,
): PhoneMicrophoneInputSelectionSnapshot? {
    val device = current.devices.firstOrNull { it.isSource && it.deviceId == deviceId } ?: return null
    return current.copy(request = PhoneMicrophoneInputRequest.SpecificDevice(device.deviceId))
}

/**
 * Process-runtime input selection shared by phone recording and phone realtime transcription.
 *
 * The selected physical Android device is deliberately not persisted across process restarts because
 * AudioDeviceInfo ids are runtime identities. AUTO is therefore the safe process-start default.
 *
 * If a manually selected device disappears we keep the request instead of silently resetting to AUTO.
 * PhoneMicrophoneCapture will then reject a missing/mismatched route before formal WAV/ASR consumers.
 */
internal object PhoneMicrophoneInputSelectionRuntime {
    private val lock = Any()
    private val initialized = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(PhoneMicrophoneInputSelectionSnapshot())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var registry: PhoneMicrophoneAudioDeviceRegistry? = null
    private var registryJob: Job? = null

    val state: StateFlow<PhoneMicrophoneInputSelectionSnapshot> = mutableState.asStateFlow()

    fun ensureInitialized(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val created = PhoneMicrophoneAudioDeviceRegistry(context.applicationContext)
        synchronized(lock) {
            registry = created
            mutableState.value = applyPhoneMicrophoneInputInventory(mutableState.value, created.snapshot())
            registryJob =
                scope.launch {
                    created.state.collect { inventory ->
                        synchronized(lock) {
                            if (registry === created) {
                                mutableState.value =
                                    applyPhoneMicrophoneInputInventory(mutableState.value, inventory)
                            }
                        }
                    }
                }
        }
    }

    fun currentRequest(): PhoneMicrophoneInputRequest = mutableState.value.request

    fun selectAuto() {
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(request = PhoneMicrophoneInputRequest.Auto)
        }
    }

    fun selectDevice(deviceId: Int): Boolean =
        synchronized(lock) {
            val selected = selectPhoneMicrophoneInputDevice(mutableState.value, deviceId) ?: return false
            mutableState.value = selected
            true
        }
}
