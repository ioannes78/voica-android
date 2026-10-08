package io.github.ioannes78.voica

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.audio.PlaybackErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackForegroundServicePolicyTest {
    @Test
    fun mediaSessionErrorsUseLocalizedMessagesInsteadOfInternalEnumNames() {
        PlaybackErrorCode.entries.forEach { code ->
            val message = playbackErrorMessage(code)
            assertTrue(message.isNotBlank())
            assertNotEquals(code.name, message)
        }
        assertEquals("读取音频失败", playbackErrorMessage(PlaybackErrorCode.AUDIO_READ_FAILED))
    }

    @Test
    fun stopSynchronouslyCancelsDetachedPlaybackNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录音播放", NotificationManager.IMPORTANCE_LOW),
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("测试播放")
                .build(),
        )
        assertEquals(1, manager.activeNotifications.count { it.id == NOTIFICATION_ID })

        PlaybackForegroundService.stop(context)

        assertTrue(manager.activeNotifications.none { it.id == NOTIFICATION_ID })
    }

    private companion object {
        const val CHANNEL_ID = "voica-playback"
        const val NOTIFICATION_ID = 13021
    }
}
