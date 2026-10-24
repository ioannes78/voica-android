package io.github.ioannes78.voica.localllm

import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
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
    val backend: ProbeBackend,
    val maxNumTokens: Int = Stage17LocalLlmProbe.PROBE_MAX_CONTEXT_TOKENS,
) {
    init {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
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
 * R6 keeps the R5 runtime/configuration fixed and changes only the Gemma 4 E2B artifact under
 * test. Real-device R4/R5 evidence showed CPU and GPU both pass Engine.initialize() and then die
 * with a native crash in createConversation() when using the May-4 model artifact. The same phone
 * can run Gemma 4 E2B in Google AI Edge Gallery, so R6 pins the earlier pre-MTP Gallery artifact
 * (revision 7fa1d784...) as an artifact/runtime compatibility control.
 *
 * Runtime/configuration remains fixed:
 * - LiteRT-LM 0.18.0;
 * - maxNumTokens = 4000;
 * - normal app-private model paths do not provide cacheDir;
 * - SamplerConfig(topK=64, topP=0.95, temperature=1.0);
 * - prompt is sent as Contents rather than the String convenience overload;
 * - speculative decoding is disabled at Engine creation through ExperimentalFlags;
 * - ConversationConfig leaves enableSpeculativeDecoding=null so the session inherits Engine OFF;
 * - conversation constrained decoding is explicitly disabled before createConversation().
 *
 * This remains a feasibility probe only. It is not wired into production summary/RAG/Q&A.
 */
@OptIn(ExperimentalApi::class)
class Stage17LocalLlmProbe {
    suspend fun run(
        request: Stage17ProbeRequest,
        onPhase: (Stage17ProbePhase) -> Unit = {},
    ): Stage17ProbeResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val previousSpeculativeDecoding = ExperimentalFlags.enableSpeculativeDecoding
                val previousConversationConstrainedDecoding =
                    ExperimentalFlags.enableConversationConstrainedDecoding
                ExperimentalFlags.enableSpeculativeDecoding = false
                ExperimentalFlags.enableConversationConstrainedDecoding = false

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
                        cacheDir = null,
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

                        ExperimentalFlags.enableConversationConstrainedDecoding = false
                        onPhase(Stage17ProbePhase.CONVERSATION_CREATING)
                        engine.createConversation(stage17P0ConversationConfig()).use { conversation ->
                            onPhase(Stage17ProbePhase.CONVERSATION_READY)
                            generationStartedNanos = SystemClock.elapsedRealtimeNanos()
                            onPhase(Stage17ProbePhase.GENERATING)
                            conversation
                                .sendMessageAsync(Contents.of(Content.Text(PROBE_PROMPT)))
                                .collect { message ->
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
                    ExperimentalFlags.enableConversationConstrainedDecoding =
                        previousConversationConstrainedDecoding
                    ExperimentalFlags.enableSpeculativeDecoding = previousSpeculativeDecoding
                }
            }
        }

    private fun currentPssKb(): Long = Debug.getPss().toLong()

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L

    companion object {
        const val RUNTIME_VERSION = "0.18.0"
        const val PROBE_PROMPT = "请用一句简短中文回答：你现在运行在本地设备上。"
        const val PROBE_MAX_CONTEXT_TOKENS = 4_000
        const val PROBE_TOP_K = 64
        const val PROBE_TOP_P = 0.95
        const val PROBE_TEMPERATURE = 1.0
        const val MAX_RESPONSE_PREVIEW_CHARS = 512
        const val PSS_SAMPLE_INTERVAL_MS = 250L
    }
}

internal fun stage17P0ConversationConfig(): ConversationConfig =
    ConversationConfig(
        samplerConfig =
            SamplerConfig(
                topK = Stage17LocalLlmProbe.PROBE_TOP_K,
                topP = Stage17LocalLlmProbe.PROBE_TOP_P,
                temperature = Stage17LocalLlmProbe.PROBE_TEMPERATURE,
            ),
        enableSpeculativeDecoding = null,
    )

/**
 * Externally verified P0 candidate metadata. These values are feasibility inputs, not a production
 * model-channel contract. R6 intentionally uses the pre-MTP Gallery artifact as a controlled
 * compatibility test after the May-4 artifact repeatedly crashed in createConversation().
 */
object Stage17P0Candidate {
    const val MODEL_ID = "litert-community/gemma-4-E2B-it-litert-lm"
    const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
    const val GALLERY_BASE_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    const val GALLERY_NEWER_MTP_REVISION = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
    const val GALLERY_BASE_SIZE_BYTES = 2_583_085_056L
    const val GALLERY_BASE_SHA256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
    const val MIN_DEVICE_MEMORY_GB = 8
    const val MAX_CONTEXT_TOKENS = 32_000
}
