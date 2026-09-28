package io.github.ioannes78.voica.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object BlePermissionPolicy {
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Set<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Set<String> = requiredPermissions(sdkInt).filterTo(linkedSetOf()) { permission ->
        context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
    }
}
