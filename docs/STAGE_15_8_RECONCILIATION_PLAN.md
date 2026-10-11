# Stage 15.8 — Realtime / Recording Lifecycle Reconciliation

状态：**COMPLETE / CI PASS / 真机 Gate PASS / QA Launcher Cleanup 已完成**

## 1. 背景

Stage 15.7 D2 已在 QS668 / CB08 真机确认：

```text
TYPE1 realtime ACTIVE
→ production TYPE3 SAVE
→ device Recording -> Idle
→ device TYPE1 media stops
BUT
→ RealtimeAudioRuntime remains ACTIVE
→ gateOpen=true
→ canonical sourceActive=true
→ post-SAVE PCM=0
```

因此 Stage 15 存在 control-plane reconciliation 缺口：设备媒体生命周期已经结束，但 App runtime/canonical lifecycle 尚未自动终止。

该缺口已由 Stage 15.8 收口。

## 2. Scope

Stage 15.8 只负责：

- 监听可信 recording lifecycle；
- 识别设备 `Recording/Paused -> Idle` 终止事实；
- 当 realtime runtime 仍 ACTIVE/PAUSED 时自动 reconciliation；
- 正常优先补发 TYPE1 STOP；
- STOP 写失败时仍以可信 device Idle 事实执行本地 lifecycle fallback；
- canonical stream 以明确 end reason 结束；
- 保持 explicit STOP、TYPE3-only、stale/failed recording state 的幂等与隔离。

不包含：

- Stage 16 streaming ASR；
- realtime ASR UI；
- Room schema 修改；
- production model channel 修改；
- ASR / diarization / AI Summary 变更；
- raw TYPE3 command 绕过；
- 第二套 BLE owner / reconnect manager / FGS。

## 3. 架构

保持依赖方向：

```text
DeviceRepository.recordingState
             │
             ▼
RealtimeRecordingLifecycleReconciler   (app layer)
             │
             ▼
RealtimeAudioRuntime.reconcileDeviceRecordingEnded()
             │
       ┌─────┴─────┐
       ▼           ▼
best-effort      local fallback
TYPE1 STOP       if STOP write fails
       │           │
       └─────┬─────┘
             ▼
canonical quiet drain
SessionEnded(DEVICE_RECORDING_ENDED)
             ▼
runtime IDLE / gate closed / source inactive
```

`core:ble` 不依赖 app runtime。

## 4. Trusted transition gate

只有满足全部条件才允许请求 reconciliation：

```text
previous trusted status in {Recording, Paused}
current status = Idle
current freshness = FRESH
runtime phase in {ACTIVE, PAUSED}
```

以下状态不触发 reconciliation request：

- 初始 Idle；
- Idle -> Idle；
- status=null；
- NOT_SYNCED / SYNCING / STALE / FAILED；
- runtime IDLE；
- runtime STOPPING / DRAINING；
- runtime TRANSPORT_CLOSED / CLOSED。

TYPE3-only Recording -> Idle 在 runtime IDLE 下只记 observation/no-op，不请求 realtime reconciliation。

## 5. Canonical end contract

新增：

```text
RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED
```

含义：device recording lifecycle 已可信结束，并由该外部设备事实导致当前 realtime session 收敛。

继续保持：

- 已接受 PCM 先于 SessionEnded；
- canonical sample index 只来自实际 decoded PCM；
- 不按 wall clock 补媒体时间；
- 不生成假 PCM；
- quiet drain = 250 ms；
- hard drain cap = 1500 ms；
- 每个 session 至多一个 SessionEnded。

## 6. Runtime reconciliation contract

### 6.1 Normal path

```text
ACTIVE / PAUSED
→ reconciliation attempt
→ controller.stop()
→ STOP write succeeds
→ DRAINING
→ canonical endActiveSessionAfterQuiet(DEVICE_RECORDING_ENDED)
→ completeStopDrain()
→ IDLE
```

普通 STOP counters 保持真实 transport 结果。

### 6.2 Local fallback

如果 device Idle 已可信确认，但补发 TYPE1 STOP 写失败：

```text
STOP write failure
→ do NOT fabricate stopSuccess
→ controller.beginExternalTerminationDrain()
→ close audio gate
→ DRAINING
→ canonical quiet drain(DEVICE_RECORDING_ENDED)
→ completeStopDrain()
→ IDLE
```

设备 Idle 事实优先于重复 STOP 写入是否成功；不能让 runtime 永久留在 ACTIVE。

### 6.3 Race / idempotence

如果 explicit realtime STOP 已把 phase 推进到 STOPPING / DRAINING / IDLE，recording Idle 到达时 reconciliation 必须 NO-OP。

如果 transport close 赢得 race，则 transport-close lifecycle 保持权威，不得额外生成 DEVICE_RECORDING_ENDED terminal event。

## 7. Diagnostics

Runtime snapshot 增加 reconciliation：

```text
attempts
stopWriteSuccess
stopWriteFailure
fallbackCount
completed
noOp
failure
lastResult
lastStopFailure
```

App reconciler snapshot 增加：

```text
trustedStateUpdates
recordingEndedTransitions
reconciliationRequested
reconciliationCompleted
reconciliationFallbackCompleted
reconciliationNoOp
reconciliationFailure
lastPreviousStatus
lastCurrentStatus
lastRuntimePhase
lastResult
```

这些 counters 只做低频 lifecycle 诊断，不进入 BLE hot path。

## 8. Automated tests / CI

最终候选 `816f6b3e4bf38ce92a4b483b3ddc909b1ff58b1b` 的 Android PR CI #1309：

- Fast PR Gate：SUCCESS；
- Unit tests and debug compile gate：SUCCESS；
- production-like QA APK build：SUCCESS；
- APK verification：SUCCESS；
- artifact upload：SUCCESS。

自动化覆盖：

- FRESH Recording -> Idle + runtime ACTIVE -> request exactly once；
- stale Idle 不形成 trusted termination；
- runtime DRAINING 时 Recording -> Idle -> no request；
- runtime ACTIVE + successful STOP -> IDLE / gate closed / source inactive；
- runtime ACTIVE + STOP write failure -> local fallback -> IDLE；
- fallback 不伪造 ordinary STOP success；
- DEVICE_RECORDING_ENDED canonical end reason；
- SessionEnded count = 1；
- reconciliation while runtime IDLE -> no transport STOP；
- fallback 后同连接下一次 realtime START 可正常工作。

## 9. True-device final facts

Stage 15.8 真机 Gate 最终：

```text
15.8 executionValid=true
15.8 factStatus=PASS
```

Gate A — TYPE3 SAVE 自动收敛：

```text
A start outcome=true/-- recording=Recording
A save eligible=true beforeStatus=Recording afterStatus=Idle
A runtime afterSave phase=IDLE gateOpen=false sourceActive=false
lastEnd=DEVICE_RECORDING_ENDED
runtime reconciliation delta:
  attempts=1
  stopWriteSuccess=1
  stopWriteFailure=0
  fallback=0
  completed=1
  failure=0
app reconciliation delta:
  requested=1
  completed=1
  fallback=0
  failure=0
A sourceDelta ended=1
A filesDelta=1
A facts autoConverged=true noManualRealtimeStop=true
```

结论：production TYPE3 SAVE 在 device 端结束 TYPE1 media 后，App 能自动收敛 realtime runtime/canonical lifecycle，无需人工补 realtime STOP。

Gate B/C — 同连接第二 session + explicit STOP：

```text
BC secondStart outcome=true/-- phase=ACTIVE sourceActive=true
BC mediaDelta acceptedBlocks=91 canonicalSamples=116480
BC explicitStop outcome=true/--
phase=IDLE gateOpen=false sourceActive=false lastEnd=STOP
BC reconcilerDelta requested=0 completed=0
BC facts secondSessionHealthy=true explicitStopDidNotReconcile=true
```

结论：自动 reconciliation 不污染下一 realtime session；正常 explicit STOP 不递归触发 reconciliation。

Gate D — TYPE3-only 隔离：

```text
D TYPE3-only afterStart recording=Recording runtimePhase=IDLE
D TYPE3-only afterSave recording=Idle runtimePhase=IDLE
D reconcilerDelta requested=0 completed=0 noOp=1
D facts type3OnlyDoesNotStartRealtime=true
```

结论：TYPE3-only lifecycle 不启动、不终止、不污染 realtime runtime。`noOp=1` 仅表示观察到 Recording -> Idle 时 runtime 本来就是 IDLE。

本次真机走正常 reconciliation STOP write success 路径；STOP write failure + local fallback 由自动化测试覆盖，不人为破坏 BLE 链路追加真机故障注入。

## 10. QA Launcher Cleanup

Stage 15.0–15.8 开发过程中多个 Probe Activity 曾临时声明 `MAIN + LAUNCHER`，导致覆盖安装后桌面显示多个 `Voica Stage...` 图标。

Stage 15.8 PASS 后已收口：

- `MainActivity` 是唯一 `MAIN + LAUNCHER`；
- `Stage15RealtimeProbeActivity` 保留源码但改为内部 Activity；
- `Stage157D0PassiveRealtimeProbeActivity` 保留源码但改为内部 Activity；
- `Stage157D1DualControlProbeActivity` 保留源码但改为内部 Activity；
- `Stage157D1CRealtimeOnlyProbeActivity` 保留源码但改为内部 Activity；
- `Stage157D2ReverseControlProbeActivity` 保留源码但改为内部 Activity；
- `Stage158ReconciliationProbeActivity` 保留源码但改为内部 Activity；
- 所有 Probe 均 `android:exported="false"` 且无 launcher intent-filter。

Stage 15.9 如需新的真机诊断操作，优先复用现有 App 内“设备诊断/高级设置”入口，不再创建额外桌面 launcher。

## 11. Completion gate

Stage 15.8 已满足：

```text
CI Fast PR Gate PASS
QA APK Gate PASS
Gate A PASS
Gate B/C PASS
Gate D PASS
```

同时确认：

- Room schema 仍为 v12；
- app version identity 仍 `90 / 1.1.0-stage15`；
- production model channel pin 未变；
- 不包含 Stage 16 streaming ASR 产品逻辑。

Stage 15.8 正式 COMPLETE。

下一步：**Stage 15.9 — Stability / Final Gate**。

在 Stage 15.9 全部稳定性和真机验收完成、用户明确“测试通过”之前：

- PR #27 保持 Draft；
- 不创建 Stage 15 Final Freeze/Handoff；
- 不 merge main。
