package io.github.ioannes78.voica

/**
 * P2-M1: pure Kotlin policy for a QA-only, fake-native owner journal.
 * The journal is separate from a best-effort phase log. It NEVER authorizes GPU/JNI.
 *
 * A previously ARMED (or malformed) journal is recovered as blocked. Only an
 * explicitly DURABLY committed RELEASE_CONFIRMED allows a later *mock* attempt.
 */
internal enum class Stage17P2M1MockOwnerState {
    ARMED,
    RELEASE_CONFIRMED,
    UNRESOLVED_POISONED,
}

internal data class Stage17P2M1MockOwnerRecord(
    val generation: Long,
    val token: Int,
    val nonce: String,
    val armEpochMs: Long,
    val processName: String,
    val state: Stage17P2M1MockOwnerState,
)

internal data class Stage17P2M1MockRecovery(
    val record: Stage17P2M1MockOwnerRecord?,
    val blocked: Boolean,
    val reason: String,
)

internal object Stage17P2M1MockJournal {
    const val EXTRA_NONCE = "p2m1_mock_attempt_nonce"
    private const val VERSION = "P2M1V1"
    private val noncePattern = Regex("[0-9a-fA-F-]{36}")

    fun encode(record: Stage17P2M1MockOwnerRecord): String =
        listOf(
            VERSION,
            record.generation.toString(),
            record.token.toString(),
            record.nonce,
            record.armEpochMs.toString(),
            record.processName,
            record.state.name,
        ).joinToString("|")

    fun restore(raw: String?, applicationId: String): Stage17P2M1MockRecovery {
        // The previous P2-L build had only a text report and NO real native.
        if (raw == null) return Stage17P2M1MockRecovery(null, false, "NO_MOCK_JOURNAL")
        val fields = raw.split('|')
        if (fields.size != 7 || fields[0] != VERSION) {
            return Stage17P2M1MockRecovery(null, true, "MALFORMED_JOURNAL")
        }
        val generation = fields[1].toLongOrNull()
        val token = fields[2].toIntOrNull()
        val epochMs = fields[4].toLongOrNull()
        val state = Stage17P2M1MockOwnerState.entries.firstOrNull { it.name == fields[6] }
        if (generation == null || generation <= 0L || token == null || token <= 0 ||
            epochMs == null || epochMs <= 0L || !noncePattern.matches(fields[3]) ||
            state == null || fields[5] != Stage17P2LMockContract.expectedProcess(applicationId)
        ) {
            return Stage17P2M1MockRecovery(null, true, "INVALID_JOURNAL_FIELDS")
        }
        val record = Stage17P2M1MockOwnerRecord(
            generation, token, fields[3], epochMs, fields[5], state,
        )
        return when (state) {
            Stage17P2M1MockOwnerState.RELEASE_CONFIRMED ->
                Stage17P2M1MockRecovery(record, false, "LAST_MOCK_RELEASE_DURABLE")
            Stage17P2M1MockOwnerState.ARMED ->
                Stage17P2M1MockRecovery(record, true, "INTERRUPTED_MOCK_ATTEMPT")
            Stage17P2M1MockOwnerState.UNRESOLVED_POISONED ->
                Stage17P2M1MockRecovery(record, true, "STICKY_MOCK_POISON")
        }
    }
}

internal data class Stage17P2M1MockExitRecord(
    val processName: String,
    val pid: Int,
    val uid: Int,
    val exitEpochMs: Long,
    val reason: Int,
)

/** Exact match only; no PID-only / first-record attribution. */
internal object Stage17P2M1MockExitCorrelator {
    enum class Verdict { MATCHED, UNKNOWN, AMBIGUOUS }

    fun classify(
        candidates: List<Stage17P2M1MockExitRecord>,
        expectedProcess: String,
        childPid: Int,
        appUid: Int,
        armEpochMs: Long,
        observedAtEpochMs: Long,
    ): Verdict {
        if (childPid <= 0 || appUid < 0 || armEpochMs <= 0L ||
            observedAtEpochMs < armEpochMs || expectedProcess.isBlank()
        ) return Verdict.UNKNOWN
        val matching = candidates.filter { event ->
            event.processName == expectedProcess &&
                event.pid == childPid &&
                event.uid == appUid &&
                event.exitEpochMs >= armEpochMs - 5_000L &&
                event.exitEpochMs <= observedAtEpochMs + 5_000L
        }
        return when (matching.size) {
            0 -> Verdict.UNKNOWN
            1 -> Verdict.MATCHED
            else -> Verdict.AMBIGUOUS
        }
    }
}

/**
 * Bounded mock-only admission demonstration. It does NOT replace the real
 * LocalLlmRuntimeCoordinator/ModelUseRegistry (both are process-local).
 */
internal class Stage17P2M1MockAdmission {
    private var owner: String? = null
    private var poisoned = false

    @Synchronized
    fun acquire(who: String): Boolean {
        if (who.isBlank() || poisoned || owner != null) return false
        owner = who
        return true
    }

    @Synchronized
    fun release(who: String, acknowledged: Boolean): Boolean {
        if (owner != who) return false
        if (!acknowledged) poisoned = true
        owner = null
        return acknowledged
    }

    @Synchronized
    fun blocked(): Boolean = poisoned || owner != null
}
