# Stage 15.7 — D0 / D1A / D1B TYPE3 与 TYPE1 真机 Fact Gate

状态：**D0 已确认 / 首轮 D1 已确认控制面耦合 / D1A-D1B 待真机分解确认**

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

2026-10-12 真机 Gate：

```text
D1 TYPE3 status afterStart=Recording afterRealtimeStart=Recording afterRealtimeStop=Idle afterSave=Idle
D1 TYPE3 size afterStart=760 afterRealtimeStart=16240 afterRealtimeStop=0
D1 realtime outcomes start=true/-- stop=true/--
D1 mediaDelta acceptedBlocks=122 packets=488 canonicalFrames=488 canonicalSamples=156160
D1 anomalyDelta ingressOverflow=0 malformed=0 dropped=0 decodeFailures=0 sourceOverflow=0
D1 source afterStop active=false started=1 ended=1 lastEnd=STOP drainTimedOut=false
D1 files current=14 afterSave=14 fileCountDelta=2
D1 invariant type3StableAcrossRealtime=false realtimeMediaObserved=true cleanRealtimeWindow=true lifecycleClosedByStop=true
```

### 3.1 已确认事实

- TYPE3 已处于 Recording 时，显式 realtime START 成功；
- realtime START 后 TYPE3 仍保持 Recording；
- TYPE1 realtime media pipeline 健康：122 blocks / 488 packets / 488 canonical frames / 156160 samples；
- realtime 窗口无 ingress overflow、malformed、drop、decode failure、source overflow；
- realtime STOP 正确闭合 canonical source，`lastEnd=STOP`，`drainTimedOut=false`；
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

### 3.2 重要源码事实：上一轮 STOP 后 SAVE 实际为 production NO-OP

当前生产 `DeviceRepository.saveRecording()` 只在 recording status 为 `Recording` 或 `Paused` 时发送 SAVE；若已经为 `Idle`，函数直接返回。

因此首轮 D1 中：

- realtime STOP 后状态已经是 Idle；
- 后续调用 `DeviceRepository.saveRecording()` 不会再发送 TYPE3 SAVE；
- `fileCountDelta=2` 不能解释为“realtime STOP 创建一个文件 + 后续 TYPE3 SAVE 又创建一个文件”；
- 上一轮没有在 realtime STOP 后立即刷新文件，因此这两个新增条目究竟何时出现、文件名是什么仍需 D1A 分解。

严禁为了测试而绕过这个 production guard 直接发送 raw TYPE3 SAVE。

## 4. D1A — Realtime STOP Finalize Fact Gate

### 4.1 目的

直接回答：

1. realtime STOP 是否同时结束 TYPE3 recording；
2. realtime STOP 后立即刷新设备文件时，是否已经产生新文件；
3. 新增的是一个还是多个文件；
4. 新增文件的准确文件名是什么。

### 4.2 固定流程

`刷新文件基线 → TYPE3 START → realtime START → 讲话 5–8 秒 → realtime STOP → 立即 sync recording → 立即 refresh files`

realtime STOP 后 **不先调用 TYPE3 SAVE**。

### 4.3 QA Surface

Launcher：`Voica Stage15 D1 Probe`

Activity：`Stage157D1DualControlProbeActivity`

新增记录：

- baseline file count / names；
- TYPE3 START 后 cached file count；
- realtime START 后 cached file count；
- realtime STOP 后立即 refresh 的 file count；
- `newFilesAfterRealtimeStop=[...]`；
- STOP 前后 recording status / size；
- realtime media 与异常计数；
- canonical STOP lifecycle。

录音进行中不强制刷新 file list；A/B 阶段只记录 cached list。关键文件事实以 STOP 后设备已 Idle 时的立即刷新为准。

### 4.4 D1A 输出解释

核心字段：

```text
D1A facts coexistenceStart=...
realtimeStopPreservedType3=...
realtimeStopCreatedFiles=...
realtimeMediaObserved=...
cleanRealtimeWindow=...
lifecycleClosedByStop=...
D1A newFilesAfterRealtimeStop=[...]
D1A factStatus=RESOLVED
```

本 Gate 不使用简单 PASS/FAIL；它用于冻结真实设备行为。

## 5. D1B — Production SAVE After STOP Fact Gate

### 5.1 目的

确认 realtime STOP 已把 recording 变成 Idle 后，再调用现有生产 `DeviceRepository.saveRecording()` 的实际效果。

### 5.2 约束

不得绕过 `DeviceRepository` 的状态 guard，不得直接发送 raw TYPE3 SAVE。

如果 STOP 后仍为 Idle，预期：

```text
eligibleAtRequest=false
productionSaveExpectedNoOp=true
postStopSaveCreatedFiles=false
postStopSaveNoOp=true
```

D1B 前后都会刷新设备文件，并记录：

```text
D1B files before=...
after=...
delta=...
D1B newFilesAfterSaveCall=[...]
D1B factStatus=RESOLVED
```

D1B 的作用不是再次测试 raw firmware SAVE，而是确认 **当前生产入口在 STOP 后不会产生第二个 SAVE 命令/第二批文件**。

## 6. D1A / D1B 真机步骤

1. 安装包含 D1A/D1B Probe 的最新 Stage 15 QA APK。
2. 正常 Voica 连接 QS668 / CB08，等待 Ready。
3. 打开 `Voica Stage15 D1 Probe`。
4. 确认 `连接 Ready：true`。
5. 点击 `重置D1A/D1B`。
6. 点击 `D1A-1 TYPE3开始`；该操作会先自动刷新文件并建立 baseline。
7. 等待 `D1A-1 完成`。
8. 点击 `D1A-2 realtime START`。
9. 正常讲话 5–8 秒。
10. 点击 `D1A-3 realtime STOP`。
11. 等待 `D1A 已完成`；Probe 会自动立即刷新设备文件。
12. 此时可以先观察 `D1A newFilesAfterRealtimeStop`。
13. 点击 `D1B STOP后SAVE`。
14. 等待 `D1B 完成`。
15. 点击 `复制D1A/D1B Gate`，提交完整文本。

测试期间不要按录音卡物理按键，不要打开其他 Stage 15 Probe。

## 7. 后续 D2

D1A/D1B 事实冻结后，再决定是否需要反向顺序：

`realtime START → TYPE3 START → TYPE3 SAVE → realtime STOP`

由于首轮 D1 已证明 realtime STOP 会改变 TYPE3 recording 状态，D2 不再以“完全双向独立”为先验，而只用于补齐反向命令的真实设备行为。
