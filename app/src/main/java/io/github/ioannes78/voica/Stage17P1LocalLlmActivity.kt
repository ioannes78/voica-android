package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmResourceDecision
import io.github.ioannes78.voica.localllm.LocalLlmResourceReason
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeDiagnosticObserverRegistry
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeException
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeState
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import io.github.ioannes78.voica.localllm.Stage17LocalLlmProbe
import io.github.ioannes78.voica.ui.theme.VoicaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** QA-only P1 surface for the managed Gemma model and production runtime foundation. */
class Stage17P1LocalLlmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!stage17P1LocalLlmEntryVisible(BuildConfig.APPLICATION_ID)) {
            finish()
            return
        }
        val container = (application as VoicaApplication).container
        val runtime = container.localLlmRuntime(this)
        val lifecycleObservability =
            LocalLlmLifecycleObservabilityRegistry.get(
                application = application,
                runtime = runtime,
            )
        val r5CrashBreadcrumbs = Stage17R5CrashBreadcrumbStore(this)
        setContent {
            val themeSettings by container.themeSettingsStore.settings.collectAsState()
            VoicaTheme(settings = themeSettings) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Stage17P1LocalLlmScreen(
                        container = container,
                        runtime = runtime,
                        lifecycleObservability = lifecycleObservability,
                        r5CrashBreadcrumbs = r5CrashBreadcrumbs,
                        onClose = ::finish,
                    )
                }
            }
        }
    }
}

internal fun stage17P1LocalLlmEntryVisible(applicationId: String): Boolean =
    applicationId.endsWith(".qa")

@Composable
private fun Stage17P1LocalLlmScreen(
    container: AppContainer,
    runtime: AppLocalLlmRuntime,
    lifecycleObservability: LocalLlmLifecycleObservability,
    r5CrashBreadcrumbs: Stage17R5CrashBreadcrumbStore,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val runtimeState by runtime.state.collectAsState()
    val lifecycleSnapshot by lifecycleObservability.monitor.snapshot.collectAsState()
    val lifecycleEvents by lifecycleObservability.diagnostics.events.collectAsState()
    val r5Breadcrumb by r5CrashBreadcrumbs.last.collectAsState()
    var modelStatus by remember { mutableStateOf("检查中…") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var operation by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    suspend fun refreshModelStatus() {
        val active = container.modelManager.activeModel(STAGE17_GEMMA_4_E2B_CANDIDATE_ID)
        modelStatus =
            if (active == null) {
                "未安装/未激活。请先到 设置 → 本地模型 下载 Gemma 4 E2B。"
            } else {
                "READY · ${active.descriptor.displayName} · ${active.descriptor.version.take(12)}… · r${active.descriptor.revision}"
            }
    }

    LaunchedEffect(Unit) { refreshModelStatus() }

    fun start(
        policy: LocalLlmBackendPolicy,
        resourceOverride: LocalLlmResourceDecision? = null,
        prompt: String = Stage17LocalLlmProbe.PROBE_PROMPT,
    ) {
        if (activeJob != null) return
        resultText = null
        operation = "$policy · 运行中"
        val config =
            LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = policy,
            )
        r5CrashBreadcrumbs.arm(
            estimatedPromptTokens = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt),
        )
        LocalLlmRuntimeDiagnosticObserverRegistry.install(r5CrashBreadcrumbs::record)
        activeJob =
            scope.launch {
                try {
                    val result =
                        runtime.generate(
                            prompt = prompt,
                            config = config,
                            resourceDecisionOverride = resourceOverride,
                        )
                    resultText = result.toP1DiagnosticsText()
                    operation = "$policy · 完成"
                } catch (_: CancellationException) {
                    operation = "$policy · 已取消"
                } catch (error: LocalLlmRuntimeException) {
                    resultText = error.failure.toString()
                    operation = "$policy · 失败 · ${error.failure.code}"
                } catch (error: Throwable) {
                    resultText = "${error::class.java.name}: ${error.message ?: "n/a"}"
                    operation = "$policy · 失败"
                } finally {
                    LocalLlmRuntimeDiagnosticObserverRegistry.install(null)
                    r5CrashBreadcrumbs.disarm()
                    activeJob = null
                    refreshModelStatus()
                }
            }
    }

    val busy = activeJob != null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Stage 17 P1 · Local LLM Runtime", style = MaterialTheme.typography.headlineSmall)
            Text(
                "正式策略：AUTO 默认 GPU 优先；GPU 不适合/不支持时安全回退 CPU。测试机不锁定生产 backend。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            P1Card("Managed Gemma") {
                Text(modelStatus)
                Text(
                    "candidate=" +
                        VoicaModelChannel.STAGE17_P1_CANDIDATE_MANIFEST_URL
                            .substringAfterLast("/releases/download/")
                            .substringBefore("/production.json"),
                )
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { refreshModelStatus() } },
                ) {
                    Text("刷新模型状态")
                }
            }
        }
        item {
            P1Card("R4 Lifecycle") {
                Text(
                    "foreground=${lifecycleSnapshot.processForeground} · " +
                        "resumed=${lifecycleSnapshot.activityResumed} · " +
                        "interactive=${lifecycleSnapshot.interactive}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "keyguard=${lifecycleSnapshot.keyguardShowing} · " +
                        "deviceLocked=${lifecycleSnapshot.deviceLocked} · " +
                        "last=${lifecycleSnapshot.lastTransition}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        clipboard.setText(
                            AnnotatedString(formatLocalLlmLifecycleDiagnostics(lifecycleEvents)),
                        )
                    },
                ) {
                    Text("复制生命周期诊断")
                }
            }
        }
        item {
            P1Card("R5 Crash Breadcrumb") {
                Text(
                    "仅 QA 持久化标量元数据；不会保存 prompt、转写内容或模型输出。进程被 native crash 杀死后，重新进入本页可读取上一进程最后成功写入的 runtime phase。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val breadcrumb = r5Breadcrumb
                if (breadcrumb == null) {
                    Text("暂无持久化 breadcrumb", style = MaterialTheme.typography.bodySmall)
                } else {
                    val diagnosticText =
                        breadcrumb.toQaDisplayText(
                            previousProcess = r5CrashBreadcrumbs.isPreviousProcess(breadcrumb),
                        )
                    Text(diagnosticText, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(diagnosticText))
                            },
                        ) {
                            Text("复制 breadcrumb")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = r5CrashBreadcrumbs::clear,
                        ) {
                            Text("清除")
                        }
                    }
                }
            }
        }
        item {
            P1Card("Runtime") {
                Text("state=$runtimeState")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { start(LocalLlmBackendPolicy.AUTO) }) {
                        Text("AUTO")
                    }
                    OutlinedButton(enabled = !busy, onClick = { start(LocalLlmBackendPolicy.GPU) }) {
                        Text("GPU")
                    }
                    OutlinedButton(enabled = !busy, onClick = { start(LocalLlmBackendPolicy.CPU) }) {
                        Text("CPU")
                    }
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        start(
                            policy = LocalLlmBackendPolicy.GPU,
                            prompt = R4C_LONG_GENERATION_PROMPT,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("GPU 长生成")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        start(
                            policy = LocalLlmBackendPolicy.GPU,
                            prompt = R4C_LONG_GENERATION_PROMPT,
                        )
                        scope.launch {
                            runtime.state.first { state ->
                                state == LocalLlmRuntimeState.GENERATING
                            }
                            delay(R4C_LOCKED_CANCEL_DELAY_MS)
                            activeJob?.cancel()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("GPU 长生成 · 8秒后自动取消")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            start(
                                policy = LocalLlmBackendPolicy.AUTO,
                                resourceOverride =
                                    LocalLlmResourceDecision(
                                        gpuAllowed = false,
                                        cpuAllowed = true,
                                        gpuReason = LocalLlmResourceReason.MEMORY_HEADROOM_LOW,
                                        cpuReason = LocalLlmResourceReason.ALLOWED,
                                    ),
                            )
                        },
                    ) {
                        Text("模拟 GPU 阻断→CPU")
                    }
                    OutlinedButton(enabled = busy, onClick = { activeJob?.cancel() }) {
                        Text("取消")
                    }
                }
                operation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        resultText?.let { diagnostics ->
            item {
                P1Card("最近结果") {
                    Text(diagnostics, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)) }) {
                        Text("复制结果")
                    }
                }
            }
        }
        item {
            Button(enabled = !busy, onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("返回闻知")
            }
        }
    }
}

private const val R4C_LONG_GENERATION_PROMPT =
    "请用中文写一篇不少于1500字的技术说明，系统讲解完全离线的语音转写与本地AI总结系统。" +
        "请依次详细讨论音频采集、VAD、ASR、时间轴、说话人处理、标点与文本整理、本地大模型总结、" +
        "任务取消与资源释放、后台运行、息屏与锁屏生命周期、错误恢复、内存和功耗管理、真机验证。" +
        "每个主题都要解释处理流程、设计原因、主要风险和验证方法；不要省略章节，不要简短回答。"

private const val R4C_LOCKED_CANCEL_DELAY_MS = 8_000L

@Composable
private fun P1Card(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun LocalLlmGenerationResult.toP1DiagnosticsText(): String {
    val d = diagnostics
    return buildString {
        appendLine("runtime=${d.runtimeVersion}")
        appendLine("model=${modelIdentity.modelId}")
        appendLine("revision=${modelIdentity.revision}")
        appendLine("requested=${backendResolution.requestedPolicy}")
        appendLine("resolved=${backendResolution.resolvedBackend}")
        appendLine("reason=${backendResolution.reason}")
        appendLine("fallback=${backendResolution.fallbackAttempted}")
        appendLine("initializeMs=${d.initializeMs ?: "n/a"}")
        appendLine("conversationCreateMs=${d.conversationCreateMs ?: "n/a"}")
        appendLine("ttfeMs=${d.timeToFirstEmissionMs ?: "n/a"}")
        appendLine("generationMs=${d.generationMs ?: "n/a"}")
        appendLine("pssBeforeKb=${d.pssBeforeKb ?: "n/a"}")
        appendLine("pssInitializedKb=${d.pssAfterInitializeKb ?: "n/a"}")
        appendLine("pssGeneratedKb=${d.pssAfterGenerationKb ?: "n/a"}")
        appendLine("pssClosedKb=${d.pssAfterCloseKb ?: "n/a"}")
        appendLine("peakPssKb=${d.peakPssKb ?: "n/a"}")
        appendLine("response=${text.take(512)}")
    }.trimEnd()
}
