package io.github.ioannes78.voica.ui.realtime

import io.github.ioannes78.voica.Stage16ARealtimeFinalText
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeMiniLiveBarTest {
    @Test
    fun `mini live bar is visible only while a logical realtime run is still alive`() {
        listOf(
            Stage16ARealtimeUiPhase.PREPARING,
            Stage16ARealtimeUiPhase.STARTING,
            Stage16ARealtimeUiPhase.ACTIVE,
            Stage16ARealtimeUiPhase.PAUSED,
            Stage16ARealtimeUiPhase.RESUMING,
            Stage16ARealtimeUiPhase.FINALIZING,
            Stage16ARealtimeUiPhase.INTERRUPTED,
        ).forEach { phase ->
            assertTrue("expected mini bar for $phase", shouldShowRealtimeMiniLiveBar(phase))
        }

        listOf(
            Stage16ARealtimeUiPhase.IDLE,
            Stage16ARealtimeUiPhase.COMPLETED,
            Stage16ARealtimeUiPhase.FAILED,
        ).forEach { phase ->
            assertFalse("expected no mini bar for $phase", shouldShowRealtimeMiniLiveBar(phase))
        }
    }

    @Test
    fun `preview prefers freshest mutable text before stable and final history`() {
        val state =
            Stage16ARealtimeUiState(
                phase = Stage16ARealtimeUiPhase.ACTIVE,
                partialText = " 最新部分 ",
                stableText = "稳定前缀",
                finalized =
                    listOf(
                        Stage16ARealtimeFinalText(
                            sessionId = 1,
                            epochId = 1,
                            continuityIndex = 1,
                            text = "已确认内容",
                        ),
                    ),
                lifecycleMessage = "生命周期提示",
            )

        assertEquals("最新部分", realtimeMiniPreview(state))
    }

    @Test
    fun `preview falls back through stable final lifecycle and empty placeholder`() {
        assertEquals(
            "稳定内容",
            realtimeMiniPreview(
                Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.ACTIVE,
                    stableText = " 稳定内容 ",
                ),
            ),
        )
        assertEquals(
            "已确认内容",
            realtimeMiniPreview(
                Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.PAUSED,
                    finalized =
                        listOf(
                            Stage16ARealtimeFinalText(
                                sessionId = 1,
                                epochId = 2,
                                continuityIndex = 2,
                                text = " 已确认内容 ",
                            ),
                        ),
                    lifecycleMessage = "已暂停",
                ),
            ),
        )
        assertEquals(
            "连接已中断",
            realtimeMiniPreview(
                Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.INTERRUPTED,
                    lifecycleMessage = " 连接已中断 ",
                ),
            ),
        )
        assertEquals(
            "实时内容将在这里持续更新",
            realtimeMiniPreview(
                Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.PREPARING,
                ),
            ),
        )
    }
}
