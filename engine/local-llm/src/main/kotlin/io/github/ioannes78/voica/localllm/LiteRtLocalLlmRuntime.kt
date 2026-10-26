package io.github.ioannes78.voica.localllm

import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

enum class LocalLlmRuntimePhase {
    ENGINE_CONSTRUCTING,
    ENGINE_INITIALIZING,
    ENGINE_INITIALIZED,
    CONVERSATION_CREATING,
    CONVERSATION_READY,
    GENERATING,
    /** One observable emission crossed the LiteRT-LM callback/JNI boundary. */
    GENERATION_PROGRESS,
    GENERATED,
    CANCEL_REQUESTED,
    NATIVE_CANCEL_SIGNAL_SENT,
    NATIVE_CANCEL_TERMINAL_RECEIVED,
    CONVERSATION_CLOSING,
    CONVERSATION_CLOSED,
    ENGINE_CLOSING,
    ENGINE_CLOSED,
    RELEASE_UNCONFIRMED,
}

/**
 * Production LiteRT-LM adapter for Stage 17.
 *
 * This class owns only one concrete inference attempt. App-wide ownership, resource gating,
 * backend AUTO resolution and retry/fallback policy belong to the P1 coordinator layers built
 * above this adapter. Keeping those concerns outside prevents the LiteRT-LM JNI boundary from
 * leaking into UI, summary or Q&A code.
 *
 * R4-A deliberately uses LiteRT-LM's callback API rather than its Flow wrapper. In LiteRT-LM
 * 0.18.0 the Flow wrapper's awaitClose block does not call Conversation.cancelProcess(), so a
 * cancelled collector does not prove the native generation stopped. Here cancellation explicitly
 * signals cancelProcess() and waits for a native terminal callback before releasing JNI ownership.
 */
@OptIn(ExperimentalApi::class)
class LiteRtLocalLlmRuntime {
    @Volatile
    private var quarantinedNativeSession: QuarantinedNativeSession? = null

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
            check(quarantinedNativeSession == null) {
                "LiteRT-LM native ownership is quarantined until process restart"
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
                val firstEmissionNanos = AtomicLong(0L)
                var generationStartedNanos = 0L
                var generationFinishedNanos = 0L
                val response = StringBuilder()
                var engine: Engine? = null
                var conversation: Conversation? = null
                var quarantineNativeOwnership = false

                try {
                    onPhase(LocalLlmRuntimePhase.ENGINE_CONSTRUCTING)
                    val liveEngine = Engine(engineConfig)
                    engine = liveEngine
                    onPhase(LocalLlmRuntimePhase.ENGINE_INITIALIZING)
                    val initializeStartedNanos = SystemClock.elapsedRealtimeNanos()
                    liveEngine.initialize()
                    val initializeFinishedNanos = SystemClock.elapsedRealtimeNanos()
                    initializeMs = nanosToMillis(initializeFinishedNanos - initializeStartedNanos)
                    pssAfterInitializeKb = currentPssKb()
                    peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterInitializeKb) }
                    onPhase(LocalLlmRuntimePhase.ENGINE_INITIALIZED)
                    coroutineContext.ensureActive()

                    ExperimentalFlags.enableConversationConstrainedDecoding = false
                    onPhase(LocalLlmRuntimePhase.CONVERSATION_CREATING)
                    val conversationCreateStartedNanos = SystemClock.elapsedRealtimeNanos()
                    val liveConversation =
                        liveEngine.createConversation(conversationConfig(request.config))
                    conversation = liveConversation
                    val conversationCreateFinishedNanos = SystemClock.elapsedRealtimeNanos()
                    conversationCreateMs =
                        nanosToMillis(
                            conversationCreateFinishedNanos - conversationCreateStartedNanos,
                        )
                    onPhase(LocalLlmRuntimePhase.CONVERSATION_READY)
                    coroutineContext.ensureActive()

                    val terminal = CompletableDeferred<GenerationTerminal>()
                    generationStartedNanos = SystemClock.elapsedRealtimeNanos()
                    onPhase(LocalLlmRuntimePhase.GENERATING)
                    liveConversation.sendMessageAsync(
                        contents = Contents.of(Content.Text(request.prompt)),
                        callback =
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    val now = SystemClock.elapsedRealtimeNanos()
                                    firstEmissionNanos.compareAndSet(0L, now)
                                    // Emit only lifecycle/progress metadata. Model content never
                                    // crosses into process-global diagnostics.
                                    onPhase(LocalLlmRuntimePhase.GENERATION_PROGRESS)
                                    synchronized(response) {
                                        response.append(message.toString())
                                    }
                                }

                                override fun onDone() {
                                    terminal.complete(GenerationTerminal.Done)
                                }

                                override fun onError(throwable: Throwable) {
                                    terminal.complete(GenerationTerminal.Error(throwable))
                                }
                            },
                        responseFormat = responseFormat,
                    )

                    val terminalResult =
                        try {
                            terminal.await()
                        } catch (cancelled: CancellationException) {
                            onPhase(LocalLlmRuntimePhase.CANCEL_REQUESTED)
                            val cancelSignalError =
                                runCatching { liveConversation.cancelProcess() }.exceptionOrNull()
                            if (cancelSignalError != null) {
                                quarantineNativeOwnership = true
                                quarantinedNativeSession =
                                    QuarantinedNativeSession(
                                        engine = engine,
                                        conversation = conversation,
                                    )
                                onPhase(LocalLlmRuntimePhase.RELEASE_UNCONFIRMED)
                                throw cancelled
                            }
                            onPhase(LocalLlmRuntimePhase.NATIVE_CANCEL_SIGNAL_SENT)

                            val nativeTerminal =
                                withContext(NonCancellable) {
                                    withTimeoutOrNull(NATIVE_CANCEL_TERMINAL_TIMEOUT_MS) {
                                        terminal.await()
                                    }
                                }
                            if (nativeTerminal == null) {
                                quarantineNativeOwnership = true
                                quarantinedNativeSession =
                                    QuarantinedNativeSession(
                                        engine = engine,
                                        conversation = conversation,
                                    )
                                onPhase(LocalLlmRuntimePhase.RELEASE_UNCONFIRMED)
                                throw cancelled
                            }

                            // LiteRT-LM reports native kCancelled through MessageCallback.onError.
                            // A raced normal completion/error is also a terminal acknowledgement:
                            // native generation is no longer running, so teardown can proceed.
                            onPhase(LocalLlmRuntimePhase.NATIVE_CANCEL_TERMINAL_RECEIVED)
                            throw cancelled
                        }

                    when (terminalResult) {
                        GenerationTerminal.Done -> {
                            generationFinishedNanos = SystemClock.elapsedRealtimeNanos()
                            onPhase(LocalLlmRuntimePhase.GENERATED)
                        }

                        is GenerationTerminal.Error -> throw terminalResult.throwable
                    }

                    pssAfterGenerationKb = currentPssKb()
                    peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterGenerationKb) }
                } finally {
                    var releaseFailure: Throwable? = null
                    if (!quarantineNativeOwnership) {
                        try {
                            conversation?.let { activeConversation ->
                                if (activeConversation.isAlive) {
                                    onPhase(LocalLlmRuntimePhase.CONVERSATION_CLOSING)
                                    activeConversation.close()
                                    onPhase(LocalLlmRuntimePhase.CONVERSATION_CLOSED)
                                }
                            }
                            engine?.let { activeEngine ->
                                if (activeEngine.isInitialized()) {
                                    onPhase(LocalLlmRuntimePhase.ENGINE_CLOSING)
                                    activeEngine.close()
                                    onPhase(LocalLlmRuntimePhase.ENGINE_CLOSED)
                                }
                            }
                        } catch (releaseError: Throwable) {
                            quarantineNativeOwnership = true
                            quarantinedNativeSession =
                                QuarantinedNativeSession(
                                    engine = engine,
                                    conversation = conversation,
                                )
                            onPhase(LocalLlmRuntimePhase.RELEASE_UNCONFIRMED)
                            releaseFailure = releaseError
                        }
                    }

                    withContext(NonCancellable) {
                        sampler.cancelAndJoin()
                    }
                    ExperimentalFlags.enableConversationConstrainedDecoding =
                        previousConversationConstrainedDecoding
                    ExperimentalFlags.enableSpeculativeDecoding = previousSpeculativeDecoding

                    releaseFailure?.let { throw it }
                }

                val pssAfterCloseKb = currentPssKb()
                peakPssKb.updateAndGet { previous -> maxOf(previous, pssAfterCloseKb) }

                val firstEmission = firstEmissionNanos.get().takeIf { it > 0L }
                val diagnostics =
                    LocalLlmDiagnostics(
                        runtimeVersion = BuildConfig.LITERT_LM_VERSION,
                        modelIdentity = request.modelIdentity,
                        backendResolution = backendResolution,
                        initializeMs = initializeMs,
                        conversationCreateMs = conversationCreateMs,
                        timeToFirstEmissionMs =
                            firstEmission?.let {
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
                    text = synchronized(response) { response.toString() },
                    modelIdentity = request.modelIdentity,
                    backendResolution = backendResolution,
                    diagnostics = diagnostics,
                )
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

    private sealed interface GenerationTerminal {
        data object Done : GenerationTerminal

        data class Error(val throwable: Throwable) : GenerationTerminal
    }

    private data class QuarantinedNativeSession(
        val engine: Engine?,
        val conversation: Conversation?,
    )

    private companion object {
        const val PSS_SAMPLE_INTERVAL_MS = 250L

        // Initial R4-A safety window. QA will measure foreground native-cancel latency before any
        // lifecycle/lock-screen gate changes this value.
        const val NATIVE_CANCEL_TERMINAL_TIMEOUT_MS = 10_000L
    }
}
