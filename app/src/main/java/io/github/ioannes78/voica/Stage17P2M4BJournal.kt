package io.github.ioannes78.voica

/**
 * P2-M4B1 controller-owned, one-writer fake journal codec.
 * No cross-process SharedPreferences contract: only the main-process controller
 * may read or commit records. No seeding from absence; missing/corrupt = deny.
 */
internal enum class Stage17P2M4BPhase {
    VERIFIED_NEVER_ARMED, INTENT_DURABLE, PARENT_BOUND, CHILD_ADMITTED,
    FAKE_OWNER_ACTIVE, UNRESOLVED_POISONED,
    TERMINAL_FAKE_OBSERVED, CHILD_RELEASE_ACK_FAKE_OBSERVED,
    PARENT_RELEASE_ACK_FAKE_OBSERVED, RELEASE_CONFIRMED_DURABLE,
}

internal data class Stage17P2M4BRecord(
    val generation: Long,
    val nonce: String,
    val phase: Stage17P2M4BPhase,
)

internal object Stage17P2M4BCodec {
    private const val VERSION = "P2M4BV1"
    private val noncePattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    fun encode(record: Stage17P2M4BRecord): String {
        if (record.phase == Stage17P2M4BPhase.VERIFIED_NEVER_ARMED) {
            require(record.generation == 0L && record.nonce.isEmpty())
        } else {
            require(record.generation > 0L && noncePattern.matches(record.nonce))
        }
        return "$VERSION|${record.generation}|${record.nonce}|${record.phase}"
    }

    fun decode(raw: String?): Stage17P2M4BRecord? {
        if (raw == null || raw.length > 160) return null
        val fields = raw.split('|')
        if (fields.size != 4 || fields[0] != VERSION) return null
        val generation = fields[1].toLongOrNull() ?: return null
        val phase = Stage17P2M4BPhase.entries.firstOrNull { it.name == fields[3] }
            ?: return null
        val record = Stage17P2M4BRecord(generation, fields[2], phase)
        return runCatching { encode(record) }.getOrNull()?.let { if (it == raw) record else null }
    }

    fun next(
        current: Stage17P2M4BRecord?, generation: Long, nonce: String,
        next: Stage17P2M4BPhase,
    ): Stage17P2M4BRecord? {
        current ?: return null
        if (current.generation != generation || current.nonce != nonce ||
            current.phase == Stage17P2M4BPhase.UNRESOLVED_POISONED
        ) return null
        val permitted = when (current.phase) {
            Stage17P2M4BPhase.INTENT_DURABLE ->
                next == Stage17P2M4BPhase.PARENT_BOUND || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            Stage17P2M4BPhase.PARENT_BOUND ->
                next == Stage17P2M4BPhase.CHILD_ADMITTED || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            Stage17P2M4BPhase.CHILD_ADMITTED ->
                next == Stage17P2M4BPhase.FAKE_OWNER_ACTIVE || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            Stage17P2M4BPhase.FAKE_OWNER_ACTIVE ->
                next == Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            Stage17P2M4BPhase.TERMINAL_FAKE_OBSERVED ->
                next == Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            Stage17P2M4BPhase.CHILD_RELEASE_ACK_FAKE_OBSERVED ->
                next == Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            Stage17P2M4BPhase.PARENT_RELEASE_ACK_FAKE_OBSERVED ->
                next == Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE || next == Stage17P2M4BPhase.UNRESOLVED_POISONED
            else -> false
        }
        return if (permitted) current.copy(phase = next) else null
    }

    fun claim(current: Stage17P2M4BRecord?, nonce: String): Stage17P2M4BRecord? {
        if (current == null || current.phase !in setOf(
                Stage17P2M4BPhase.VERIFIED_NEVER_ARMED,
                Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
            ) || current.generation == Long.MAX_VALUE || current.nonce == nonce ||
            !noncePattern.matches(nonce)
        ) return null
        return Stage17P2M4BRecord(current.generation + 1L, nonce, Stage17P2M4BPhase.INTENT_DURABLE)
    }

    /**
     * Reopening is read-only. Binder availability and process metadata never prove
     * native or fake ownership release.
     */
    fun recoveryVerdict(record: Stage17P2M4BRecord?): String = when {
        record == null -> "DENY_UNTRUSTED_JOURNAL"
        record.phase == Stage17P2M4BPhase.VERIFIED_NEVER_ARMED -> "VERIFIED_NEVER_ARMED"
        record.phase == Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE -> "LAST_FAKE_RELEASE_DURABLE"
        record.phase == Stage17P2M4BPhase.UNRESOLVED_POISONED -> "STICKY_M4B_POISON"
        else -> "DENY_INTERRUPTED_FAKE_OWNER"
    }

    fun blocked(current: Stage17P2M4BRecord?): Boolean =
        current == null || current.phase !in setOf(
            Stage17P2M4BPhase.VERIFIED_NEVER_ARMED,
            Stage17P2M4BPhase.RELEASE_CONFIRMED_DURABLE,
        )
}
