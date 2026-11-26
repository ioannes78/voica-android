package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.OfflineQaOutcome
import io.github.ioannes78.voica.ai.*
import io.github.ioannes78.voica.database.OfflineQaSourceException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Collections

internal enum class OfflineQaSessionPhase { LOADING, READY, RUNNING, CANCELLING, UNAVAILABLE, CLOSED }

internal data class OfflineQaSessionTurn(
    val question: String,
    val outcome: OfflineQaOutcome,
)

internal data class OfflineQaSessionState(
    val phase: OfflineQaSessionPhase = OfflineQaSessionPhase.LOADING,
    val source: OfflineQaSourceIdentity? = null,
    val turns: List<OfflineQaSessionTurn> = emptyList(),
    val pendingQuestion: String? = null,
    val failure: OfflineQaFailureCode? = null,
)

/** Page-owned, dispatcher-confined session. Call public methods from the page dispatcher. */
internal class OfflineQaSession(
    private val recordingId: String,
    parentScope: CoroutineScope,
    private val loadCurrent: suspend (String) -> OfflineQaSnapshot,
    private val ask: suspend (OfflineQaRequest, List<OfflineQaHistoryTurn>) -> OfflineQaOutcome,
) {
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(OfflineQaSessionState())
    val state: StateFlow<OfflineQaSessionState> = mutable.asStateFlow()
    private var generation = 0L
    private var task: Job? = null

    init { require(recordingId.isNotBlank()) }

    fun refresh(): Boolean {
        if (!owner.isActive || task?.isCompleted == false) return false
        val token = ++generation
        val previous = mutable.value
        mutable.value = previous.copy(phase = OfflineQaSessionPhase.LOADING, failure = null)
        launchOwned(token) {
            val snapshot = loadCurrent(recordingId)
            if (snapshot.source.recordingId != recordingId) throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
            if (isCurrent(token)) {
                mutable.value = OfflineQaSessionState(
                    phase = OfflineQaSessionPhase.READY,
                    source = snapshot.source,
                    turns = if (previous.source == snapshot.source) previous.turns else emptyList(),
                )
            }
        }
        return true
    }

    fun submit(question: String, speakerId: String? = null, sampleRange: OfflineQaSampleRange? = null): Boolean {
        val before = mutable.value
        val source = before.source ?: return false
        if (!owner.isActive || before.phase != OfflineQaSessionPhase.READY || task?.isCompleted == false) return false
        val request = try { OfflineQaRequest(source, question.trim(), speakerId, sampleRange) }
        catch (_: IllegalArgumentException) { return false }
        val history = before.turns.takeLast(OfflineQaPromptContract.MAX_HISTORY_TURNS)
            .mapNotNull { (it.outcome as? OfflineQaOutcome.Answer)?.result?.let(::OfflineQaHistoryTurn) }
        // Do not bridge a failed most recent turn into older context.
        val usableHistory = if (before.turns.lastOrNull()?.outcome is OfflineQaOutcome.Failed) emptyList() else history
        val token = ++generation
        mutable.value = before.copy(phase = OfflineQaSessionPhase.RUNNING, pendingQuestion = request.question, failure = null)
        launchOwned(token) {
            requireSource(source)
            val outcome = ask(request, usableHistory)
            currentCoroutineContext().ensureActive()
            if (outcome is OfflineQaOutcome.Failed &&
                outcome.code in setOf(OfflineQaFailureCode.SOURCE_CHANGED, OfflineQaFailureCode.SOURCE_UNAVAILABLE)) {
                throw OfflineQaSourceException(outcome.code)
            }
            if (outcome is OfflineQaOutcome.Answer && outcome.result.context.request != request) {
                throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
            }
            requireSource(source)
            if (isCurrent(token)) {
                val turns = Collections.unmodifiableList(ArrayList((before.turns +
                    OfflineQaSessionTurn(request.question, outcome)).takeLast(MAX_DISPLAY_TURNS)))
                mutable.value = before.copy(phase = OfflineQaSessionPhase.READY, turns = turns, pendingQuestion = null)
            }
        }
        return true
    }

    fun cancel() {
        val running = task ?: return
        if (running.isCompleted || mutable.value.phase in setOf(OfflineQaSessionPhase.CLOSED, OfflineQaSessionPhase.CANCELLING, OfflineQaSessionPhase.UNAVAILABLE)) return
        val token = ++generation
        mutable.value = mutable.value.copy(phase = OfflineQaSessionPhase.CANCELLING, pendingQuestion = null)
        running.cancel()
        // Wait for coordinator cleanup before allowing another page request.
        launchOwned(token) {
            running.join()
            if (isCurrent(token)) {
                val current = mutable.value
                mutable.value = current.copy(
                    phase = if (current.source == null) OfflineQaSessionPhase.UNAVAILABLE else OfflineQaSessionPhase.READY,
                    failure = OfflineQaFailureCode.CANCELLED,
                )
            }
        }
    }

    fun clear() {
        if (!owner.isActive) return
        mutable.value = mutable.value.copy(turns = emptyList(), pendingQuestion = null, failure = null)
        if (task?.isCompleted == false) cancel()
    }

    /** Called by page lineage observer; fail closed until refresh verifies a new snapshot. */
    fun invalidate() {
        if (!owner.isActive || mutable.value.phase == OfflineQaSessionPhase.UNAVAILABLE) return
        val old = task
        val previousPhase = mutable.value.phase
        val token = ++generation
        mutable.value = OfflineQaSessionState(phase = OfflineQaSessionPhase.UNAVAILABLE, failure = OfflineQaFailureCode.SOURCE_CHANGED)
        // A cancellation waiter already owns the original cleanup; do not cancel that waiter.
        if (old != null && !old.isCompleted) {
            if (previousPhase != OfflineQaSessionPhase.CANCELLING) old.cancel()
            launchOwned(token) { old.join() }
        }
    }

    fun close() {
        ++generation
        mutable.value = OfflineQaSessionState(phase = OfflineQaSessionPhase.CLOSED)
        owner.cancel()
    }

    private suspend fun requireSource(source: OfflineQaSourceIdentity) {
        if (loadCurrent(recordingId).source != source) throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
    }

    private fun isCurrent(token: Long): Boolean = owner.isActive && generation == token

    private fun launchOwned(token: Long, body: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                body()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OfflineQaSourceException) {
                if (isCurrent(token)) mutable.value = OfflineQaSessionState(
                    phase = OfflineQaSessionPhase.UNAVAILABLE, failure = error.code,
                )
            } catch (_: Exception) {
                if (isCurrent(token)) mutable.value = OfflineQaSessionState(
                    phase = OfflineQaSessionPhase.UNAVAILABLE, failure = OfflineQaFailureCode.GENERATION_FAILED,
                )
            }
        }
        task = job
        job.start()
    }

    companion object { const val MAX_DISPLAY_TURNS = 20 }
}
