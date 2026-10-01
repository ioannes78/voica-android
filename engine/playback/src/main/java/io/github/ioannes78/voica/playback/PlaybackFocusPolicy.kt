package io.github.ioannes78.voica.playback

internal class PlaybackFocusPolicy {
    private var resumeEligible = false

    fun onTransientLoss(wasPlaying: Boolean) {
        resumeEligible = wasPlaying
    }

    fun cancelResume() {
        resumeEligible = false
    }

    fun consumeResumeOnGain(appForeground: Boolean): Boolean {
        val shouldResume = resumeEligible && appForeground
        resumeEligible = false
        return shouldResume
    }
}
