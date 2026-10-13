package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Test

class Stage16ARealtimeNotificationPolicyTest {
    @Test
    fun `active realtime overrides recording label and includes frozen model`() {
        assertEquals(
            "正在实时转写 · Small Bilingual",
            Stage16ARealtimeNotificationPolicy.label(
                baseDeviceLabel = "正在录音",
                realtime = projection(Stage16ARealtimeUiPhase.ACTIVE, "Small Bilingual"),
                downloadActive = false,
            ),
        )
    }

    @Test
    fun `download remains highest priority explicit device operation`() {
        assertEquals(
            "正在下载 · 42%",
            Stage16ARealtimeNotificationPolicy.label(
                baseDeviceLabel = "正在下载 · 42%",
                realtime = projection(Stage16ARealtimeUiPhase.ACTIVE, "Small Bilingual"),
                downloadActive = true,
            ),
        )
    }

    @Test
    fun `realtime lifecycle maps to stable low frequency notification labels`() {
        assertEquals(
            "实时转写已暂停",
            Stage16ARealtimeNotificationPolicy.label(
                "录音已暂停",
                projection(Stage16ARealtimeUiPhase.PAUSED),
                downloadActive = false,
            ),
        )
        assertEquals(
            "正在继续实时转写",
            Stage16ARealtimeNotificationPolicy.label(
                "正在录音",
                projection(Stage16ARealtimeUiPhase.RESUMING),
                downloadActive = false,
            ),
        )
        assertEquals(
            "正在完成最后内容…",
            Stage16ARealtimeNotificationPolicy.label(
                "正在录音",
                projection(Stage16ARealtimeUiPhase.FINALIZING),
                downloadActive = false,
            ),
        )
    }

    @Test
    fun `interrupted realtime preserves explicit reconnect state`() {
        assertEquals(
            "正在重连 · 第 2 次",
            Stage16ARealtimeNotificationPolicy.label(
                "正在重连 · 第 2 次",
                projection(Stage16ARealtimeUiPhase.INTERRUPTED),
                downloadActive = false,
            ),
        )
        assertEquals(
            "连接已中断 · 等待恢复",
            Stage16ARealtimeNotificationPolicy.label(
                "正在录音",
                projection(Stage16ARealtimeUiPhase.INTERRUPTED),
                downloadActive = false,
            ),
        )
    }

    @Test
    fun `terminal realtime restores base device label`() {
        listOf(
            Stage16ARealtimeUiPhase.IDLE,
            Stage16ARealtimeUiPhase.COMPLETED,
            Stage16ARealtimeUiPhase.FAILED,
        ).forEach { phase ->
            assertEquals(
                "已连接",
                Stage16ARealtimeNotificationPolicy.label(
                    "已连接",
                    projection(phase, "Small Bilingual"),
                    downloadActive = false,
                ),
            )
        }
    }

    @Test
    fun `projection ignores hypothesis and waveform churn`() {
        val first =
            Stage16ARealtimeNotificationPolicy.project(
                Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.ACTIVE,
                    modelLabel = " Small Bilingual ",
                    partialText = "第一版 partial",
                    audioActivityLevels = listOf(0.1f),
                ),
            )
        val second =
            Stage16ARealtimeNotificationPolicy.project(
                Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.ACTIVE,
                    modelLabel = "Small Bilingual",
                    stableText = "稳定文本",
                    partialText = "完全不同的 partial",
                    audioActivityLevels = listOf(0.8f, 0.4f),
                ),
            )

        assertEquals(first, second)
    }

    private fun projection(
        phase: Stage16ARealtimeUiPhase,
        modelLabel: String? = null,
    ) = Stage16ARealtimeNotificationProjection(
        phase = phase,
        modelLabel = modelLabel,
    )
}
