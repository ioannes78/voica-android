package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM unit tests; NO LLM, JNI, Binder or GPU. */
class Stage17P2M2MockReportTest {
    private val appId = "io.github.ioannes78.voica.qa"
    private val previous = Stage17P2M1MockOwnerRecord(
        generation = 3L, token = 9,
        nonce = "09f9f9ab-1550-4689-93e2-b3d21c0c1127",
        armEpochMs = 1_789_000_000_000L,
        processName = Stage17P2LMockContract.expectedProcess(appId),
        state = Stage17P2M1MockOwnerState.RELEASE_CONFIRMED,
    )
    private fun restore(state: Stage17P2M1MockOwnerState) =
        Stage17P2M1MockJournal.restore(
            Stage17P2M1MockJournal.encode(previous.copy(state = state)), appId,
        )

    @Test fun reopenedReportUsesDurableReleaseNotCachedArmed() {
        val stale = """
            poisoned=false
            p2m1OwnerDurability=ARMED_DURABLE
            eventCount=13
            +14ms SEND_ENTERED
            +15ms FIRST_EMISSION
            +16ms SEND_RETURNED
            +55ms RELEASE_CONFIRMED
        """.trimIndent()
        val report = Stage17P2M2MockReport.recovered(
            restore(Stage17P2M1MockOwnerState.RELEASE_CONFIRMED), stale,
        )
        assertTrue(report.contains("p2m2ReportScope=RECOVERED_OWNER_JOURNAL"))
        assertTrue(report.contains("p2m1OwnerRecovery=LAST_MOCK_RELEASE_DURABLE"))
        assertTrue(report.contains("p2m1OwnerDurability=RELEASE_CONFIRMED_DURABLE"))
        assertTrue(report.contains("p2m1OwnerBlocked=false"))
        assertFalse(report.contains("p2m1OwnerDurability=ARMED_DURABLE"))
        assertTrue(report.contains("eventCount=0"))
        assertTrue(report.contains("historicalSnapshotOnly=true"))
        assertTrue(report.contains("historicalEventCount=4"))
        assertTrue(report.contains("historicalEvent=+15ms FIRST_EMISSION"))
    }

    @Test fun interruptedOrPoisonedOwnerCannotLookReleased() {
        for (state in listOf(
            Stage17P2M1MockOwnerState.ARMED,
            Stage17P2M1MockOwnerState.UNRESOLVED_POISONED,
        )) {
            val report = Stage17P2M2MockReport.recovered(restore(state), "+1ms SEND_ENTERED")
            assertTrue(report.contains("p2m1OwnerBlocked=true"))
            assertTrue(report.contains("poisoned=true"))
            assertTrue(report.contains("eventCount=0"))
        }
    }

    @Test fun malformedJournalReportsBlocked() {
        val recovery = Stage17P2M1MockJournal.restore("corrupted", appId)
        val report = Stage17P2M2MockReport.recovered(recovery, null)
        assertTrue(report.contains("p2m1OwnerDurability=INVALID_JOURNAL_BLOCKED"))
        assertTrue(report.contains("p2m1OwnerBlocked=true"))
        assertTrue(report.contains("historicalEventCount=0"))
    }

    @Test fun historicalEvidenceIsBoundedAndContentFree() {
        val stale = buildString {
            appendLine("p2m1OwnerDurability=ARMED_DURABLE")
            appendLine("prompt=private speech")
            appendLine("recording=/private.wav")
            repeat(70) { count -> appendLine("+${count}ms SEND_ENTERED") }
            appendLine("+3ms BAD_INJECTION; DELETE")
        }
        val report = Stage17P2M2MockReport.recovered(
            Stage17P2M1MockJournal.restore(null, appId), stale,
        )
        assertTrue(report.contains("historicalEventCount=32"))
        assertEquals(32, report.lineSequence().count { it.startsWith("historicalEvent=") })
        assertFalse(report.contains("private speech"))
        assertFalse(report.contains("/private.wav"))
        assertFalse(report.contains("BAD_INJECTION"))
        assertFalse(report.contains("p2m1OwnerDurability=ARMED_DURABLE"))
    }
}

class Stage17P2M2MockConnectionPolicyTest {
    private fun canSend(
        bindingIsCurrent: Boolean = true,
        tokenActive: Boolean = true,
        ownerBlocked: Boolean = false,
        releaseCommitPending: Boolean = false,
        serviceClassMatches: Boolean = true,
        binderPresent: Boolean = true,
    ) = Stage17P2M2MockConnectionPolicy.canSend(
        bindingIsCurrent, tokenActive, ownerBlocked,
        releaseCommitPending, serviceClassMatches, binderPresent,
    )

    @Test fun onlyCurrentUnblockedBindingCanSend() {
        assertTrue(canSend())
        assertFalse(canSend(bindingIsCurrent = false))
        assertFalse(canSend(tokenActive = false))
        assertFalse(canSend(ownerBlocked = true))
        assertFalse(canSend(releaseCommitPending = true))
        assertFalse(canSend(serviceClassMatches = false))
        assertFalse(canSend(binderPresent = false))
    }

    @Test fun reconnectAfterLossDoesNotAutoResend() {
        assertFalse(canSend(bindingIsCurrent = false, ownerBlocked = true))
        assertFalse(canSend(bindingIsCurrent = false, tokenActive = false))
    }
}


/** A prior asynchronous fake callback must not impersonate a later attempt. */
class Stage17P2M2MockCallbackGuardTest {
    @Test fun delayedCallbackCannotInheritNewTokenOrNonce() {
        val token = 131
        val nonce = "old-opaque-nonce"
        val guard = Stage17P2M2MockCallbackGuard
        assertTrue(guard.matches(token, nonce, token, nonce))
        assertFalse(guard.matches(token, nonce, 999, "new-opaque-nonce"))
        assertFalse(guard.matches(token, nonce, token, "new-opaque-nonce"))
        assertFalse(guard.matches(token, nonce, 0, null))
        assertFalse(guard.matches(token, null, token, nonce))
    }
}
