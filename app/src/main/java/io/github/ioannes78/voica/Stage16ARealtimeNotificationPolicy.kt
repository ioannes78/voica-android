package io.github.ioannes78.voica

internal data class Stage16ARealtimeNotificationProjection(
    val phase: Stage16ARealtimeUiPhase,
    val modelLabel: String?,
)

/**
 * Projects Stage 16A product state onto the already-owned connected-device foreground notification.
 *
 * Text hypotheses are deliberately excluded from the projection. Callers can therefore apply
 * distinctUntilChanged() and avoid PARTIAL/STABLE inference churn becoming notification churn.
 */
internal object Stage16ARealtimeNotificationPolicy {
    fun project(state: Stage16ARealtimeUiState): Stage16ARealtimeNotificationProjection =
        Stage16ARealtimeNotificationProjection(
            phase = state.phase,
            modelLabel = state.modelLabel?.trim()?.takeIf { it.isNotEmpty() },
        )

    fun opensRealtime(realtime: Stage16ARealtimeNotificationProjection?): Boolean =
        when (realtime?.phase) {
            Stage16ARealtimeUiPhase.PREPARING,
            Stage16ARealtimeUiPhase.STARTING,
            Stage16ARealtimeUiPhase.ACTIVE,
            Stage16ARealtimeUiPhase.PAUSED,
            Stage16ARealtimeUiPhase.RESUMING,
            Stage16ARealtimeUiPhase.FINALIZING,
            Stage16ARealtimeUiPhase.INTERRUPTED,
            -> true

            Stage16ARealtimeUiPhase.IDLE,
            Stage16ARealtimeUiPhase.COMPLETED,
            Stage16ARealtimeUiPhase.FAILED,
            null,
            -> false
        }

    fun label(
        baseDeviceLabel: String,
        realtime: Stage16ARealtimeNotificationProjection?,
        downloadActive: Boolean,
    ): String {
        if (downloadActive || realtime == null) return baseDeviceLabel

        return when (realtime.phase) {
            Stage16ARealtimeUiPhase.PREPARING -> withModel("正在准备实时转写", realtime.modelLabel)
            Stage16ARealtimeUiPhase.STARTING -> withModel("正在启动实时转写", realtime.modelLabel)
            Stage16ARealtimeUiPhase.ACTIVE -> withModel("正在实时转写", realtime.modelLabel)
            Stage16ARealtimeUiPhase.PAUSED -> "实时转写已暂停"
            Stage16ARealtimeUiPhase.RESUMING -> "正在继续实时转写"
            Stage16ARealtimeUiPhase.FINALIZING -> "正在完成最后内容…"
            Stage16ARealtimeUiPhase.INTERRUPTED -> interruptedLabel(baseDeviceLabel)
            Stage16ARealtimeUiPhase.IDLE,
            Stage16ARealtimeUiPhase.COMPLETED,
            Stage16ARealtimeUiPhase.FAILED,
            -> baseDeviceLabel
        }
    }

    private fun withModel(label: String, modelLabel: String?): String =
        if (modelLabel.isNullOrBlank()) label else "$label · $modelLabel"

    private fun interruptedLabel(baseDeviceLabel: String): String =
        when {
            baseDeviceLabel.startsWith("正在重连") -> baseDeviceLabel
            baseDeviceLabel.startsWith("等待设备重新连接") -> baseDeviceLabel
            baseDeviceLabel.startsWith("正在连接") -> baseDeviceLabel
            else -> "连接已中断 · 等待恢复"
        }
}
