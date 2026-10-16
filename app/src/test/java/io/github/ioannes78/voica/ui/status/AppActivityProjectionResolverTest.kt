package io.github.ioannes78.voica.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActivityProjectionResolverTest {
    @Test
    fun `single phone recording becomes primary activity`() {
        val recording =
            fact(
                key = "phone-recording",
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                lifecycle = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(recording))

        assertEquals(recording, projection.primaryActivity)
        assertTrue(projection.secondaryActivities.isEmpty())
        assertNull(projection.playback)
    }

    @Test
    fun `explicitly focused source wins primary and other active source stays secondary`() {
        val device =
            fact(
                key = "device-recording",
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.RECORDER_CARD,
                lifecycle = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
                startedAtMs = 200L,
            )
        val phone =
            fact(
                key = "phone-recording",
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                lifecycle = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
                explicitlyFocused = true,
                startedAtMs = 100L,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(device, phone))

        assertEquals(phone, projection.primaryActivity)
        assertEquals(listOf(device), projection.secondaryActivities)
    }

    @Test
    fun `playback is projected separately and never steals recording primary`() {
        val recording =
            fact(
                key = "device-realtime",
                kind = AppActivityKind.REALTIME_TRANSCRIPTION,
                source = AppActivitySource.RECORDER_CARD,
                lifecycle = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
            )
        val playback =
            fact(
                key = "playback-a",
                kind = AppActivityKind.PLAYBACK,
                source = AppActivitySource.PLAYBACK,
                lifecycle = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
                priorityHint = 100,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(playback, recording))

        assertEquals(recording, projection.primaryActivity)
        assertEquals(playback, projection.playback)
    }

    @Test
    fun `background work never replaces an active recording`() {
        val recording =
            fact(
                key = "phone-recording",
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                lifecycle = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
            )
        val transcription =
            fact(
                key = "transcription-1",
                kind = AppActivityKind.OFFLINE_TRANSCRIPTION,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycle = AppActivityLifecycleClass.BACKGROUND_RUNNING,
                priorityHint = 999,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(transcription, recording))

        assertEquals(recording, projection.primaryActivity)
        assertEquals(listOf(transcription), projection.backgroundTasks)
    }

    @Test
    fun `failed and interrupted items are attention with failure first`() {
        val completed =
            fact(
                key = "summary-completed",
                kind = AppActivityKind.AI_SUMMARY,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycle = AppActivityLifecycleClass.COMPLETED_ATTENTION,
            )
        val interrupted =
            fact(
                key = "diarization-interrupted",
                kind = AppActivityKind.DIARIZATION,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycle = AppActivityLifecycleClass.INTERRUPTED_ATTENTION,
            )
        val failed =
            fact(
                key = "transcription-failed",
                kind = AppActivityKind.OFFLINE_TRANSCRIPTION,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycle = AppActivityLifecycleClass.FAILED_ATTENTION,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(completed, interrupted, failed))

        assertEquals(listOf(failed, interrupted, completed), projection.attentionItems)
        assertNull(projection.primaryActivity)
    }

    @Test
    fun `same logical key collapses to most urgent presentation fact`() {
        val running =
            fact(
                key = "task-1",
                kind = AppActivityKind.OFFLINE_TRANSCRIPTION,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycle = AppActivityLifecycleClass.BACKGROUND_RUNNING,
            )
        val failed =
            fact(
                key = "task-1",
                kind = AppActivityKind.OFFLINE_TRANSCRIPTION,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycle = AppActivityLifecycleClass.FAILED_ATTENTION,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(running, failed))

        assertTrue(projection.backgroundTasks.isEmpty())
        assertEquals(listOf(failed), projection.attentionItems)
    }

    @Test
    fun `passive facts do not render into active global surfaces`() {
        val passive =
            fact(
                key = "completed-recording-history",
                kind = AppActivityKind.RECORDING,
                source = AppActivitySource.PHONE_MICROPHONE,
                lifecycle = AppActivityLifecycleClass.PASSIVE,
            )

        val projection = AppActivityProjectionResolver.resolve(listOf(passive))

        assertNull(projection.primaryActivity)
        assertTrue(projection.secondaryActivities.isEmpty())
        assertTrue(projection.backgroundTasks.isEmpty())
        assertTrue(projection.attentionItems.isEmpty())
    }

    private fun fact(
        key: String,
        kind: AppActivityKind,
        source: AppActivitySource,
        lifecycle: AppActivityLifecycleClass,
        priorityHint: Int = 0,
        explicitlyFocused: Boolean = false,
        startedAtMs: Long? = null,
    ): AppActivityFact =
        AppActivityFact(
            logicalKey = key,
            kind = kind,
            source = source,
            lifecycleClass = lifecycle,
            title = key,
            priorityHint = priorityHint,
            explicitlyFocused = explicitlyFocused,
            startedAtMs = startedAtMs,
        )
}
