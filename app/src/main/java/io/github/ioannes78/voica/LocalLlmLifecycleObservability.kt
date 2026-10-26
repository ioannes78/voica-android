package io.github.ioannes78.voica

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.ioannes78.voica.localllm.LocalLlmGenerationProgress
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeState
import java.util.Locale
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class LocalLlmLifecycleTransition {
    INITIAL,
    PROCESS_FOREGROUND,
    PROCESS_BACKGROUND,
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    SCREEN_ON,
    SCREEN_OFF,
    USER_PRESENT,
}

internal data class LocalLlmLifecycleSignalState(
    val processForeground: Boolean,
    val resumedActivityCount: Int,
) {
    val activityResumed: Boolean
        get() = resumedActivityCount > 0
}

internal fun LocalLlmLifecycleSignalState.reduce(
    transition: LocalLlmLifecycleTransition,
): LocalLlmLifecycleSignalState =
    when (transition) {
        LocalLlmLifecycleTransition.PROCESS_FOREGROUND -> copy(processForeground = true)
        LocalLlmLifecycleTransition.PROCESS_BACKGROUND -> copy(processForeground = false)
        LocalLlmLifecycleTransition.ACTIVITY_RESUMED ->
            copy(resumedActivityCount = resumedActivityCount + 1)
        LocalLlmLifecycleTransition.ACTIVITY_PAUSED ->
            copy(resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0))
        LocalLlmLifecycleTransition.INITIAL,
        LocalLlmLifecycleTransition.SCREEN_ON,
        LocalLlmLifecycleTransition.SCREEN_OFF,
        LocalLlmLifecycleTransition.USER_PRESENT,
        -> this
    }

internal data class LocalLlmLifecycleSnapshot(
    val processForeground: Boolean,
    val activityResumed: Boolean,
    val interactive: Boolean,
    val keyguardShowing: Boolean,
    val deviceLocked: Boolean,
    val lastTransition: LocalLlmLifecycleTransition,
    val timestampNanos: Long,
)

internal data class LocalLlmLifecycleEvent(
    val transition: LocalLlmLifecycleTransition,
    val snapshot: LocalLlmLifecycleSnapshot,
)

internal enum class LocalLlmLifecycleDiagnosticSource {
    LIFECYCLE,
    RUNTIME,
}

internal data class LocalLlmLifecycleDiagnosticEvent(
    val source: LocalLlmLifecycleDiagnosticSource,
    val detail: String,
    val timestampNanos: Long,
    val lifecycle: LocalLlmLifecycleSnapshot,
    val runtimeState: LocalLlmRuntimeState,
    val generationActive: Boolean,
    val emissionCount: Long,
)

/**
 * Process-level Android lifecycle observer used only for Stage 17 GPU capability diagnostics.
 *
 * This monitor never selects CPU/GPU and never cancels inference. R4-B is observation-only: it
 * preserves the R2 structured-summary CPU isolation while giving R4-C a precise timeline for
 * process visibility, display interactivity, keyguard state, and device lock state.
 */
internal class LocalLlmLifecycleMonitor(
    private val application: Application,
    private val clockNanos: () -> Long = System::nanoTime,
) : DefaultLifecycleObserver,
    Application.ActivityLifecycleCallbacks {
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val keyguardManager = application.getSystemService(KeyguardManager::class.java)
    private var signalState =
        LocalLlmLifecycleSignalState(
            processForeground =
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            resumedActivityCount = 0,
        )

    private val initialSnapshot = sample(LocalLlmLifecycleTransition.INITIAL)
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)
    private val mutableEvents =
        MutableStateFlow(
            listOf(
                LocalLlmLifecycleEvent(
                    transition = LocalLlmLifecycleTransition.INITIAL,
                    snapshot = initialSnapshot,
                ),
            ),
        )

    val snapshot: StateFlow<LocalLlmLifecycleSnapshot> = mutableSnapshot.asStateFlow()
    val events: StateFlow<List<LocalLlmLifecycleEvent>> = mutableEvents.asStateFlow()

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val transition =
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> LocalLlmLifecycleTransition.SCREEN_ON
                        Intent.ACTION_SCREEN_OFF -> LocalLlmLifecycleTransition.SCREEN_OFF
                        Intent.ACTION_USER_PRESENT -> LocalLlmLifecycleTransition.USER_PRESENT
                        else -> return
                    }
                publish(transition)
            }
        }

    init {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        ContextCompat.registerReceiver(
            application,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStart(owner: LifecycleOwner) {
        signalState = signalState.reduce(LocalLlmLifecycleTransition.PROCESS_FOREGROUND)
        publish(LocalLlmLifecycleTransition.PROCESS_FOREGROUND)
    }

    override fun onStop(owner: LifecycleOwner) {
        signalState = signalState.reduce(LocalLlmLifecycleTransition.PROCESS_BACKGROUND)
        publish(LocalLlmLifecycleTransition.PROCESS_BACKGROUND)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        signalState = signalState.reduce(LocalLlmLifecycleTransition.ACTIVITY_RESUMED)
        publish(LocalLlmLifecycleTransition.ACTIVITY_RESUMED)
    }

    override fun onActivityPaused(activity: Activity) {
        signalState = signalState.reduce(LocalLlmLifecycleTransition.ACTIVITY_PAUSED)
        publish(LocalLlmLifecycleTransition.ACTIVITY_PAUSED)
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun publish(transition: LocalLlmLifecycleTransition) {
        val next = sample(transition)
        mutableSnapshot.value = next
        mutableEvents.update { current ->
            (current + LocalLlmLifecycleEvent(transition = transition, snapshot = next))
                .takeLast(MAX_LIFECYCLE_EVENTS)
        }
    }

    private fun sample(transition: LocalLlmLifecycleTransition): LocalLlmLifecycleSnapshot =
        LocalLlmLifecycleSnapshot(
            processForeground = signalState.processForeground,
            activityResumed = signalState.activityResumed,
            interactive = powerManager.isInteractive,
            keyguardShowing = keyguardManager.isKeyguardLocked,
            deviceLocked = keyguardManager.isDeviceLocked,
            lastTransition = transition,
            timestampNanos = clockNanos(),
        )

    private companion object {
        const val MAX_LIFECYCLE_EVENTS = 64
    }
}

/**
 * Bounded, content-free correlation timeline for R4 lifecycle validation.
 *
 * It records lifecycle transitions beside the current runtime state and records runtime-state
 * transitions beside the current Android lifecycle snapshot. Generated text/prompt content is
 * deliberately absent.
 */
internal class LocalLlmLifecycleDiagnostics(
    scope: CoroutineScope,
    monitor: LocalLlmLifecycleMonitor,
    runtime: AppLocalLlmRuntime,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val mutableEvents = MutableStateFlow<List<LocalLlmLifecycleDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<LocalLlmLifecycleDiagnosticEvent>> = mutableEvents.asStateFlow()

    init {
        scope.launch {
            monitor.events
                .map { it.lastOrNull() }
                .distinctUntilChanged()
                .collect { event ->
                    if (event != null) {
                        record(
                            source = LocalLlmLifecycleDiagnosticSource.LIFECYCLE,
                            detail = event.transition.name,
                            timestampNanos = event.snapshot.timestampNanos,
                            lifecycle = event.snapshot,
                            runtimeState = runtime.state.value,
                            progress = runtime.generationProgress.value,
                        )
                    }
                }
        }
        scope.launch {
            runtime.state
                .distinctUntilChanged()
                .collect { state ->
                    record(
                        source = LocalLlmLifecycleDiagnosticSource.RUNTIME,
                        detail = state.name,
                        timestampNanos = clockNanos(),
                        lifecycle = monitor.snapshot.value,
                        runtimeState = state,
                        progress = runtime.generationProgress.value,
                    )
                }
        }
    }

    private fun record(
        source: LocalLlmLifecycleDiagnosticSource,
        detail: String,
        timestampNanos: Long,
        lifecycle: LocalLlmLifecycleSnapshot,
        runtimeState: LocalLlmRuntimeState,
        progress: LocalLlmGenerationProgress,
    ) {
        val event =
            LocalLlmLifecycleDiagnosticEvent(
                source = source,
                detail = detail,
                timestampNanos = timestampNanos,
                lifecycle = lifecycle,
                runtimeState = runtimeState,
                generationActive = progress.active,
                emissionCount = progress.emissionCount,
            )
        mutableEvents.update { current -> (current + event).takeLast(MAX_DIAGNOSTIC_EVENTS) }
    }

    private companion object {
        const val MAX_DIAGNOSTIC_EVENTS = 96
    }
}

internal data class LocalLlmLifecycleObservability(
    val monitor: LocalLlmLifecycleMonitor,
    val diagnostics: LocalLlmLifecycleDiagnostics,
)

/**
 * QA registry keeps the observer alive across Activity stop/recreation without adding another LLM
 * owner. The registry is only reached from the existing QA-only Stage 17 P1 activity.
 */
internal object LocalLlmLifecycleObservabilityRegistry {
    private val byApplication = WeakHashMap<Application, LocalLlmLifecycleObservability>()

    @Synchronized
    fun get(
        application: Application,
        runtime: AppLocalLlmRuntime,
    ): LocalLlmLifecycleObservability =
        byApplication.getOrPut(application) {
            val monitor = LocalLlmLifecycleMonitor(application)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            LocalLlmLifecycleObservability(
                monitor = monitor,
                diagnostics =
                    LocalLlmLifecycleDiagnostics(
                        scope = scope,
                        monitor = monitor,
                        runtime = runtime,
                    ),
            )
        }
}

internal fun formatLocalLlmLifecycleDiagnostics(
    events: List<LocalLlmLifecycleDiagnosticEvent>,
    limit: Int = 32,
): String {
    val selected = events.takeLast(limit.coerceAtLeast(1))
    if (selected.isEmpty()) return "lifecycleDiagnostics=empty"
    val origin = selected.first().timestampNanos
    return buildString {
        appendLine("lifecycleDiagnostics=${selected.size}")
        selected.forEach { event ->
            val deltaMs = (event.timestampNanos - origin).coerceAtLeast(0L) / 1_000_000L
            val life = event.lifecycle
            append("+")
            append(deltaMs)
            append("ms ")
            append(event.source.name.lowercase(Locale.US))
            append("=")
            append(event.detail)
            append(" runtime=")
            append(event.runtimeState)
            append(" generationActive=")
            append(event.generationActive)
            append(" emissions=")
            append(event.emissionCount)
            append(" foreground=")
            append(life.processForeground)
            append(" resumed=")
            append(life.activityResumed)
            append(" interactive=")
            append(life.interactive)
            append(" keyguard=")
            append(life.keyguardShowing)
            append(" deviceLocked=")
            appendLine(life.deviceLocked)
        }
    }.trimEnd()
}
