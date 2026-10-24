package io.github.ioannes78.voica

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.localllm.ProbeBackend
import io.github.ioannes78.voica.localllm.Stage17LocalLlmProbe
import io.github.ioannes78.voica.localllm.Stage17P0Candidate
import io.github.ioannes78.voica.localllm.Stage17ProbeRequest
import io.github.ioannes78.voica.localllm.Stage17ProbeResult
import io.github.ioannes78.voica.ui.theme.VoicaTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** QA-only physical-device harness for Stage 17 / P0. */
class Stage17P0LocalLlmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!stage17P0LocalLlmEntryVisible(BuildConfig.APPLICATION_ID)) {
            finish()
            return
        }
        val container = (application as VoicaApplication).container
        setContent {
            val themeSettings by container.themeSettingsStore.settings.collectAsState()
            VoicaTheme(settings = themeSettings) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Stage17P0LocalLlmScreen(onClose = ::finish)
                }
            }
        }
    }
}

internal fun stage17P0LocalLlmEntryVisible(applicationId: String): Boolean =
    applicationId.endsWith(".qa")

@Composable
private fun Stage17P0LocalLlmScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val probe = remember { Stage17LocalLlmProbe() }
    val modelDir = remember { File(context.filesDir, "stage17-p0") }
    val modelFile = remember { File(modelDir, Stage17P0Candidate.MODEL_FILE) }
    val cacheDir = remember { File(context.cacheDir, "stage17-p0-litertlm").apply { mkdirs() } }
    val memoryInfo =
        remember {
            ActivityManager.MemoryInfo().also { info ->
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            }
        }

    var importedBytes by remember {
        mutableLongStateOf(modelFile.takeIf { it.isFile }?.length() ?: 0L)
    }
    var copiedBytes by remember { mutableLongStateOf(0L) }
    var operation by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    fun startProbe(backend: ProbeBackend) {
        if (activeJob != null || !modelFile.isValidP0Candidate()) return
        operation = "${backend.name} probe 运行中"
        resultText = null
        activeJob =
            scope.launch {
                try {
                    val result =
                        probe.run(
                            Stage17ProbeRequest(
                                modelPath = modelFile.absolutePath,
                                cacheDir = cacheDir.absolutePath,
                                backend = backend,
                            ),
                        )
                    resultText = result.toDiagnosticsText()
                    operation = "${backend.name} probe 完成"
                } catch (_: CancellationException) {
                    operation = "probe 已请求取消"
                } catch (error: Throwable) {
                    resultText =
                        "[Stage 17 P0 ${backend.name} failure]\n" +
                            "${error::class.java.name}: ${error.message ?: "n/a"}"
                    operation = "${backend.name} probe 失败"
                } finally {
                    activeJob = null
                }
            }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null || activeJob != null) return@rememberLauncherForActivityResult
            copiedBytes = 0L
            resultText = null
            operation = "正在导入 P0 模型"
            activeJob =
                scope.launch {
                    try {
                        val imported =
                            context.importStage17P0Candidate(
                                destination = modelFile,
                                source = uri,
                                onProgress = { copiedBytes = it },
                            )
                        importedBytes = imported.length()
                        operation = "模型已导入并通过大小校验"
                    } catch (_: CancellationException) {
                        operation = "模型导入已取消"
                    } catch (error: Throwable) {
                        importedBytes = modelFile.takeIf { it.isFile }?.length() ?: 0L
                        operation = "模型导入失败"
                        resultText = error.message ?: error::class.java.name
                    } finally {
                        activeJob = null
                    }
                }
        }

    val totalMemoryBytes = memoryInfo.totalMem
    val modelReady = modelFile.isValidP0Candidate()
    val busy = activeJob != null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Stage 17 P0 · 本地 LLM 可行性", style = MaterialTheme.typography.headlineSmall)
            Text(
                "仅 QA 构建。此页不进入正式总结/RAG/问答链路，只验证 Gemma 4 E2B + LiteRT-LM 的真机运行、内存与延迟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            P0Card(title = "候选与设备") {
                Text("Runtime：LiteRT-LM 0.18.1")
                Text("Model：${Stage17P0Candidate.MODEL_ID}")
                Text("Revision：${Stage17P0Candidate.GALLERY_BASE_REVISION}")
                Text("候选文件：${formatBytes(Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES)}")
                Text("设备 RAM：${formatBytes(totalMemoryBytes)}（P0 门槛 ≥ ${Stage17P0Candidate.MIN_DEVICE_MEMORY_GB} GB）")
                Text("应用可用存储：${formatBytes(context.filesDir.usableSpace)}")
                if (totalMemoryBytes < Stage17P0Candidate.MIN_DEVICE_MEMORY_GB * 1_000_000_000L) {
                    Text(
                        "当前设备低于候选建议内存门槛；结果只能记为受限/失败证据。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://huggingface.co/${Stage17P0Candidate.MODEL_ID}/tree/${Stage17P0Candidate.GALLERY_BASE_REVISION}",
                                ),
                            ),
                        )
                    },
                ) {
                    Text("打开候选模型页")
                }
            }
        }

        item {
            P0Card(title = "P0 模型文件") {
                Text(
                    if (modelReady) {
                        "已就绪：${modelFile.name} · ${formatBytes(importedBytes)}"
                    } else {
                        "未就绪。下载 exact revision 后选择 ${Stage17P0Candidate.MODEL_FILE}。"
                    },
                )
                if (operation == "正在导入 P0 模型") {
                    Text(
                        "已复制 ${formatBytes(copiedBytes)} / ${formatBytes(Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { picker.launch(arrayOf("*/*")) },
                    ) {
                        Text("选择 .litertlm")
                    }
                    OutlinedButton(
                        enabled = !busy && modelFile.exists(),
                        onClick = {
                            modelFile.delete()
                            importedBytes = 0L
                            resultText = null
                            operation = "已删除 P0 本地模型"
                        },
                    ) {
                        Text("删除")
                    }
                }
            }
        }

        item {
            P0Card(title = "运行 Probe") {
                Text(
                    "固定中文 smoke prompt；最多 ${Stage17LocalLlmProbe.PROBE_MAX_OUTPUT_TOKENS} tokens；speculative decoding=OFF。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = modelReady && !busy, onClick = { startProbe(ProbeBackend.CPU) }) {
                        Text("CPU")
                    }
                    Button(enabled = modelReady && !busy, onClick = { startProbe(ProbeBackend.GPU) }) {
                        Text("GPU")
                    }
                    OutlinedButton(
                        enabled = busy,
                        onClick = { activeJob?.cancel() },
                    ) {
                        Text("取消")
                    }
                }
                operation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        resultText?.let { diagnostics ->
            item {
                P0Card(title = "最近结果") {
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

@Composable
private fun P0Card(
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

private suspend fun android.content.Context.importStage17P0Candidate(
    destination: File,
    source: Uri,
    onProgress: (Long) -> Unit,
): File =
    withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        temporary.delete()
        var promoted = false
        try {
            contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "无法打开所选模型文件" }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    var nextProgress = COPY_PROGRESS_STEP_BYTES
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (total >= nextProgress) {
                            withContext(Dispatchers.Main.immediate) { onProgress(total) }
                            nextProgress = total + COPY_PROGRESS_STEP_BYTES
                        }
                    }
                    output.fd.sync()
                    withContext(Dispatchers.Main.immediate) { onProgress(total) }
                    require(total == Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES) {
                        "文件大小不匹配：实际 $total bytes，期望 ${Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES} bytes。P0 只接受已冻结 base revision。"
                    }
                }
            }
            if (destination.exists()) check(destination.delete()) { "无法替换旧 P0 模型" }
            check(temporary.renameTo(destination)) { "P0 模型原子提升失败" }
            promoted = true
            destination
        } finally {
            if (!promoted) temporary.delete()
        }
    }

private fun File.isValidP0Candidate(): Boolean =
    isFile && length() == Stage17P0Candidate.GALLERY_BASE_SIZE_BYTES

private fun Stage17ProbeResult.toDiagnosticsText(): String =
    buildString {
        appendLine("[Stage 17 P0 local LLM]")
        appendLine("runtime=$runtimeVersion backend=$backend")
        appendLine("initializeMs=$initializeMs ttfeMs=${timeToFirstEmissionMs ?: -1} generationMs=$totalGenerationMs")
        appendLine(
            "pssKb before=$pssBeforeInitializeKb initialized=$pssAfterInitializeKb " +
                "generated=$pssAfterGenerationKb closed=$pssAfterCloseKb peak=$peakPssKb",
        )
        appendLine("prompt=${Stage17LocalLlmProbe.PROBE_PROMPT}")
        append("response=$responsePreview")
    }

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "$bytes B"
    }

private const val COPY_BUFFER_BYTES = 4 * 1024 * 1024
private const val COPY_PROGRESS_STEP_BYTES = 32L * 1024L * 1024L
