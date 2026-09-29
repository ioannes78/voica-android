package io.github.ioannes78.voica.ble

import android.content.Context

internal class RememberedDeviceStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readAddress(): String? =
        preferences.getString(KEY_LAST_CONNECTED_ADDRESS, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun remember(address: String) {
        val normalized = address.trim()
        if (normalized.isEmpty()) return
        preferences.edit()
            .putString(KEY_LAST_CONNECTED_ADDRESS, normalized)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "voica_ble"
        const val KEY_LAST_CONNECTED_ADDRESS = "last_connected_address"
    }
}

internal object RememberedDeviceAutoConnectPolicy {
    fun shouldAttempt(
        foreground: Boolean,
        userDisconnectedThisProcess: Boolean,
        rememberedAddress: String?,
        currentState: DeviceConnectionState,
    ): Boolean {
        if (!foreground || userDisconnectedThisProcess || rememberedAddress.isNullOrBlank()) {
            return false
        }
        return when (currentState) {
            DeviceConnectionState.Idle,
            is DeviceConnectionState.Disconnected,
            is DeviceConnectionState.Error,
            -> true
            else -> false
        }
    }
}
