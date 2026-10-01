package io.github.ioannes78.voica.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFocusPolicyTest {
    @Test
    fun transientLossCanResumeOnlyWhenItInterruptedPlayback() {
        val policy = PlaybackFocusPolicy()

        policy.onTransientLoss(wasPlaying = true)
        assertTrue(policy.consumeResumeOnGain(appForeground = true))

        policy.onTransientLoss(wasPlaying = false)
        assertFalse(policy.consumeResumeOnGain(appForeground = true))
    }

    @Test
    fun userPauseCancelsTransientResume() {
        val policy = PlaybackFocusPolicy()
        policy.onTransientLoss(wasPlaying = true)

        policy.cancelResume()

        assertFalse(policy.consumeResumeOnGain(appForeground = true))
    }

    @Test
    fun gainWhileBackgroundDoesNotResumeLater() {
        val policy = PlaybackFocusPolicy()
        policy.onTransientLoss(wasPlaying = true)

        assertFalse(policy.consumeResumeOnGain(appForeground = false))
        assertFalse(policy.consumeResumeOnGain(appForeground = true))
    }
}
