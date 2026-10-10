package io.github.ioannes78.voica

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExportDestinationSnapshot(
    val treeUri: Uri?,
    val label: String,
) {
    val custom: Boolean
        get() = treeUri != null
}

data class LastExportedFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val locationLabel: String,
)

class ExportDestinationStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _destination = MutableStateFlow(readDestination())
    val destination: StateFlow<ExportDestinationSnapshot> = _destination.asStateFlow()

    private val _feedbackEnabled =
        MutableStateFlow(preferences.getBoolean(KEY_FEEDBACK_ENABLED, true))
    val feedbackEnabled: StateFlow<Boolean> = _feedbackEnabled.asStateFlow()

    private val _lastExport = MutableStateFlow(readLastExport())
    val lastExport: StateFlow<LastExportedFile?> = _lastExport.asStateFlow()

    fun current(): ExportDestinationSnapshot = readDestination()

    fun currentFeedbackEnabled(): Boolean =
        preferences.getBoolean(KEY_FEEDBACK_ENABLED, true)

    fun setFeedbackEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FEEDBACK_ENABLED, enabled).apply()
        _feedbackEnabled.value = enabled
    }

    fun saveCustomTree(
        treeUri: Uri,
        label: String = resolveTreeLabel(treeUri),
    ) {
        preferences.edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .putString(KEY_TREE_LABEL, label.ifBlank { CUSTOM_FALLBACK_LABEL })
            .apply()
        _destination.value = readDestination()
    }

    fun resetDefault() {
        preferences.edit()
            .remove(KEY_TREE_URI)
            .remove(KEY_TREE_LABEL)
            .apply()
        _destination.value = readDestination()
    }

    fun resolveTreeLabel(treeUri: Uri): String {
        return runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: CUSTOM_FALLBACK_LABEL
    }

    fun recordLastExport(
        uri: Uri,
        displayName: String,
        mimeType: String,
        locationLabel: String,
    ) {
        preferences.edit()
            .putString(KEY_LAST_URI, uri.toString())
            .putString(KEY_LAST_NAME, displayName)
            .putString(KEY_LAST_MIME, mimeType)
            .putString(KEY_LAST_LOCATION, locationLabel)
            .apply()
        _lastExport.value = readLastExport()
    }

    private fun readDestination(): ExportDestinationSnapshot {
        val uri =
            preferences.getString(KEY_TREE_URI, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
        val label =
            if (uri == null) {
                DEFAULT_DISPLAY_LABEL
            } else {
                preferences.getString(KEY_TREE_LABEL, null)
                    ?.takeIf { it.isNotBlank() }
                    ?: CUSTOM_FALLBACK_LABEL
            }
        return ExportDestinationSnapshot(uri, label)
    }

    private fun readLastExport(): LastExportedFile? {
        val uri = preferences.getString(KEY_LAST_URI, null)?.let(Uri::parse) ?: return null
        val name = preferences.getString(KEY_LAST_NAME, null) ?: return null
        val mime = preferences.getString(KEY_LAST_MIME, null) ?: return null
        val location = preferences.getString(KEY_LAST_LOCATION, null) ?: DEFAULT_SHORT_LABEL
        return LastExportedFile(uri, name, mime, location)
    }

    companion object {
        const val DEFAULT_DISPLAY_LABEL = "系统下载 / Voica"
        const val DEFAULT_SHORT_LABEL = "下载/Voica"
        private const val CUSTOM_FALLBACK_LABEL = "自定义文件夹"
        private const val PREFERENCES_NAME = "export-destination"
        private const val KEY_TREE_URI = "tree-uri"
        private const val KEY_TREE_LABEL = "tree-label"
        private const val KEY_FEEDBACK_ENABLED = "feedback-enabled"
        private const val KEY_LAST_URI = "last-uri"
        private const val KEY_LAST_NAME = "last-name"
        private const val KEY_LAST_MIME = "last-mime"
        private const val KEY_LAST_LOCATION = "last-location"
    }
}

data class ExportFeedbackEvent(
    val message: String,
    val success: Boolean,
    val destinationUri: Uri? = null,
    val mimeType: String? = null,
)

object ExportFeedbackBus {
    private val mutableEvents =
        MutableSharedFlow<ExportFeedbackEvent>(
            extraBufferCapacity = 8,
        )
    val events: SharedFlow<ExportFeedbackEvent> = mutableEvents.asSharedFlow()

    fun publish(event: ExportFeedbackEvent) {
        mutableEvents.tryEmit(event)
    }
}

fun publishExportSuccess(
    context: Context,
    displayName: String,
    destinationUri: Uri?,
    mimeType: String,
    locationLabel: String,
) {
    if (destinationUri != null) {
        ExportDestinationStore(context).recordLastExport(
            uri = destinationUri,
            displayName = displayName,
            mimeType = mimeType,
            locationLabel = locationLabel,
        )
    }
    ExportFeedbackBus.publish(
        ExportFeedbackEvent(
            message = "已导出：$displayName · $locationLabel",
            success = true,
            destinationUri = destinationUri,
            mimeType = mimeType,
        ),
    )
}

fun publishExportFailure(message: String) {
    ExportFeedbackBus.publish(
        ExportFeedbackEvent(
            message = if (message.startsWith("导出失败")) message else "导出失败：$message",
            success = false,
        ),
    )
}

fun openExportedFile(
    context: Context,
    uri: Uri,
    mimeType: String,
): Boolean =
    runCatching {
        val viewIntent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(viewIntent, "打开已导出文件"))
        true
    }.getOrDefault(false)

@Composable
fun ExportFeedbackHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val destinationStore = remember(context) { ExportDestinationStore(context) }
    val feedbackEnabled by destinationStore.feedbackEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedbackEnabled) {
        ExportFeedbackBus.events.collect { event ->
            if (!event.success || destinationStore.currentFeedbackEnabled()) {
                val canOpen =
                    event.success && event.destinationUri != null && event.mimeType != null
                val result =
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = if (canOpen) "打开" else null,
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed && canOpen) {
                    val opened =
                        openExportedFile(
                            context,
                            requireNotNull(event.destinationUri),
                            requireNotNull(event.mimeType),
                        )
                    if (!opened) {
                        snackbarHostState.showSnackbar(
                            message = "无法打开已导出文件",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 76.dp),
        )
    }
}
