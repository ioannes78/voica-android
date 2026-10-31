#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "app/src/main/java/io/github/ioannes78/voica/OfflineSummaryLongRunner.kt"
COORDINATOR = ROOT / "app/src/main/java/io/github/ioannes78/voica/OfflineSummaryCoordinator.kt"
DIAGNOSTICS = ROOT / "app/src/main/java/io/github/ioannes78/voica/Stage173LongSummaryDiagnostics.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one diagnostic injection match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    RUNNER,
    '''    private val mapRenderer: OfflineSummaryMapPromptRenderer = OfflineSummaryMapPromptRenderer(),
    private val reduceRenderer: OfflineSummaryReducePromptRenderer = OfflineSummaryReducePromptRenderer(),
    private val finalRenderer: OfflineSummaryFinalPromptRenderer = OfflineSummaryFinalPromptRenderer(),
) : OfflineSummaryLongRunnerPort {''',
    '''    private val mapRenderer: OfflineSummaryMapPromptRenderer = OfflineSummaryMapPromptRenderer(),
    private val reduceRenderer: OfflineSummaryReducePromptRenderer = OfflineSummaryReducePromptRenderer(),
    private val finalRenderer: OfflineSummaryFinalPromptRenderer = OfflineSummaryFinalPromptRenderer(),
    private val diagnostics: OfflineSummaryLongDiagnosticRecorder = NoOpOfflineSummaryLongDiagnosticRecorder,
) : OfflineSummaryLongRunnerPort {''',
)

replace_once(
    RUNNER,
    '''        onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.ANALYZING, "正在规划长文本分段"))
        val plan = chunkPlanner.plan(input)
        store.create(summaryId, input, plan, directPromptEstimatedTokens)''',
    '''        onProgress(OfflineSummaryLongProgressUpdate(OfflineSummaryRunPhase.ANALYZING, "正在规划长文本分段"))
        val plan = chunkPlanner.plan(input)
        resetDiagnostic(summaryId, buildPlanDiagnostic(input, plan, directPromptEstimatedTokens))
        store.create(summaryId, input, plan, directPromptEstimatedTokens)''',
)

replace_once(
    RUNNER,
    '''        val plan = chunkPlanner.plan(input)
        val snapshot = store.snapshot(summaryId)
            ?: throw OfflineSummaryCoordinatorException(''',
    '''        val plan = chunkPlanner.plan(input)
        recordDiagnostic(
            summaryId,
            "RESUME",
            "explicit user resume; plannedChunks=${plan.chunks.size}; inputDigest=${plan.inputContentDigest}",
        )
        val snapshot = store.snapshot(summaryId)
            ?: throw OfflineSummaryCoordinatorException(''',
)

replace_once(
    RUNNER,
    '''            store.markNodeRunning(node.id)
            try {
                val generated =
                    generateValidated(
                        prompt = mapRenderer.render(chunk.fragments),
                        preparedIdentity = preparedIdentity,
                        finalResult = false,
                    )
                anyRepair = anyRepair || generated.repairAttempted
                store.checkpointIntermediate(node.id, generated.text)''',
    '''            store.markNodeRunning(node.id)
            try {
                val renderedPrompt = mapRenderer.render(chunk.fragments)
                recordDiagnostic(
                    summaryId,
                    "MAP ${index + 1}/${plan.chunks.size} INPUT",
                    renderedPrompt.text,
                )
                val generated =
                    generateValidated(
                        prompt = renderedPrompt,
                        preparedIdentity = preparedIdentity,
                        finalResult = false,
                    )
                recordDiagnostic(summaryId, "MAP ${index + 1}/${plan.chunks.size} OUTPUT", generated.text)
                anyRepair = anyRepair || generated.repairAttempted
                store.checkpointIntermediate(node.id, generated.text)''',
)

replace_once(
    RUNNER,
    '''                store.markNodeRunning(node.id)
                try {
                    val generated =
                        generateValidated(
                            prompt = reduceRenderer.render(group.nodes),
                            preparedIdentity = preparedIdentity,
                            finalResult = false,
                        )
                    anyRepair = anyRepair || generated.repairAttempted
                    store.checkpointIntermediate(node.id, generated.text)''',
    '''                store.markNodeRunning(node.id)
                try {
                    val renderedPrompt = reduceRenderer.render(group.nodes)
                    recordDiagnostic(
                        summaryId,
                        "REDUCE level=$level ${index + 1}/${groups.size} INPUT",
                        renderedPrompt.text,
                    )
                    val generated =
                        generateValidated(
                            prompt = renderedPrompt,
                            preparedIdentity = preparedIdentity,
                            finalResult = false,
                        )
                    recordDiagnostic(
                        summaryId,
                        "REDUCE level=$level ${index + 1}/${groups.size} OUTPUT",
                        generated.text,
                    )
                    anyRepair = anyRepair || generated.repairAttempted
                    store.checkpointIntermediate(node.id, generated.text)''',
)

replace_once(
    RUNNER,
    '''        store.markNodeRunning(finalNode.id)
        try {
            val generated =
                generateValidated(
                    prompt = finalRenderer.render(current),
                    preparedIdentity = preparedIdentity,
                    finalResult = true,
                )
            val allowedEvidenceRefs = current.flatMapTo(LinkedHashSet()) { it.evidenceRefs }
            val result = SummaryResultCodec.decode(generated.text, allowedEvidenceRefs)
            val evidence = OfflineSummaryEvidenceMapper.map(input, result)
            val cleanupWarning = withContext(NonCancellable) { store.complete(summaryId, result, evidence) }
            return OfflineSummaryLongRunResult(
                finalGeneration = generated.generation,
                repairAttempted = inheritedRepairAttempted || generated.repairAttempted,
                warning = cleanupWarning?.let { "总结已保存，但长文本检查点清理待启动恢复：${sanitize(it)}" },
            )''',
    '''        store.markNodeRunning(finalNode.id)
        try {
            val renderedPrompt = finalRenderer.render(current)
            recordDiagnostic(summaryId, "FINAL INPUT", renderedPrompt.text)
            val generated =
                generateValidated(
                    prompt = renderedPrompt,
                    preparedIdentity = preparedIdentity,
                    finalResult = true,
                )
            recordDiagnostic(summaryId, "FINAL OUTPUT", generated.text)
            val allowedEvidenceRefs = current.flatMapTo(LinkedHashSet()) { it.evidenceRefs }
            val result = SummaryResultCodec.decode(generated.text, allowedEvidenceRefs)
            val evidence = OfflineSummaryEvidenceMapper.map(input, result)
            val diagnosticLocation = publishDiagnostic(summaryId)
            val cleanupWarning = withContext(NonCancellable) { store.complete(summaryId, result, evidence) }
            val warnings =
                buildList {
                    cleanupWarning?.let {
                        add("总结已保存，但长文本检查点清理待启动恢复：${sanitize(it)}")
                    }
                    diagnosticLocation?.let { add("QA 长文本诊断已导出：$it") }
                }
            return OfflineSummaryLongRunResult(
                finalGeneration = generated.generation,
                repairAttempted = inheritedRepairAttempted || generated.repairAttempted,
                warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("；"),
            )''',
)

replace_once(
    RUNNER,
    '''    private fun sanitize(message: String?): String? =
        message?.replace(Regex("\\\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(180)

    private fun resumeException(error: OfflineSummaryLongPersistenceException): OfflineSummaryCoordinatorException {''',
    '''    private fun sanitize(message: String?): String? =
        message?.replace(Regex("\\\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(180)

    private suspend fun resetDiagnostic(summaryId: String, header: String) {
        try {
            diagnostics.reset(summaryId, header)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // QA observability must never change product inference behavior.
        }
    }

    private suspend fun recordDiagnostic(summaryId: String, section: String, body: String) {
        try {
            diagnostics.append(summaryId, section, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // QA observability must never change product inference behavior.
        }
    }

    private suspend fun publishDiagnostic(summaryId: String): String? =
        try {
            diagnostics.publish(summaryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

    private fun buildPlanDiagnostic(
        input: StructuredTranscriptInput,
        plan: OfflineSummaryLongPlan,
        directPromptEstimatedTokens: Int,
    ): String =
        buildString {
            appendLine("pipelineVersion=${plan.pipelineVersion}")
            appendLine("plannerVersion=${plan.plannerVersion}")
            appendLine("recordingId=${input.recordingId}")
            appendLine("inputDigest=${plan.inputContentDigest}")
            appendLine("sourceUnitCount=${input.units.size}")
            appendLine("directPromptEstimatedTokens=$directPromptEstimatedTokens")
            appendLine("plannedChunks=${plan.chunks.size}")
            plan.chunks.forEach { chunk ->
                appendLine(
                    "chunk=${chunk.index + 1}/${plan.chunks.size} sourceUnits=${chunk.sourceUnitStartOrdinal + 1}..${chunk.sourceUnitEndOrdinalExclusive} fragments=${chunk.fragments.size} estimatedPromptTokens=${chunk.estimatedPromptTokens}",
                )
                appendLine("chunkSourceRefs=${chunk.fragments.joinToString(",") { it.sourceRef }}")
            }
        }

    private fun resumeException(error: OfflineSummaryLongPersistenceException): OfflineSummaryCoordinatorException {''',
)

replace_once(
    COORDINATOR,
    '''            generateLocal = generateLocal,
        )
    return OfflineSummaryCoordinator(''',
    '''            generateLocal = generateLocal,
            diagnostics = Stage173FileOfflineSummaryLongDiagnosticRecorder(application),
        )
    return OfflineSummaryCoordinator(''',
)

DIAGNOSTICS.write_text(
    r'''package io.github.ioannes78.voica

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Temporary Stage 17.3 QA recorder used only to isolate long-summary semantic coverage loss.
 * It does not participate in routing, inference, validation, persistence, resume or cleanup truth.
 */
internal interface OfflineSummaryLongDiagnosticRecorder {
    suspend fun reset(summaryId: String, header: String)

    suspend fun append(summaryId: String, section: String, body: String)

    /** Returns a user-visible Downloads path when export succeeds. */
    suspend fun publish(summaryId: String): String?
}

internal object NoOpOfflineSummaryLongDiagnosticRecorder : OfflineSummaryLongDiagnosticRecorder {
    override suspend fun reset(summaryId: String, header: String) = Unit

    override suspend fun append(summaryId: String, section: String, body: String) = Unit

    override suspend fun publish(summaryId: String): String? = null
}

internal class Stage173FileOfflineSummaryLongDiagnosticRecorder(
    context: Context,
) : OfflineSummaryLongDiagnosticRecorder {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val directory = File(appContext.filesDir, "stage17_3_quality_diagnostics")

    override suspend fun reset(summaryId: String, header: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                directory.mkdirs()
                diagnosticFile(summaryId).writeText(
                    buildString {
                        appendLine("# WENZHI Stage 17.3 Long Summary Quality Diagnostic")
                        appendLine("formatVersion=1")
                        appendLine("createdAtEpochMs=${System.currentTimeMillis()}")
                        appendLine("summaryId=$summaryId")
                        appendLine()
                        append(header.trimEnd())
                        appendLine()
                    },
                )
            }
        }
    }

    override suspend fun append(summaryId: String, section: String, body: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                directory.mkdirs()
                val target = diagnosticFile(summaryId)
                if (!target.exists()) {
                    target.writeText(
                        "# WENZHI Stage 17.3 Long Summary Quality Diagnostic\n" +
                            "formatVersion=1\nsummaryId=$summaryId\n",
                    )
                }
                target.appendText(
                    buildString {
                        appendLine()
                        appendLine("===== ${section.trim()} =====")
                        append(body.trimEnd())
                        appendLine()
                    },
                )
            }
        }
    }

    override suspend fun publish(summaryId: String): String? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            val source = diagnosticFile(summaryId)
            if (!source.isFile || source.length() == 0L) return@withContext null

            val resolver = appContext.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/WENZHI/"
            val displayName =
                "WENZHI-Stage17.3-LongSummary-Diagnostic-${System.currentTimeMillis()}.txt"
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("cannot create Stage 17.3 diagnostic download")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("cannot open Stage 17.3 diagnostic download")
                val completed =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                resolver.update(uri, completed, null, null)
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
            "$relativePath$displayName"
        }

    private fun diagnosticFile(summaryId: String): File {
        val safeId = summaryId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return File(directory, "$safeId.txt")
    }
}
''',
    encoding="utf-8",
)

print("Stage 17.3 quality diagnostic instrumentation applied deterministically.")
