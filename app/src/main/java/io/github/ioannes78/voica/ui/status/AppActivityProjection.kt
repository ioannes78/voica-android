package io.github.ioannes78.voica.ui.status

/**
 * Presentation-only classification for activities projected into Homepage / App global status.
 *
 * None of these values own or mutate the underlying recording/task lifecycle. Adapters must rebuild
 * facts from the existing runtime/Room/coordinator truth.
 */
internal enum class AppActivityKind {
    RECORDING,
    REALTIME_TRANSCRIPTION,
    PLAYBACK,
    CANONICAL_GENERATION,
    OFFLINE_TRANSCRIPTION,
    DIARIZATION,
    AI_SUMMARY,
    MODEL_INSTALL,
}

internal enum class AppActivitySource {
    RECORDER_CARD,
    PHONE_MICROPHONE,
    LOCAL_PROCESSING,
    MODEL_MANAGER,
    PLAYBACK,
}

internal enum class AppActivityLifecycleClass {
    ACTIVE_CONTROLLABLE,
    BACKGROUND_RUNNING,
    COMPLETED_ATTENTION,
    FAILED_ATTENTION,
    INTERRUPTED_ATTENTION,
    PASSIVE,
}

/** Actions are capabilities only. Execution remains owned by the existing controller/runtime. */
internal enum class AppActivityAction {
    PAUSE,
    RESUME,
    STOP,
    OPEN,
    CANCEL,
    RETRY,
    ACKNOWLEDGE,
}

internal data class AppActivityFact(
    val logicalKey: String,
    val kind: AppActivityKind,
    val source: AppActivitySource,
    val lifecycleClass: AppActivityLifecycleClass,
    val title: String,
    val subtitle: String? = null,
    val progressFraction: Float? = null,
    val recordingId: String? = null,
    val taskId: String? = null,
    val destination: AppActivityDestination? = null,
    val availableActions: Set<AppActivityAction> = emptySet(),
    /** Optional live-media presentation fields. They are rebuilt from existing runtime truth. */
    val elapsedMs: Long? = null,
    val mediaDisplayName: String? = null,
    val mediaSizeBytes: Long? = null,
    val audioActivityLevels: List<Float> = emptyList(),
    val waveformActive: Boolean = false,
    /** Higher values win only inside the presentation resolver. They never alter business truth. */
    val priorityHint: Int = 0,
    /** Set by UI/navigation context when the user explicitly selected this active source. */
    val explicitlyFocused: Boolean = false,
    /** Optional presentation tie-breaker; epoch/wall time is never used as media truth. */
    val startedAtMs: Long? = null,
) {
    init {
        require(logicalKey.isNotBlank()) { "logicalKey must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        progressFraction?.let { progress ->
            require(progress.isFinite() && progress in 0f..1f) {
                "progressFraction must be finite and between 0 and 1"
            }
        }
        elapsedMs?.let { elapsed -> require(elapsed >= 0L) { "elapsedMs must not be negative" } }
        mediaSizeBytes?.let { size -> require(size >= 0L) { "mediaSizeBytes must not be negative" } }
        require(audioActivityLevels.all { level -> level.isFinite() && level in 0f..1f }) {
            "audioActivityLevels must be finite and between 0 and 1"
        }
    }
}

internal data class AppActivityProjection(
    val primaryActivity: AppActivityFact? = null,
    val secondaryActivities: List<AppActivityFact> = emptyList(),
    val playback: AppActivityFact? = null,
    val backgroundTasks: List<AppActivityFact> = emptyList(),
    val attentionItems: List<AppActivityFact> = emptyList(),
)

/**
 * Pure presentation resolver. It deliberately performs no I/O and has no dependency on Android,
 * Room, BLE, audio capture, ASR, foreground services, WorkManager, or notification APIs.
 */
internal object AppActivityProjectionResolver {
    fun resolve(facts: Collection<AppActivityFact>): AppActivityProjection {
        val uniqueFacts = collapseDuplicateLogicalKeys(facts)

        val controllableActivities =
            uniqueFacts
                .filter { fact ->
                    fact.lifecycleClass == AppActivityLifecycleClass.ACTIVE_CONTROLLABLE &&
                        fact.kind in PRIMARY_ACTIVITY_KINDS
                }.sortedWith(activityDisplayComparator)

        val playback =
            uniqueFacts
                .filter { fact ->
                    fact.kind == AppActivityKind.PLAYBACK &&
                        fact.lifecycleClass != AppActivityLifecycleClass.PASSIVE
                }.sortedWith(activityDisplayComparator)
                .firstOrNull()

        val backgroundTasks =
            uniqueFacts
                .filter { fact ->
                    fact.lifecycleClass == AppActivityLifecycleClass.BACKGROUND_RUNNING &&
                        fact.kind != AppActivityKind.PLAYBACK
                }.sortedWith(activityDisplayComparator)

        val attentionItems =
            uniqueFacts
                .filter { fact -> fact.lifecycleClass in ATTENTION_CLASSES }
                .sortedWith(attentionComparator)

        return AppActivityProjection(
            primaryActivity = controllableActivities.firstOrNull(),
            secondaryActivities = controllableActivities.drop(1),
            playback = playback,
            backgroundTasks = backgroundTasks,
            attentionItems = attentionItems,
        )
    }

    private fun collapseDuplicateLogicalKeys(facts: Collection<AppActivityFact>): List<AppActivityFact> =
        facts
            .groupBy(AppActivityFact::logicalKey)
            .values
            .mapNotNull { duplicates -> duplicates.maxWithOrNull(duplicateTruthComparator) }

    private val duplicateTruthComparator =
        compareBy<AppActivityFact> { lifecycleUrgency(it.lifecycleClass) }
            .thenBy { it.priorityHint }
            .thenBy { it.startedAtMs ?: Long.MIN_VALUE }

    private val activityDisplayComparator =
        compareByDescending<AppActivityFact> { if (it.explicitlyFocused) 1 else 0 }
            .thenByDescending { it.priorityHint }
            .thenByDescending { it.startedAtMs ?: Long.MIN_VALUE }
            .thenBy { it.logicalKey }

    private val attentionComparator =
        compareByDescending<AppActivityFact> { lifecycleUrgency(it.lifecycleClass) }
            .thenByDescending { it.priorityHint }
            .thenByDescending { it.startedAtMs ?: Long.MIN_VALUE }
            .thenBy { it.logicalKey }

    private fun lifecycleUrgency(lifecycleClass: AppActivityLifecycleClass): Int =
        when (lifecycleClass) {
            AppActivityLifecycleClass.FAILED_ATTENTION -> 60
            AppActivityLifecycleClass.INTERRUPTED_ATTENTION -> 50
            AppActivityLifecycleClass.ACTIVE_CONTROLLABLE -> 40
            AppActivityLifecycleClass.BACKGROUND_RUNNING -> 30
            AppActivityLifecycleClass.COMPLETED_ATTENTION -> 20
            AppActivityLifecycleClass.PASSIVE -> 0
        }

    private val PRIMARY_ACTIVITY_KINDS =
        setOf(
            AppActivityKind.RECORDING,
            AppActivityKind.REALTIME_TRANSCRIPTION,
        )

    private val ATTENTION_CLASSES =
        setOf(
            AppActivityLifecycleClass.COMPLETED_ATTENTION,
            AppActivityLifecycleClass.FAILED_ATTENTION,
            AppActivityLifecycleClass.INTERRUPTED_ATTENTION,
        )
}
