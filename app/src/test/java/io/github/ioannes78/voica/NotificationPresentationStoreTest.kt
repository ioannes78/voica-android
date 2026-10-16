package io.github.ioannes78.voica

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationPresentationStoreTest {
    private lateinit var context: Context
    private var clock = 100L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearStore()
    }

    @After
    fun tearDown() {
        clearStore()
    }

    @Test
    fun `system dismissal survives presentation store recreation`() {
        val first = NotificationPresentationStore(context, nowMs = { clock++ })
        assertFalse(first.isSystemDismissed("completion:TRANSCRIPTION:tx-1"))

        first.markSystemDismissed("completion:TRANSCRIPTION:tx-1")

        val recreated = NotificationPresentationStore(context, nowMs = { clock++ })
        assertTrue(recreated.isSystemDismissed("completion:TRANSCRIPTION:tx-1"))
        assertFalse(recreated.isSystemDismissed("completion:TRANSCRIPTION:tx-2"))
    }

    @Test
    fun `clearing presentation dismissal does not affect unrelated generation`() {
        val store = NotificationPresentationStore(context, nowMs = { clock++ })
        store.markSystemDismissed("attention:summary:sum-1:FAILED")
        store.markSystemDismissed("attention:summary:sum-2:FAILED")

        store.clearSystemDismissed("attention:summary:sum-1:FAILED")

        assertFalse(store.isSystemDismissed("attention:summary:sum-1:FAILED"))
        assertTrue(store.isSystemDismissed("attention:summary:sum-2:FAILED"))
    }

    @Test
    fun `dismiss receiver records presentation only key`() {
        NotificationPresentationDismissReceiver().onReceive(
            context,
            Intent(context, NotificationPresentationDismissReceiver::class.java)
                .setAction(ACTION_NOTIFICATION_PRESENTATION_DISMISSED)
                .putExtra(
                    EXTRA_NOTIFICATION_PRESENTATION_KEY,
                    "candidate:transcription:tx-candidate",
                ),
        )

        assertTrue(
            NotificationPresentationStore(context)
                .isSystemDismissed("candidate:transcription:tx-candidate"),
        )
    }

    private fun clearStore() {
        context.getSharedPreferences("voica-notification-presentation", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
