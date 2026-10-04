package io.github.ioannes78.voica.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.database.ContentVersionDeleteResult
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.TranscriptRevisionSpeakerDisplayModeValue
import io.github.ioannes78.voica.database.TranscriptionRevisionEntity
import io.github.ioannes78.voica.database.TranscriptionRevisionParagraphDraft
import io.github.ioannes78.voica.transcript.RevisionParagraphDraft
import io.github.ioannes78.voica.transcript.RevisionTimingQuality
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TranscriptReadingParagraph(
    val stableId: String,
    val text: String,
    val sourceAnchorRefs: List<String>,
    val anchorStartSampleIndex: Long?,
    val anchorEndSampleIndexExclusive: Long?,
    val speakerId: String?,
    val speakerDisplayName: String?,
    val timingQuality: RevisionTimingQuality,
    val isUserModified: Boolean,
)

data class TranscriptContentState(
    val transcriptionId: String? = null,
    val displayName: String? = null,
    val currentRevisionId: String? = null,
    val revisions: List<TranscriptionRevisionEntity> = emptyList(),
    val paragraphs: List<TranscriptReadingParagraph> = emptyList(),
    val loading: Boolean = false,
    val notice: String? = null,
)

class TranscriptContentViewModel(
    private val repository: Stage12CContentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TranscriptContentState())
    val state: StateFlow<TranscriptContentState> = mutableState.asStateFlow()

    private var boundDocument: TranscriptDocument? = null
    private var loadJob: Job? = null
    private var generation = 0L

    fun bind(document: TranscriptDocument?) {
        boundDocument = document
        generation += 1L
        val expectedGeneration = generation
        loadJob?.cancel()
        if (document == null) {
            mutableState.value = TranscriptContentState()
            return
        }
        mutableState.value =
            mutableState.value.copy(
                transcriptionId = document.transcriptionId,
                loading = true,
            )
        loadJob =
            viewModelScope.launch {
                load(document, expectedGeneration)
            }
    }

    fun saveRevision(drafts: List<RevisionParagraphDraft>) {
        val document = boundDocument ?: return
        viewModelScope.launch {
            runCatching {
                repository.createTranscriptionRevision(
                    transcriptionId = document.transcriptionId,
                    paragraphs =
                        drafts.map { draft ->
                            TranscriptionRevisionParagraphDraft(
                                text = draft.text,
                                sourceAnchorRefsJson = encodeAnchorRefs(draft.sourceAnchorRefs),
                                anchorStartSampleIndex = draft.anchorStartSampleIndex,
                                anchorEndSampleIndexExclusive = draft.anchorEndSampleIndexExclusive,
                                speakerId = draft.speakerId,
                                speakerDisplayMode = TranscriptRevisionSpeakerDisplayModeValue.INHERIT,
                                timingQuality = draft.timingQuality.name,
                                isUserModified = draft.isUserModified,
                            )
                        },
                )
            }.onSuccess {
                reload("人工整理稿已保存。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "保存人工整理稿失败",
                    )
            }
        }
    }

    fun restoreModelOriginal() {
        val transcriptionId = boundDocument?.transcriptionId ?: return
        viewModelScope.launch {
            runCatching {
                repository.setCurrentTranscriptionRevision(transcriptionId, null)
            }.onSuccess {
                reload("已恢复模型原文。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "恢复模型原文失败",
                    )
            }
        }
    }

    fun selectRevision(revisionId: String?) {
        val transcriptionId = boundDocument?.transcriptionId ?: return
        viewModelScope.launch {
            runCatching {
                repository.setCurrentTranscriptionRevision(transcriptionId, revisionId)
            }.onSuccess {
                reload(null)
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "切换修订版本失败",
                    )
            }
        }
    }

    fun deleteRevision(revisionId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteTranscriptionRevision(revisionId)
            }.onSuccess { deleted ->
                reload(if (deleted) "修订版本已删除。" else "修订版本不存在。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "删除修订版本失败",
                    )
            }
        }
    }

    fun renameVersion(displayName: String?) {
        val transcriptionId = boundDocument?.transcriptionId ?: return
        viewModelScope.launch {
            runCatching {
                repository.renameTranscriptionVersion(transcriptionId, displayName)
            }.onSuccess {
                reload("转写版本名称已更新。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "重命名失败",
                    )
            }
        }
    }

    fun deleteVersion(
        transcriptionId: String,
        onDeleted: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            when (val outcome = repository.deleteTranscriptionVersion(transcriptionId)) {
                ContentVersionDeleteResult.NotFound ->
                    mutableState.value =
                        mutableState.value.copy(notice = "转写版本不存在。")
                ContentVersionDeleteResult.ActiveTask ->
                    mutableState.value =
                        mutableState.value.copy(
                            notice = "转写任务仍在运行，暂时不能删除。",
                        )
                is ContentVersionDeleteResult.ReferencedByAiSummaries ->
                    mutableState.value =
                        mutableState.value.copy(
                            notice =
                                "该转写仍被 " +
                                    outcome.count +
                                    " 个 AI 总结版本引用，请先删除相关 AI 总结版本。",
                        )
                is ContentVersionDeleteResult.Deleted -> {
                    mutableState.value =
                        TranscriptContentState(notice = "转写版本已删除。")
                    onDeleted(outcome.fallbackVersionId)
                }
            }
        }
    }

    fun clearNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    private fun reload(notice: String?) {
        val document = boundDocument ?: return
        generation += 1L
        val expectedGeneration = generation
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                load(document, expectedGeneration, notice)
            }
    }

    private suspend fun load(
        document: TranscriptDocument,
        expectedGeneration: Long,
        notice: String? = null,
    ) {
        val metadata = repository.loadTranscriptionMetadata(document.transcriptionId)
        val revisions = repository.loadTranscriptionRevisions(document.transcriptionId)
        val effective = repository.loadEffectiveTranscriptParagraphs(document.transcriptionId)
        if (expectedGeneration != generation) return

        val paragraphs =
            if (metadata?.currentRevisionId == null) {
                document.segments.map { segment ->
                    TranscriptReadingParagraph(
                        stableId = segment.stableId,
                        text = segment.text,
                        sourceAnchorRefs =
                            listOf("SEGMENT:" + segment.sourceSegmentId),
                        anchorStartSampleIndex = segment.startSampleIndex,
                        anchorEndSampleIndexExclusive = segment.endSampleIndexExclusive,
                        speakerId = segment.speakerId,
                        speakerDisplayName =
                            segment.speakerDisplayName
                                ?: segment.speakerOrdinal?.let { ordinal ->
                                    "说话人 " + (ordinal + 1)
                                },
                        timingQuality = RevisionTimingQuality.EXACT,
                        isUserModified = false,
                    )
                }
            } else {
                val speakerNames =
                    document.speakers.associate { speaker ->
                        speaker.speakerId to
                            (
                                speaker.displayName?.takeIf { it.isNotBlank() }
                                    ?: "说话人 " + (speaker.speakerOrdinal + 1)
                            )
                    }
                effective.map { paragraph ->
                    TranscriptReadingParagraph(
                        stableId = paragraph.stableId,
                        text = paragraph.text,
                        sourceAnchorRefs = decodeAnchorRefs(paragraph.sourceAnchorRefsJson),
                        anchorStartSampleIndex = paragraph.anchorStartSampleIndex,
                        anchorEndSampleIndexExclusive = paragraph.anchorEndSampleIndexExclusive,
                        speakerId = paragraph.speakerId,
                        speakerDisplayName =
                            paragraph.speakerId?.let(speakerNames::get),
                        timingQuality =
                            runCatching {
                                RevisionTimingQuality.valueOf(paragraph.timingQuality)
                            }.getOrDefault(RevisionTimingQuality.APPROXIMATE),
                        isUserModified = paragraph.isUserModified,
                    )
                }
            }

        mutableState.value =
            TranscriptContentState(
                transcriptionId = document.transcriptionId,
                displayName = metadata?.displayName,
                currentRevisionId = metadata?.currentRevisionId,
                revisions = revisions,
                paragraphs = paragraphs,
                loading = false,
                notice = notice,
            )
    }

    private fun encodeAnchorRefs(refs: List<String>): String =
        refs.distinct()
            .joinToString(prefix = "[", postfix = "]") { ref ->
                "\"" +
                    ref.replace("\\", "\\\\").replace("\"", "\\\"") +
                    "\""
            }

    private fun decodeAnchorRefs(raw: String): List<String> =
        Regex("\"([^\"]+)\"")
            .findAll(raw)
            .map { match -> match.groupValues[1] }
            .toList()

    class Factory(
        private val repository: Stage12CContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TranscriptContentViewModel(repository) as T
    }
}
