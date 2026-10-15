package io.github.ioannes78.voica.ui.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionFailureProductMessageTest {
    @Test
    fun canonicalLineageInternalErrorUsesChineseProductMessage() {
        assertEquals(
            "标准音频尚未准备好，请稍后重试",
            productTranscriptionFailureMessage("verified canonical PCM is not ready"),
        )
    }

    @Test
    fun unknownEnglishInternalErrorDoesNotLeakToProductUi() {
        assertEquals(
            "离线转写遇到内部错误，请重试；如持续出现，请查看诊断信息",
            productTranscriptionFailureMessage("IllegalStateException: native engine failed"),
        )
    }

    @Test
    fun existingChineseProductMessageIsPreserved() {
        assertEquals(
            "请先在设置中下载并启用所需模型",
            productTranscriptionFailureMessage("请先在设置中下载并启用所需模型"),
        )
    }
}
