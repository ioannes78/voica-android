package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * P2-M4B1. QA-only, FAKE model, no native execution. Separate from P2-M1/M3.
 * This gate intentionally has NO process termination command.
 */
internal object Stage17P2M4BContract {
    const val JOURNAL = "stage17_p2m4b_disposable_parent_journal"
    const val KEY_RECORD = "owner_record"
    const val KEY_SEEDED = "explicitly_seeded_v1"
    const val PARENT_SUFFIX = ":stage17_m4b_parent"
    const val CHILD_SUFFIX = ":stage17_m4b_child"
    const val FAKE_MODEL = "P2_M4B_FAKE_ONLY"
    const val WHAT_ARM = 41
    const val WHAT_CHILD_ADMIT = 42
    const val WHAT_CHILD_READY = 43
    const val WHAT_PARENT_READY = 44
    const val WHAT_DENIED = 45
    const val EXTRA_GENERATION = "generation"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_MODEL = "model"
    const val EXTRA_PROCESS = "process"
    const val EXTRA_UID = "uid"
    const val EXTRA_PID = "pid"
    const val FAULT_INJECTION_ENABLED = false
    // No controller broker/durable authorization wired in B1: deny Binder admission.
    const val IPC_ADMISSION_ENABLED = false

    fun currentProcessName(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName

    fun allowPackage(appId: String): Boolean =
        appId == "io.github.ioannes78.voica.qa" ||
            appId == "io.github.ioannes78.voica.m4blab"

    fun parentProcess(appId: String) = appId + PARENT_SUFFIX
    fun childProcess(appId: String) = appId + CHILD_SUFFIX

    fun validIdentity(
        appId: String, observedProcess: String?,
        expectedSuffix: String, generation: Long, nonce: String?,
        model: String?, uid: Int, actualUid: Int,
    ): Boolean =
        allowPackage(appId) &&
            observedProcess == appId + expectedSuffix &&
            generation > 0L &&
            nonce != null &&
            nonce.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) &&
            model == FAKE_MODEL &&
            uid == actualUid && uid > 0

    /** Never authorize an exit in B1; later gates require explicit approval. */
    fun allowParentSelfExit(appId: String, process: String?, approved: Boolean): Boolean =
        FAULT_INJECTION_ENABLED &&
            approved && allowPackage(appId) && process == parentProcess(appId)
}
