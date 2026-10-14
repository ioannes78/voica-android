package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Stage16ARealtimeMissingModelMessageTest {
    @Test
    fun `missing small realtime model exposes localized product message without internal id`() {
        val error =
            MissingRealtimeTranscriptionModelsException(
                listOf(Stage13ARealtimeModelIds.SMALL_BILINGUAL),
            )

        assertEquals(
            "实时转写模型未安装，请先下载 Small Bilingual Zipformer。",
            error.message,
        )
        assertFalse(error.message.orEmpty().contains(Stage13ARealtimeModelIds.SMALL_BILINGUAL))
        assertEquals(listOf(Stage13ARealtimeModelIds.SMALL_BILINGUAL), error.modelIds)
    }

    @Test
    fun `missing large realtime model exposes localized product message without internal id`() {
        val error =
            MissingRealtimeTranscriptionModelsException(
                listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC),
            )

        assertEquals(
            "实时转写模型未安装，请先下载 Chinese Large Zipformer CTC INT8。",
            error.message,
        )
        assertFalse(error.message.orEmpty().contains(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC))
        assertEquals(listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC), error.modelIds)
    }

    @Test
    fun `missing supporting realtime dependency stays localized and generic`() {
        val error = MissingRealtimeTranscriptionModelsException(listOf(Stage8ModelIds.VAD))

        assertEquals(
            "实时转写所需模型未安装，请先在本地语音识别中完成模型安装。",
            error.message,
        )
        assertFalse(error.message.orEmpty().contains(Stage8ModelIds.VAD))
        assertEquals(listOf(Stage8ModelIds.VAD), error.modelIds)
    }
}
