# Voica Stage 5 Handoff

## 当前结论

Stage 5 已完成开发、自动验证和 QS668/CB08 真机验收。

用户最终明确回复：

**“测试通过”**

本 Handoff 供完全没有阅读本次聊天的新 Agent 接管 Stage 6。

## 仓库与分支

Repository：

`ioannes78/voica-android`

Stage 5 开发分支：

`stage5-development`

Stage 5 Draft PR：

`#5 Stage 5：文件下载、自动刷新与设备文件删除`

Stage 5 起始 main：

`c5e5ef93df1e389c4acdfaeb874ffce17c0006dd`

最终真机候选 HEAD：

`bf6dda333f2581815bf58e99f6c383e8960b1e44`

Candidate CI：

`36690007416` — **success**

APK SHA-256：

`284a1f03a190c4592b9815814c7574db9c7fe1478e6776250eb40734b9b78a40`

PR 合并后的最终 `main` SHA，Stage 6 接管时必须从 GitHub 重新读取，不能从聊天或本文件猜测。

## 版本

- versionCode：14
- versionName：`0.5.3-stage5-alpha4`

## 当前模块

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

Stage 5 未创建未来空模块。

## Stage 5 核心新增

### :core:protocol

- FileTransferProtocol
- download / range / abort / delete builders
- CMD=3 / CMD=5 / CMD=11 / CMD=13 decoders

### :core:ble

- FileOperationState / FileOperationError
- DeviceFileOperationCoordinator
- FileTransferFrameRouter
- ReliableFileTransferConsumer
- FileTransferSession
- StreamingDownloadWriter
- LocalRecordingStore
- RemoteDeletePolicy
- RangeProbeDataSink
- RemoteDeleteDiagnostics
- RangeProbeDiagnostics
- 自动 refresh scheduling / coalescing

### :app

- 设备文件下载进度 / 取消
- 设备单录音删除确认
- 本地文件独立页面
- 本地删除确认
- OPUS 原始流显示
- Stage 5 Diagnostics

## 最重要真机事实

### 下载

```
2/2 → 2/3 → 2/4 × N → 2/5
```

当前真机 BLE 下载内容为 raw Opus，不是 WAV。

Stage 4 sizeBytes 与实际下载字节数一致。

### Range

`0..255` 请求实际返回 255 bytes，与完整下载本地前缀完全一致。

冻结：

`[start, end)`

### 删除

Alpha 3 的 rawEntry CMD=8 body 已被真机否定。

最终真机确认：

```
2/8 body =
00 00 00 00
+
full filename fixed 24B
```

成功删除后设备列表目标消失，电脑确认设备物理同名 `.opus + .wav` 同时消失。

### 本地 / 设备独立

- 删除设备文件不删除手机文件
- 删除手机文件不删除设备文件
- 不提供联合删除
- 不提供 Delete All

## Stage 6 应做

Stage 6：本地录音库 + Opus/WAV 音频链路。

建议从以下问题开始重新梳理需求：

- Stage 5 lightweight metadata → Room 正式数据模型/迁移
- raw Opus packet structure 正式验证
- raw Opus → decoder input
- 是否需要 Ogg encapsulation
- PCM 解码标准
- 16 kHz mono 标准化
- WAV 生成策略
- 原始文件与派生音频的缓存/生命周期
- 本地录音库 UI、重命名、删除
- 后续播放器/ASR 所需稳定音频接口

Stage 6 不得直接从旧 VoiceCard 项目搬运代码。

## Stage 6 接管顺序

1. 读取 GitHub 最新 `main`、HEAD、branch、open PR、Actions。
2. 阅读根目录 `AGENTS.md`。
3. 阅读：
   - `docs/STAGE_5_TEST.md`
   - `docs/STAGE_5_FREEZE.md`
   - `docs/STAGE_5_HANDOFF.md`
   - `docs/STAGE_5_PROTOCOL_FINDINGS.md`
   - `docs/STAGE_5_REAL_DEVICE_FINDINGS.md`
   - `docs/ARCHITECTURE.md`
   - `docs/ROADMAP.md`
4. 检查实际 Stage 5 源码与测试。
5. 执行 baseline validation。
6. 输出《Voica Stage 6 修订需求 V1》。
7. 等待用户确认。
8. 再输出《Voica Stage 6 修订开发规划》。
9. 再次等待用户确认后才能编码。


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
