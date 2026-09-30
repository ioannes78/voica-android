# Voica Stage 5 Freeze

状态：**FROZEN / ACCEPTED**

用户最终验收日期：2026-09-30

## 1. 冻结范围

Stage 5 冻结：

- 文件列表首次 Ready / reconnect / 录音完成自动刷新
- refresh coalescing / pending refresh
- DeviceFileOperationCoordinator
- 统一 FileOperationState / FileOperationError
- TYPE=2 CMD=2/3/4/5 文件下载
- DATA 专用可靠传输通道
- FileTransferSession
- 下载进度 / cancel / timeout / disconnect
- `.part` 流式落盘
- size 校验 / SHA-256 / fsync / atomic commit
- LocalRecordingArtifact
- app-private/noBackup 本地存储
- raw Opus 本地识别与显示
- CMD=12 ranged transfer
- 单文件设备删除 CMD=8
- OutcomeUnknown destructive-operation 保护
- 删除后 Fresh list 验证
- Remote / Local 独立删除
- 不提供 Delete All / DeleteBoth
- Stage 5 Unit Tests 与真机协议证据

## 2. 技术基线

- versionCode：14
- versionName：`0.5.3-stage5-alpha4`
- Kotlin：2.4.20
- AGP：9.4.0
- Gradle：9.6
- JDK：17
- compileSdk：37.1
- targetSdk：37
- minSdk：26
- Application ID：`io.github.ioannes78.voica`
- 模块：`:app`、`:core:protocol`、`:core:ble`

## 3. 最终真机候选

HEAD：

`bf6dda333f2581815bf58e99f6c383e8960b1e44`

CI Run：

`36690007416` — **success**

通过：

- `:core:protocol:test`
- `:core:ble:testDebugUnitTest`
- `:app:assembleDebug`

APK SHA-256：

`284a1f03a190c4592b9815814c7574db9c7fe1478e6776250eb40734b9b78a40`

## 4. 冻结下载协议

```
2/2 request
2/3 start
2/4 data × N
2/5 end
```

当前真机下载的是 raw Opus 字节流，不是 RIFF/WAV。

原始音频优先保存，不在 Stage 5 转换。

## 5. 冻结 Range 语义

真机：

`0..255 → 255 bytes`

且与完整下载字节前缀一致。

冻结：

`[start, end)`

end exclusive。

## 6. 冻结删除协议

当前真机单录音删除：

```
TYPE=2 CMD=8
body:
00 00 00 00
filename fixed 24B
```

raw file-list entry 删除布局已经由真机否定。

成功删除设备录音会同时删除物理存储同名：

- `.opus`
- `.wav`

Stage 5 不提供 Delete All。

## 7. 删除安全

- destructive request 后 timeout/disconnect → OutcomeUnknown
- OutcomeUnknown 不自动重发
- reconnect/refresh 后用 Fresh list 核对
- 本地副本与设备文件独立
- DeleteRemote 不删除 Local
- DeleteLocal 不删除 Remote
- 无 DeleteBoth

## 8. 本地原始文件

Stage 5 保存：

- raw `.opus`
- SHA-256
- remote identity mapping
- downloadedAt
- recordedAt
- size
- audio type

当前真机 raw stream 显示为：

**OPUS 原始流**

Stage 6 才负责正式 Opus/Ogg/PCM/WAV 音频链路。

## 9. 用户验收

用户最终明确回复：

**“测试通过”**

因此 Stage 5 满足 Freeze/Handoff 门禁。

## 10. 下一阶段

Stage 6：**本地录音库 + Opus/WAV 音频链路**

Stage 6 必须从 Stage 5 合并后的 `main` 重新检查实际 HEAD、文档与代码，再进入需求/规划双确认门禁。


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
