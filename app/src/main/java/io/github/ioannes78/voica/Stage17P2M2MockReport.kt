package io.github.ioannes78.voica

/** Recovered durable owner status is never inferred from cached phase text. */
internal object Stage17P2M2MockReport {
    private val phaseLine = Regex("""^\+\d{1,12}ms [A-Z_]{3,48}$""")

    fun recovered(
        recovery: Stage17P2M1MockRecovery,
        cachedPreviousReport: String?,
    ): String {
        val history = cachedPreviousReport.orEmpty().lineSequence()
            .filter { phaseLine.matches(it) }
            .take(Stage17P2LMockContract.MAX_EVENTS).toList()
        val currentDurability = when (recovery.record?.state) {
            Stage17P2M1MockOwnerState.RELEASE_CONFIRMED -> "RELEASE_CONFIRMED_DURABLE"
            Stage17P2M1MockOwnerState.ARMED -> "RECOVERED_ARMED_BLOCKED"
            Stage17P2M1MockOwnerState.UNRESOLVED_POISONED -> "UNRESOLVED_POISON_DURABLE"
            null -> if (recovery.blocked) "INVALID_JOURNAL_BLOCKED" else "NO_MOCK_JOURNAL"
        }
        return buildString {
            appendLine("P2-L MOCK ONLY · NO LLM / NO JNI / NO GPU")
            appendLine("nativeExecution=false")
            appendLine("p2m2ReportScope=RECOVERED_OWNER_JOURNAL")
            appendLine("poisoned=${recovery.blocked}")
            appendLine("p2m1OwnerDurability=$currentDurability")
            appendLine("p2m1OwnerRecovery=${recovery.reason}")
            appendLine("p2m1OwnerBlocked=${recovery.blocked}")
            appendLine("p2m1ReleaseCommitPending=false")
            appendLine("p2m1ChildExitCorrelation=NOT_CHECKED")
            appendLine("eventCount=0")
            appendLine("historicalSnapshotOnly=${history.isNotEmpty()}")
            appendLine("historicalEventCount=${history.size}")
            history.forEach { appendLine("historicalEvent=$it") }
        }.trimEnd()
    }
}

/** No automatic retry when Binder delivers a late/reconnected Service callback. */
internal object Stage17P2M2MockConnectionPolicy {
    fun canSend(
        bindingIsCurrent: Boolean,
        tokenActive: Boolean,
        ownerBlocked: Boolean,
        releaseCommitPending: Boolean,
        serviceClassMatches: Boolean,
        binderPresent: Boolean,
    ): Boolean =
        bindingIsCurrent && tokenActive && !ownerBlocked &&
            !releaseCommitPending && serviceClassMatches && binderPresent
}
