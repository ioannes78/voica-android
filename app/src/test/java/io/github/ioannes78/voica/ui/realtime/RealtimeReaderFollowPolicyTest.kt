package io.github.ioannes78.voica.ui.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeReaderFollowPolicyTest {
    @Test
    fun `user drag away from live edge enters history mode`() {
        assertEquals(
            RealtimeReaderFollowMode.READING_HISTORY,
            RealtimeReaderFollowPolicy.reduce(
                current = RealtimeReaderFollowMode.FOLLOW_LIVE,
                userDragging = true,
                atBottom = false,
            ),
        )
    }

    @Test
    fun `programmatic movement does not disable live follow`() {
        assertEquals(
            RealtimeReaderFollowMode.FOLLOW_LIVE,
            RealtimeReaderFollowPolicy.reduce(
                current = RealtimeReaderFollowMode.FOLLOW_LIVE,
                userDragging = false,
                atBottom = false,
            ),
        )
    }

    @Test
    fun `history mode stays pinned until reader returns to live edge`() {
        assertEquals(
            RealtimeReaderFollowMode.READING_HISTORY,
            RealtimeReaderFollowPolicy.reduce(
                current = RealtimeReaderFollowMode.READING_HISTORY,
                userDragging = false,
                atBottom = false,
            ),
        )
        assertEquals(
            RealtimeReaderFollowMode.FOLLOW_LIVE,
            RealtimeReaderFollowPolicy.reduce(
                current = RealtimeReaderFollowMode.READING_HISTORY,
                userDragging = false,
                atBottom = true,
            ),
        )
    }

    @Test
    fun `return to live affordance only appears for history with transcript content`() {
        assertTrue(
            RealtimeReaderFollowPolicy.shouldShowReturnToLive(
                mode = RealtimeReaderFollowMode.READING_HISTORY,
                hasTranscriptContent = true,
            ),
        )
        assertFalse(
            RealtimeReaderFollowPolicy.shouldShowReturnToLive(
                mode = RealtimeReaderFollowMode.READING_HISTORY,
                hasTranscriptContent = false,
            ),
        )
        assertFalse(
            RealtimeReaderFollowPolicy.shouldShowReturnToLive(
                mode = RealtimeReaderFollowMode.FOLLOW_LIVE,
                hasTranscriptContent = true,
            ),
        )
    }
}
