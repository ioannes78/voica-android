package io.github.ioannes78.voica

import java.util.concurrent.atomic.AtomicReference

/** Whole product task ownership, independent of native engine ownership. Never queues or preempts. */
internal class OfflineAiTaskAdmission {
    enum class Kind { SUMMARY, QA }

    class Lease internal constructor(val kind: Kind, val recordingId: String)

    private val owner = AtomicReference<Lease?>(null)

    fun tryAcquire(kind: Kind, recordingId: String): Lease? {
        require(recordingId.isNotBlank())
        val lease = Lease(kind, recordingId)
        return if (owner.compareAndSet(null, lease)) lease else null
    }

    fun release(lease: Lease): Boolean = owner.compareAndSet(lease, null)

    fun current(): Lease? = owner.get()
}
