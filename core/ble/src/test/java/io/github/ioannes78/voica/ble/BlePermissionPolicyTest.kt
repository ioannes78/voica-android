package io.github.ioannes78.voica.ble

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class BlePermissionPolicyTest {
    @Test
    fun api30UsesLocationPermission() {
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.requiredPermissions(30),
        )
    }

    @Test
    fun api31UsesScanAndConnect() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.requiredPermissions(31),
        )
    }
}
