package io.github.ioannes78.voica

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Persistent presentation-only state for Android system notification dismissals.
 *
 * This store never represents task success/failure, never changes coordinator/Room state, and never
 * acknowledges an in-app result. It only prevents the exact same terminal notification generation
 * from being re-published after the user explicitly swiped it away.
 */
internal class NotificationPresentationStore(
    context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isSystemDismissed(logicalKey: String): Boolean =
        logicalKey.isNotBlank() && preferences.contains(dismissedKey(logicalKey))

    @Synchronized
    fun markSystemDismissed(logicalKey: String) {
        if (logicalKey.isBlank()) return
        preferences.edit()
            .putLong(dismissedKey(logicalKey), nowMs())
            .apply()
        prune()
    }

    @Synchronized
    fun clearSystemDismissed(logicalKey: String) {
        if (logicalKey.isBlank()) return
        preferences.edit().remove(dismissedKey(logicalKey)).apply()
    }

    private fun prune() {
        val entries =
            preferences.all
                .mapNotNull { (key, value) ->
                    if (!key.startsWith(DISMISSED_PREFIX)) return@mapNotNull null
                    val timestamp = value as? Long ?: return@mapNotNull null
                    key to timestamp
                }
                .sortedByDescending { it.second }
        if (entries.size <= MAX_DISMISSED_KEYS) return
        val editor = preferences.edit()
        entries.drop(MAX_DISMISSED_KEYS).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun dismissedKey(logicalKey: String): String = "$DISMISSED_PREFIX$logicalKey"

    private companion object {
        const val PREFERENCES_NAME = "voica-notification-presentation"
        const val DISMISSED_PREFIX = "system-dismissed."
        const val MAX_DISMISSED_KEYS = 256
    }
}

internal fun notificationDismissPendingIntent(
    context: Context,
    logicalKey: String,
    requestCode: Int,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, NotificationPresentationDismissReceiver::class.java)
            .setAction(ACTION_NOTIFICATION_PRESENTATION_DISMISSED)
            .putExtra(EXTRA_NOTIFICATION_PRESENTATION_KEY, logicalKey),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** Records only an explicit Android-system-notification dismissal. */
internal class NotificationPresentationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_NOTIFICATION_PRESENTATION_DISMISSED) return
        val logicalKey =
            intent.getStringExtra(EXTRA_NOTIFICATION_PRESENTATION_KEY)
                ?.takeIf { it.isNotBlank() }
                ?: return
        NotificationPresentationStore(context).markSystemDismissed(logicalKey)
    }
}

internal const val ACTION_NOTIFICATION_PRESENTATION_DISMISSED =
    "io.github.ioannes78.voica.NOTIFICATION_PRESENTATION_DISMISSED"
internal const val EXTRA_NOTIFICATION_PRESENTATION_KEY =
    "io.github.ioannes78.voica.extra.NOTIFICATION_PRESENTATION_KEY"
