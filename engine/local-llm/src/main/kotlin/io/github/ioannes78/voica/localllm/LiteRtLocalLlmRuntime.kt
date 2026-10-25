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
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class LocalLlmRuntimePhase {
    ENGINE_CONSTRUCTING,
    ENGINE_INITIALIZING,
    ENGINE_INITIALIZED,
    CONVERSATION_CREATING,
    CONVERSATION_READY,
    GENERATING,
    GENERATED,
    ENGINE_CLOSED,
}

/**
 * Production LiteRT-LM adapter for Stage 17.
 *
 * This class owns only one concrete inference attempt. App-wide ownership, resource gating,
 * backend AUTO resolution and retry/fallback policy belong to the P1 coordinator layers built
 * above this adapter. Keeping those concerns outside prevents the LiteRT-LM JNI boundary from
 * leaking into UI, summary or Q&A code.
 */
@OptIn(ExperimentalApi::class)
class LiteRtLocalLlmRuntime {
    suspend fun generate(
        modelPath: String,
        request: LocalLlmGenerationRequest,
        backendResolution: LocalLlmBackendResolution,
        onPhase: (LocalLlmRuntimePhase) -> Unit = {},
    ): LocalLlmGenerationResult =
        withContext(Dispatchers.IO) {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            require(request.config.backendPolicy == backendResolution.requestedPolicy) {
                "backend resolution must preserve the request backend policy"
            }

            coroutineScope {
                val previousSpeculativeDecoding = ExperimentalFlags.enableSpeculativeDecoding
                val previousConversationConstrainedDecoding =
                    ExperimentalFlags.enableConversationConstrainedDecoding
                ExperimentalFlags.enableSpeculativeDecoding = request.config.speculativeDecoding
                ExperimentalFlags.enableConversationConstrainedDecoding = false

                val responseFormat =
                    request.config.responseJsonSchema?.let { schema -> ResponseFormat.json(schema) }
                val backend =
                    when (backendResolution.resolvedBackend) {
                        LocalLlmBackend.CPU -> Backend.CPU()
                        LocalLlmBackend.GPU -> Backend.GPU()
                    }
                val engineConfig =
                    EngineConfig(
                        modelPath = requestModelPath(modelPath),
                        backend = backend,
                        maxNumTokens = request.config.maxNumTokens,
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

                var initializeMs = 0L
                var conversationCreateMs = 0L
                var pssAfterInitializeKb = pssBeforeInitializeKb
                var pssAfterGenerationKb = pssBeforeInitializeKb
                var firstEmissionNanos: Long? = null
                var generationStartedNanos = 0L
                var generationFinishedNanos = 0L
                val response = StringBuilder()

                try {
                    onPhase(LocalLlmRuntimePhase.ENGINE_CONSTRUCTING)
                    Engine(engineConfig).use { engine ->
                        onPhase(LocalLlmRuntimePhase.ENGINE_INITIALIZING)
                        val initializeStartedNanos = SystemClock.elapsedRealtimeNanos()
                        engine.initialize()
                        val initializeFinishedNanos = SystemClock.elapsedRealtimeNanos()
                        initializeMs = nanosToMillis(initializeFinishedNanos - initializeStartedNanos)
                        pssAfterInitializeKb = currentPssKb()
                        peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterInitializeKb) }
                        onPhase(LocalLlmRuntimePhase.ENGINE_INITIALIZED)

                        ExperimentalFlags.enableConversationConstrainedDecoding = false
                        onPhase(LocalLlmRuntimePhase.CONVERSATION_CREATING)
                        val conversationCreateStartedNanos = SystemClock.elapsedRealtimeNanos()
                        engine.createConversation(conversationConfig(request.config)).use { conversation ->
                            val conversationCreateFinishedNanos = SystemClock.elapsedRealtimeNanos()
                            conversationCreateMs =
                                nanosToMillis(
                                    conversationCreateFinishedNanos - conversationCreateStartedNanos,
                                )
                            onPhase(LocalLlmRuntimePhase.CONVERSATION_READY)
                            generationStartedNanos = SystemClock.elapsedRealtimeNanos()
                            onPhase(LocalLlmRuntimePhase.GENERATING)
                            conversation
                                .sendMessageAsync(
                                    contents = Contents.of(Content.Text(request.prompt)),
                                    responseFormat = responseFormat,
                                )
                                .collect { message ->
                                    coroutineContext.ensureActive()
                                    if (firstEmissionNanos == null) {
                                        firstEmissionNanos = SystemClock.elapsedRealtimeNanos()
                                    }
                                    response.append(message.toString())
                                }
                            generationFinishedNanos = SystemClock.elapsedRealtimeNanos()
                            onPhase(LocalLlmRuntimePhase.GENERATED)
                        }
                        pssAfterGenerationKb = currentPssKb()
                        peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterGenerationKb) }
                    }

                    val pssAfterCloseKb = currentPssKb()
                    peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterCloseKb) }
                    onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)

                    val diagnostics =
                        LocalLlmDiagnostics(
                            runtimeVersion = BuildConfig.LITERT_LM_VERSION,
                            modelIdentity = request.modelIdentity,
                            backendResolution = backendResolution,
                            initializeMs = initializeMs,
                            conversationCreateMs = conversationCreateMs,
                            timeToFirstEmissionMs =
                                firstEmissionNanos?.let {
                                    nanosToMillis(it - generationStartedNanos)
                                },
                            generationMs =
                                nanosToMillis(generationFinishedNanos - generationStartedNanos),
                            pssBeforeKb = pssBeforeInitializeKb,
                            pssAfterInitializeKb = pssAfterInitializeKb,
                            pssAfterGenerationKb = pssAfterGenerationKb,
                            pssAfterCloseKb = pssAfterCloseKb,
                            peakPssKb = peakPssKb.get(),
                        )
                    LocalLlmGenerationResult(
                        text = response.toString(),
                        modelIdentity = request.modelIdentity,
                        backendResolution = backendResolution,
                        diagnostics = diagnostics,
                    )
                } finally {
                    sampler.cancelAndJoin()
                    ExperimentalFlags.enableConversationConstrainedDecoding =
                        previousConversationConstrainedDecoding
                    ExperimentalFlags.enableSpeculativeDecoding = previousSpeculativeDecoding
                }
            }
        }

    /**
     * Candidate activation smoke test. CPU is deliberately used only for deterministic install
     * validation; normal product inference remains AUTO (GPU preferred, CPU fallback).
     *
     * P0 showed that Engine.initialize() alone is insufficient because the historical R4-R6 abort
     * occurred while creating the Conversation. Therefore validation crosses that JNI boundary and
     * closes immediately without running a product prompt.
     */
    suspend fun validateModel(
        modelPath: String,
        maxNumTokens: Int = LocalLlmValidatedPresets.P0_BASELINE.maxNumTokens,
    ) = withContext(Dispatchers.IO) {
        require(modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(maxNumTokens > 0) { "maxNumTokens must be positive" }

        val previousSpeculativeDecoding = ExperimentalFlags.enableSpeculativeDecoding
        val previousConversationConstrainedDecoding =
            ExperimentalFlags.enableConversationConstrainedDecoding
        ExperimentalFlags.enableSpeculativeDecoding = false
        ExperimentalFlags.enableConversationConstrainedDecoding = false

        try {
            val config =
                LocalLlmValidatedPresets.P0_BASELINE.copy(
                    backendPolicy = LocalLlmBackendPolicy.CPU,
                    maxNumTokens = maxNumTokens,
                )
            Engine(
                EngineConfig(
                    modelPath = requestModelPath(modelPath),
                    backend = Backend.CPU(),
                    maxNumTokens = maxNumTokens,
                    cacheDir = null,
                ),
            ).use { engine ->
                engine.initialize()
                ExperimentalFlags.enableConversationConstrainedDecoding = false
                engine.createConversation(conversationConfig(config)).use { }
            }
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding =
                previousConversationConstrainedDecoding
            ExperimentalFlags.enableSpeculativeDecoding = previousSpeculativeDecoding
        }
    }

    private fun requestModelPath(modelPath: String): String = modelPath

    private fun currentPssKb(): Long = Debug.getPss().toLong()

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L

    private fun conversationConfig(config: LocalLlmRuntimeConfig): ConversationConfig =
        ConversationConfig(
            samplerConfig =
                SamplerConfig(
                    topK = config.topK,
                    topP = config.topP,
                    temperature = config.temperature,
                ),
            maxOutputToken = config.maxOutputTokens,
            enableResponseFormat = config.responseJsonSchema != null,
            enableSpeculativeDecoding = null,
        )

    private companion object {
        const val PSS_SAMPLE_INTERVAL_MS = 250L
    }
}
