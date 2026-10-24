package io.github.ioannes78.voica.localllm

import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ProbeBackend {
    CPU,
    GPU,
}

data class Stage17ProbeRequest(
    val modelPath: String,
    val cacheDir: String,
    val backend: ProbeBackend,
) {
    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(cacheDir.isNotBlank()) { "cacheDir must not be blank" }
    }
}

data class Stage17ProbeResult(
    val runtimeVersion: String,
    val backend: ProbeBackend,
    val initializeMs: Long,
    val timeToFirstEmissionMs: Long?,
    val totalGenerationMs: Long,
    val pssBeforeInitializeKb: Long,
    val pssAfterInitializeKb: Long,
    val pssAfterGenerationKb: Long,
    val responsePreview: String,
)

/**
 * Stage 17 / P0 only.
 *
 * This probe deliberately stays outside the production summary / retrieval / Q&A path. Its only
 * job is to verify that the pinned LiteRT-LM runtime and a caller-supplied .litertlm artifact can
 * initialize and generate on a physical Android device while capturing coarse latency/PSS data.
 * Cancelling the calling coroutine closes the conversation and engine through the use blocks.
 */
class Stage17LocalLlmProbe {
    suspend fun run(request: Stage17ProbeRequest): Stage17ProbeResult =
        withContext(Dispatchers.Default) {
            val backend =
                when (request.backend) {
                    ProbeBackend.CPU -> Backend.CPU()
                    ProbeBackend.GPU -> Backend.GPU()
                }
            val config =
                EngineConfig(
                    modelPath = request.modelPath,
                    backend = backend,
                    cacheDir = request.cacheDir,
                )

            val pssBeforeInitializeKb = Debug.getPss()
            var pssAfterInitializeKb = pssBeforeInitializeKb
            var firstEmissionNanos: Long? = null
            var generationStartedNanos = 0L
            var generationFinishedNanos = 0L
            val response = StringBuilder()

            Engine(config).use { engine ->
                val initializeStartedNanos = SystemClock.elapsedRealtimeNanos()
                engine.initialize()
                val initializeFinishedNanos = SystemClock.elapsedRealtimeNanos()
                pssAfterInitializeKb = Debug.getPss()

                engine.createConversation().use { conversation ->
                    generationStartedNanos = SystemClock.elapsedRealtimeNanos()
                    conversation.sendMessageAsync(PROBE_PROMPT).collect { message ->
                        coroutineContext.ensureActive()
                        if (firstEmissionNanos == null) {
                            firstEmissionNanos = SystemClock.elapsedRealtimeNanos()
                        }
                        if (response.length < MAX_RESPONSE_PREVIEW_CHARS) {
                            response.append(message.toString())
                        }
                    }
                    generationFinishedNanos = SystemClock.elapsedRealtimeNanos()
                }

                return@withContext Stage17ProbeResult(
                    runtimeVersion = BuildConfig.LITERT_LM_VERSION,
                    backend = request.backend,
                    initializeMs = nanosToMillis(initializeFinishedNanos - initializeStartedNanos),
                    timeToFirstEmissionMs =
                        firstEmissionNanos?.let { nanosToMillis(it - generationStartedNanos) },
                    totalGenerationMs = nanosToMillis(generationFinishedNanos - generationStartedNanos),
                    pssBeforeInitializeKb = pssBeforeInitializeKb,
                    pssAfterInitializeKb = pssAfterInitializeKb,
                    pssAfterGenerationKb = Debug.getPss(),
                    responsePreview = response.toString().take(MAX_RESPONSE_PREVIEW_CHARS),
                )
            }
        }

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L

    companion object {
        const val PROBE_PROMPT = "请用一句中文回答：你现在运行在本地设备上。"
        const val MAX_RESPONSE_PREVIEW_CHARS = 512
    }
}

/**
 * Externally verified P0 candidate metadata. These values are feasibility inputs, not a production
 * model-channel contract. P0 must still complete real-device CPU/GPU measurements before P1.
 */
object Stage17P0Candidate {
    const val MODEL_ID = "litert-community/gemma-4-E2B-it-litert-lm"
    const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
    const val GALLERY_BASE_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
    const val GALLERY_MTP_UPDATE_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    const val GALLERY_BASE_SIZE_BYTES = 2_588_147_712L
    const val MIN_DEVICE_MEMORY_GB = 8
    const val MAX_CONTEXT_TOKENS = 32_000
}
