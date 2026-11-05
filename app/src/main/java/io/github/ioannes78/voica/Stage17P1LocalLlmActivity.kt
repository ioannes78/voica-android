package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.SummaryStructuredOutputException
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmResourceDecision
import io.github.ioannes78.voica.localllm.LocalLlmResourceReason
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeDiagnosticObserverRegistry
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeException
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeState
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import io.github.ioannes78.voica.localllm.Stage17LocalLlmProbe
import io.github.ioannes78.voica.ui.theme.VoicaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val appContext = LocalContext.current
    val runtimeState by runtime.state.collectAsState()
    val lifecycleSnapshot by lifecycleObservability.monitor.snapshot.collectAsState()
    val lifecycleEvents by lifecycleObservability.diagnostics.events.collectAsState()
    val r5Breadcrumb by r5CrashBreadcrumbs.last.collectAsState()
    var modelStatus by remember { mutableStateOf("检查中…") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var operation by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val capacityHistory = remember { mutableStateListOf<String>() }
    var r17bSource by remember { mutableStateOf<Stage17R17BReplay.Source?>(null) }
    var r17bStatus by remember { mutableStateOf("尚未导入真实长文本诊断") }
    val r17bRuns = remember { mutableStateListOf<Stage17R17BReplay.Run>() }
    var r17bPendingReport by remember { mutableStateOf<String?>(null) }
    var isolationSources by remember { mutableStateOf<List<Stage17R5IsolationSource>>(emptyList()) }
    var isolationSourceIndex by remember { mutableStateOf(0) }
    var isolationSourceStatus by remember { mutableStateOf("正在查找可用于正式 Summary prompt 的转写…") }

    suspend fun refreshModelStatus() {
        val active = container.modelManager.activeModel(STAGE17_GEMMA_4_E2B_CANDIDATE_ID)
        modelStatus =
            if (active == null) {
                "未安装/未激活。请先到 设置 → 本地模型 下载 Gemma 4 E2B。"
            } else {
                "READY · ${active.descriptor.displayName} · ${active.descriptor.version.take(12)}… · r${active.descriptor.revision}"
            }
    }

    suspend fun refreshIsolationSources() {
        isolationSourceStatus = "正在查找可用于正式 Summary prompt 的转写…"
        val loaded =
            runCatching { container.loadStage17R5IsolationSources() }
                .getOrElse { error ->
                    isolationSourceStatus =
                        "加载失败：${error.message ?: error::class.java.simpleName}"
                    emptyList()
                }
        isolationSources = loaded
        isolationSourceIndex = 0
        if (loaded.isNotEmpty()) {
            isolationSourceStatus = "READY · ${loaded.size} 条可用转写"
        } else if (!isolationSourceStatus.startsWith("加载失败")) {
            isolationSourceStatus = "没有找到已完成且符合 Stage 17.2 单次预算的转写"
        }
    }

    LaunchedEffect(Unit) {
        refreshModelStatus()
        refreshIsolationSources()
    }

    fun startRequest(
        label: String,
        prompt: String,
        config: LocalLlmRuntimeConfig,
        resourceOverride: LocalLlmResourceDecision? = null,
        includeResponseInDiagnostics: Boolean = true,
        structuredValidationRefs: Set<String>? = null,
        estimatedPromptTokens: Int = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt),
        capacityProbe: Stage17CapacityQa.Request? = null,
        replayRequest: Stage17R17BReplay.Request? = null,
        replaySource: Stage17R17BReplay.Source? = null,
    ) {
        if (activeJob != null) return
        resultText = null
        operation = "$label · 运行中"
        r5CrashBreadcrumbs.arm(estimatedPromptTokens = estimatedPromptTokens)
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
                    if (replayRequest != null) {
                        requireNotNull(replaySource) { "R17B 同源上下文丢失" }
                        Stage172OfflineSummaryModelContract.requireMatches(result.modelIdentity)
                    }
                    val replayAssessment =
                        if (replayRequest != null && replaySource != null) {
                            Stage17R17BReplay.evaluate(result.text, replaySource)
                        } else {
                            null
                        }
                    val validationFailure =
                        structuredValidationRefs?.let { refs ->
                            runCatching { SummaryResultCodec.decode(result.text, refs) }
                                .exceptionOrNull()
                        }
                    val validationText =
                        if (structuredValidationRefs == null) {
                            null
                        } else if (validationFailure == null) {
                            "structuredValidation=PASS"
                        } else {
                            val code =
                                (validationFailure as? SummaryStructuredOutputException)
                                    ?.code
                                    ?.name
                                    ?: validationFailure::class.java.simpleName
                            "structuredValidation=FAIL:$code"
                        }
                    resultText =
                        buildString {
                            append(result.toP1DiagnosticsText(includeResponseInDiagnostics))
                            if (validationText != null) {
                                appendLine()
                                append(validationText)
                            }
                            if (replayAssessment != null && replayRequest != null) {
                                appendLine()
                                appendLine("qaR17B=${replayRequest.testCase.label}")
                                appendLine("qaSourceSHA=${replaySource?.promptSha256}")
                                appendLine(replayAssessment.concise())
                            }
                            if (capacityProbe != null) {
                                appendLine()
                                appendLine("qaContextTokens=${capacityProbe.config.maxNumTokens}")
                                appendLine("qaOutputTokens=${capacityProbe.config.maxOutputTokens}")
                                appendLine("qaPromptEstimatedTokens=${capacityProbe.estimatedPromptTokens}")
                                capacityProbe.expectedRecord?.let { expected ->
                                    appendLine("qaLastRecord=$expected")
                                    appendLine("qaTailRecall=${if (result.text.contains(expected)) "PASS" else "FAIL"}")
                                }
                                if (capacityProbe.factIds.isNotEmpty()) {
                                    appendLine(Stage17CapacityQa.evaluateOutput(result.text, capacityProbe.factIds))
                                }
                            }
                        }
                    if (replayRequest != null && replayAssessment != null) {
                        r17bRuns.removeAll { it.testCase == replayRequest.testCase }
                        r17bRuns.add(
                            Stage17R17BReplay.Run(
                                testCase = replayRequest.testCase,
                                rawOutput = result.text,
                                runtimeDiagnostic = result.toP1DiagnosticsText(false),
                                assessment = replayAssessment,
                            ),
                        )
                    }
                    operation =
                        when {
                            validationText == null -> "$label · 完成"
                            validationFailure == null -> "$label · 完成 · JSON PASS"
                            else -> "$label · 完成 · JSON FAIL"
                        }
                } catch (_: CancellationException) {
                    operation = "$label · 已取消"
                } catch (error: LocalLlmRuntimeException) {
                    resultText = error.failure.toString()
                    operation = "$label · 失败 · ${error.failure.code}"
                } catch (error: Throwable) {
                    resultText = "${error::class.java.name}: ${error.message ?: "n/a"}"
                    operation = "$label · 失败"
                } finally {
                    if (capacityProbe != null) {
                        capacityHistory.add("${capacityProbe.label}: ${operation.orEmpty()}\n${resultText.orEmpty()}")
                    }
                    LocalLlmRuntimeDiagnosticObserverRegistry.install(null)
                    r5CrashBreadcrumbs.disarm()
                    activeJob = null
                    refreshModelStatus()
                }
            }
    }

    fun start(
        policy: LocalLlmBackendPolicy,
        resourceOverride: LocalLlmResourceDecision? = null,
        prompt: String = Stage17LocalLlmProbe.PROBE_PROMPT,
    ) {
        startRequest(
            label = policy.name,
            prompt = prompt,
            config =
                LocalLlmValidatedPresets.P0_BASELINE.copy(
                    backendPolicy = policy,
                ),
            resourceOverride = resourceOverride,
        )
    }

    fun startIsolation(testCase: Stage17R5IsolationCase) {
        val source = isolationSources.getOrNull(isolationSourceIndex) ?: return
        val request = buildStage17R5IsolationRequest(testCase, source)
        startRequest(
            label = "R5-${testCase.code}",
            prompt = request.prompt,
            config = request.config,
            includeResponseInDiagnostics = false,
            structuredValidationRefs =
                if (request.testCase.requiresStructuredValidation) {
                    request.allowedEvidenceRefs
                } else {
                    null
                },
            estimatedPromptTokens = request.estimatedPromptTokens,
        )
    }

    fun startCapacity(request: Stage17CapacityQa.Request) {
        startRequest(
            label = "R17A ${request.label}",
            prompt = request.prompt,
            config = request.config,
            includeResponseInDiagnostics = false,
            estimatedPromptTokens = request.estimatedPromptTokens,
            capacityProbe = request,
        )
    }

    val busy = activeJob != null
    val selectedIsolationSource = isolationSources.getOrNull(isolationSourceIndex)
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
            P1Card("R5-B Foreground Isolation Matrix") {
                Text(
                    "只用于前台亮屏、无锁屏、无 Home/background 的回归隔离。正式 Summary 的 AUTO+schema→CPU containment 不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(isolationSourceStatus, style = MaterialTheme.typography.bodySmall)
                if (selectedIsolationSource != null) {
                    Text("录音=${selectedIsolationSource.displayName}")
                    Text(
                        "duration=${formatQaDuration(selectedIsolationSource.durationMs)} · " +
                            "formalPromptTokens=" +
                            ConservativeOfflineSummaryTokenEstimator.estimateTokens(
                                selectedIsolationSource.formalPrompt.text,
                            ) +
                            " · evidenceRefs=${selectedIsolationSource.formalPrompt.allowedEvidenceRefs.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy && isolationSourceIndex > 0,
                            onClick = { isolationSourceIndex -= 1 },
                        ) {
                            Text("上一条")
                        }
                        OutlinedButton(
                            enabled = !busy && isolationSourceIndex < isolationSources.lastIndex,
                            onClick = { isolationSourceIndex += 1 },
                        ) {
                            Text("下一条")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { scope.launch { refreshIsolationSources() } },
                        ) {
                            Text("刷新")
                        }
                    }
                    Text(
                        "严格按 B1 → B2 → B3，一次只运行一项；任一 process restart / POISONED / RELEASE_UNCONFIRMED 即停止。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            startIsolation(Stage17R5IsolationCase.B1_FORMAL_PROMPT_GPU_NO_SCHEMA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("B1 · 正式 prompt + GPU + 无 schema")
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            startIsolation(Stage17R5IsolationCase.B2_SMALL_PROMPT_GPU_FORMAL_SCHEMA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("B2 · 小 prompt + GPU + 正式 schema")
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            startIsolation(Stage17R5IsolationCase.B3_FORMAL_PROMPT_GPU_FORMAL_SCHEMA)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("B3 · 正式 prompt + GPU + 正式 schema")
                    }
                } else {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { scope.launch { refreshIsolationSources() } },
                    ) {
                        Text("重新查找转写")
                    }
                }
            }
        }
        item {
            P1Card("R17A · 上下文与输出容量验证（QA 专用）") {
                Text(
                    "不修改正式总结：4K/1024 token、原始 4×2 schema 保持不变。本页测试固定 CPU、每项手动触发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("严格顺序：①4K → ②8K同输入 → ③8K长输入。若8K异常/重启，立即停止；④16K仅在8K稳定后尝试。")
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.contextProbe(4_000, false)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("① 4K · 输入约2200估算token") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.contextProbe(8_000, false)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("② 8K · 同样输入（测KV cache）") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.contextProbe(8_000, true)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("③ 8K · 输入约5000估算token") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.contextProbe(16_000, true)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("④ 16K · 风险较高（8K通过后）") }
                Text("输出容量逐项对照：相同16条合成事实，先扩schema，再扩输出token，最后只扩大上下文。")
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.outputProbe(8, 1_024, 4_000)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⑤ 4K · 8条 / 1024输出（基线）") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.outputProbe(16, 1_024, 4_000)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⑥ 4K · 16条 / 1024（只扩schema）") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.outputProbe(16, 2_048, 4_000)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⑦ 4K · 16条 / 2048（只扩输出）") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { startCapacity(Stage17CapacityQa.outputProbe(16, 2_048, 8_000)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("⑧ 8K · 16条 / 2048（只扩上下文）") }
                OutlinedButton(
                    enabled = capacityHistory.isNotEmpty(),
                    onClick = {
                        clipboard.setText(AnnotatedString(capacityHistory.joinToString("\n\n---\n\n")))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制本次容量报告（${capacityHistory.size}项）") }
                Text(
                    "注意：16K可能造成原生内存不足甚至进程重启。若异常，重新打开本页复制R5 Crash Breadcrumb，停止继续升档。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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

private fun LocalLlmGenerationResult.toP1DiagnosticsText(
    includeResponse: Boolean = true,
): String {
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
        if (includeResponse) {
            appendLine("response=${text.take(512)}")
        } else {
            appendLine("response=<omitted>")
        }
    }.trimEnd()
}

private fun formatQaDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs < 0L) return "n/a"
    val totalSeconds = durationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
