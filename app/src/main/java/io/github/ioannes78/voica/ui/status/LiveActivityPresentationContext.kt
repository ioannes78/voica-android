package io.github.ioannes78.voica.ui.status

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.ioannes78.voica.LocalSpeechSettings
import io.github.ioannes78.voica.LocalSpeechSettingsStore
import io.github.ioannes78.voica.RealtimeAsrModelChoice
import io.github.ioannes78.voica.ble.RecordingDeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only presentation inputs shared by Home/global/realtime UI.
 *
 * These values never own recording or realtime lifecycle. They only expose existing truth to
 * presentation code so Stage 16A.1.5 can render one consistent UI without creating another
 * business-state owner.
 */
internal val LocalRecorderRecordingState = staticCompositionLocalOf<StateFlow<RecordingDeviceState>> {
    MutableStateFlow(RecordingDeviceState()).asStateFlow()
}

/** Safe presentation flow for product UI/tests; the real app provides the canonical settings flow. */
internal val LocalProductSpeechSettings = staticCompositionLocalOf<StateFlow<LocalSpeechSettings>> {
    MutableStateFlow(LocalSpeechSettings()).asStateFlow()
}

/** Kept as a compatibility seam while Stage 16A.1.5 callers move to the read-only settings flow. */
internal val LocalProductSpeechSettingsStore = staticCompositionLocalOf<LocalSpeechSettingsStore> {
    error("LocalSpeechSettingsStore was not provided")
}

internal fun RealtimeAsrModelChoice.productDisplayName(): String =
    when (this) {
        RealtimeAsrModelChoice.CHINESE_LARGE_CTC -> "Chinese Large CTC"
        RealtimeAsrModelChoice.AUTO,
        RealtimeAsrModelChoice.SMALL_BILINGUAL,
        RealtimeAsrModelChoice.CHINESE_LARGE_TRANSDUCER,
        -> "Small Bilingual"
    }

/** Exact realtime reader currently visible in-app; presentation-only suppression signal. */
internal object AppLiveActivityVisibility {
    private val mutableVisibleRealtimeSource = MutableStateFlow<AppActivitySource?>(null)
    val visibleRealtimeSource: StateFlow<AppActivitySource?> = mutableVisibleRealtimeSource.asStateFlow()

    fun enterRealtime(source: AppActivitySource) {
        mutableVisibleRealtimeSource.value = source
    }

    fun leaveRealtime(source: AppActivitySource) {
        if (mutableVisibleRealtimeSource.value == source) {
            mutableVisibleRealtimeSource.value = null
        }
    }
}
