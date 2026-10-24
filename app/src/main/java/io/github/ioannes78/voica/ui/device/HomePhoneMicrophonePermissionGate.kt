package io.github.ioannes78.voica.ui.device

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ioannes78.voica.PhoneMicrophonePermissionAction
import io.github.ioannes78.voica.PhoneMicrophonePermissionPolicy
import io.github.ioannes78.voica.PhoneMicrophonePermissionRequestStore

internal enum class HomePhoneMicrophoneStartAction {
    RECORDING,
    REALTIME,
}

/**
 * Homepage-only permission presentation. It intentionally reuses the frozen Stage 16A.1 permission
 * policy/store and owns no recording state. Once permission is ready the existing controller remains
 * the only operation entry point.
 */
@Composable
internal fun HomePhoneMicrophonePermissionGate(
    onPermissionReady: (HomePhoneMicrophoneStartAction) -> Unit,
    content: @Composable (
        requestStart: (HomePhoneMicrophoneStartAction) -> Unit,
        message: String?,
    ) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findHomeActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember(context.applicationContext) { PhoneMicrophonePermissionRequestStore(context) }
    val latestOnReady by rememberUpdatedState(onPermissionReady)
    var pendingAction by remember { mutableStateOf<HomePhoneMicrophoneStartAction?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lifecycleTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) lifecycleTick += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAction
            if (granted && action != null) {
                message = null
                pendingAction = null
                latestOnReady(action)
            } else if (!granted) {
                val rationale =
                    activity?.let {
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            it,
                            Manifest.permission.RECORD_AUDIO,
                        )
                    } ?: false
                message =
                    PhoneMicrophonePermissionPolicy.resolve(
                        granted = false,
                        requestedBefore = true,
                        shouldShowRationale = rationale,
                    ).message
            }
        }

    fun launchPermissionRequest() {
        store.markRequested()
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val requestStart: (HomePhoneMicrophoneStartAction) -> Unit = { action ->
        pendingAction = action
        // Reading lifecycleTick makes Settings -> app resume participate in this decision, matching
        // the existing Stage 16A.1 phone pages.
        @Suppress("UNUSED_VARIABLE") val resumedRevision = lifecycleTick
        val granted = PhoneMicrophonePermissionPolicy.hasPermission(context)
        val shouldShowRationale =
            activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.RECORD_AUDIO,
                )
            } ?: false
        val decision =
            PhoneMicrophonePermissionPolicy.resolve(
                granted = granted,
                requestedBefore = store.requestedBefore(),
                shouldShowRationale = shouldShowRationale,
            )
        when (decision.action) {
            PhoneMicrophonePermissionAction.READY -> {
                message = null
                pendingAction = null
                latestOnReady(action)
            }
            PhoneMicrophonePermissionAction.REQUEST_PERMISSION -> {
                message = decision.message
                launchPermissionRequest()
            }
            PhoneMicrophonePermissionAction.SHOW_RATIONALE_AND_REQUEST -> {
                message = decision.message
                showRationale = true
            }
            PhoneMicrophonePermissionAction.OPEN_APP_SETTINGS -> {
                message = decision.message
                context.startActivity(PhoneMicrophonePermissionPolicy.appSettingsIntent(context))
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("允许麦克风") },
            text = { Text("闻知只会在你主动开始手机录音或手机实时转写时使用麦克风。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        launchPermissionRequest()
                    },
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        pendingAction = null
                    },
                ) {
                    Text("暂不")
                }
            },
        )
    }

    content(requestStart, message)
}

private tailrec fun Context.findHomeActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHomeActivity()
        else -> null
    }
