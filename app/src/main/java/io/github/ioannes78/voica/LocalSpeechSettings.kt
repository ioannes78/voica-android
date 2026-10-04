package io.github.ioannes78.voica

import android.app.Application
import kotlin.math.min
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

enum class SpeechPerformanceProfile {
    AUTO,
    POWER_SAVER,
    BALANCED,
    PERFORMANCE,
}

data class LocalSpeechSettings(
    val realtimeAsrModel: RealtimeAsrModelChoice = RealtimeAsrModelChoice.AUTO,
    val performanceProfile: SpeechPerformanceProfile = SpeechPerformanceProfile.AUTO,
    val requestedThreads: Int? = null,
)

data class ResolvedSpeechPerformance(
    val profile: SpeechPerformanceProfile,
    val requestedThreads: Int?,
    val effectiveThreads: Int,
    val logicalProcessors: Int,
) {
    init {
        require(effectiveThreads >= 1)
        require(logicalProcessors >= 1)
    }
}

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

fun LocalSpeechSettings.resolvePerformance(
    logicalProcessors: Int = Runtime.getRuntime().availableProcessors(),
): ResolvedSpeechPerformance {
    val safeLogicalProcessors = logicalProcessors.coerceAtLeast(1)
    val safeMaxThreads = min(safeLogicalProcessors, MAX_CONFIGURABLE_THREADS)
    val effective =
        requestedThreads?.coerceIn(1, safeMaxThreads)
            ?: when (performanceProfile) {
                SpeechPerformanceProfile.AUTO ->
                    min(DEFAULT_BALANCED_THREADS, safeMaxThreads)
                SpeechPerformanceProfile.POWER_SAVER -> 1
                SpeechPerformanceProfile.BALANCED ->
                    min(DEFAULT_BALANCED_THREADS, safeMaxThreads)
                SpeechPerformanceProfile.PERFORMANCE ->
                    min(PERFORMANCE_THREADS, safeMaxThreads)
            }
    return ResolvedSpeechPerformance(
        profile = performanceProfile,
        requestedThreads = requestedThreads,
        effectiveThreads = effective,
        logicalProcessors = safeLogicalProcessors,
    )
}

interface LocalSpeechSettingsStore {
    val settings: StateFlow<LocalSpeechSettings>

    fun setRealtimeAsrModel(choice: RealtimeAsrModelChoice)

    fun setPerformanceProfile(profile: SpeechPerformanceProfile)

    fun setRequestedThreads(threads: Int?)
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
                performanceProfile =
                    preferences.getString(KEY_PERFORMANCE_PROFILE, null)
                        ?.let { runCatching { SpeechPerformanceProfile.valueOf(it) }.getOrNull() }
                        ?: SpeechPerformanceProfile.AUTO,
                requestedThreads =
                    preferences.getInt(KEY_REQUESTED_THREADS, 0)
                        .takeIf { it > 0 },
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

    override fun setPerformanceProfile(profile: SpeechPerformanceProfile) {
        preferences.edit()
            .putString(KEY_PERFORMANCE_PROFILE, profile.name)
            .apply()
        mutableSettings.value =
            mutableSettings.value.copy(performanceProfile = profile)
    }

    override fun setRequestedThreads(threads: Int?) {
        require(threads == null || threads > 0)
        val editor = preferences.edit()
        if (threads == null) {
            editor.remove(KEY_REQUESTED_THREADS)
        } else {
            editor.putInt(KEY_REQUESTED_THREADS, threads)
        }
        editor.apply()
        mutableSettings.value =
            mutableSettings.value.copy(requestedThreads = threads)
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-local-speech"
        const val KEY_REALTIME_ASR_MODEL = "realtime-asr-model"
        const val KEY_PERFORMANCE_PROFILE = "performance-profile"
        const val KEY_REQUESTED_THREADS = "requested-threads"
    }
}

const val MAX_CONFIGURABLE_THREADS = 8
private const val DEFAULT_BALANCED_THREADS = 2
private const val PERFORMANCE_THREADS = 4
