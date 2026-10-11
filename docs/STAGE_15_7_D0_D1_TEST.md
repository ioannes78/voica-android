# Stage 15.7 — D0 / D1A / D1B / D1C TYPE3 与 TYPE1 真机 Fact Gate

状态：**D0 已确认 / D1 已确认控制面耦合 / D1A-D1B 已确认 / D1C 待真机隔离验证**

## 1. 范围

本 Gate 只验证 QS668 / CB08 当前真机上的两套控制面关系：

- TYPE=3：设备本地录音生命周期；
- TYPE=1：BLE realtime audio 生命周期。

不得扩展为 Stage 16A streaming ASR 产品逻辑。

生产 BLE transport、RealtimeAudioRuntime、Opus decoder、canonical source 不因本 Gate 绕过或替换。

## 2. D0 — Passive TYPE1 Fact Gate

固定操作：

`TYPE3 APP_RECORD_START → 录音 8–10 秒 → TYPE3 APP_RECORD_SAVE`

明确禁止调用 realtime START / STOP / PAUSE / RESUME。

2026-10-12 真机结果：

```text
runtime realtimeCommands start=0/0 pause=0/0 resume=0/0 stop=0/0
runtime fence gatedReject=0 staleOffer=0 quarantineReject=0 overflow=0
runtime source started=0 ended=0 offeredFrames=0 offeredSamples=0
D0 delta gatedReject=0 realtimeStart=0 realtimePause=0 realtimeResume=0 realtimeStop=0
D0 sourceDelta started=0 ended=0 fileCountDelta=1
D0 invariant noRealtimeControl=true passiveType1Observed=false
```

冻结事实：

- TYPE3 普通设备录音可以独立创建录音；
- 未调用 realtime START 时，真机不会发送 TYPE1 realtime audio；
- `TYPE3 APP_RECORD_START` 不会隐式启动 TYPE1 realtime audio；
- TYPE1 realtime audio 必须由 App 显式执行 realtime START。

正确表达：

```text
D0 factStatus=RESOLVED
passiveType1DuringType3=false
conclusion=TYPE1_REQUIRES_EXPLICIT_REALTIME_START
```

## 3. 首轮 D1 — 已确认真机事实

原固定顺序：

`TYPE3 START → realtime START → realtime STOP → DeviceRepository.saveRecording()`

首轮真机已确认：

- TYPE3 已处于 Recording 时，显式 realtime START 成功；
- realtime START 后 TYPE3 仍保持 Recording；
- realtime media pipeline 健康；
- realtime STOP 正确闭合 canonical source；
- **realtime STOP 后设备录音状态从 Recording 变为 Idle，size 归零**；
- 因此“TYPE1 STOP 保持 TYPE3 Recording”的完全独立假设为 false。

正确表达：

```text
D1 factStatus=RESOLVED
TYPE3_PLUS_REALTIME_START_COEXISTENCE=true
REALTIME_STOP_PRESERVES_TYPE3=false
REALTIME_PIPELINE_HEALTHY=true
```

`factStatus=RESOLVED` 表示事实探测成功，不等同于原假设 PASS。

## 4. D1A — Realtime STOP Finalize Fact Gate（已确认）

固定流程：

`刷新文件基线 → TYPE3 START → realtime START → 讲话 5–8 秒 → realtime STOP → 立即 sync recording → 立即 refresh files`

2026-10-12 真机结果：

```text
D1A TYPE3 status afterStart=Recording afterRealtimeStart=Recording afterRealtimeStop=Idle
D1A TYPE3 size afterStart=840 afterRealtimeStart=25280 afterRealtimeStop=0
D1A realtime outcomes start=true/-- stop=true/--
D1A baseline fileCount=19
D1A stopFiles count=21 deltaFromBaseline=2
D1A newFilesAfterRealtimeStop=[note20261012-021237.opus, note20261012-021250.opus]
D1A mediaDelta acceptedBlocks=168 packets=672 canonicalFrames=672 canonicalSamples=215040
D1A anomalyDelta ingressOverflow=0 malformed=0 dropped=0 decodeFailures=0 sourceOverflow=0
D1A source afterStop active=false lastEnd=STOP drainTimedOut=false
D1A facts coexistenceStart=true realtimeStopPreservedType3=false realtimeStopCreatedFiles=true
D1A factStatus=RESOLVED
```

### 4.1 冻结事实

- TYPE3 Recording 与显式 TYPE1 realtime START 可以同时存在；
- TYPE1 realtime STOP 会把设备 recording 状态变为 Idle；
- TYPE1 realtime STOP 后 canonical source 以 STOP 正常闭合；
- realtime 窗口媒体与解码链路健康，无 overflow / malformed / drop / decode failure；
- 混合 TYPE3 + TYPE1 会话在 STOP 后文件列表由 19 增至 21；
- 新增两个不同时间戳文件：
  - `note20261012-021237.opus`
  - `note20261012-021250.opus`
- 因此两套控制面的 STOP / finalize 语义并非完全独立。

当前不能仅凭 D1A 断言第二个文件一定由 TYPE1 START 单独创建；需要 D1C TYPE1-only 隔离验证。

## 5. D1B — Production SAVE After STOP Fact Gate（已确认）

当前生产 `DeviceRepository.saveRecording()` 只在 recording status 为 `Recording` 或 `Paused` 时发送 SAVE；若已经为 `Idle`，函数直接返回。

2026-10-12 真机结果：

```text
D1B save attempted=true eligibleAtRequest=false statusBefore=Idle statusAfter=Idle
D1B files before=21 after=21 delta=0
D1B newFilesAfterSaveCall=[]
D1B facts productionSaveExpectedNoOp=true postStopSaveCreatedFiles=false postStopSaveNoOp=true
D1B factStatus=RESOLVED
```

### 5.1 冻结事实

- realtime STOP 后 production recording state 已为 Idle；
- 后续调用 `DeviceRepository.saveRecording()` 不会发送新的 TYPE3 SAVE；
- D1B 前后文件数量 21 → 21；
- 没有新增文件；
- 因此 D1A 中的两个新增文件全部形成于 D1B SAVE 调用之前。

严禁为了测试而绕过 production guard 直接发送 raw TYPE3 SAVE。

## 6. D1C — TYPE1-only File Side-Effect Fact Gate

### 6.1 目的

隔离回答以下事实：

1. 未启动 TYPE3 时，单独 realtime START 是否会把设备 recording state 从 Idle 改为非 Idle；
2. realtime START 后设备是否出现 recording filename / size；
3. TYPE1-only realtime START/STOP 是否会创建设备录音文件；
4. 若创建，准确新增文件数量和文件名是什么；
5. TYPE1-only STOP 是否回到 Idle，同时 canonical lifecycle 正常闭合。

### 6.2 QA Surface

Launcher：

`Voica Stage15 D1C Probe`

Activity：

`Stage157D1CRealtimeOnlyProbeActivity`

该 Activity **不会调用**：

- `DeviceRepository.startRecording()`；
- `DeviceRepository.saveRecording()`；
- TYPE3 PAUSE / RESUME；
- raw TYPE3 protocol command。

只调用：

- recording state sync；
- device file list refresh；
- `RealtimeAudioRuntime.start()`；
- `RealtimeAudioRuntime.stop()`。

为避免 QA 页面自身制造 canonical slow-consumer backpressure，仍持续 drain canonical events，但不参与媒体处理。

### 6.3 固定真机流程

`同步 recording + 刷新文件基线 → realtime START → 讲话 5–8 秒 → realtime STOP → sync recording → refresh files`

测试期间：

- 不点击正常 Voica 的设备录音开始/停止；
- 不按录音卡物理录音键；
- 不打开 D0 / D1 / 通用 Stage15 Probe；
- 不发送任何 TYPE3 控制。

### 6.4 核心输出

```text
D1C baseline recordingStatus=...
D1C afterRealtimeStart status=... filename=... sizeBytes=...
D1C afterRealtimeStop status=... filename=... sizeBytes=...
D1C stopFiles count=... deltaFromBaseline=...
D1C newFilesAfterRealtimeStop=[...]
D1C facts baselineIdle=...
type1ChangedRecordingState=...
type1StopReturnedIdle=...
type1OnlyCreatedFiles=...
exactlyOneFileCreated=...
realtimeMediaObserved=...
cleanRealtimeWindow=...
lifecycleClosedByStop=...
D1C executionValid=...
D1C factStatus=RESOLVED
```

### 6.5 事实解释矩阵

若 D1C 得到：

```text
baselineIdle=true
type1ChangedRecordingState=true
type1OnlyCreatedFiles=true
```

则可确认 TYPE1 realtime 并非纯 BLE streaming 控制；它会同时触发设备 recording/file lifecycle。

若进一步得到：

```text
exactlyOneFileCreated=true
```

则结合：

```text
D0  TYPE3-only  -> +1 file
D1C TYPE1-only  -> +1 file
D1A TYPE3+TYPE1 -> +2 files
```

可以把“混合会话两个文件分别来源于两套启动生命周期”的解释提升为强事实结论。

如果 TYPE1-only 不创建文件，则必须回到 D1A 调查第二文件的其他设备端来源，不得按假设修改生产代码。

## 7. D1C 真机步骤

1. 安装包含 D1C Probe 的最新 Stage 15 QA APK。
2. 正常 Voica 连接 QS668 / CB08，等待 Ready。
3. 确认正常 Voica 当前没有进行 TYPE3 录音。
4. 返回桌面，打开 `Voica Stage15 D1C Probe`。
5. 确认 `连接 Ready：true`。
6. 点击 `重置D1C`。
7. 点击 `D1C-1 realtime START`；Probe 会先自动同步 recording 并刷新文件列表建立 baseline，然后仅发送 realtime START。
8. 等页面提示 START 成功。
9. 正常讲话 5–8 秒。
10. 点击 `D1C-2 realtime STOP`。
11. 等待 `D1C 完成`；Probe 会自动 sync recording 并刷新文件列表。
12. 点击 `复制D1C Gate`，提交完整文本。

## 8. 后续 D2

只有 D1C 事实冻结后，再执行反向命令 Gate：

`realtime START → TYPE3 START → TYPE3 SAVE → realtime STOP`

D2 不再以“完全双向独立”为先验，而用于确认：

- TYPE3 START 在已有 TYPE1 realtime 下的行为；
- TYPE3 SAVE 是否会反向终止 TYPE1 realtime；
- 反向顺序下的设备文件数量、文件名和 canonical lifecycle。

D1C / D2 真机事实完成前，不修改生产 BLE / Realtime lifecycle contract。
