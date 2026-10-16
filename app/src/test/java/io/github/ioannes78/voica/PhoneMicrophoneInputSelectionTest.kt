package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneInputSelectionTest {
    private val builtIn =
        PhoneMicrophoneAudioDeviceDescriptor(
            deviceId = 16,
            deviceType = 15,
            productName = "Phone mic",
            address = "bottom",
            isSource = true,
        )
    private val usb =
        PhoneMicrophoneAudioDeviceDescriptor(
            deviceId = 42,
            deviceType = 11,
            productName = "USB mic",
            address = "usb",
            isSource = true,
        )

    @Test
    fun `inventory keeps auto as process default`() {
        val snapshot =
            applyPhoneMicrophoneInputInventory(
                PhoneMicrophoneInputSelectionSnapshot(),
                PhoneMicrophoneAudioDeviceInventorySnapshot(
                    generation = 3,
                    devices = listOf(builtIn, usb),
                ),
            )

        assertEquals(PhoneMicrophoneInputRequest.Auto, snapshot.request)
        assertEquals(3L, snapshot.inventoryGeneration)
        assertEquals(listOf(builtIn, usb), snapshot.devices)
    }

    @Test
    fun `manual selection accepts only currently exposed source device`() {
        val snapshot =
            PhoneMicrophoneInputSelectionSnapshot(
                devices = listOf(builtIn, usb),
            )

        val selected = selectPhoneMicrophoneInputDevice(snapshot, usb.deviceId)
        assertEquals(PhoneMicrophoneInputRequest.SpecificDevice(usb.deviceId), selected?.request)
        assertEquals(usb, selected?.selectedDevice)
        assertNull(selectPhoneMicrophoneInputDevice(snapshot, 999))
    }

    @Test
    fun `disappearing manual device stays requested and becomes missing`() {
        val selected =
            checkNotNull(
                selectPhoneMicrophoneInputDevice(
                    PhoneMicrophoneInputSelectionSnapshot(devices = listOf(builtIn, usb)),
                    usb.deviceId,
                ),
            )
        assertFalse(selected.selectedDeviceMissing)

        val afterDisconnect =
            applyPhoneMicrophoneInputInventory(
                selected,
                PhoneMicrophoneAudioDeviceInventorySnapshot(
                    generation = 4,
                    devices = listOf(builtIn),
                ),
            )

        assertEquals(PhoneMicrophoneInputRequest.SpecificDevice(usb.deviceId), afterDisconnect.request)
        assertTrue(afterDisconnect.selectedDeviceMissing)
        assertNull(afterDisconnect.selectedDevice)
    }
}
