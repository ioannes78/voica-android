# Stage 15.6 — Lifecycle / Reconnect / FGS / Resource Release
## 修订开发规划 V2

状态：**CONFIRMED FOR IMPLEMENTATION**

本文件记录 Stage 15.6 当前开发权威规划。它不扩大 Stage 15 到 ASR Provider/WebSocket；Stage 16A/18 的新增 streaming shaping/cloud streaming 约束分别由独立契约文件负责。

## 1. 当前阶段状态

- Stage 15.0–15.4：完成
- Stage 15.5-A：PASS
- Stage 15.5-B：PASS
- Stage 15.5-C：PASS
- Stage 15.5-D：PASS
- Stage 15.5：完成
- 当前下一阶段：Stage 15.6

开发分支：`stage15-ble-realtime-audio`

本规划确认前核验 HEAD：`1daab960b2cf2a8f29fc60272944a55d9ccf0578`

PR：`#27 [APK] Stage 15 — BLE realtime audio pipeline`，继续保持 Draft。

## 2. Stage 15.6 定位

把 Stage 15.0–15.5 已验证的：

`BLE TYPE=1 → Opus → canonical PCM → canonical lifecycle`

从临时 Probe 链路提升为 application-scoped 正式 realtime audio runtime。

本阶段收口：

- Activity/App lifecycle
- BLE generation lifecycle
- reconnect
- Bluetooth off/on
- background / screen-off
- existing connectedDevice FGS
- START / RESUME command transaction
- STOP drain
- disconnect terminal barrier
- decoder / queue / coroutine / native handle release

本阶段明确不实现：

- Streaming ASR engine
- Streaming VAD
- Provider API
- WebSocket
- heartbeat
- cloud reconnect
- Provider-specific chunk scheduling
- transcript PARTIAL/STABLE/FINAL UI

## 3. 正式 runtime 架构

`AppContainer`

- `DefaultDeviceRepository`：唯一 AndroidDeviceSession / GATT owner
- `ServiceBackedDeviceRepository`：唯一 connectedDevice FGS owner
- `RealtimeAudioRuntime`：Stage 15.6 新增进程级 realtime media owner

`RealtimeAudioRuntime` 协调：

- realtime command state machine
- generation-aware command fence
- RealtimeAudioIngress
- RealtimeCanonicalAudioPipeline
- RealtimeCanonicalAudioSource
- Opus decoder runtime

Activity不得拥有 decoder/GATT/ingress生命周期。

## 4. Realtime transport seam

在 `core:ble` 建立窄 `RealtimeDeviceTransport`（最终命名开发时按源码审计确认）。

仅暴露：

- connection state
- current transport generation/session identity
- register/unregister realtime audio consumer
- send TYPE=1 realtime command
- transport close signal

不暴露 BluetoothGatt/UUID/MTU/callback。

不得建立第二个 GATT owner或第二套 reconnect manager。

## 5. Runtime 生命周期分层

### 5.1 Process lifetime

- RealtimeAudioRuntime
- Stage16A-facing canonical source contract

### 5.2 Transport generation lifetime

- RealtimeAudioIngress
- RealtimeCanonicalAudioPipeline
- Opus decoder
- registered RealtimeAudioConsumer
- command fence

### 5.3 Logical realtime session lifetime

由 TYPE=1 START/STOP 控制。

同一 GATT 下 STOP→START 必须产生新 logical session；GATT reconnect 后未来 START 也必须产生新 logical session。

## 6. Command state machine

正式状态建议：

- IDLE
- STARTING
- ACTIVE
- PAUSING
- PAUSED
- RESUMING
- STOPPING
- DRAINING
- TRANSPORT_CLOSED
- ERROR
- CLOSED

命令必须串行，非法状态组合明确拒绝。

## 7. START transaction fence

禁止正式产品继续使用：

`beginRealtimeSession() → send START`

因为 write failure 会制造假 session。

也不能简单改成 `send START → beginRealtimeSession()`，因为首批 realtime audio 可能早于 coroutine completion。

正式顺序：

`capture generation → prepare bounded START fence → hold arriving realtime blocks → send START → written=true && generation unchanged → commit logical session → release fenced blocks`

失败：

- abort fence
- 不创建 SessionStarted/EpochStarted
- 不推进 canonical timeline

unresolved期间收到audio而命令最终失败时必须标记 command ambiguity，不得静默拼入旧 timeline。

Fence 必须 bounded、callback non-blocking，不执行 JNI/suspend/heavy work。

## 8. RESUME transaction fence

正式顺序：

`prepare RESUME fence → send RESUME → success → commit CONTROL_BOUNDARY → decoder reset → new epoch → release fenced path`

失败：

- 不创建新 epoch
- 不 reset decoder
- 不推进 timeline
- 保持 PAUSED truth

transport generation 变化时旧 completion 必须 stale reject。

## 9. PAUSE

`ACTIVE → PAUSING → send PAUSE`

成功进入 PAUSED；失败回 ACTIVE。

只有成功 RESUME 后才创建新的 CONTROL_BOUNDARY epoch。

## 10. STOP

继续冻结 Stage 15.5 已真机验证的 contract：

`STOPPING → send STOP → success → DRAINING → 250 ms canonical quiet → max 1500 ms → SessionEnded(STOP) → IDLE`

wall clock只用于 finalize 时机，不推进 sample index。

STOP write失败不得伪造 SessionEnded(STOP)，应恢复命令前 ACTIVE/PAUSED 状态。

## 11. Transport close barrier

必须避免 `SessionEnded` 后旧 pipeline 又产生 PcmFrame。

关闭顺序：

`transport generation closed → reject old-generation input → invalidate ingress → stop pipeline consumption → join worker → 确认不再产生PCM → end canonical session → release decoder/native handle → release generation runtime`

Terminal event必须是该 logical session 最后一个媒体 lifecycle event。

## 12. Session end reason

core/audio 使用 transport-independent reason：

- STOP
- TRANSPORT_CLOSED
- SUPERSEDED_BY_NEW_SESSION
- RUNTIME_CLOSED

BluetoothOff/GattError/PermissionRevoked/RemoteDisconnect等保留在 transport diagnostics，不暴露给 Stage16A media contract。

## 13. Reconnect

继续复用 DefaultDeviceRepository 现有 reconnect policy。

`old generation active → remote disconnect → SessionEnded(TRANSPORT_CLOSED) → release old decoder/ingress/pipeline → repository reconnect → new generation Ready → runtime IDLE`

连接恢复不等于 realtime logical session 自动恢复。必须再次 START。

## 14. Bluetooth Off / On

Off：关闭旧 generation、canonical terminal、decoder/resource release。

On：允许 repository 按现有逻辑重连，但新 generation runtime 为 IDLE，不恢复旧 media timeline。

## 15. Background / screen-off / Activity recreation

RealtimeAudioRuntime 属于 AppContainer/applicationScope。

Activity onDestroy 不得 close：

- decoder
- pipeline
- ingress
- source
- device session

Activity只取消自己的 UI collector。

Home/background/screen-off期间有效 device session继续运行。

## 16. FGS

不新增 RealtimeForegroundService，不新增第二个 connectedDevice notification，不允许 per-packet startForegroundService。

`ServiceBackedDeviceRepository` 继续是唯一 Android execution lifetime owner。

`RealtimeAudioRuntime` 不直接调用 Android Service API。

## 17. Resource ownership

正常 STOP 后允许保留轻量/warm runtime，但必须：

- sessionActive=false
- PCM queue=0
- no pending command/fence/drain
- no PCM production

transport close/process runtime close时必须完全释放：

- realtime consumer registration
- ingress queue
- pending fence
- pipeline worker
- decoder JNI handle
- pending drain
- generation-specific jobs

## 18. Probe

Probe 切换为正式 AppContainer/deviceRepository/RealtimeAudioRuntime 链路，不再自己拥有独立 AndroidDeviceSession。

新增 diagnostics至少包括：

- runtimePhase
- transportGeneration
- logicalSessionId
- transportRuntimeCreates/releases
- start/resume success/failure
- commandFence current/peak
- commandAmbiguityCount
- staleCommandCompletionCount
- decoderCreate/ReleaseCount
- transportCloseCount
- sourceSessionActive
- existing ingress/pipeline/source diagnostics
- FGS generation/phase

## 19. 自动化测试

### Command transaction

- START success/failure
- pending START disconnect
- stale START completion
- audio during START fence
- RESUME success/failure
- generation change during RESUME
- command ambiguity

### Runtime lifecycle

- START→STOP
- START→PAUSE→RESUME→STOP
- STOP→START
- disconnect ACTIVE/PAUSED
- disconnect during STOP drain
- reconnect fresh generation
- Bluetooth-off equivalent
- runtime close
- no PCM after SessionEnded

### Resource balance

验证 decoder/runtime create-release 平衡、queue清空、无 pending fence/drain。

### FGS regression

验证 single lease、background不重复 acquire、reconnect维持existing owner、user disconnect release、realtime不直接创建service。

## 20. 真机 QA

- 15.6-A Background：START→Home 20–30s→return→STOP
- 15.6-B Screen-off：START→screen off 20–30s→on→STOP
- 15.6-C user disconnect while ACTIVE
- 15.6-D remote disconnect/reconnect
- 15.6-E Bluetooth Off/On
- 15.6-F disconnect during STOP drain
- 15.6-G repeated START/STOP + reconnect lifecycle

15.7继续负责 synthetic overflow/fault matrix/waveform alignment/diagnostics finalization；15.8负责10–15/30/60/120分钟稳定性。

## 21. Cross-stage guard

Stage 15.6 输出到后续 Stage 的唯一媒体 seam 继续是 canonical stream events。

Provider/engine前的 audio shaping 由：

`docs/STAGE_16_STREAMING_CONTRACT_V3.md`

定义。

云端 WebSocket/heartbeat/reconnect由：

`docs/STAGE_18_CLOUD_STREAMING_CONTRACT_V1.md`

定义。

Stage 15.6 不提前实现这些后续能力。

## 22. 不变项

- Room v12
- production model channel
- canonical 16k mono PCM contract
- Stage15 Opus framing
- protocol SEQ语义
- existing BLE reconnect manager
- existing connectedDevice FGS
- TYPE=3 recording contract
- Stage13A frozen realtime model matrix

## 23. 开发提交顺序

- 15.6-A：transport seam + command state machine/fence + tests
- 15.6-B：application-scoped runtime + close barrier/resource ownership
- 15.6-C：AppContainer + reconnect/FGS integration
- 15.6-D：Probe切正式runtime + diagnostics
- 15.6-E：CI/APK + true-device QA

真机通过前不创建 Stage 15 Freeze/Handoff，不把 PR #27 标记 Ready，不 merge。
