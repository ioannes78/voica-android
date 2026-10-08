package io.github.ioannes78.voica

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * QA-only export surface for Stage 13C benchmark reports.
 *
 * The production app never writes these diagnostics to public storage. QA builds export the most
 * recent settled report to Downloads/Voica/Diagnostics so a real-device run can be attached to the
 * Stage 13C benchmark record without adb or access to app-private storage.
 */
class DiarizationBenchmarkExportController(
    private val application: Application,
    private val runner: DiarizationBenchmarkRunner,
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            if (BuildConfig.BUILD_TYPE != QA_BUILD_TYPE) return@launch
            runner.latestReport
                .filterNotNull()
                .collectLatest { report ->
                    // Auto alignment normally follows diarization immediately. Debouncing lets the
                    // alignment-enriched report replace the terminal diarization snapshot instead
                    // of producing two public files for one benchmark id.
                    delay(EXPORT_SETTLE_DELAY_MS)
                    withContext(Dispatchers.IO) {
                        runCatching { export(report) }
                            .onSuccess { location ->
                                Log.i(TAG, "Stage 13C benchmark exported: $location")
                            }
                            .onFailure { error ->
                                Log.w(TAG, "Failed to export Stage 13C benchmark", error)
                            }
                    }
                }
        }

    private fun export(report: DiarizationBenchmarkReport): String {
        val fileName = "diarization-benchmark-${report.benchmarkId}.json"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(fileName, report.toJson())
        } else {
            exportToAppExternalDownloads(fileName, report.toJson())
        }
    }

    private fun exportWithMediaStore(
        fileName: String,
        json: String,
    ): String {
        val resolver = application.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Voica/Diagnostics/"

        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
            arrayOf(fileName, relativePath),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                resolver.delete(
                    android.content.ContentUris.withAppendedId(collection, id),
                    null,
                    null,
                )
            }
        }

        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, JSON_MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { writer ->
                writer.write(json)
            } ?: error("MediaStore output stream unavailable")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return relativePath + fileName
    }

    @Suppress("DEPRECATION")
    private fun exportToAppExternalDownloads(
        fileName: String,
        json: String,
    ): String {
        val root =
            application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("external downloads directory unavailable")
        val directory = File(root, "Voica/Diagnostics").apply { mkdirs() }
        val destination = File(directory, fileName)
        destination.writeText(json)
        return destination.absolutePath
    }

    private companion object {
        const val TAG = "DiarizationProfile"
        const val QA_BUILD_TYPE = "qa"
        const val JSON_MIME_TYPE = "application/json"
        const val EXPORT_SETTLE_DELAY_MS = 1_500L
    }
}
