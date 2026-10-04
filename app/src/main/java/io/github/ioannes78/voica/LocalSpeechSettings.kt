package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.transcript.DiarizationConfig
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

object Stage13AOfflineModelIds {
    const val SENSEVOICE = Stage8ModelIds.SECOND_PASS_ASR
    const val FIRERED_ASR2 = "fireredasr2-ctc-zh-en-int8"
    const val QWEN3_ASR = "qwen3-asr-0.6b-int8"

    val ALL =
        listOf(
            SENSEVOICE,
            FIRERED_ASR2,
            QWEN3_ASR,
        )
}

enum class OfflineAsrQualityChoice {
    AUTO,
    BALANCED,
    HIGH_QUALITY,
    ULTRA,
}

enum class SpeechPerformanceProfile {
    AUTO,
    POWER_SAVER,
    BALANCED,
    PERFORMANCE,
}

enum class SpeakerCountChoice {
    AUTO,
    ONE,
    TWO,
    THREE,
    FOUR,
    FIVE_PLUS,
}

fun SpeakerCountChoice.toDiarizationConfig(): DiarizationConfig =
    when (this) {
        SpeakerCountChoice.AUTO ->
            DiarizationConfig()
        SpeakerCountChoice.ONE ->
            DiarizationConfig(
                expectedSpeakerCount = 1,
                minimumGlobalSpeakerCount = 1,
                maximumGlobalSpeakerCount = 1,
            )
        SpeakerCountChoice.TWO ->
            DiarizationConfig(
                expectedSpeakerCount = 2,
                minimumGlobalSpeakerCount = 2,
                maximumGlobalSpeakerCount = 2,
            )
        SpeakerCountChoice.THREE ->
            DiarizationConfig(
                expectedSpeakerCount = 3,
                minimumGlobalSpeakerCount = 3,
                maximumGlobalSpeakerCount = 3,
            )
        SpeakerCountChoice.FOUR ->
            DiarizationConfig(
                expectedSpeakerCount = 4,
                minimumGlobalSpeakerCount = 4,
                maximumGlobalSpeakerCount = 4,
            )
        SpeakerCountChoice.FIVE_PLUS ->
            DiarizationConfig(
                expectedSpeakerCount = FIVE_PLUS_MINIMUM_SPEAKERS,
                minimumGlobalSpeakerCount = FIVE_PLUS_MINIMUM_SPEAKERS,
                maximumGlobalSpeakerCount = FIVE_PLUS_MAXIMUM_SPEAKERS,
                clusteringThreshold = FIVE_PLUS_INITIAL_CLUSTERING_THRESHOLD,
            )
    }

data class LocalVadSettings(
    val threshold: Float = 0.5F,
    val minSilenceDurationSeconds: Float = 0.25F,
    val minSpeechDurationSeconds: Float = 0.25F,
    val maxSpeechDurationSeconds: Float = 30F,
) {
    init {
        require(threshold in 0F..1F)
        require(minSilenceDurationSeconds >= 0F)
        require(minSpeechDurationSeconds >= 0F)
        require(maxSpeechDurationSeconds > 0F)
    }
}

data class LocalSpeechSettings(
    val realtimeAsrModel: RealtimeAsrModelChoice = RealtimeAsrModelChoice.AUTO,
    val offlineAsrQuality: OfflineAsrQualityChoice = OfflineAsrQualityChoice.AUTO,
    val performanceProfile: SpeechPerformanceProfile = SpeechPerformanceProfile.AUTO,
    val requestedThreads: Int? = null,
    val vad: LocalVadSettings = LocalVadSettings(),
    val speakerCount: SpeakerCountChoice = SpeakerCountChoice.AUTO,
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

fun OfflineAsrQualityChoice.preferredModelIds(): List<String> =
    when (this) {
        OfflineAsrQualityChoice.AUTO,
        OfflineAsrQualityChoice.BALANCED,
        -> listOf(Stage13AOfflineModelIds.SENSEVOICE)
        OfflineAsrQualityChoice.HIGH_QUALITY ->
            listOf(Stage13AOfflineModelIds.FIRERED_ASR2)
        OfflineAsrQualityChoice.ULTRA ->
            listOf(Stage13AOfflineModelIds.QWEN3_ASR)
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

    fun setOfflineAsrQuality(choice: OfflineAsrQualityChoice)

    fun setPerformanceProfile(profile: SpeechPerformanceProfile)

    fun setRequestedThreads(threads: Int?)

    fun setVadSettings(settings: LocalVadSettings)

    fun resetVadSettings()

    fun setSpeakerCount(choice: SpeakerCountChoice)
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
                offlineAsrQuality =
                    preferences.getString(KEY_OFFLINE_ASR_QUALITY, null)
                        ?.let { runCatching { OfflineAsrQualityChoice.valueOf(it) }.getOrNull() }
                        ?: OfflineAsrQualityChoice.AUTO,
                performanceProfile =
                    preferences.getString(KEY_PERFORMANCE_PROFILE, null)
                        ?.let { runCatching { SpeechPerformanceProfile.valueOf(it) }.getOrNull() }
                        ?: SpeechPerformanceProfile.AUTO,
                requestedThreads =
                    preferences.getInt(KEY_REQUESTED_THREADS, 0)
                        .takeIf { it > 0 },
                vad = readVadSettings(preferences),
                speakerCount =
                    preferences.getString(KEY_SPEAKER_COUNT, null)
                        ?.let { runCatching { SpeakerCountChoice.valueOf(it) }.getOrNull() }
                        ?: SpeakerCountChoice.AUTO,
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

    override fun setOfflineAsrQuality(choice: OfflineAsrQualityChoice) {
        preferences.edit()
            .putString(KEY_OFFLINE_ASR_QUALITY, choice.name)
            .apply()
        mutableSettings.value =
            mutableSettings.value.copy(offlineAsrQuality = choice)
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

    override fun setVadSettings(settings: LocalVadSettings) {
        preferences.edit()
            .putFloat(KEY_VAD_THRESHOLD, settings.threshold)
            .putFloat(KEY_VAD_MIN_SILENCE, settings.minSilenceDurationSeconds)
            .putFloat(KEY_VAD_MIN_SPEECH, settings.minSpeechDurationSeconds)
            .putFloat(KEY_VAD_MAX_SPEECH, settings.maxSpeechDurationSeconds)
            .apply()
        mutableSettings.value = mutableSettings.value.copy(vad = settings)
    }

    override fun resetVadSettings() {
        val defaults = LocalVadSettings()
        preferences.edit()
            .remove(KEY_VAD_THRESHOLD)
            .remove(KEY_VAD_MIN_SILENCE)
            .remove(KEY_VAD_MIN_SPEECH)
            .remove(KEY_VAD_MAX_SPEECH)
            .apply()
        mutableSettings.value = mutableSettings.value.copy(vad = defaults)
    }

    override fun setSpeakerCount(choice: SpeakerCountChoice) {
        preferences.edit()
            .putString(KEY_SPEAKER_COUNT, choice.name)
            .apply()
        mutableSettings.value = mutableSettings.value.copy(speakerCount = choice)
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-local-speech"
        const val KEY_REALTIME_ASR_MODEL = "realtime-asr-model"
        const val KEY_OFFLINE_ASR_QUALITY = "offline-asr-quality"
        const val KEY_PERFORMANCE_PROFILE = "performance-profile"
        const val KEY_REQUESTED_THREADS = "requested-threads"
        const val KEY_VAD_THRESHOLD = "vad-threshold"
        const val KEY_VAD_MIN_SILENCE = "vad-min-silence"
        const val KEY_VAD_MIN_SPEECH = "vad-min-speech"
        const val KEY_VAD_MAX_SPEECH = "vad-max-speech"
        const val KEY_SPEAKER_COUNT = "speaker-count"

        fun readVadSettings(
            preferences: android.content.SharedPreferences,
        ): LocalVadSettings =
            runCatching {
                LocalVadSettings(
                    threshold = preferences.getFloat(KEY_VAD_THRESHOLD, 0.5F),
                    minSilenceDurationSeconds =
                        preferences.getFloat(KEY_VAD_MIN_SILENCE, 0.25F),
                    minSpeechDurationSeconds =
                        preferences.getFloat(KEY_VAD_MIN_SPEECH, 0.25F),
                    maxSpeechDurationSeconds =
                        preferences.getFloat(KEY_VAD_MAX_SPEECH, 30F),
                )
            }.getOrDefault(LocalVadSettings())
    }
}

const val MAX_CONFIGURABLE_THREADS = 8
const val FIVE_PLUS_INITIAL_CLUSTERING_THRESHOLD = 0.45F
const val FIVE_PLUS_MINIMUM_SPEAKERS = 5
const val FIVE_PLUS_MAXIMUM_SPEAKERS = 8
private const val DEFAULT_BALANCED_THREADS = 2
private const val PERFORMANCE_THREADS = 4
