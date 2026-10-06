package io.github.ioannes78.voica.ui.files

import io.github.ioannes78.voica.ble.FileOperationErrorCode

internal fun FileOperationErrorCode.toUserMessage(): String =
    when (this) {
        FileOperationErrorCode.NOT_READY -> "录音卡尚未就绪"
        FileOperationErrorCode.RECORDING_ACTIVE -> "录音卡正在录音，暂时不能执行此操作"
        FileOperationErrorCode.FILE_LIST_NOT_FRESH -> "设备文件列表已过期，请刷新后重试"
        FileOperationErrorCode.FILE_OPERATION_BUSY -> "已有文件操作正在进行"
        FileOperationErrorCode.INVALID_REMOTE_RECORDING -> "设备录音信息无效"
        FileOperationErrorCode.INVALID_FILENAME,
        FileOperationErrorCode.INVALID_TRANSFER_FILENAME,
        -> "设备文件名无效"

        FileOperationErrorCode.BLE_DISCONNECTED -> "录音卡连接已断开"
        FileOperationErrorCode.GATT_WRITE_FAILED,
        FileOperationErrorCode.DELETE_WRITE_FAILED,
        -> "蓝牙数据发送失败"

        FileOperationErrorCode.SESSION_REPLACED -> "蓝牙连接已切换，请重试"
        FileOperationErrorCode.DATA_PIPELINE_OVERFLOW -> "接收数据过快，传输已中断"
        FileOperationErrorCode.UNEXPECTED_FRAME -> "设备返回了无法识别的数据"
        FileOperationErrorCode.MALFORMED_START,
        FileOperationErrorCode.MALFORMED_END,
        -> "设备返回的文件传输信息异常"

        FileOperationErrorCode.UNKNOWN_REMOTE_STATUS -> "设备返回未知状态"
        FileOperationErrorCode.START_TIMEOUT -> "设备开始传输超时"
        FileOperationErrorCode.TRANSFER_IDLE_TIMEOUT -> "传输长时间没有收到数据"
        FileOperationErrorCode.TRANSFER_TIMEOUT -> "文件传输超时"
        FileOperationErrorCode.REMOTE_FILE_NOT_FOUND -> "设备上的录音文件不存在"
        FileOperationErrorCode.REMOTE_BAD_OFFSET -> "设备不支持当前续传位置"
        FileOperationErrorCode.REMOTE_STOPPED -> "设备已停止文件传输"
        FileOperationErrorCode.SIZE_MISMATCH -> "下载文件大小与设备报告不一致"
        FileOperationErrorCode.INVALID_AUDIO_CONTAINER -> "下载的音频文件格式无效"
        FileOperationErrorCode.INSUFFICIENT_STORAGE -> "手机存储空间不足"
        FileOperationErrorCode.TEMP_FILE_CREATE_FAILED -> "无法创建临时下载文件"
        FileOperationErrorCode.LOCAL_WRITE_FAILED -> "文件写入失败"
        FileOperationErrorCode.FSYNC_FAILED -> "文件保存失败"
        FileOperationErrorCode.ATOMIC_COMMIT_FAILED -> "文件最终保存失败"
        FileOperationErrorCode.LOCAL_ARTIFACT_CONFLICT -> "本地已存在冲突的录音文件"
        FileOperationErrorCode.LOCAL_ASSET_REGISTRATION_FAILED -> "录音文件登记失败"
        FileOperationErrorCode.DELETE_RESPONSE_TIMEOUT -> "设备删除响应超时"
        FileOperationErrorCode.DELETE_REJECTED -> "设备拒绝删除录音"
        FileOperationErrorCode.DELETE_VERIFICATION_FAILED -> "无法确认设备录音是否已删除"
        FileOperationErrorCode.DELETE_OUTCOME_UNKNOWN -> "设备连接中断，删除结果暂时无法确认"
        FileOperationErrorCode.LOCAL_DELETE_FAILED -> "本地录音删除失败"
        FileOperationErrorCode.USER_CANCELLED -> "操作已取消"
        FileOperationErrorCode.BACKGROUND_CANCELLED -> "操作因进入后台而中断"
        FileOperationErrorCode.RECORDING_PRIORITY_CANCELLED -> "录音开始后已暂停文件操作"
    }
