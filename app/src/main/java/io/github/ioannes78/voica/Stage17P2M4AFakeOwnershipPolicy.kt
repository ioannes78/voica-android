package io.github.ioannes78.voica

/**
 * P2-M4A: DETERMINISTIC JVM FAKE ONLY. No Binder, Android process, FileLock,
 * disk/model I/O, LiteRT-LM, Engine, JNI or GPU.
 *
 * A shared in-memory fixture imitates an interprocess *transaction* for tests,
 * NOT an actual Android cross-process exclusion primitive.
 */
internal enum class Stage17P2M4AFakeOwner {
    PRODUCT_CPU, QA_CHILD,
}

internal enum class Stage17P2M4AFakePhase {
    VERIFIED_NEVER_ARMED,
    INTENT_DURABLE,
    CHILD_ADMITTED,
    NATIVE_ACTIVE,
    TERMINAL_OBSERVED,
    OWNER_RELEASE_REPORTED,
    RELEASE_CONFIRMED_DURABLE,
    UNRESOLVED_POISONED,
}

internal data class Stage17P2M4AFakeModelPin(
    val modelId: String,
    val version: String,
    val revision: Long,
    val sha256: String,
) {
    fun valid(): Boolean =
        modelId.matches(Regex("[a-zA-Z0-9._-]{1,100}")) &&
            version.matches(Regex("[a-zA-Z0-9._-]{1,100}")) &&
            revision > 0L &&
            sha256.matches(Regex("[0-9a-f]{64}"))
}

internal data class Stage17P2M4AFakeOwnerRecord(
    val generation: Long,
    val nonce: String,
    val owner: Stage17P2M4AFakeOwner?,
    val model: Stage17P2M4AFakeModelPin?,
    val phase: Stage17P2M4AFakePhase,
)

/**
 * Strict, content-free fake journal. Absence/invalid bytes are NEVER treated
 * as a clean native installation. The clean seed is explicitly test-only.
 */
internal object Stage17P2M4AFakeCodec {
    private const val VERSION = "P2M4AV1"
    private val noncePattern = Regex("[0-9a-fA-F-]{36}")

    fun encode(record: Stage17P2M4AFakeOwnerRecord): String {
        if (record.phase == Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED) {
            require(record.generation == 0L && record.nonce.isEmpty() &&
                record.owner == null && record.model == null)
            return "$VERSION|0|VERIFIED_NEVER_ARMED"
        }
        require(record.generation > 0L &&
            noncePattern.matches(record.nonce) &&
            record.owner != null &&
            record.model?.valid() == true)
        val model = requireNotNull(record.model)
        return listOf(
            VERSION, record.generation.toString(), record.phase.name,
            requireNotNull(record.owner).name, record.nonce,
            model.modelId, model.version, model.revision.toString(), model.sha256,
        ).joinToString("|")
    }

    fun decode(raw: String?): Stage17P2M4AFakeOwnerRecord? {
        if (raw == null || raw.length > 512) return null
        val fields = raw.split('|')
        if (fields.size == 3 && fields[0] == VERSION &&
            fields[1] == "0" && fields[2] == "VERIFIED_NEVER_ARMED"
        ) return cleanRecord()
        if (fields.size != 9 || fields[0] != VERSION) return null
        val generation = fields[1].toLongOrNull() ?: return null
        val phase = Stage17P2M4AFakePhase.entries.firstOrNull {
            it.name == fields[2] && it != Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED
        } ?: return null
        val owner = Stage17P2M4AFakeOwner.entries.firstOrNull { it.name == fields[3] }
            ?: return null
        val model = Stage17P2M4AFakeModelPin(
            fields[5], fields[6], fields[7].toLongOrNull() ?: return null, fields[8],
        )
        if (generation <= 0L || !noncePattern.matches(fields[4]) || !model.valid()) {
            return null
        }
        return Stage17P2M4AFakeOwnerRecord(generation, fields[4], owner, model, phase)
    }

    fun cleanRecord() = Stage17P2M4AFakeOwnerRecord(
        0L, "", null, null, Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED,
    )
}

/**
 * A fake durable medium. Sharing THIS object between arbiters is the test's
 * stand-in for a serialized, cross-process transaction. There is no real
 * filesystem or OS lock. Writes either commit fully or return false.
 */
internal class Stage17P2M4AFakeJournal private constructor(initialRaw: String?) {
    private var raw: String? = initialRaw
    private var failNext = false
    private var writeUncertain = false

    companion object {
        fun verifiedNeverArmedTestFixture(): Stage17P2M4AFakeJournal =
            Stage17P2M4AFakeJournal(
                Stage17P2M4AFakeCodec.encode(Stage17P2M4AFakeCodec.cleanRecord()),
            )

        fun untrustedTestFixture(raw: String?): Stage17P2M4AFakeJournal =
            Stage17P2M4AFakeJournal(raw)
    }

    @Synchronized fun readRaw(): String? = raw
    @Synchronized fun failNextCommit() { failNext = true }
    @Synchronized fun isWriteUncertain(): Boolean = writeUncertain

    // Only synthetic test code supplies forged/corrupt bytes; never a real file.
    @Synchronized fun simulateCorruptedStorage(rawOverride: String?) {
        raw = rawOverride
        writeUncertain = true
    }

    @Synchronized fun commit(next: String): Boolean {
        if (writeUncertain || failNext) {
            failNext = false
            writeUncertain = true
            return false
        }
        raw = next
        return true
    }
}

internal enum class Stage17P2M4AFakeMutation {
    REMOVE_VERSION, ROLLBACK, ACTIVATE, REPLACE, CLEAN_REFERENCED, IMMUTABLE_STAGING,
}

internal data class Stage17P2M4AFakeVerdict(
    val allowed: Boolean,
    val reason: String,
)

/**
 * Multiple JVM instances may compete with one FakeJournal to model the
 * arbitration transaction. This class is NOT wired to product model paths.
 */
internal class Stage17P2M4AFakeArbiter(
    private val journal: Stage17P2M4AFakeJournal,
) {
    fun snapshot(): Stage17P2M4AFakeOwnerRecord? =
        synchronized(journal) {
            if (journal.isWriteUncertain()) null
            else Stage17P2M4AFakeCodec.decode(journal.readRaw())
        }

    fun claim(
        owner: Stage17P2M4AFakeOwner,
        model: Stage17P2M4AFakeModelPin,
        nonce: String,
    ): Stage17P2M4AFakeVerdict = synchronized(journal) {
        val previous = usableRecord() ?: return@synchronized deny("JOURNAL_UNTRUSTED")
        if (!model.valid() || !Regex("[0-9a-fA-F-]{36}").matches(nonce)) {
            return@synchronized deny("IDENTITY_INVALID")
        }
        if (previous.phase != Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED &&
            previous.phase != Stage17P2M4AFakePhase.RELEASE_CONFIRMED_DURABLE
        ) return@synchronized deny("OWNER_UNRESOLVED")
        if (previous.generation == Long.MAX_VALUE ||
            (previous.generation > 0L && previous.nonce == nonce)
        ) return@synchronized deny("GENERATION_OR_NONCE_REPLAY")

        val next = Stage17P2M4AFakeOwnerRecord(
            generation = previous.generation + 1L,
            nonce = nonce,
            owner = owner,
            model = model,
            phase = Stage17P2M4AFakePhase.INTENT_DURABLE,
        )
        if (!journal.commit(Stage17P2M4AFakeCodec.encode(next))) {
            deny("DURABLE_ARM_FAILED")
        } else {
            grant("INTENT_DURABLE")
        }
    }

    fun childAdmission(
        generation: Long, nonce: String, actualModel: Stage17P2M4AFakeModelPin,
    ): Stage17P2M4AFakeVerdict = transition(
        generation, nonce,
        expected = Stage17P2M4AFakePhase.INTENT_DURABLE,
        next = Stage17P2M4AFakePhase.CHILD_ADMITTED,
        requiredOwner = Stage17P2M4AFakeOwner.QA_CHILD,
        presentedModel = actualModel,
    )

    /** Fake CPU owner enters without claiming a separate child process. */
    fun productCpuEntry(generation: Long, nonce: String): Stage17P2M4AFakeVerdict =
        transition(
            generation, nonce,
            Stage17P2M4AFakePhase.INTENT_DURABLE,
            Stage17P2M4AFakePhase.NATIVE_ACTIVE,
            requiredOwner = Stage17P2M4AFakeOwner.PRODUCT_CPU,
        )

    fun childNativeEntry(generation: Long, nonce: String): Stage17P2M4AFakeVerdict =
        transition(
            generation, nonce,
            Stage17P2M4AFakePhase.CHILD_ADMITTED,
            Stage17P2M4AFakePhase.NATIVE_ACTIVE,
            requiredOwner = Stage17P2M4AFakeOwner.QA_CHILD,
        )

    fun terminal(generation: Long, nonce: String): Stage17P2M4AFakeVerdict =
        transition(
            generation, nonce,
            Stage17P2M4AFakePhase.NATIVE_ACTIVE,
            Stage17P2M4AFakePhase.TERMINAL_OBSERVED,
        )

    fun childReleaseAck(generation: Long, nonce: String): Stage17P2M4AFakeVerdict =
        transition(
            generation, nonce,
            Stage17P2M4AFakePhase.TERMINAL_OBSERVED,
            Stage17P2M4AFakePhase.OWNER_RELEASE_REPORTED,
        )

    fun parentCommitRelease(generation: Long, nonce: String): Stage17P2M4AFakeVerdict =
        transition(
            generation, nonce,
            Stage17P2M4AFakePhase.OWNER_RELEASE_REPORTED,
            Stage17P2M4AFakePhase.RELEASE_CONFIRMED_DURABLE,
        )

    fun releaseUnconfirmed(generation: Long, nonce: String): Stage17P2M4AFakeVerdict =
        synchronized(journal) {
            val rec = usableRecord() ?: return@synchronized deny("JOURNAL_UNTRUSTED")
            if (rec.generation != generation || rec.nonce != nonce) {
                return@synchronized deny("STALE_ATTEMPT")
            }
            if (rec.phase == Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED ||
                rec.phase == Stage17P2M4AFakePhase.RELEASE_CONFIRMED_DURABLE ||
                rec.phase == Stage17P2M4AFakePhase.UNRESOLVED_POISONED
            ) return@synchronized deny("NOT_ACTIVE")
            write(rec.copy(phase = Stage17P2M4AFakePhase.UNRESOLVED_POISONED))
        }

    /**
     * A local ModelLease.close can occur after quarantine. It NEVER
     * changes this fake cross-process effective deletion barrier.
     */
    fun legacyLocalLeaseClosed(): Stage17P2M4AFakeVerdict =
        synchronized(journal) {
            if (usableRecord() == null) deny("JOURNAL_UNTRUSTED")
            else grant("NO_GLOBAL_OWNER_CHANGE")
        }

    fun modelMutation(
        modelId: String,
        mutation: Stage17P2M4AFakeMutation,
    ): Stage17P2M4AFakeVerdict = synchronized(journal) {
        val rec = usableRecord() ?: return@synchronized deny("JOURNAL_UNTRUSTED")
        if (mutation == Stage17P2M4AFakeMutation.IMMUTABLE_STAGING) {
            return@synchronized grant("ISOLATED_STAGING_ONLY")
        }
        if (rec.model?.modelId == modelId &&
            rec.phase != Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED &&
            rec.phase != Stage17P2M4AFakePhase.RELEASE_CONFIRMED_DURABLE
        ) deny("PINNED_MODEL_IN_USE_OR_POISONED") else grant("NO_PINNED_OWNER")
    }

    fun nativeAdmissionBlocked(): Boolean = synchronized(journal) {
        val rec = usableRecord()
        rec == null || (rec.phase != Stage17P2M4AFakePhase.VERIFIED_NEVER_ARMED &&
            rec.phase != Stage17P2M4AFakePhase.RELEASE_CONFIRMED_DURABLE)
    }

    private fun transition(
        generation: Long,
        nonce: String,
        expected: Stage17P2M4AFakePhase,
        next: Stage17P2M4AFakePhase,
        requiredOwner: Stage17P2M4AFakeOwner? = null,
        presentedModel: Stage17P2M4AFakeModelPin? = null,
    ): Stage17P2M4AFakeVerdict = synchronized(journal) {
        val rec = usableRecord() ?: return@synchronized deny("JOURNAL_UNTRUSTED")
        if (rec.generation != generation || rec.nonce != nonce) {
            return@synchronized deny("STALE_ATTEMPT")
        }
        if (rec.phase != expected || (requiredOwner != null && rec.owner != requiredOwner)) {
            return@synchronized deny("INVALID_PHASE_OR_OWNER")
        }
        if (presentedModel != null && rec.model != presentedModel) {
            return@synchronized deny("MODEL_PIN_MISMATCH")
        }
        write(rec.copy(phase = next))
    }

    private fun write(next: Stage17P2M4AFakeOwnerRecord): Stage17P2M4AFakeVerdict =
        if (journal.commit(Stage17P2M4AFakeCodec.encode(next))) grant(next.phase.name)
        else deny("JOURNAL_COMMIT_FAILED")

    private fun usableRecord(): Stage17P2M4AFakeOwnerRecord? =
        if (journal.isWriteUncertain()) null
        else Stage17P2M4AFakeCodec.decode(journal.readRaw())

    private fun grant(reason: String) = Stage17P2M4AFakeVerdict(true, reason)
    private fun deny(reason: String) = Stage17P2M4AFakeVerdict(false, reason)
}
