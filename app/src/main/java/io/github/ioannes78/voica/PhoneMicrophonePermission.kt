package io.github.ioannes78.voica

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

internal enum class PhoneMicrophonePermissionAction {
    READY,
    REQUEST_PERMISSION,
    SHOW_RATIONALE_AND_REQUEST,
    OPEN_APP_SETTINGS,
}

internal data class PhoneMicrophonePermissionDecision(
    val action: PhoneMicrophonePermissionAction,
    val message: String,
)

internal class PhoneMicrophonePermissionRequestStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun requestedBefore(): Boolean = preferences.getBoolean(KEY_REQUESTED_BEFORE, false)

    fun markRequested() {
        preferences.edit().putBoolean(KEY_REQUESTED_BEFORE, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_microphone_permission"
        const val KEY_REQUESTED_BEFORE = "requested_before"
    }
}

/**
 * Pure permission policy for Stage 16A.1.
 *
 * `requestedBefore` is UI-owned durable/session metadata; Android's
 * `shouldShowRequestPermissionRationale=false` alone cannot distinguish first request from a
 * permanently denied permission. Keeping that distinction explicit avoids sending first-time users
 * directly to Settings.
 */
internal object PhoneMicrophonePermissionPolicy {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun resolve(
        granted: Boolean,
        requestedBefore: Boolean,
        shouldShowRationale: Boolean,
    ): PhoneMicrophonePermissionDecision =
        when {
            granted ->
                PhoneMicrophonePermissionDecision(
                    action = PhoneMicrophonePermissionAction.READY,
                    message = "麦克风权限已允许",
                )

            shouldShowRationale ->
                PhoneMicrophonePermissionDecision(
                    action = PhoneMicrophonePermissionAction.SHOW_RATIONALE_AND_REQUEST,
                    message = "手机录音需要麦克风权限，Voica 只会在你主动开始录音时使用麦克风",
                )

            requestedBefore ->
                PhoneMicrophonePermissionDecision(
                    action = PhoneMicrophonePermissionAction.OPEN_APP_SETTINGS,
                    message = "麦克风权限已被关闭，请在系统设置中允许后再录音",
                )

            else ->
                PhoneMicrophonePermissionDecision(
                    action = PhoneMicrophonePermissionAction.REQUEST_PERMISSION,
                    message = "需要麦克风权限才能开始手机录音",
                )
        }

    fun appSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
