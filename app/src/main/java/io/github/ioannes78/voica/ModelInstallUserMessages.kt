package io.github.ioannes78.voica

internal fun modelInstallRecordUserMessage(record: ModelInstallJournalRecord): String? =
    when (record.lastFailureCode) {
        null -> null
        "NETWORK" -> "网络连接异常，正在等待重试"
        "NETWORK_TIMEOUT" -> "网络连接超时，正在等待重试"
        "NETWORK_CONNECTION_ABORTED" -> "网络连接已中断，正在等待重试"
        "NETWORK_HTTP_5XX" -> "服务器暂时不可用，稍后将自动重试"
        "NETWORK_UNAVAILABLE" -> "网络不可用，请检查网络连接"
        "NETWORK_RESUME" -> "下载连接异常，将安全重新建立下载"
        "STORAGE_LOW" ->
            chineseMessageOrNull(record.lastFailureMessage)
                ?: "存储空间不足，请释放空间后继续安装"
        "RUNTIME_VALIDATE" -> "运行库验证失败，可重新验证"
        "INTEGRITY" -> "模型文件校验失败，请重新下载"
        "SCHEDULE_FAILED" -> "模型安装任务启动失败，请重试"
        "EXECUTOR_MISSING" -> "安装已中断，请点击继续安装"
        "USER_STOPPED" -> "安装已中断，请点击继续安装"
        "USER_PAUSED" -> "安装已暂停，可继续安装"
        "MODEL_REMOVED" -> "模型已从本机删除，可重新下载安装"
        "EXECUTION" -> "模型安装未完成，请重试"
        else ->
            chineseMessageOrNull(record.lastFailureMessage)
                ?: "模型安装未完成，请重试"
    }

internal fun modelUserSafeErrorMessage(
    rawMessage: String?,
    fallback: String,
): String = chineseMessageOrNull(rawMessage) ?: fallback

private fun chineseMessageOrNull(message: String?): String? =
    message
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() && value.any(::isCjkCharacter) }

private fun isCjkCharacter(character: Char): Boolean =
    character.code in 0x3400..0x4DBF ||
        character.code in 0x4E00..0x9FFF ||
        character.code in 0xF900..0xFAFF
