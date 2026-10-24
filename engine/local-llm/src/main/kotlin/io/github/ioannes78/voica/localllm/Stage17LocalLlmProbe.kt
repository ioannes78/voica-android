package io.github.ioannes78.voica.localllm

import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ProbeBackend {
    CPU,
    GPU,
}

enum class Stage17ProbePhase {
    ENGINE_CONSTRUCTING,
    ENGINE_INITIALIZING,
    ENGINE_INITIALIZED,
    CONVERSATION_CREATING,
    CONVERSATION_READY,
    GENERATING,
    GENERATED,
    ENGINE_CLOSED,
}

data class Stage17ProbeRequest(
    val modelPath: String,
    val cacheDir: String,
    val backend: ProbeBackend,
    val maxNumTokens: Int = Stage17LocalLlmProbe.PROBE_MAX_CONTEXT_TOKENS,
) {
    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(cacheDir.isNotBlank()) { "cacheDir must not be blank" }
        require(maxNumTokens > 0) { "maxNumTokens must be positive" }
    }
}

data class Stage17ProbeResult(
    val runtimeVersion: String,
    val backend: ProbeBackend,
    val maxNumTokens: Int,
    val initializeMs: Long,
    val timeToFirstEmissionMs: Long?,
    val totalGenerationMs: Long,
    val pssBeforeInitializeKb: Long,
    val pssAfterInitializeKb: Long,
    val pssAfterGenerationKb: Long,
    val pssAfterCloseKb: Long,
    val peakPssKb: Long,
    val responsePreview: String,
)

/**
 * Stage 17 / P0 only.
 *
 * This probe deliberately stays outside the production summary / retrieval / Q&A path. Its only
 * job is to verify that the pinned LiteRT-LM runtime and a caller-supplied .litertlm artifact can
 * initialize and generate on a physical Android device while capturing latency/PSS data.
 * Cancelling the calling coroutine closes the conversation and engine through structured resource
 * ownership. Speculative decoding is explicitly disabled so the P0 baseline exercises one model.
 *
 * P0 deliberately caps the KV cache instead of inheriting Gemma 4 E2B's advertised 32K maximum.
 * The smoke prompt does not need a production-sized context window, and an unbounded/default KV
 * cache can turn runtime feasibility into a device-memory stress test before generation begins.
 */
class Stage17LocalLlmProbe {
    suspend fun run(
        request: Stage17ProbeRequest,
        onPhase: (Stage17ProbePhase) -> Unit = {},
    ): Stage17ProbeResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val backend =
                    when (request.backend) {
                        ProbeBackend.CPU -> Backend.CPU()
                        ProbeBackend.GPU -> Backend.GPU()
                    }
                val engineConfig =
                    EngineConfig(
                        modelPath = request.modelPath,
                        backend = backend,
                        maxNumTokens = request.maxNumTokens,
                        cacheDir = request.cacheDir,
                    )
                val conversationConfig =
                    ConversationConfig(
                        maxOutputToken = PROBE_MAX_OUTPUT_TOKENS,
                        enableSpeculativeDecoding = false,
                    )

                val pssBeforeInitializeKb = currentPssKb()
                val peakPssKb = AtomicLong(pssBeforeInitializeKb)
                val sampler =
                    launch(Dispatchers.Default) {
                        while (isActive) {
                            val sample = currentPssKb()
                            peakPssKb.updateAndGet { previous -> maxOf(previous, sample) }
                            delay(PSS_SAMPLE_INTERVAL_MS)
                        }
                    }

                var pssAfterInitializeKb = pssBeforeInitializeKb
                var pssAfterGenerationKb = pssBeforeInitializeKb
                var firstEmissionNanos: Long? = null
                var initializeMs = 0L
                var generationStartedNanos = 0L
                var generationFinishedNanos = 0L
                val response = StringBuilder()

                try {
                    onPhase(Stage17ProbePhase.ENGINE_CONSTRUCTING)
                    Engine(engineConfig).use { engine ->
                        onPhase(Stage17ProbePhase.ENGINE_INITIALIZING)
                        val initializeStartedNanos = SystemClock.elapsedRealtimeNanos()
                        engine.initialize()
                        val initializeFinishedNanos = SystemClock.elapsedRealtimeNanos()
                        initializeMs = nanosToMillis(initializeFinishedNanos - initializeStartedNanos)
                        pssAfterInitializeKb = currentPssKb()
                        peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterInitializeKb) }
                        onPhase(Stage17ProbePhase.ENGINE_INITIALIZED)

                        onPhase(Stage17ProbePhase.CONVERSATION_CREATING)
                        engine.createConversation(conversationConfig).use { conversation ->
                            onPhase(Stage17ProbePhase.CONVERSATION_READY)
                            generationStartedNanos = SystemClock.elapsedRealtimeNanos()
                            onPhase(Stage17ProbePhase.GENERATING)
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
                            onPhase(Stage17ProbePhase.GENERATED)
                        }
                        pssAfterGenerationKb = currentPssKb()
                        peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterGenerationKb) }
                    }

                    val pssAfterCloseKb = currentPssKb()
                    peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterCloseKb) }
                    onPhase(Stage17ProbePhase.ENGINE_CLOSED)
                    Stage17ProbeResult(
                        runtimeVersion = BuildConfig.LITERT_LM_VERSION,
                        backend = request.backend,
                        maxNumTokens = request.maxNumTokens,
                        initializeMs = initializeMs,
                        timeToFirstEmissionMs =
                            firstEmissionNanos?.let { nanosToMillis(it - generationStartedNanos) },
                        totalGenerationMs = nanosToMillis(generationFinishedNanos - generationStartedNanos),
                        pssBeforeInitializeKb = pssBeforeInitializeKb,
                        pssAfterInitializeKb = pssAfterInitializeKb,
                        pssAfterGenerationKb = pssAfterGenerationKb,
                        pssAfterCloseKb = pssAfterCloseKb,
                        peakPssKb = peakPssKb.get(),
                        responsePreview = response.toString().take(MAX_RESPONSE_PREVIEW_CHARS),
                    )
                } finally {
                    sampler.cancelAndJoin()
                }
            }
        }

    private fun currentPssKb(): Long = Debug.getPss().toLong()

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L

    companion object {
        const val PROBE_PROMPT = "请用一句中文回答：你现在运行在本地设备上。"
        const val PROBE_MAX_CONTEXT_TOKENS = 2_048
        const val PROBE_MAX_OUTPUT_TOKENS = 64
        const val MAX_RESPONSE_PREVIEW_CHARS = 512
        const val PSS_SAMPLE_INTERVAL_MS = 250L
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
    const val GALLERY_BASE_SHA256 = "181938105e0eefd105961417e8da75903deacda102c4fce9ce90f50b97139a63c"
    const val MIN_DEVICE_MEMORY_GB = 8
    const val MAX_CONTEXT_TOKENS = 32_000
}
