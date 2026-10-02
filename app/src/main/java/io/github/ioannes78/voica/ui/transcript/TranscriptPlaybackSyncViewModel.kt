package io.github.ioannes78.voica.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.transcript.TranscriptTimeline
import io.github.ioannes78.voica.transcript.TranscriptTimelinePositionMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class TranscriptFollowMode {
    FOLLOWING,
    USER_SUSPENDED,
}

data class TranscriptPlaybackSyncState(
    val activeRowId: String? = null,
    val activeCueId: String? = null,
    val activeSpeakerId: String? = null,
    val syncAvailable: Boolean = false,
    val playbackCompatible: Boolean = false,
    val followMode: TranscriptFollowMode = TranscriptFollowMode.FOLLOWING,
    val timelineGeneration: Long = 0L,
    val discontinuityGeneration: Long = 0L,
)

internal class TranscriptPlaybackSyncStateMachine {
    private var timeline: TranscriptTimeline? = null
    private var mapper: TranscriptTimelinePositionMapper? = null
    private var compatiblePlaybackAssetId: String? = null
    private var state = TranscriptPlaybackSyncState()

    fun bind(
        timeline: TranscriptTimeline?,
        compatiblePlaybackAssetId: String?,
        playback: PlaybackSnapshot,
    ): TranscriptPlaybackSyncState {
        this.timeline = timeline
        mapper = timeline?.let(::TranscriptTimelinePositionMapper)
        this.compatiblePlaybackAssetId = compatiblePlaybackAssetId
        state =
            state.copy(
                activeRowId = null,
                activeCueId = null,
                activeSpeakerId = null,
                syncAvailable = timeline != null && compatiblePlaybackAssetId != null,
                playbackCompatible = false,
                timelineGeneration = state.timelineGeneration + 1L,
                discontinuityGeneration = playback.discontinuityGeneration,
            )
        return update(playback)
    }

    fun update(playback: PlaybackSnapshot): TranscriptPlaybackSyncState {
        val currentTimeline = timeline
        val sourceAssetId = compatiblePlaybackAssetId
        val compatible =
            currentTimeline != null &&
                sourceAssetId != null &&
                playback.recordingId == currentTimeline.recordingId &&
                playback.sourceAssetId == sourceAssetId &&
                playback.durationSampleCount == currentTimeline.totalSampleCount

        val position =
            if (compatible) {
                mapper?.map(playback.positionSampleIndex)
            } else {
                null
            }

        state =
            state.copy(
                activeRowId = position?.activeRowId,
                activeCueId = position?.activeCueId,
                activeSpeakerId = position?.speakerId,
                syncAvailable = currentTimeline != null && sourceAssetId != null,
                playbackCompatible = compatible,
                discontinuityGeneration = playback.discontinuityGeneration,
            )
        return state
    }

    fun suspendFollowing(): TranscriptPlaybackSyncState {
        if (state.followMode == TranscriptFollowMode.USER_SUSPENDED) return state
        state = state.copy(followMode = TranscriptFollowMode.USER_SUSPENDED)
        return state
    }

    fun resumeFollowing(): TranscriptPlaybackSyncState {
        if (state.followMode == TranscriptFollowMode.FOLLOWING) return state
        state = state.copy(followMode = TranscriptFollowMode.FOLLOWING)
        return state
    }
}

class TranscriptPlaybackSyncViewModel(
    private val playbackController: PlaybackController,
) : ViewModel() {
    private val machine = TranscriptPlaybackSyncStateMachine()
    private val mutableState = MutableStateFlow(TranscriptPlaybackSyncState())
    val state: StateFlow<TranscriptPlaybackSyncState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackController.snapshot.collect { snapshot ->
                publish(machine.update(snapshot))
            }
        }
    }

    fun bind(
        timeline: TranscriptTimeline?,
        compatiblePlaybackAssetId: String?,
    ) {
        publish(
            machine.bind(
                timeline = timeline,
                compatiblePlaybackAssetId = compatiblePlaybackAssetId,
                playback = playbackController.snapshot.value,
            ),
        )
    }

    fun suspendFollowing() {
        publish(machine.suspendFollowing())
    }

    fun resumeFollowing() {
        publish(machine.resumeFollowing())
    }

    private fun publish(next: TranscriptPlaybackSyncState) {
        if (mutableState.value != next) {
            mutableState.value = next
        }
    }

    class Factory(
        private val playbackController: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranscriptPlaybackSyncViewModel(playbackController) as T
    }
}
