package io.github.ioannes78.voica

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneP6DiagnosticsTest {
    @Test
    fun `P6 diagnostics keep requested preferred actual route and PCM evidence together`() {
        val selectedDevice =
            PhoneMicrophoneAudioDeviceDescriptor(
                deviceId = 17,
                deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                productName = "QA Headset",
                address = "AA:BB",
                isSource = true,
            )
        val selection =
            PhoneMicrophoneInputSelectionSnapshot(
                request = PhoneMicrophoneInputRequest.SpecificDevice(17),
                inventoryGeneration = 4L,
                devices = listOf(selectedDevice),
            )
        val capability =
            PhoneMicrophoneInputCapabilitySnapshot(
                deviceId = 17,
                deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                typeLabel = "BLUETOOTH_SCO",
                productName = "QA Headset",
                address = "AA:BB",
                isSource = true,
                sampleRates = listOf(8_000, 16_000),
                channelCounts = listOf(1),
                encodings = listOf(AudioFormat.ENCODING_PCM_16BIT),
            )
        val capture =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.STOPPED,
                requestedInput = PhoneMicrophoneInputRequest.SpecificDevice(17),
                selectedAudioSource = PhoneMicrophoneAudioSource.MIC,
                preferredDeviceAccepted = true,
                route =
                    PhoneMicrophoneRoute(
                        deviceId = 17,
                        deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        productName = "QA Headset",
                        address = "AA:BB",
                    ),
                routeGeneration = 2L,
                routeChangeCount = 1L,
                lastRouteChangeReason = PhoneMicrophoneRouteChangeReason.PLATFORM_REROUTE,
                clientSilenced = false,
                readCalls = 12L,
                samplesRead = 15_360L,
                readErrors = 0L,
                consumerErrors = 0L,
            )

        val text =
            buildPhoneMicrophoneP6DiagnosticsText(
                selection = selection,
                capture = capture,
                capabilities = listOf(capability),
            )

        assertTrue(text.contains("inventoryGeneration=4 devices=1"))
        assertTrue(text.contains("currentSelection=MANUAL(id=17) selectedDeviceMissing=false"))
        assertTrue(text.contains("type:BLUETOOTH_SCO(${AudioDeviceInfo.TYPE_BLUETOOTH_SCO})"))
        assertTrue(text.contains("rates:[8000, 16000]"))
        assertTrue(text.contains("channels:[1]"))
        assertTrue(text.contains("encodings:[PCM16(${AudioFormat.ENCODING_PCM_16BIT})]"))
        assertTrue(text.contains("request=MANUAL(id=17) preferredAccepted=true"))
        assertTrue(text.contains("actualRoute=id:17 type:BLUETOOTH_SCO"))
        assertTrue(text.contains("routeGeneration=2 routeChanges=1"))
        assertTrue(text.contains("clientSilenced=false reads=12 samples=15360 readErrors=0 consumerErrors=0"))
        assertTrue(text.contains("source=MIC"))
    }

    @Test
    fun `manual selection stays missing instead of silently becoming AUTO`() {
        val selection =
            PhoneMicrophoneInputSelectionSnapshot(
                request = PhoneMicrophoneInputRequest.SpecificDevice(29),
                inventoryGeneration = 8L,
                devices = emptyList(),
            )

        val text =
            buildPhoneMicrophoneP6DiagnosticsText(
                selection = selection,
                capture = null,
                capabilities = emptyList(),
            )

        assertTrue(selection.selectedDeviceMissing)
        assertTrue(text.contains("currentSelection=MANUAL(id=29) selectedDeviceMissing=true"))
        assertTrue(text.contains("capture=n/a"))
    }

    @Test
    fun `known P6 input type labels remain distinct`() {
        assertEquals("BUILTIN_MIC", phoneMicrophoneInputTypeLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("WIRED_HEADSET", phoneMicrophoneInputTypeLabel(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("USB_DEVICE", phoneMicrophoneInputTypeLabel(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("USB_HEADSET", phoneMicrophoneInputTypeLabel(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("BLUETOOTH_SCO", phoneMicrophoneInputTypeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }
}
