# Voica Stage 4 Handoff

## 当前结论

Stage 4 已完成开发、自动验证和 QS668/CB08 真机验收。

用户最终明确回复：

**“测试通过”**

本 Handoff 供完全没有阅读本次聊天的新 Agent 接管 Stage 5。

## 仓库与分支

Repository：

`ioannes78/voica-android`

Stage 4 开发分支：

`stage4-development`

PR：

`#4 Stage 4：设备文件列表与文件名解析`

Stage 4 起始 main：

`37425ce22cf61f73a90a5e0697edb210cfe7158b`

最终真机候选 HEAD：

`f75a7a3e9169e4cdb92a1ccec5df5dde2067b9e8`

核心 duration 提交：

`4ca20453269ce054bd3fc0dbb80f69e721831656`

Core CI：

`36587944561` — success

Candidate APK CI：

`36588581441` — success

APK SHA-256：

`b7d48c12a7346cc2f391645ddffe13f0edbb3f1c7cdf70183c76e3430cd000bc`

Freeze/Handoff 内容提交：

`3802dcfa1e77b11ab3981b2df76bbe6854438852`

Freeze 内容 CI：

`36590518755` — **success**

该 Run 再次通过：

- `:core:protocol:test`
- `:core:ble:testDebugUnitTest`
- `:app:assembleDebug`

本 Handoff 的 seal 提交仅记录最终验证证据，不改变 Stage 4 生产代码。

PR #4 合并后的最终 `main` SHA，Stage 5 接管时必须从 GitHub 重新读取，不能从聊天或本文件猜测。

## 版本

- versionCode：10
- versionName：`0.4.2-stage4`

## 当前模块

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

Stage 4 没有创建空未来模块。

## Stage 4 新增核心

### :core:protocol

- RawDeviceFileEntry
- FileListChunk
- FileListDecodeResult
- FileListDecoder
- DeviceFilenameResolver
- FilenameResolution
- RecordingFilenameParser
- dynamic filename-field parsing
- strict UTF-8 / malformed validation

### :core:ble

- RemoteDeviceFile
- DeviceFileListState
- FileListFreshness
- FileListError
- FileListDiagnostics
- FileListFrameRouter
- FileListSessionCoordinator
- RemoteDeviceFileMapper
- DeviceRepository.deviceFileListState
- DeviceRepository.refreshDeviceFiles()
- AndroidDeviceSession.requestFileList()

### :app

- DeviceViewModel file-list state/action
- DeviceFilesCard
- 文件名/录制时间/时长/大小
- Refresh / Empty / Stale / Failed UI
- Stage 4 BLE/File-list Diagnostics

## 最重要的真机协议事实

### 文件列表

```text
2/0 request
↓
2/1 data
↓
2/1 data
↓
...
↓
2/18 done
```

- 2/1 可多帧
- 必须等 2/18 才发布 Fresh/Empty
- 当前真机 2/1、2/18 均观察到 AE22
- 但代码继续按 TYPE/CMD 路由，不硬绑定 notification source
- 当前 CMD=18 body length=1B
- sequence 不要求回显 request sequence

### Entry

当前真机：

- COUNT：BE32
- duration：BE32 seconds
- size：BE32 byte-count semantics
- filename field：20B

标准 raw：

`noteYYYYMMDD-HHMMSS.`

标准 resolved：

`noteYYYYMMDD-HHMMSS.opus`

0.4.1 真机：

- 11 秒 → rawTimeValue=11
- 24 秒 → rawTimeValue=24

因此业务层：

`durationSeconds = rawTimeValue`

## 文件名策略

优先使用设备完整名。

只有严格匹配：

`noteYYYYMMDD-HHMMSS.`

才恢复：

`noteYYYYMMDD-HHMMSS.opus`

未知 trailing-dot 不猜；不主动生成 WAV variant。

## Session / Stale 规则

- active session 最多一个
- 2/1 全部聚合到临时 accumulator
- 2/18 后原子发布
- malformed / disconnect / generation mismatch 不发布 partial Fresh
- 有历史成功结果时 disconnect/失败可显示 Stale
- reconnect 后必须重新刷新才 Fresh

## Stage 3 回归边界

Stage 3 Freeze 继续全部有效：

- App 2/4/6/8 + 01 卡录音控制
- Device 1/3/5/7 物理事件
- Pause semantic latch
- GET_STATE=1 不能覆盖 Paused
- Recording 前台约 1 秒 GET_TIME Poller
- Full sync/物理事件前停止 Poller
- Idle 跳过 GET_TIME/GET_FILENAME
- 辅助错误仅 Diagnostics
- remembered-device auto-connect

Stage 4 Recording/Paused 时禁用文件刷新，避免与 Poller 竞争。

## Stage 5 特别注意

Stage 5 目标：

**文件下载 + 删除 + 原始音频落盘**

必须重新验证：

- TYPE=2/CMD=2
- TYPE=2/CMD=3
- TYPE=2/CMD=4
- TYPE=2/CMD=5
- TYPE=2/CMD=7
- TYPE=2/CMD=12

官方 Android App 已确认：

- 2/12 是 ranged file transfer
- 2/2、2/12 使用实际 UTF-8 filename bytes

当前 Voica Stage 1 的 `encodeFilename24` / fixed-24 builder 是历史实现事实，**不是 Stage 4 冻结的下载协议 contract**。

Stage 5 不得直接以“标准文件名正好 24B，所以 filename 永远 fixed-24”作为实现前提。

同时保持 Stage 2 冻结原则：

> 完整 ProtocolFrame 必须作为一次 GATT Write，不得应用层拆分。

## Stage 5 不要提前做

除 Stage 5 明确需求外，不要同时加入：

- Room 本地录音库
- Opus/WAV 解码
- 播放器
- ASR/VAD
- Speaker
- AI

## Stage 5 接管流程

新 Agent 必须：

1. 从 GitHub 读取合并后 `main` HEAD、branches、PR、CI。
2. 阅读根目录 `AGENTS.md`。
3. 阅读 `docs/STAGE_4_TEST.md`、`STAGE_4_FREEZE.md`、`STAGE_4_HANDOFF.md`。
4. 阅读 `STAGE_4_PROTOCOL_FINDINGS.md`、`STAGE_4_REAL_DEVICE_FINDINGS.md`。
5. 阅读 ROADMAP、ARCHITECTURE 与实际源码。
6. 重新核对官方 APK / NextProto / 真机对 Stage 5 文件传输的证据。
7. 先输出 Stage 5 修订需求并等待用户确认。
8. 再输出 Stage 5 修订开发规划并等待第二次确认。
9. 确认后才允许编码。
