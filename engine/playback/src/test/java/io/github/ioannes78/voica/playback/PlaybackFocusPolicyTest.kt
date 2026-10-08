package io.github.ioannes78.voica.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFocusPolicyTest {
    @Test
    fun transientLossCanResumeOnlyWhenItInterruptedPlayback() {
        val policy = PlaybackFocusPolicy()

        policy.onTransientLoss(wasPlaying = true)
        assertTrue(policy.consumeResumeOnGain())

        policy.onTransientLoss(wasPlaying = false)
        assertFalse(policy.consumeResumeOnGain())
    }

    @Test
    fun userPauseCancelsTransientResume() {
        val policy = PlaybackFocusPolicy()
        policy.onTransientLoss(wasPlaying = true)

        policy.cancelResume()

        assertFalse(policy.consumeResumeOnGain())
    }

    @Test
    fun focusGainCanResumeWhileAppIsBackgrounded() {
        val policy = PlaybackFocusPolicy()
        policy.onTransientLoss(wasPlaying = true)

        assertTrue(policy.consumeResumeOnGain())
        assertFalse(policy.consumeResumeOnGain())
    }
}
