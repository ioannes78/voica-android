package io.github.ioannes78.voica.localllm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

enum class LocalLlmThermalLevel {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

enum class LocalLlmResourceReason {
    ALLOWED,
    SYSTEM_LOW_MEMORY,
    MEMORY_HEADROOM_LOW,
    THERMAL_SEVERE,
    THERMAL_CRITICAL,
}

data class LocalLlmResourceSnapshot(
    val availableMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val lowMemory: Boolean,
    val lowMemoryThresholdBytes: Long,
    val thermalLevel: LocalLlmThermalLevel,
) {
    init {
        require(availableMemoryBytes >= 0L)
        require(totalMemoryBytes >= 0L)
        require(lowMemoryThresholdBytes >= 0L)
    }
}

data class LocalLlmResourceDecision(
    val gpuAllowed: Boolean,
    val cpuAllowed: Boolean,
    val gpuReason: LocalLlmResourceReason,
    val cpuReason: LocalLlmResourceReason,
)

/** Device-state gate. It deliberately does not encode the P0 test phone's 8 GB RAM as a product rule. */
class LocalLlmResourceGate {
    fun evaluate(snapshot: LocalLlmResourceSnapshot): LocalLlmResourceDecision {
        if (snapshot.lowMemory) {
            return LocalLlmResourceDecision(
                gpuAllowed = false,
                cpuAllowed = false,
                gpuReason = LocalLlmResourceReason.SYSTEM_LOW_MEMORY,
                cpuReason = LocalLlmResourceReason.SYSTEM_LOW_MEMORY,
            )
        }

        val gpuThermalBlocked = snapshot.thermalLevel >= LocalLlmThermalLevel.SEVERE
        val cpuThermalBlocked = snapshot.thermalLevel >= LocalLlmThermalLevel.CRITICAL
        val cpuMemoryFloor = snapshot.lowMemoryThresholdBytes
        val gpuMemoryFloor = saturatingDouble(cpuMemoryFloor)
        val cpuMemoryAllowed =
            cpuMemoryFloor == 0L || snapshot.availableMemoryBytes > cpuMemoryFloor
        val gpuMemoryAllowed =
            gpuMemoryFloor == 0L || snapshot.availableMemoryBytes > gpuMemoryFloor

        val gpuAllowed = !gpuThermalBlocked && gpuMemoryAllowed
        val cpuAllowed = !cpuThermalBlocked && cpuMemoryAllowed
        return LocalLlmResourceDecision(
            gpuAllowed = gpuAllowed,
            cpuAllowed = cpuAllowed,
            gpuReason =
                when {
                    gpuThermalBlocked -> LocalLlmResourceReason.THERMAL_SEVERE
                    !gpuMemoryAllowed -> LocalLlmResourceReason.MEMORY_HEADROOM_LOW
                    else -> LocalLlmResourceReason.ALLOWED
                },
            cpuReason =
                when {
                    cpuThermalBlocked -> LocalLlmResourceReason.THERMAL_CRITICAL
                    !cpuMemoryAllowed -> LocalLlmResourceReason.MEMORY_HEADROOM_LOW
                    else -> LocalLlmResourceReason.ALLOWED
                },
        )
    }

    private fun saturatingDouble(value: Long): Long =
        if (value > Long.MAX_VALUE / 2L) Long.MAX_VALUE else value * 2L
}

class AndroidLocalLlmResourceProbe(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    fun snapshot(): LocalLlmResourceSnapshot {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return LocalLlmResourceSnapshot(
            availableMemoryBytes = memoryInfo.availMem.coerceAtLeast(0L),
            totalMemoryBytes = memoryInfo.totalMem.coerceAtLeast(0L),
            lowMemory = memoryInfo.lowMemory,
            lowMemoryThresholdBytes = memoryInfo.threshold.coerceAtLeast(0L),
            thermalLevel = currentThermalLevel(),
        )
    }

    private fun currentThermalLevel(): LocalLlmThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return LocalLlmThermalLevel.UNKNOWN
        }
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> LocalLlmThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> LocalLlmThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> LocalLlmThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> LocalLlmThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> LocalLlmThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> LocalLlmThermalLevel.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> LocalLlmThermalLevel.SHUTDOWN
            else -> LocalLlmThermalLevel.UNKNOWN
        }
    }
}

class LocalLlmBackendResolver {
    fun resolve(
        policy: LocalLlmBackendPolicy,
        decision: LocalLlmResourceDecision,
    ): List<LocalLlmBackendResolution> =
        when (policy) {
            LocalLlmBackendPolicy.GPU -> {
                requireAllowed(
                    policy = policy,
                    backend = LocalLlmBackend.GPU,
                    allowed = decision.gpuAllowed,
                    reason = decision.gpuReason,
                )
                listOf(
                    LocalLlmBackendResolution(
                        requestedPolicy = policy,
                        resolvedBackend = LocalLlmBackend.GPU,
                        reason = "EXPLICIT_GPU",
                    ),
                )
            }

            LocalLlmBackendPolicy.CPU -> {
                requireAllowed(
                    policy = policy,
                    backend = LocalLlmBackend.CPU,
                    allowed = decision.cpuAllowed,
                    reason = decision.cpuReason,
                )
                listOf(
                    LocalLlmBackendResolution(
                        requestedPolicy = policy,
                        resolvedBackend = LocalLlmBackend.CPU,
                        reason = "EXPLICIT_CPU",
                    ),
                )
            }

            LocalLlmBackendPolicy.AUTO -> {
                val candidates = mutableListOf<LocalLlmBackendResolution>()
                if (decision.gpuAllowed) {
                    candidates +=
                        LocalLlmBackendResolution(
                            requestedPolicy = policy,
                            resolvedBackend = LocalLlmBackend.GPU,
                            reason = "GPU_AVAILABLE",
                        )
                }
                if (decision.cpuAllowed) {
                    candidates +=
                        LocalLlmBackendResolution(
                            requestedPolicy = policy,
                            resolvedBackend = LocalLlmBackend.CPU,
                            reason =
                                if (decision.gpuAllowed) {
                                    "GPU_RUNTIME_FALLBACK"
                                } else {
                                    "GPU_PREFLIGHT_BLOCKED_${decision.gpuReason.name}"
                                },
                            fallbackAttempted = true,
                        )
                }
                if (candidates.isEmpty()) {
                    throw blockedFailure(policy, decision)
                }
                candidates
            }
        }

    private fun requireAllowed(
        policy: LocalLlmBackendPolicy,
        backend: LocalLlmBackend,
        allowed: Boolean,
        reason: LocalLlmResourceReason,
    ) {
        if (allowed) return
        throw LocalLlmRuntimeException(
            LocalLlmFailure(
                code = reason.toFailureCode(),
                phase = LocalLlmFailurePhase.PREFLIGHT,
                message = "$backend blocked by resource preflight: $reason",
                requestedPolicy = policy,
                resolvedBackend = backend,
            ),
        )
    }

    private fun blockedFailure(
        policy: LocalLlmBackendPolicy,
        decision: LocalLlmResourceDecision,
    ): LocalLlmRuntimeException {
        val reason =
            if (decision.cpuReason == LocalLlmResourceReason.THERMAL_CRITICAL ||
                decision.gpuReason == LocalLlmResourceReason.THERMAL_SEVERE
            ) {
                LocalLlmResourceReason.THERMAL_CRITICAL
            } else {
                decision.cpuReason
            }
        return LocalLlmRuntimeException(
            LocalLlmFailure(
                code = reason.toFailureCode(),
                phase = LocalLlmFailurePhase.PREFLIGHT,
                message = "no local LLM backend passed resource preflight",
                requestedPolicy = policy,
            ),
        )
    }
}

private fun LocalLlmResourceReason.toFailureCode(): LocalLlmFailureCode =
    when (this) {
        LocalLlmResourceReason.THERMAL_SEVERE,
        LocalLlmResourceReason.THERMAL_CRITICAL,
        -> LocalLlmFailureCode.THERMAL_BLOCKED

        LocalLlmResourceReason.SYSTEM_LOW_MEMORY,
        LocalLlmResourceReason.MEMORY_HEADROOM_LOW,
        -> LocalLlmFailureCode.INSUFFICIENT_MEMORY

        LocalLlmResourceReason.ALLOWED -> LocalLlmFailureCode.UNKNOWN
    }

/** Applies AUTO GPU-first execution while keeping retry safety outside the native adapter. */
class LocalLlmExecutionController(
    private val attempt:
        suspend (
            modelPath: String,
            request: LocalLlmGenerationRequest,
            resolution: LocalLlmBackendResolution,
        ) -> LocalLlmGenerationResult,
    private val resolver: LocalLlmBackendResolver = LocalLlmBackendResolver(),
) {
    constructor(
        coordinator: LocalLlmRuntimeCoordinator,
        resolver: LocalLlmBackendResolver = LocalLlmBackendResolver(),
    ) : this(
        attempt = { modelPath, request, resolution ->
            coordinator.generate(modelPath, request, resolution)
        },
        resolver = resolver,
    )

    suspend fun generate(
        modelPath: String,
        request: LocalLlmGenerationRequest,
        resourceDecision: LocalLlmResourceDecision,
    ): LocalLlmGenerationResult {
        val candidates = resolver.resolve(request.config.backendPolicy, resourceDecision)
        var gpuFailure: LocalLlmRuntimeException? = null

        candidates.forEach { candidate ->
            val effectiveCandidate =
                if (candidate.resolvedBackend == LocalLlmBackend.CPU && gpuFailure != null) {
                    candidate.copy(
                        reason = "GPU_${gpuFailure!!.failure.code.name}_SAFE_FALLBACK",
                        fallbackAttempted = true,
                    )
                } else {
                    candidate
                }
            try {
                return attempt(modelPath, request, effectiveCandidate)
            } catch (failure: LocalLlmRuntimeException) {
                val canRetryCpu =
                    request.config.backendPolicy == LocalLlmBackendPolicy.AUTO &&
                        candidate.resolvedBackend == LocalLlmBackend.GPU &&
                        failure.failure.isSafeForCpuFallback() &&
                        candidates.any { it.resolvedBackend == LocalLlmBackend.CPU }
                if (!canRetryCpu) throw failure
                gpuFailure = failure
            }
        }

        throw gpuFailure
            ?: LocalLlmRuntimeException(
                LocalLlmFailure(
                    code = LocalLlmFailureCode.UNKNOWN,
                    phase = LocalLlmFailurePhase.PREFLIGHT,
                    message = "no local LLM backend candidate executed",
                    requestedPolicy = request.config.backendPolicy,
                ),
            )
    }
}

internal fun LocalLlmFailure.isSafeForCpuFallback(): Boolean {
    if (causeClassName?.contains("OutOfMemoryError") == true) return false
    if (phase == LocalLlmFailurePhase.GENERATING) return false
    return code == LocalLlmFailureCode.BACKEND_UNSUPPORTED ||
        code == LocalLlmFailureCode.ENGINE_INIT_FAILED ||
        code == LocalLlmFailureCode.CONVERSATION_CREATE_FAILED
}
