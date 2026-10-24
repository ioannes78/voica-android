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
 */
class Stage17LocalLlmProbe {
    suspend fun run(request: Stage17ProbeRequest): Stage17ProbeResult =
        withContext(Dispatchers.Default) {
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
                    Engine(engineConfig).use { engine ->
                        val initializeStartedNanos = SystemClock.elapsedRealtimeNanos()
                        engine.initialize()
                        val initializeFinishedNanos = SystemClock.elapsedRealtimeNanos()
                        initializeMs = nanosToMillis(initializeFinishedNanos - initializeStartedNanos)
                        pssAfterInitializeKb = currentPssKb()
                        peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterInitializeKb) }

                        engine.createConversation(conversationConfig).use { conversation ->
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
                        pssAfterGenerationKb = currentPssKb()
                        peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterGenerationKb) }
                    }

                    val pssAfterCloseKb = currentPssKb()
                    peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterCloseKb) }
                    Stage17ProbeResult(
                        runtimeVersion = BuildConfig.LITERT_LM_VERSION,
                        backend = request.backend,
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
        const val PROBE_MAX_OUTPUT_TOKENS = 64
        const val MAX_RESPONSE_PREVIEW_CHARS = 512
        const val PSS_SAMPLE_INTERVAL_MS = 100L
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
