package io.github.ioannes78.voica.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.OfflineQaOutcome
import io.github.ioannes78.voica.ai.*
import io.github.ioannes78.voica.database.OfflineQaSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OfflineQaScreen(
    recordingName: String,
    session: OfflineQaSession,
    requireCurrent: suspend (OfflineQaSourceIdentity) -> OfflineQaSnapshot,
    onBack: () -> Unit,
    evidencePlayback: OfflineQaEvidencePlaybackCoordinator,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember(session) { mutableStateOf("") }
    var copyNotice by remember(session) { mutableStateOf<String?>(null) }
    var copyBusy by remember(session) { mutableStateOf(false) }
    var clearDialog by remember(session) { mutableStateOf(false) }
    var playbackBusy by remember(session) { mutableStateOf(false) }
    var playbackNotice by remember(session) { mutableStateOf<String?>(null) }
    var playbackJob by remember(session) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val listState = rememberLazyListState()
    val ready = state.phase == OfflineQaSessionPhase.READY && !playbackBusy
    val active = state.phase == OfflineQaSessionPhase.RUNNING || state.phase == OfflineQaSessionPhase.CANCELLING
    val validInput = input.isNotBlank() && input.trim().codePointCount(0, input.trim().length) <= OfflineQaRequest.MAX_QUESTION_CODE_POINTS

    LaunchedEffect(session) { session.refresh() }
    DisposableEffect(session) { onDispose { playbackJob?.cancel(); session.close() } }
    LaunchedEffect(state.turns.size, state.pendingQuestion, state.turns.lastOrNull()) {
        if (state.turns.isNotEmpty() || state.pendingQuestion != null) {
            val index = state.turns.size + if (state.pendingQuestion != null) 0 else -1
            if (index >= 0) listState.scrollToItem(index)
        }
    }
    fun submit(text: String) {
        if (!playbackBusy && session.submit(text)) { input = ""; copyNotice = null; playbackNotice = null }
    }
    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回转写") }
            Column(Modifier.weight(1f)) {
                Text("离线问答", style = MaterialTheme.typography.titleLarge)
                Text(recordingName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { clearDialog = true }, enabled = state.turns.isNotEmpty() || active) { Text("清空") }
        }
        Text("本地 AI · 仅依据当前转写", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.turns.isEmpty() && state.pendingQuestion == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("关于这段录音，你想了解什么？", style = MaterialTheme.typography.titleMedium)
                        Text("回答受本次检索证据限制，并非全文穷尽。", style = MaterialTheme.typography.bodySmall)
                        if (ready) listOf("这次会议讨论了哪些主要事项？", "有哪些需要跟进的工作？",
                            "会议中涉及哪些关键数据？", "各项任务分别由谁负责？").forEach { question ->
                            OutlinedButton(onClick = { submit(question) }, modifier = Modifier.fillMaxWidth()) { Text(question) }
                        }
                    }
                }
            }
            items(state.turns) { turn ->
                QaQuestion(turn.question)
                when (val outcome = turn.outcome) {
                    is OfflineQaOutcome.Answer -> QaAnswer(outcome.result, copyEnabled = ready && !copyBusy,
                        playbackEnabled = ready && !copyBusy, onPlay = { ref ->
                            if (!playbackBusy) {
                                playbackBusy = true
                                playbackNotice = "正在核验原声证据…"
                                playbackJob = scope.launch {
                                    try {
                                        playbackNotice = when (evidencePlayback.play(turn, ref) { session.state.value }) {
                                            QaEvidencePlayOutcome.STARTED -> "已定位原声证据，可在播放器控制"
                                            QaEvidencePlayOutcome.BUSY -> "正在处理其他回听请求"
                                            QaEvidencePlayOutcome.STALE -> {
                                                session.invalidate()
                                                "证据来源已变化，请重新加载"
                                            }
                                            QaEvidencePlayOutcome.REJECTED -> "原声校验或播放准入失败，未启动回听"
                                        }
                                    } finally { playbackBusy = false }
                                }
                            }
                        }, onCopy = {
                        copyBusy = true
                        scope.launch {
                            try {
                                val expected = outcome.result.context.request.source
                                val fresh = requireCurrent(expected)
                                val current = session.state.value
                                if (fresh.source != expected || current.source != expected ||
                                    current.phase != OfflineQaSessionPhase.READY ||
                                    current.turns.none { it === turn }) {
                                    copyNotice = "会话已变化，未复制"
                                    return@launch
                                }
                                val text = offlineQaCopyText(outcome.result)
                                if (text.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("闻知离线问答", text))
                                    copyNotice = "已复制回答"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: OfflineQaSourceException) { session.invalidate(); copyNotice = "来源已变化，未复制" }
                            catch (_: Exception) { copyNotice = "复制失败" }
                            finally { copyBusy = false }
                        }
                    })
                    is OfflineQaOutcome.Failed -> Text(offlineQaFailureMessage(outcome.code), color = MaterialTheme.colorScheme.error)
                }
            }
            state.pendingQuestion?.let { question ->
                item {
                    QaQuestion(question)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("正在检索并生成回答…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        when (state.phase) {
            OfflineQaSessionPhase.LOADING -> Text("正在核验当前转写…")
            OfflineQaSessionPhase.CANCELLING -> Text("正在停止并清理任务…")
            OfflineQaSessionPhase.UNAVAILABLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(offlineQaFailureMessage(state.failure ?: OfflineQaFailureCode.SOURCE_UNAVAILABLE), Modifier.weight(1f))
                TextButton(onClick = { session.refresh() }) { Text("重新加载") }
            }
            else -> if (state.failure == OfflineQaFailureCode.CANCELLED) Text("已取消")
        }
        copyNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        playbackNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (playbackBusy) TextButton(onClick = { playbackJob?.cancel(); playbackNotice = "已取消回听请求" }) { Text("取消回听") }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = {
                if (it.codePointCount(0, it.length) <= OfflineQaRequest.MAX_QUESTION_CODE_POINTS) input = it
            }, modifier = Modifier.weight(1f), enabled = ready, placeholder = { Text("针对这段录音提问…") },
                maxLines = 4, supportingText = { Text("${input.codePointCount(0, input.length)}/512") })
            if (active) OutlinedButton(onClick = session::cancel, enabled = state.phase == OfflineQaSessionPhase.RUNNING) { Text("取消") }
            else Button(onClick = { submit(input) }, enabled = ready && validInput) { Text("发送") }
        }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false },
        title = { Text("清空问答？") }, text = { Text("当前页面的问答历史将被清除。") },
        confirmButton = { TextButton(onClick = { playbackJob?.cancel(); session.clear(); input = ""; copyNotice = null; playbackNotice = null; clearDialog = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("保留") } })
}

@Composable
private fun QaQuestion(question: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.86f)) {
            Text(question, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun QaAnswer(result: OfflineQaResult, copyEnabled: Boolean, playbackEnabled: Boolean, onPlay: (String) -> Unit, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("闻知", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (result.claims.isNotEmpty()) TextButton(onClick = onCopy, enabled = copyEnabled) { Text("复制") }
        }
        when (result.status) {
            OfflineQaStatus.INSUFFICIENT_EVIDENCE -> Text("当前检索证据不足，无法可靠回答。")
            OfflineQaStatus.PARTIAL -> Text("部分回答 · 其余内容证据不足", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfflineQaStatus.ANSWERED -> Unit
        }
        result.claims.forEach { claim ->
            Text(claim.text, style = MaterialTheme.typography.bodyLarge)
            claim.evidenceIds.forEach { ref ->
                val evidence = result.context.candidates.firstOrNull { it.anchor.ref == ref } ?: return@forEach
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${qaTime(evidence.anchor.startSampleIndex)} – ${qaTime(evidence.anchor.endSampleIndexExclusive)} · $ref",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        val speaker = if (evidence.anchor.ambiguous || evidence.anchor.overlap) "说话人归属不确定"
                            else evidence.speakerDisplayName ?: evidence.speakerId ?: "未知说话人"
                        Text(speaker, style = MaterialTheme.typography.labelSmall)
                        Text(evidence.excerpt, style = MaterialTheme.typography.bodySmall)
                        if (evidence.isManualRevision) Text("人工修订文本 · 非原声逐字证明", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { onPlay(ref) }, enabled = playbackEnabled) { Text("回听原声") }
                    }
                }
            }
        }
    }
}

internal fun qaTime(sample: Long): String {
    val seconds = sample.coerceAtLeast(0) / 16000L
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}

internal fun offlineQaCopyText(result: OfflineQaResult): String =
    result.claims.joinToString("\n") { claim -> claim.text + " [" + claim.evidenceIds.joinToString(", ") + "]" }
        .let { if (it.isNotEmpty() && result.status == OfflineQaStatus.PARTIAL) "部分回答（其余证据不足）\n" + it else it }

internal fun offlineQaFailureMessage(code: OfflineQaFailureCode): String = when (code) {
    OfflineQaFailureCode.SOURCE_CHANGED -> "当前转写已变化，请重新加载。"
    OfflineQaFailureCode.SOURCE_UNAVAILABLE -> "当前转写或音频来源不可用。"
    OfflineQaFailureCode.INVALID_OUTPUT, OfflineQaFailureCode.INVALID_EVIDENCE -> "回答未通过证据校验，请重新提问。"
    OfflineQaFailureCode.INPUT_TOO_LARGE -> "证据超出本地模型预算，请缩小问题范围。"
    OfflineQaFailureCode.MODEL_NOT_INSTALLED -> "请先在设置中安装本地 AI 模型。"
    OfflineQaFailureCode.MODEL_NOT_READY -> "当前本地 AI 模型不可用。"
    OfflineQaFailureCode.RESOURCE_BLOCKED -> "录音、转写或设备资源正在使用，请稍后重试。"
    OfflineQaFailureCode.RUNTIME_BUSY -> "本地 AI 任务正在运行，请完成后再试。"
    OfflineQaFailureCode.RUNTIME_POISONED -> "本地 AI 运行状态异常，请重启应用。"
    OfflineQaFailureCode.GENERATION_FAILED -> "本次问答失败，请重试。"
    OfflineQaFailureCode.CANCELLED -> "已取消。"
}
