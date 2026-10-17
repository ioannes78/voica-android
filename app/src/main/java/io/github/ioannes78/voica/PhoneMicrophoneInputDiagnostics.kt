package io.github.ioannes78.voica

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import java.util.Locale

/**
 * Read-only Android input capability snapshot used by Stage 16A.1.6 P6 diagnostics.
 *
 * This is deliberately separate from QS668/CB08 BLE GATT state. A Bluetooth GATT connection does
 * not imply that Android exposes an AudioDeviceInfo capture source.
 */
internal data class PhoneMicrophoneInputCapabilitySnapshot(
    val deviceId: Int,
    val deviceType: Int,
    val typeLabel: String,
    val productName: String,
    val address: String?,
    val isSource: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>,
)

internal fun queryPhoneMicrophoneInputCapabilities(
    context: Context,
): List<PhoneMicrophoneInputCapabilitySnapshot> {
    val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    return audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .asSequence()
        .filter { it.isSource }
        .map { device ->
            PhoneMicrophoneInputCapabilitySnapshot(
                deviceId = device.id,
                deviceType = device.type,
                typeLabel = phoneMicrophoneInputTypeLabel(device.type),
                productName = device.productName.toString().ifBlank { "未知音频设备" },
                address =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        device.address.trim().takeIf { it.isNotEmpty() }
                    } else {
                        null
                    },
                isSource = device.isSource,
                sampleRates = device.sampleRates.toList().distinct().sorted(),
                channelCounts = device.channelCounts.toList().distinct().sorted(),
                encodings = device.encodings.toList().distinct().sorted(),
            )
        }
        .sortedWith(
            compareBy<PhoneMicrophoneInputCapabilitySnapshot> { it.deviceType }
                .thenBy { it.productName }
                .thenBy { it.deviceId },
        )
        .toList()
}

/**
 * Stable diagnostic label for Android input-device types relevant to P6.
 *
 * TYPE_BLE_HEADSET is only evaluated behind its Android 12 / API 31 guard so minSdk 26 remains
 * supported. Unknown framework types are preserved numerically instead of being misclassified.
 */
internal fun phoneMicrophoneInputTypeLabel(deviceType: Int): String =
    when {
        deviceType == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        deviceType == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        deviceType == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        deviceType == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        else -> "TYPE_$deviceType"
    }

internal fun phoneMicrophoneInputUserLabel(deviceType: Int): String =
    when (phoneMicrophoneInputTypeLabel(deviceType)) {
        "BUILTIN_MIC" -> "手机内置麦克风"
        "WIRED_HEADSET" -> "有线耳机麦克风"
        "USB_DEVICE" -> "USB 音频输入"
        "USB_HEADSET" -> "USB 耳机麦克风"
        "BLUETOOTH_SCO" -> "蓝牙耳机麦克风"
        "BLE_HEADSET" -> "BLE Audio 麦克风"
        else -> "Android 输入设备 · type $deviceType"
    }

internal fun phoneMicrophoneInputCompactLabel(
    device: PhoneMicrophoneAudioDeviceDescriptor,
): String =
    when (phoneMicrophoneInputTypeLabel(device.deviceType)) {
        "BUILTIN_MIC" -> "内置麦克风"
        "WIRED_HEADSET" -> "有线耳麦"
        "USB_DEVICE",
        "USB_HEADSET",
        -> "USB 麦克风"
        "BLUETOOTH_SCO",
        "BLE_HEADSET",
        -> "蓝牙耳机"
        else -> device.productName.trim().ifBlank { "输入设备" }
    }

internal fun buildPhoneMicrophoneP6DiagnosticsText(
    selection: PhoneMicrophoneInputSelectionSnapshot,
    capture: PhoneMicrophoneCaptureSnapshot?,
    capabilities: List<PhoneMicrophoneInputCapabilitySnapshot>,
    bluetoothCommunication: PhoneMicrophoneBluetoothCommunicationSnapshot =
        PhoneMicrophoneBluetoothCommunicationDiagnostics.snapshot(),
): String =
    buildString {
        appendLine("[P6 Android input inventory]")
        appendLine("inventoryGeneration=${selection.inventoryGeneration} devices=${capabilities.size}")
        appendLine(
            "currentSelection=${selection.request.toP6DiagnosticsName()} " +
                "selectedDeviceMissing=${selection.selectedDeviceMissing}",
        )
        if (capabilities.isEmpty()) {
            appendLine("device=n/a")
        } else {
            capabilities.forEach { device ->
                appendLine(
                    "device=id:${device.deviceId} type:${device.typeLabel}(${device.deviceType}) " +
                        "product:${device.productName} address:${device.address ?: "n/a"} " +
                        "isSource:${device.isSource}",
                )
                appendLine(
                    "capabilities=id:${device.deviceId} " +
                        "rates:${device.sampleRates.toDiagnosticsList()} " +
                        "channels:${device.channelCounts.toDiagnosticsList()} " +
                        "encodings:${device.encodings.toEncodingDiagnosticsList()}",
                )
            }
        }

        appendLine("[P6 last AudioRecord route]")
        if (capture == null) {
            appendLine("capture=n/a")
        } else {
            val route = capture.route
            appendLine(
                "request=${capture.requestedInput.toP6DiagnosticsName()} " +
                    "preferredAccepted=${capture.preferredDeviceAccepted ?: "n/a"}",
            )
            appendLine(
                "actualRoute=" +
                    if (route == null) {
                        "n/a"
                    } else {
                        "id:${route.deviceId} type:${phoneMicrophoneInputTypeLabel(route.deviceType)}" +
                            "(${route.deviceType}) product:${route.productName} " +
                            "address:${route.address ?: "n/a"}"
                    },
            )
            appendLine(
                "routeGeneration=${capture.routeGeneration} routeChanges=${capture.routeChangeCount} " +
                    "routeReason=${capture.lastRouteChangeReason?.name ?: "n/a"}",
            )
            appendLine(
                "clientSilenced=${capture.clientSilenced ?: "n/a"} reads=${capture.readCalls} " +
                    "samples=${capture.samplesRead} readErrors=${capture.readErrors} " +
                    "consumerErrors=${capture.consumerErrors}",
            )
            capture.signal?.let { signal ->
                appendLine(
                    "signal=peak:${signal.peakSample} peakDbfs:${signal.peakDbfs.toP6Dbfs()} " +
                        "rms:${signal.rms.toP6Amplitude()} rmsDbfs:${signal.rmsDbfs.toP6Dbfs()} " +
                        "zero:${signal.zeroSamples}/${signal.zeroSampleRatio.toP6Ratio()} " +
                        "clip:${signal.clippingSamples}/${signal.clippingRatio.toP6Ratio()}",
                )
            } ?: appendLine("signal=n/a")
            capture.signalSummary?.let { summary ->
                appendLine(
                    "signalSummary=frames:${summary.frameCount} samples:${summary.sampleCount} " +
                        "peak:${summary.peakSample} peakDbfs:${summary.peakDbfs.toP6Dbfs()} " +
                        "rms:${summary.rms.toP6Amplitude()} rmsDbfs:${summary.rmsDbfs.toP6Dbfs()} " +
                        "noiseFloorCandidateDbfs:${summary.noiseFloorCandidateDbfs.toP6Dbfs()} " +
                        "zero:${summary.zeroSamples}/${summary.zeroSampleRatio.toP6Ratio()} " +
                        "clip:${summary.clippingSamples}/${summary.clippingRatio.toP6Ratio()}",
                )
            } ?: appendLine("signalSummary=n/a")
            appendLine(
                "phase=${capture.phase} source=${capture.selectedAudioSource?.diagnosticsName ?: "n/a"} " +
                    "lastFailure=${capture.lastFailure ?: "n/a"}",
            )
        }

        appendLine("[P6 Bluetooth communication]")
        appendLine(
            "phase=${bluetoothCommunication.phase} path=${bluetoothCommunication.path}",
        )
        appendLine(
            "requestedInput=" +
                bluetoothCommunication.deviceDiagnostics(
                    id = bluetoothCommunication.requestedInputDeviceId,
                    type = bluetoothCommunication.requestedInputType,
                    product = bluetoothCommunication.requestedInputProductName,
                    address = bluetoothCommunication.requestedInputAddress,
                ),
        )
        appendLine(
            "modeBefore=${bluetoothCommunication.modeBefore.toP6AudioMode()} " +
                "modeActive=${bluetoothCommunication.modeActive.toP6AudioMode()} " +
                "modeRestored=${bluetoothCommunication.modeRestored.toP6AudioMode()}",
        )
        appendLine(
            "requestedComm=" +
                bluetoothCommunication.deviceDiagnostics(
                    id = bluetoothCommunication.requestedCommunicationDeviceId,
                    type = bluetoothCommunication.requestedCommunicationDeviceType,
                    product = bluetoothCommunication.requestedCommunicationDeviceProductName,
                    address = bluetoothCommunication.requestedCommunicationDeviceAddress,
                ),
        )
        appendLine(
            "actualComm=" +
                bluetoothCommunication.deviceDiagnostics(
                    id = bluetoothCommunication.actualCommunicationDeviceId,
                    type = bluetoothCommunication.actualCommunicationDeviceType,
                    product = bluetoothCommunication.actualCommunicationDeviceProductName,
                    address = bluetoothCommunication.actualCommunicationDeviceAddress,
                ),
        )
        appendLine(
            "setCommunicationDeviceAccepted=${bluetoothCommunication.setCommunicationDeviceAccepted ?: "n/a"} " +
                "legacyScoState=${bluetoothCommunication.legacyScoState.toP6ScoState()}",
        )
        appendLine(
            "setupLatencyMs=${bluetoothCommunication.setupLatencyMs ?: "n/a"} " +
                "cleanupSucceeded=${bluetoothCommunication.cleanupSucceeded ?: "n/a"}",
        )
        append(
            "failure=${bluetoothCommunication.failureCode ?: "n/a"}/" +
                (bluetoothCommunication.failureDetail ?: "n/a"),
        )
    }

private fun PhoneMicrophoneInputRequest.toP6DiagnosticsName(): String =
    when (this) {
        PhoneMicrophoneInputRequest.Auto -> "AUTO"
        is PhoneMicrophoneInputRequest.SpecificDevice -> "MANUAL(id=$deviceId)"
    }

private fun List<Int>.toDiagnosticsList(): String =
    if (isEmpty()) "unreported" else joinToString(prefix = "[", postfix = "]")

private fun List<Int>.toEncodingDiagnosticsList(): String =
    if (isEmpty()) {
        "unreported"
    } else {
        joinToString(prefix = "[", postfix = "]") { encoding ->
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> "PCM16($encoding)"
                AudioFormat.ENCODING_PCM_8BIT -> "PCM8($encoding)"
                AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT($encoding)"
                else -> encoding.toString()
            }
        }
    }

private fun PhoneMicrophoneBluetoothCommunicationSnapshot.deviceDiagnostics(
    id: Int?,
    type: Int?,
    product: String?,
    address: String?,
): String =
    if (id == null && type == null && product == null && address == null) {
        "n/a"
    } else {
        "id:${id ?: "n/a"} type:${type?.let(::phoneMicrophoneInputTypeLabel) ?: "n/a"}" +
            "(${type ?: "n/a"}) product:${product ?: "n/a"} address:${address ?: "n/a"}"
    }

private fun Int?.toP6AudioMode(): String =
    when (this) {
        null -> "n/a"
        AudioManager.MODE_NORMAL -> "NORMAL($this)"
        AudioManager.MODE_RINGTONE -> "RINGTONE($this)"
        AudioManager.MODE_IN_CALL -> "IN_CALL($this)"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION($this)"
        else -> "MODE_$this"
    }

private fun Int?.toP6ScoState(): String =
    when (this) {
        null -> "n/a"
        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED($this)"
        AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED($this)"
        AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING($this)"
        AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR($this)"
        else -> "STATE_$this"
    }

private fun Double.toP6Dbfs(): String = String.format(Locale.US, "%.1f dBFS", this)

private fun Double.toP6Amplitude(): String = String.format(Locale.US, "%.1f", this)

private fun Double.toP6Ratio(): String = String.format(Locale.US, "%.2f%%", this * 100.0)