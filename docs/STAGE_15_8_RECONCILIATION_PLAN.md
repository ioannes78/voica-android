# Stage 15.8 — Realtime / Recording Lifecycle Reconciliation

状态：**需求已确认 / 开发规划已确认 / 实现候选开发中 / 待 CI + 真机 Gate**

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

该缺口必须在 Stage 16 streaming ASR 接入前收口，否则下游可能把一个已经没有 PCM 的 session 当作仍在运行。

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

## 8. Automated tests

实现候选必须覆盖：

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

## 9. True-device QA Surface

Launcher：

`Voica Stage15.8 Reconcile Probe`

Activity：

`Stage158ReconciliationProbeActivity`

Probe 持续 drain canonical events，避免 QA surface 自己制造 slow-consumer backpressure。

### Gate A — TYPE3 SAVE 自动收敛

```text
Idle
→ realtime START
→ 讲话约 5 秒
→ production TYPE3 SAVE
→ 不点击 realtime STOP
→ 等待自动 reconciliation
```

必须：

```text
recordingStatus=Idle
runtimePhase=IDLE
gateOpen=false
sourceActive=false
lastEnd=DEVICE_RECORDING_ENDED
reconciliation requested delta=1
reconciliation completed delta=1
failure delta=0
```

### Gate B/C — 同连接第二 session + explicit STOP 隔离

```text
Gate A 完成
→ second realtime START
→ 讲话约 5 秒
→ explicit realtime STOP
```

必须：

- second START 成功；
- canonicalSamples 在第二 session 继续增长；
- explicit STOP 成功；
- final runtime = IDLE；
- lastEnd = STOP；
- explicit STOP 前后 app `reconciliationRequested` delta = 0。

### Gate D — TYPE3-only 隔离

```text
runtime IDLE
→ TYPE3 START
→ 讲话约 5 秒
→ TYPE3 SAVE
```

必须：

- recording Recording -> Idle；
- runtime 始终 IDLE；
- `reconciliationRequested` delta = 0。

App reconciler 可以记录一个 recording-ended observation/no-op；这不等于请求 realtime reconciliation。

## 10. Freeze gate

Stage 15.8 只有在以下全部满足后才算完成：

```text
CI Fast PR Gate PASS
QA APK Gate PASS
Gate A PASS
Gate B/C PASS
Gate D PASS
```

并同时确认：

- 无 Room schema 变化；
- app version identity 仍 `90 / 1.1.0-stage15`；
- production model channel pin 未变；
- 不包含 Stage 16 streaming ASR 产品逻辑。

真机通过之前：

- PR #27 保持 Draft；
- 不创建 Stage 15 Final Freeze/Handoff；
- 不 merge main。
