# Voica Stage 5 测试报告

状态：**ACCEPTED / 用户最终确认测试通过**

最终用户确认日期：2026-09-30

## 1. 最终真机候选

版本：

- versionCode：14
- versionName：`0.5.3-stage5-alpha4`

最终候选 HEAD：

`bf6dda333f2581815bf58e99f6c383e8960b1e44`

Candidate CI：

- Run ID：`36690007416`
- 结论：**success**
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过
- APK artifact：上传成功

最终真机 APK SHA-256：

`284a1f03a190c4592b9815814c7574db9c7fe1478e6776250eb40734b9b78a40`

## 2. 用户最终验收

用户最终明确回复：

**“测试通过”**

最终明确确认：

- 本地已下载 `.opus` 显示“OPUS 原始流”
- 单条设备录音删除正常
- 删除后设备列表目标消失
- 电脑确认设备物理同名 `.opus + .wav` 同时消失

## 3. 下载链路

真机确认：

```
2/2
→ 2/3
→ 2/4 × N
→ 2/5
```

验证：

- 自动刷新正常
- 完整下载正常
- progress 正常
- expected / received 一致
- 原始字节流可靠落盘
- 本地不是 WAV
- 本地与设备 Opus 大小语义一致

## 4. Range Probe

真机：

- 0..255 → 255 bytes
- 与完整下载本地前缀逐字节一致
- remote status=0

冻结：

`[start, end)`

## 5. 删除专项

错误候选：

`CMD=8 body = raw list entry`

真机被拒绝/OutcomeUnknown，不冻结。

最终候选：

`CMD=8 body = 00000000 + filename fixed24`

真机删除通过，并确认物理同名 `.opus + .wav` 一起消失。

## 6. 本地与设备生命周期

冻结：

- DeleteRemote 不删除本地
- DeleteLocal 不发送 BLE 命令
- 不提供 DeleteBoth
- 不提供 Delete All

## 7. 自动化覆盖

Stage 5 新增/强化：

- FileTransferProtocol builders/decoders
- dynamic download filename bytes
- CMD=3/5/7/11/12/8/13
- FileTransferFrameRouter
- reliable file-transfer data path
- FileTransferSession
- start/idle/absolute timeout
- cancel/abort
- disconnect/session replacement
- data pipeline overflow
- StreamingDownloadWriter
- size mismatch
- SHA-256
- temp cleanup
- atomic commit
- duplicate protection
- LocalRecordingStore
- RangeProbeDataSink
- RemoteDeletePolicy
- DeviceFileOperationCoordinator
- refresh coalescing
- Ready/reconnect/recording finalized auto-refresh
- Stage 1–4 regression

## 8. Stage 5 边界

Stage 5 不实现：

- Room 本地录音库
- Opus 解码 / Ogg 封装 / WAV 转换
- 播放器 / 波形 / 时间轴
- ASR / VAD / 标点
- Speaker
- AI / Cloud
- Foreground Service
- persistent resume
- parallel download
- Delete All
- DeleteBoth

以上从 Stage 6 及后续阶段继续。


## Freeze/Handoff 收口验证

Freeze/Handoff 内容提交：

`ddbd5989e1d567f92f9b9612f583f53dd890dc4a`

Android PR CI：

`36691943084` — **success**

该 Run 在用户最终“测试通过”后再次通过：

- `:core:protocol:test`
- `:core:ble:testDebugUnitTest`
- `:app:assembleDebug`

本 seal 提交仅记录最终验证证据，不改变 Stage 5 生产代码。
