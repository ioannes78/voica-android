package io.github.ioannes78.voica

import android.app.ActivityManager
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.material3.Checkbox
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
import io.github.ioannes78.voica.localllm.LocalLlmBackend
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
    var r17cSources by remember { mutableStateOf<List<Stage17R17CDirectQa.Source>>(emptyList()) }
    var r17cSourceIndex by remember { mutableStateOf(0) }
    var r17cStatus by remember { mutableStateOf("尚未读取本地有效逐字稿") }
    val r17cRuns = remember { mutableStateListOf<Stage17R17CDirectQa.Run>() }
    var r17cRecentOutput by remember { mutableStateOf<String?>(null) }
    var r17cPendingReport by remember { mutableStateOf<String?>(null) }
    var p2RiskAcknowledged by remember { mutableStateOf(false) }
    var p2ShortPassed by remember { mutableStateOf(false) }
    var p2SessionBlocked by remember { mutableStateOf(false) }
    var p2Status by remember { mutableStateOf("未执行 GPU 自由文本短探针") }
    var p2ReportSource by remember { mutableStateOf<Stage17R17CDirectQa.Source?>(null) }
    val p2Runs = remember { mutableStateListOf<Stage17R17CP2GpuQa.Run>() }
    var p2PendingReport by remember { mutableStateOf<String?>(null) }
    var p2cStatus by remember { mutableStateOf("P2-C1 尚未开始短数字对照") }
    var p2cBlocked by remember { mutableStateOf(false) }
    val p2cRuns = remember { mutableStateListOf<Stage17R17CP2CNumericQa.Run>() }
    var p2cPendingReport by remember { mutableStateOf<String?>(null) }
    var isolationSources by remember { mutableStateOf<List<Stage17R5IsolationSource>>(emptyList()) }
    var isolationSourceIndex by remember { mutableStateOf(0) }
    var isolationSourceStatus by remember { mutableStateOf("正在查找可用于正式 Summary prompt 的转写…") }

    val r17bImportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && activeJob == null) {
                scope.launch {
                    r17bStatus = "正在读取本地诊断文件..."
                    try {
                        val loaded =
                            withContext(Dispatchers.IO) {
                                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                                    Stage17R17BReplay.parseDiagnostic(Stage17R17BReplay.readBounded(stream))
                                } ?: error("无法读取选择的文件")
                            }
                        r17bSource = loaded
                        r17bRuns.clear()
                        resultText = null
                        r17bStatus = "已锁定 FINAL 输入 · 估算 ${loaded.estimatedPromptTokens} token · " +
                            "${loaded.semanticNodeCount} 个 NODE · Evidence ${loaded.allowedEvidenceRefs.size} · " +
                            "SHA ${loaded.promptSha256.take(12)}"
                    } catch (error: Exception) {
                        r17bSource = null
                        r17bRuns.clear()
                        r17bStatus = "导入失败：${error.message ?: error::class.java.simpleName}"
                    }
                }
            }
        }
    val r17bExportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val pending = r17bPendingReport
            if (uri != null && pending != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appContext.contentResolver.openOutputStream(uri, "w")?.use { output ->
                                output.write(pending.toByteArray(Charsets.UTF_8))
                            } ?: error("无法写入导出文件")
                        }
                        r17bStatus = "R17B 对照报告已保存到所选位置"
                    } catch (error: Exception) {
                        r17bStatus = "导出失败：${error.message ?: error::class.java.simpleName}"
                    } finally {
                        r17bPendingReport = null
                    }
                }
            } else {
                r17bPendingReport = null
            }
        }
    val r17cExportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val pending = r17cPendingReport
            if (uri != null && pending != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appContext.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                                stream.write(pending.toByteArray(Charsets.UTF_8))
                            } ?: error("无法写入 QA 报告")
                        }
                        r17cStatus = "R17C 诊断报告已保存到所选位置"
                    } catch (error: Exception) {
                        r17cStatus = "R17C 导出失败：" + (error.message ?: error::class.java.simpleName)
                    } finally {
                        r17cPendingReport = null
                    }
                }
            } else {
                r17cPendingReport = null
            }
        }

    val p2ExportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val report = p2PendingReport
            if (uri != null && report != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appContext.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                                stream.write(report.toByteArray(Charsets.UTF_8))
                            } ?: error("无法写入 P2 QA 报告")
                        }
                        p2Status = "P2 GPU QA 诊断报告已保存"
                    } catch (error: Exception) {
                        p2Status = "P2 报告导出失败：" + (error.message ?: error::class.java.simpleName)
                    } finally {
                        p2PendingReport = null
                    }
                }
            } else {
                p2PendingReport = null
            }
        }

    val p2cExportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val report = p2cPendingReport
            if (uri != null && report != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            appContext.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                                stream.write(report.toByteArray(Charsets.UTF_8))
                            } ?: error("无法写入 P2-C1 诊断报告")
                        }
                        p2cStatus = "P2-C1 诊断报告已保存到所选位置"
                    } catch (error: Exception) {
                        p2cStatus = "P2-C1 报告导出失败：" + (error.message ?: error::class.java.simpleName)
                    } finally {
                        p2cPendingReport = null
                    }
                }
            } else {
                p2cPendingReport = null
            }
        }

    suspend fun refreshR17CSources() {
        if (activeJob != null) return
        r17cStatus = "正在读取本地当前有效转写快照…"
        val loaded = runCatching {
            withContext(Dispatchers.IO) { container.loadStage17R17CSources() }
        }.getOrElse { error ->
            r17cStatus = "读取失败：" + (error.message ?: error::class.java.simpleName)
            emptyList()
        }
        r17cSources = loaded
        r17cSourceIndex = 0
        r17cRuns.clear()
        r17cRecentOutput = null
        if (loaded.isNotEmpty()) {
            r17cStatus = "READY · 已找到 " + loaded.size + " 条已完成的有效逐字稿"
        } else if (!r17cStatus.startsWith("读取失败")) {
            r17cStatus = "没有可用的已完成转写（按最近30条候选扫描）"
        }
    }

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
        directRequest: Stage17R17CDirectQa.Request? = null,
        directSource: Stage17R17CDirectQa.Source? = null,
        p2Request: Stage17R17CP2GpuQa.Request? = null,
        p2Source: Stage17R17CDirectQa.Source? = null,
        numericRequest: Stage17R17CP2CNumericQa.Request? = null,
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
                    if (directRequest != null) {
                        requireNotNull(directSource) { "R17C 输入快照丢失" }
                        Stage172OfflineSummaryModelContract.requireMatches(result.modelIdentity)
                        require(result.backendResolution.resolvedBackend == LocalLlmBackend.CPU) {
                            "R17C P1 禁止非 CPU 后端"
                        }
                        r17cRuns.removeAll { it.tier == directRequest.tier }
                        r17cRuns.add(
                            Stage17R17CDirectQa.Run(
                                tier = directRequest.tier,
                                rawOutput = result.text,
                                runtimeDiagnostic = result.toP1DiagnosticsText(false) +
                                    "\nqaR17C=DirectPlainText" +
                                    "\nqaPromptSHA=" + directRequest.promptSha256 +
                                    "\nqaPromptEstimate=" + directRequest.estimatedPromptTokens,
                            ),
                        )
                        r17cRecentOutput = result.text
                    }
                    if (p2Request != null) {
                        Stage172OfflineSummaryModelContract.requireMatches(result.modelIdentity)
                        require(result.backendResolution.resolvedBackend == LocalLlmBackend.GPU) {
                            "P2 GPU QA 不能接受 CPU 代替或假回退"
                        }
                        require(!result.backendResolution.fallbackAttempted) {
                            "P2 显式 GPU 不允许 CPU fallback"
                        }
                        require(runtime.state.value == LocalLlmRuntimeState.IDLE) {
                            "P2 GPU 生成结束后原生资源未恢复 IDLE"
                        }
                        require(result.diagnostics.pssAfterCloseKb != null) {
                            "P2 GPU 结束时缺少原生资源关闭后的 PSS 记录"
                        }
                        if (p2Request.testCase == Stage17R17CP2GpuQa.Case.SHORT_4K) {
                            require(Stage17R17CP2GpuQa.shortProbePass(
                                output = result.text,
                                resolvedBackend = result.backendResolution.resolvedBackend,
                                fallbackAttempted = result.backendResolution.fallbackAttempted,
                                afterClosePssKb = result.diagnostics.pssAfterCloseKb,
                                nativeRuntimeIdle = runtime.state.value == LocalLlmRuntimeState.IDLE,
                            )) { "短探针输出或原生释放校验未通过，不允许继续 8K" }
                            p2ShortPassed = true
                        } else {
                            requireNotNull(p2Source) { "P2 长文本源快照丢失" }
                            require(p2Request.sourceDigest == p2Source.inputDigest &&
                                p2Request.transcriptSha256 == p2Source.transcriptSha256) {
                                "P2 同源快照身份不匹配"
                            }
                            p2ReportSource = p2Source
                        }
                        p2Runs.removeAll { it.testCase == p2Request.testCase }
                        p2Runs.add(
                            Stage17R17CP2GpuQa.Run(
                                testCase = p2Request.testCase,
                                rawOutput = result.text,
                                runtimeDiagnostic = result.toP1DiagnosticsText(false) +
                                    "\nqaR17CP2=GPU_NO_SCHEMA" +
                                    "\nqaPromptTokens=" + p2Request.estimatedPromptTokens +
                                    "\nqaOutputCap=" + p2Request.config.maxOutputTokens,
                                promptSha256 = p2Request.promptSha256,
                            ),
                        )
                        p2Status = p2Request.testCase.label +
                            " 完成 · 已核实 GPU / IDLE / close PSS；长文本事实仍须人工复核"
                    }
                    if (numericRequest != null) {
                        Stage172OfflineSummaryModelContract.requireMatches(result.modelIdentity)
                        require(Stage17R17CP2CNumericQa.runtimePass(
                            testCase = numericRequest.testCase,
                            actual = result.backendResolution.resolvedBackend,
                            fallback = result.backendResolution.fallbackAttempted,
                            idle = runtime.state.value == LocalLlmRuntimeState.IDLE,
                            closedPssKb = result.diagnostics.pssAfterCloseKb,
                        )) { "P2-C1 要求实际后端一致、无回退、关闭 PSS 可见且 Runtime 为 IDLE" }
                        val audit = Stage17R17CP2CNumericQa.assess(result.text)
                        p2cRuns.removeAll { it.testCase == numericRequest.testCase }
                        p2cRuns.add(
                            Stage17R17CP2CNumericQa.Run(
                                testCase = numericRequest.testCase,
                                output = result.text,
                                runtimeDiagnostic = result.toP1DiagnosticsText(false) +
                                    "\nqaR17CP2C1=NUMERIC_NO_SCHEMA" +
                                    "\nqaPromptSHA=" + numericRequest.promptSha256 +
                                    "\nqaPromptTokens=" + numericRequest.estimatedPromptTokens +
                                    "\nqaOutputCap=" + numericRequest.config.maxOutputTokens,
                                assessment = audit,
                            ),
                        )
                        p2cStatus = numericRequest.testCase.label + " 完成；" + audit.summary()
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
                            numericRequest != null -> "$label · 短数字对照完成（必须人工审核）"
                            p2Request != null -> "$label · GPU QA 完成（非生产资格）"
                            directRequest != null -> {
                                val source = requireNotNull(directSource)
                                val audit = Stage17R17CDirectQa.knownNumericRegression(
                                    source.transcriptText,
                                    result.text,
                                )
                                if (audit.startsWith("FLAG_CRITICAL")) {
                                    "$label · 完成但已知数字单位错误（见下方）"
                                } else {
                                    "$label · 自由文本完成（仍须人工校验事实）"
                                }
                            }
                            replayAssessment != null && !replayAssessment.valid -> "$label · FINAL JSON FAIL"
                            replayAssessment != null -> "$label · FINAL JSON PASS"
                            validationText == null -> "$label · 完成"
                            validationFailure == null -> "$label · 完成 · JSON PASS"
                            else -> "$label · 完成 · JSON FAIL"
                        }
                } catch (_: CancellationException) {
                    if (numericRequest != null) {
                        p2cBlocked = true
                        p2cStatus = "数字对照已取消；本轮不再进行原生推理"
                        if (numericRequest.testCase == Stage17R17CP2CNumericQa.Case.GPU) {
                            p2SessionBlocked = true
                            p2ShortPassed = false
                            p2RiskAcknowledged = false
                        }
                    }
                    if (p2Request != null) {
                        p2SessionBlocked = true
                        p2ShortPassed = false
                        p2RiskAcknowledged = false
                        p2Status = "GPU QA 已取消；本次测试禁止再次运行，请检查原生状态"
                    }
                    operation = "$label · 已取消"
                } catch (error: LocalLlmRuntimeException) {
                    if (numericRequest != null) {
                        p2cBlocked = true
                        p2cStatus = "数字对照原生失败，本轮已锁定：" + error.failure.code
                        if (numericRequest.testCase == Stage17R17CP2CNumericQa.Case.GPU) {
                            p2SessionBlocked = true
                            p2ShortPassed = false
                            p2RiskAcknowledged = false
                        }
                    }
                    if (p2Request != null) {
                        p2SessionBlocked = true
                        p2ShortPassed = false
                        p2RiskAcknowledged = false
                        p2Status = "GPU native/runtime 失败，禁止本轮重试：" + error.failure.code
                    }
                    resultText = error.failure.toString()
                    operation = "$label · 失败 · ${error.failure.code}"
                } catch (error: Throwable) {
                    if (numericRequest != null) {
                        p2cBlocked = true
                        p2cStatus = "数字对照验证失败，本轮已锁定：" +
                            (error.message ?: error::class.java.simpleName)
                        if (numericRequest.testCase == Stage17R17CP2CNumericQa.Case.GPU) {
                            p2SessionBlocked = true
                            p2ShortPassed = false
                            p2RiskAcknowledged = false
                        }
                    }
                    if (p2Request != null) {
                        p2SessionBlocked = true
                        p2ShortPassed = false
                        p2RiskAcknowledged = false
                        p2Status = "GPU QA 验证失败，本轮已锁定：" +
                            (error.message ?: error::class.java.simpleName)
                    }
                    resultText = "${error::class.java.name}: ${error.message ?: "n/a"}"
                    operation = "$label · 失败"
                } finally {
                    if (p2Request != null ||
                        numericRequest?.testCase == Stage17R17CP2CNumericQa.Case.GPU
                    ) {
                        (appContext as? ComponentActivity)?.window?.clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        )
                    }
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

    fun startR17B(testCase: Stage17R17BReplay.Case) {
        val source = r17bSource ?: return
        if (activeJob != null) return
        val request =
            runCatching { Stage17R17BReplay.request(source, testCase) }
                .getOrElse { error ->
                    r17bStatus = "配置拒绝：${error.message ?: error::class.java.simpleName}"
                    return
                }
        startRequest(
            label = "R17B-1 ${testCase.label}",
            prompt = request.prompt,
            config = request.config,
            includeResponseInDiagnostics = false,
            estimatedPromptTokens = request.estimatedPromptTokens,
            replayRequest = request,
            replaySource = source,
        )
    }
    fun startR17C(tier: Stage17R17CDirectQa.Tier) {
        val source = r17cSources.getOrNull(r17cSourceIndex) ?: return
        if (activeJob != null) return
        val request = runCatching { Stage17R17CDirectQa.request(source, tier) }
            .getOrElse { error ->
                r17cStatus = "预算预检拒绝：" + (error.message ?: error::class.java.simpleName)
                return
            }
        r17cStatus = "已锁定输入 " + source.transcriptSha256.take(12) +
            " · 估算 " + request.estimatedPromptTokens + " tokens · Context " + tier.context
        startRequest(
            label = "R17C " + tier.label,
            prompt = request.prompt,
            config = request.config,
            includeResponseInDiagnostics = false,
            estimatedPromptTokens = request.estimatedPromptTokens,
            directRequest = request,
            directSource = source,
        )
    }
    fun startP2(testCase: Stage17R17CP2GpuQa.Case) {
        if (activeJob != null) return
        if (testCase == Stage17R17CP2GpuQa.Case.REAL_8K_2048 &&
            p2cRuns.any { it.testCase == Stage17R17CP2CNumericQa.Case.GPU }
        ) {
            p2Status = "P2-C1 已执行 GPU 数字对照，本轮不再叠加 GPU 8K 压力测试"
            return
        }
        if (p2Runs.any { it.testCase == testCase }) {
            p2Status = "此项在当前 QA 页面已经成功执行；不重复进行 GPU 压力测试"
            return
        }
        val denial = Stage17R17CP2GpuQa.preflightReason(
            testCase = testCase,
            acknowledgedRisk = p2RiskAcknowledged,
            shortProbePassed = p2ShortPassed,
            previousFailureInSession = p2SessionBlocked,
            foreground = lifecycleSnapshot.processForeground,
            activityResumed = lifecycleSnapshot.activityResumed,
            interactive = lifecycleSnapshot.interactive,
            keyguardShowing = lifecycleSnapshot.keyguardShowing,
            deviceLocked = lifecycleSnapshot.deviceLocked,
            runtimeIdle = runtime.state.value == LocalLlmRuntimeState.IDLE,
        )
        if (denial != null) {
            p2Status = "GPU 预检拒绝：" + denial
            return
        }
        val source =
            if (testCase == Stage17R17CP2GpuQa.Case.REAL_8K_2048) {
                r17cSources.getOrNull(r17cSourceIndex) ?: run {
                    p2Status = "请先读取并选择真实逐字稿"
                    return
                }
            } else {
                null
            }
        if (testCase == Stage17R17CP2GpuQa.Case.REAL_8K_2048) {
            val memory = ActivityManager.MemoryInfo()
            appContext.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            if (!Stage17R17CP2GpuQa.longMemoryReady(memory.availMem, memory.lowMemory)) {
                p2Status = "GPU 8K 已拒绝：当前可用内存不足 3 GiB 或系统处于低内存状态"
                return
            }
        }
        val request = runCatching {
            if (source == null) Stage17R17CP2GpuQa.shortProbe()
            else Stage17R17CP2GpuQa.fullTranscriptProbe(source)
        }.getOrElse { error ->
            p2Status = "GPU 输入预算预检拒绝：" +
                (error.message ?: error::class.java.simpleName)
            return
        }
        p2Status = request.testCase.label + " · 前台亮屏中，正在调用原生 GPU…"
        // P2-only mitigation: prevent automatic screen timeout during native GPU inference.
        // Manual power/keyguard transitions remain unsafe and must not be tested.
        (appContext as? ComponentActivity)?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        startRequest(
            label = "R17C " + testCase.label,
            prompt = request.prompt,
            config = request.config,
            includeResponseInDiagnostics = false,
            estimatedPromptTokens = request.estimatedPromptTokens,
            p2Request = request,
            p2Source = source,
        )
    }
    fun startP2C(testCase: Stage17R17CP2CNumericQa.Case) {
        if (activeJob != null) return
        if (p2cBlocked || p2SessionBlocked) {
            p2cStatus = "本次会话存在失败记录；禁止继续短数字 QA"
            return
        }
        if (p2cRuns.any { it.testCase == testCase }) {
            p2cStatus = "该后端已经完成一次对照；不重复执行"
            return
        }
        if (!lifecycleSnapshot.processForeground || !lifecycleSnapshot.activityResumed ||
            !lifecycleSnapshot.interactive || lifecycleSnapshot.deviceLocked ||
            lifecycleSnapshot.keyguardShowing ||
            runtime.state.value != LocalLlmRuntimeState.IDLE
        ) {
            p2cStatus = "前台、亮屏、未锁定且 Runtime IDLE 才能执行短数字 QA"
            return
        }
        if (testCase == Stage17R17CP2CNumericQa.Case.GPU) {
            if (p2Runs.any { it.testCase == Stage17R17CP2GpuQa.Case.REAL_8K_2048 }) {
                p2cStatus = "本次页面已执行 GPU 8K，不再进行额外 GPU 负载"
                return
            }
            if (p2cRuns.none { it.testCase == Stage17R17CP2CNumericQa.Case.CPU }) {
                p2cStatus = "先完成 CPU 4K/512 参考对照"
                return
            }
            val reason = Stage17R17CP2GpuQa.preflightReason(
                testCase = Stage17R17CP2GpuQa.Case.REAL_8K_2048,
                acknowledgedRisk = p2RiskAcknowledged,
                shortProbePassed = p2ShortPassed,
                previousFailureInSession = p2SessionBlocked,
                foreground = lifecycleSnapshot.processForeground,
                activityResumed = lifecycleSnapshot.activityResumed,
                interactive = lifecycleSnapshot.interactive,
                keyguardShowing = lifecycleSnapshot.keyguardShowing,
                deviceLocked = lifecycleSnapshot.deviceLocked,
                runtimeIdle = runtime.state.value == LocalLlmRuntimeState.IDLE,
            )
            if (reason != null) {
                p2cStatus = "GPU 预检拒绝：" + reason
                return
            }
        }
        val request = runCatching { Stage17R17CP2CNumericQa.request(testCase) }
            .getOrElse { error ->
                p2cStatus = "输入预算拒绝：" + (error.message ?: error::class.java.simpleName)
                return
            }
        p2cStatus = testCase.label + " · 执行中，原文与采样参数已固定"
        if (testCase == Stage17R17CP2CNumericQa.Case.GPU) {
            (appContext as? ComponentActivity)?.window?.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        startRequest(
            label = testCase.label,
            prompt = request.prompt,
            config = request.config,
            includeResponseInDiagnostics = false,
            estimatedPromptTokens = request.estimatedPromptTokens,
            numericRequest = request,
        )
    }
    val busy = activeJob != null
    val selectedR17CSource = r17cSources.getOrNull(r17cSourceIndex)
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
            P1Card("R17C P0/P1 · 完整逐字稿直读（CPU / QA）") {
                Text(
                    "从闻知当前有效转写快照直接读取全文；跳过 MAP/REDUCE/FINAL，不使用 JSON Schema，" +
                        "不修改正式总结、Room 或录音。仅在用户选择保存时导出本地报告。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    enabled = !busy,
                    onClick = { scope.launch { refreshR17CSources() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("读取本机已完成的逐字稿") }
                Text(r17cStatus, style = MaterialTheme.typography.bodySmall)
                val source = selectedR17CSource
                if (source != null) {
                    Text(
                        "样本 " + (r17cSourceIndex + 1) + "/" + r17cSources.size +
                            " · " + source.displayName + " · " + formatQaDuration(source.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy && r17cSourceIndex > 0,
                            onClick = {
                                r17cSourceIndex--
                                r17cRuns.clear()
                                r17cRecentOutput = null
                            },
                        ) { Text("上一条") }
                        OutlinedButton(
                            enabled = !busy && r17cSourceIndex < r17cSources.lastIndex,
                            onClick = {
                                r17cSourceIndex++
                                r17cRuns.clear()
                                r17cRecentOutput = null
                            },
                        ) { Text("下一条") }
                    }
                    Text(
                        "原文 " + source.transcriptText.length + " 字符 · " + source.unitCount +
                            " 单元 · SHA " + source.transcriptSha256.take(12) +
                            " · inputDigest " + source.inputDigest.take(12),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    r17bSource?.let { frozen ->
                        Text(
                            "与已导入 R17B 源快照对照：" +
                                if (frozen.inputDigest == source.inputDigest) "同源（digest 一致）"
                                else "不同源（不可直接比较）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "R2 优先执行 8K/2048 同源对照；不要同时启动旧 16K 测试。预算超限会拒绝且不会截断原文。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17C(Stage17R17CDirectQa.Tier.C4K) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("P1 · 4K / 1024 · CPU 全文直读") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17C(Stage17R17CDirectQa.Tier.C8K) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("P1 · 8K / 1024 · CPU 全文直读") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17C(Stage17R17CDirectQa.Tier.C8K_2048) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("P1-R2 · 8K / 2048 · CPU 同源对照（优先）") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17C(Stage17R17CDirectQa.Tier.C16K) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("P1 · 16K / 2048 · CPU（高资源）") }
                    r17cRuns.forEach { run ->
                        Text(
                            run.tier.label + " · " +
                                Stage17R17CDirectQa.knownNumericRegression(
                                    source.transcriptText,
                                    run.rawOutput,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            r17cPendingReport = Stage17R17CDirectQa.report(
                                source, r17cRuns.toList(), r17bSource?.inputDigest,
                            )
                            r17cExportPicker.launch("WENZHI-Stage17.3-R17C-P1-R2-8K2048-DirectSummary.txt")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存 P0 原文及 P1 总结对照 TXT") }
                    r17cRecentOutput?.let { summary ->
                        Text("最近一次自由文本总结（仅预览）：", style = MaterialTheme.typography.bodySmall)
                        Text(summary.take(8_000), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "导出文件含完整原始逐字稿和对应 Evidence，可能涉及隐私；不要把输出字数等同事实保真。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            P1Card("R17C P2 · GPU 自由文本兼容性（QA 专用）") {
                Text(
                    "专测当前 LiteRT-LM 0.18.0 + Gemma 4 E2B。只启用显式 GPU，" +
                        "不使用原生 JSON Schema，不修改正式总结的 CPU 保护。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "风险：历史 QA 中曾发生 GPU/native ANR、锁屏失去响应及设备重启。" +
                        "只在前台亮屏、设备温度正常、无其他高负载时手动尝试；" +
                        "不可锁屏、切后台或连续压力测试。发生异常立即停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Checkbox(
                        checked = p2RiskAcknowledged,
                        onCheckedChange = { p2RiskAcknowledged = it },
                        enabled = !busy && !p2SessionBlocked,
                    )
                    Text(
                        "已知 GPU 原生故障可能导致 App 卡死或设备重启；本次仅手动测试一轮",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "安全检查：前台=" + lifecycleSnapshot.processForeground +
                        " · 活跃=" + lifecycleSnapshot.activityResumed +
                        " · 亮屏=" + lifecycleSnapshot.interactive +
                        " · 锁屏=" + lifecycleSnapshot.deviceLocked +
                        " · Runtime=" + runtimeState,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(p2Status, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = !busy && p2RiskAcknowledged && !p2ShortPassed &&
                        !p2SessionBlocked && runtimeState == LocalLlmRuntimeState.IDLE,
                    onClick = { startP2(Stage17R17CP2GpuQa.Case.SHORT_4K) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("P2-A · GPU 4K 短探针（第一步）")
                }
                OutlinedButton(
                    enabled = !busy && p2RiskAcknowledged && p2ShortPassed &&
                        !p2SessionBlocked && selectedR17CSource != null &&
                        p2Runs.none { it.testCase == Stage17R17CP2GpuQa.Case.REAL_8K_2048 } &&
                        p2cRuns.none { it.testCase == Stage17R17CP2CNumericQa.Case.GPU } &&
                        runtimeState == LocalLlmRuntimeState.IDLE,
                    onClick = { startP2(Stage17R17CP2GpuQa.Case.REAL_8K_2048) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("P2-B · GPU 8K / 2048 完整逐字稿（短探针 PASS 后）")
                }
                if (!p2ShortPassed) {
                    Text(
                        "8K GPU 暂未解锁：必须先完成一次 4K 短探针并确认 GPU、输出及释放正常。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "短探针完成；请先检查运行结果、PSS 和设备表现，再决定是否手动执行 8K。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                p2Runs.forEach { run ->
                    Text(run.testCase.label + " · 已完成；Prompt SHA " +
                        run.promptSha256.take(12), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    enabled = !busy && p2Runs.isNotEmpty(),
                    onClick = {
                        p2PendingReport = Stage17R17CP2GpuQa.report(
                            source = p2ReportSource,
                            runs = p2Runs.toList(),
                            shortProbePassed = p2ShortPassed,
                            sessionBlocked = p2SessionBlocked,
                        )
                        p2ExportPicker.launch("WENZHI-Stage17.3-R17C-P2-GPU-Compatibility.txt")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存 GPU 对照与安全诊断 TXT")
                }
                Text(
                    "若 native 卡死/重启而无输出，重新打开 QA 页面复制 R5 Crash Breadcrumb，" +
                        "不要再试 GPU。完成一次测试并不代表生产 GPU 已达稳定性标准。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            P1Card("R17C P2-C1 · CPU/GPU 数字保真对照（短文本 QA）") {
                Text(
                    "固定五组来自原录音的数值资料，CPU 与 GPU 使用同一 Prompt、4K/512 和相同采样参数。" +
                        "只检测短文本数字变形，不等同整篇总结质量，也不升级 LiteRT-LM。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "真机顺序：①CPU 数字基线 → ②上方 P2-A GPU 短探针 → ③本区 GPU 数字对照。" +
                        "每项只允许运行一次，勿锁屏或切后台。已运行 GPU 8K 的页面不可再做 P2-C1 GPU。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(p2cStatus, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = !busy && !p2cBlocked && !p2SessionBlocked &&
                        runtimeState == LocalLlmRuntimeState.IDLE &&
                        p2cRuns.none { it.testCase == Stage17R17CP2CNumericQa.Case.CPU },
                    onClick = { startP2C(Stage17R17CP2CNumericQa.Case.CPU) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("P2-C1-A · CPU 4K/512 数字基线")
                }
                OutlinedButton(
                    enabled = !busy && p2RiskAcknowledged && p2ShortPassed &&
                        !p2cBlocked && !p2SessionBlocked &&
                        runtimeState == LocalLlmRuntimeState.IDLE &&
                        p2cRuns.any { it.testCase == Stage17R17CP2CNumericQa.Case.CPU } &&
                        p2cRuns.none { it.testCase == Stage17R17CP2CNumericQa.Case.GPU } &&
                        p2Runs.none { it.testCase == Stage17R17CP2GpuQa.Case.REAL_8K_2048 },
                    onClick = { startP2C(Stage17R17CP2CNumericQa.Case.GPU) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("P2-C1-B · GPU 4K/512 数字基线（P2-A 通过后）")
                }
                p2cRuns.forEach { run ->
                    Text(
                        run.testCase.label + " · " + run.assessment.summary(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (run.assessment.knownErrors.isNotEmpty()) {
                        Text(
                            "已知数字错误：" + run.assessment.knownErrors.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (run.assessment.languageSignals.isNotEmpty()) {
                        Text(
                            "异常语种：" + run.assessment.languageSignals.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedButton(
                    enabled = !busy && p2cRuns.isNotEmpty(),
                    onClick = {
                        p2cPendingReport = Stage17R17CP2CNumericQa.report(
                            runs = p2cRuns.toList(),
                            blocked = p2cBlocked || p2SessionBlocked,
                            shortProbePassed = p2ShortPassed,
                        )
                        p2cExportPicker.launch("WENZHI-Stage17.3-R17C-P2-C1-NumericQA.txt")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存短数字 CPU/GPU 对照 TXT")
                }
                Text(
                    "NO_KNOWN_FLAG 不代表事实正确。对照报告仅本机手动导出。" +
                        "如 GPU 发生 ANR、异常发热或进程重启，立即停止并保存 R5 Breadcrumb。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            P1Card("R17B-1 · 真实转写 FINAL 同源对照（QA 专用）") {
                Text(
                    "从 Downloads/WENZHI 选择先前导出的 Stage 17.3 LongSummary Diagnostic TXT。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("只读取已验证的 FINAL INPUT/OUTPUT；不修改录音、Room 或正式总结。保留原始 prompt，在本地 CPU 手动对比。")
                OutlinedButton(
                    enabled = !busy,
                    onClick = { r17bImportPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导入同一录音的长文本诊断 TXT") }
                Text(r17bStatus, style = MaterialTheme.typography.bodySmall)
                val source = r17bSource
                if (source != null) {
                    Text(
                        "原始 FINAL · ${Stage17R17BReplay.evaluate(source.baselineRaw, source).concise()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("逐项运行 B1 → B2 → B3。每项最多一次 native 请求；异常时停止。")
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17B(Stage17R17BReplay.Case.B1_EXPANDED_4K) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("B1 · 4K / 4×4 条 / 1024 输出") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17B(Stage17R17BReplay.Case.B2_EXPANDED_8K) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("B2 · 8K / 4×4 条 / 1024 输出") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { startR17B(Stage17R17BReplay.Case.B3_EXTENDED_OUTPUT_8K) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("B3 · 8K / 4×4 条 / 2048 输出") }
                    r17bRuns.forEach { run ->
                        Text("${run.testCase.label} · ${run.assessment.concise()}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        enabled = !busy && r17bRuns.isNotEmpty(),
                        onClick = {
                            r17bPendingReport = Stage17R17BReplay.report(source, r17bRuns.toList())
                            r17bExportPicker.launch("WENZHI-Stage17.3-R17B1-FINAL-Comparison.txt")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存原始与新版本对照报告 TXT") }
                    Text(
                        "报告包含来自真实转写的 FINAL 内容，只有手动选择保存位置才导出；" +
                            "Evidence 编号存在不代表事实语义正确，需继续人工核对原文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
