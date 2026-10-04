package io.github.ioannes78.voica

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Stage13ARealtimeModelIds {
    const val SMALL_BILINGUAL = Stage8ModelIds.FIRST_PASS_ASR
    const val CHINESE_LARGE_TRANSDUCER = "zipformer-large-zh-transducer-int8"
    const val CHINESE_LARGE_CTC = "zipformer-large-zh-ctc-int8"

    val ALL =
        listOf(
            SMALL_BILINGUAL,
            CHINESE_LARGE_TRANSDUCER,
            CHINESE_LARGE_CTC,
        )
}

enum class RealtimeAsrModelChoice {
    AUTO,
    SMALL_BILINGUAL,
    CHINESE_LARGE_TRANSDUCER,
    CHINESE_LARGE_CTC,
}

data class LocalSpeechSettings(
    val realtimeAsrModel: RealtimeAsrModelChoice = RealtimeAsrModelChoice.AUTO,
)

fun RealtimeAsrModelChoice.preferredModelIds(): List<String> =
    when (this) {
        RealtimeAsrModelChoice.AUTO ->
            listOf(
                Stage13ARealtimeModelIds.CHINESE_LARGE_TRANSDUCER,
                Stage13ARealtimeModelIds.CHINESE_LARGE_CTC,
                Stage13ARealtimeModelIds.SMALL_BILINGUAL,
            )
        RealtimeAsrModelChoice.SMALL_BILINGUAL ->
            listOf(Stage13ARealtimeModelIds.SMALL_BILINGUAL)
        RealtimeAsrModelChoice.CHINESE_LARGE_TRANSDUCER ->
            listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_TRANSDUCER)
        RealtimeAsrModelChoice.CHINESE_LARGE_CTC ->
            listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC)
    }

interface LocalSpeechSettingsStore {
    val settings: StateFlow<LocalSpeechSettings>

    fun setRealtimeAsrModel(choice: RealtimeAsrModelChoice)
}

class SharedPreferencesLocalSpeechSettingsStore(
    application: Application,
) : LocalSpeechSettingsStore {
    private val preferences =
        application.getSharedPreferences(
            PREFERENCES_NAME,
            Application.MODE_PRIVATE,
        )

    private val mutableSettings =
        MutableStateFlow(
            LocalSpeechSettings(
                realtimeAsrModel =
                    preferences.getString(KEY_REALTIME_ASR_MODEL, null)
                        ?.let { runCatching { RealtimeAsrModelChoice.valueOf(it) }.getOrNull() }
                        ?: RealtimeAsrModelChoice.AUTO,
            ),
        )

    override val settings: StateFlow<LocalSpeechSettings> =
        mutableSettings.asStateFlow()

    override fun setRealtimeAsrModel(choice: RealtimeAsrModelChoice) {
        preferences.edit()
            .putString(KEY_REALTIME_ASR_MODEL, choice.name)
            .apply()
        mutableSettings.value =
            mutableSettings.value.copy(realtimeAsrModel = choice)
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-local-speech"
        const val KEY_REALTIME_ASR_MODEL = "realtime-asr-model"
    }
}
