# Stage 15 — BLE 实时音频链路 开发规划 V1

状态：**已确认 / 15.0–15.7 真机事实与媒体链已完成 / 15.8 Reconciliation 实现候选开发中 / 待 CI + 真机 Gate**

## 基线

Stage 15 从 main `d82210cf2b8ce25df92f4f07df200019c501f8ea` 开始。

进入开发时基线：

- versionCode `89`
- versionName `1.0.1`
- Room `v12`
- production model channel main `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion `7`
- Stage 16 Streaming Contract V2 保持冻结

Stage 15 当前 QA development identity：

- versionCode `90`
- versionName `1.1.0-stage15`
- Room 仍为 `v12`
- production model channel pin 不变

开发分支：`stage15-ble-realtime-audio`

## 15.0 — Protocol / audio fact gate

已完成并冻结：

- TYPE=1 START / STOP / PAUSE / RESUME command contract；
- G1：真实 Opus packet boundary；
- G2：protocol SEQ realtime 语义；
- cadence / silence semantics；
- temporary true-device Probe。

### 15.0 / 15.0B / 15.0C 最终真机事实

15.0B 三组 cadence/silence 矩阵：

1. 静音：`401 bodies / 32.479 s / 12.346 bodies/s`；
2. 持续声音：`322 bodies / 26.186 s / 12.297 bodies/s`；
3. 声音→静音→声音：`486 bodies / 39.354 s / 12.349 bodies/s`。

共同结论：

- transport body 固定 `160 bytes`；
- steady-state cadence 约 `80 ms/body`；
- 普通静音不停止 transport；
- protocol SEQ 恒 0，不提供 audio ordering/loss domain；
- whole-body `inspect(160B)` 的 20ms 结果不能证明 packet boundary。

15.0C direct packet-boundary gate：

- `417/417` 160B body eligible；
- `0` rejected body；
- 拆分得到 `1668` 个 40B packet；
- `1668/1668` Opus inspect valid；
- `1668/1668` persistent decode success；
- slot0/1/2/3 全部 mono、`960 samples @48k = 20 ms`；
- 每 packet 解码输出 `320 samples @16k = 20 ms`；
- split40 media duration `33.360 s`；
- first→last audio arrival span `33.246 s`；
- `mediaArrivalSpanRatio=1.003`；
- Probe overflow / generic observer overflow / CRC error 均为 0。

正式冻结：

- **G1 PASS**：`TYPE1/CMD1 160B transport block = 4 × fixed-40-byte Opus packets`。
- **G2 PASS（否定性语义）**：audio protocol SEQ 恒 0，不可用于 duplicate / reorder / loss / PLC / timeline。
- 每 40B packet = 20ms mono Opus；每 160B block = 80ms。
- 16k mono decoder 正常每 packet 输出 320 samples；每 block 共 1280 canonical samples。
- canonical timeline 后续按**实际 decoder 输出 sample count**推进，不硬编码 wall clock/SEQ。

### Stage 15 Probe

- QA development identity：versionCode `90` / versionName `1.1.0-stage15`。
- 临时第二 launcher `Voica Stage15 Probe` 继续作为 Stage 15 真机诊断面；Final Freeze 前必须移除或并入正式诊断入口。
- Probe 启动时 park 正常 `DeviceRepository`，禁止同一设备双 GATT owner。

## 15.1 — Dedicated realtime packet ingress

正式路径：

`GATT notification → existing FrameParser/CRC → TYPE1/CMD1 classify → RealtimeAudioConsumer → RealtimeAudioIngress`

实现：

- `AndroidDeviceSession` 允许单一 `RealtimeAudioConsumer` 注册；
- realtime CMD=1 不再送 generic `notifications` SharedFlow；
- realtime CMD=1 不再执行每 block `RX ...` 日志；
- notification diagnostics 对纯 realtime audio 每 64 frame 聚合刷新一次；
- callback 只执行 parse/classify、一次 160B body copy、bounded offer/counter；不执行 JNI decode、Opus inspect、PCM、Room、ASR；
- stale GATT generation 在 consumer 前拒绝；
- GATT generation release 调用 `onSessionClosed(generation)`。

### 15.1/15.2 真机 normal-path gate

正式 ingress Probe 真机样本：

- device audio blocks `382`；
- `acceptedBlocks=382`，100% 对齐；
- `audioBytes=61120` / `acceptedBytes=61120`；
- `queueBlocks=0 / queueBytes=0`（STOP 后）；
- `peakBlocks=1 / peakBytes=160`；
- `overflowEvents=0`；
- `malformedBlocks=0`；
- `droppedBlocks=0 / droppedBytes=0`；
- `continuityId=0`；
- 382 blocks → 1528 split40 packets，`1528/1528` valid + decode success；
- `mediaArrivalSpanRatio=1.003`；
- Probe queue overflow / generic observer overflow / CRC error 均为 0。

结论：

- **Stage 15.1 normal-path true-device gate PASS**；
- **Stage 15.2 normal-path true-device gate PASS**；
- normal load 下 worker 基本即时消费，实际 peak backlog 仅 1 block；
- `router AE22=320` 是每 64 realtime frame 发布一次 diagnostics 的快照滞后：`320=64×5`，不是 382 中丢失 62 blocks；audio authority 以 ingress `acceptedBlocks=382` 为准。

## 15.2 — Realtime deaggregation + bounded encoded buffer

正式 transport unit：`160 bytes / 80 ms`。

`RealtimeAudioIngress` 初始硬上限：

- `12 blocks`
- `2048 encoded bytes`

正常最大 backlog：

- `12 × 80 ms = 960 ms`
- `12 × 160 B = 1920 B`

Ingress 行为：

- 保存 `sessionGeneration`、source、raw protocol SEQ、monotonic arrival timestamp（仅诊断）、continuityId；
- 不使用 protocol SEQ 排序/去重；
- body 必须恰好 160B；非 160B 不猜测 framing，标 malformed + continuity advance；
- queue count/byte 任一越界：记录 overflow，丢弃整个 stale backlog，continuityId++，当前 live block 仍接收并标记 `discontinuityBefore=true`；
- generation change / session close 清理旧 backlog并形成 continuity boundary；
- callback 外由 `RealtimeOpusPacketizer` 固定拆为 4 × 40B；
- 只有第一个 40B packet 继承 block 的 `discontinuityBefore`。

异常 overflow 真机故障注入留到 15.7 fault matrix。

## 15.3 — Continuous Opus decode

正式实现：

`RealtimeAudioIngress → 160B block → 4×40B RealtimeOpusPacket → Opus inspect → persistent decoder(16k/mono) → ShortArray`

约束：

- 复用 `engine:opus` / `OpusDecoderFactory`，不新增 codec 实现；
- decoder 创建为 canonical `16kHz / mono`，不增加 resampler；
- worker 单消费者并运行在 GATT callback 之外；
- packet inspector 继续校验 `valid / mono / 960 samples@48k`，shape anomaly 只做诊断；
- 正常 packet 预期 320 samples，但 canonical timeline 使用**实际 decode 返回长度**；
- invalid packet、decode exception、empty PCM → decoder recovery boundary；
- recovery 前 reset decoder；若 reset 失败则销毁并重建 decoder；
- 不做 PLC，不按 wall clock 补 20ms；失败 packet 的未知/缺失媒体时间不进入下一 epoch sample index。

## 15.4 — Canonical PCM + timeline

`core:audio` 提供 BLE/Opus 无关的 `RealtimeCanonicalPcmFrame` contract：

- opaque `sessionId`；
- session-local `epochId`；
- epoch-local `startSampleIndex`；
- canonical PCM16 `ShortArray samples`；
- `epochStartReason`：SESSION_START / CONTROL_BOUNDARY / DISCONTINUITY / DECODER_RECOVERY；
- sampleRate 固定 16kHz，channel 固定 mono。

正式 timeline 语义：

- 同一 GATT 连接里再次 START 也创建新的 realtime session；GATT reconnect 同样创建新 session；
- ingress continuity/discontinuity 创建新 epoch；
- RESUME 前请求 control boundary，使恢复后的第一帧创建新 epoch；
- decoder recovery 创建新 epoch；
- epoch 内 `startSampleIndex += actual pcm.size`；
- 新 epoch 的第一个 canonical frame 必须从 sample index `0` 开始并携带明确 reason；
- 普通自然静音不形成 discontinuity；
- wall clock / BLE arrival timestamp / protocol SEQ 不参与 canonical media timeline。

### 15.3/15.4 真机 normal-path gate

30 秒级正式 canonical pipeline 样本：

- `acceptedBlocks=403 / canonicalPipeline blocks=403`；
- `opusPackets=1612 = 403×4`；
- `decodeSuccess=1612 / decodeFailures=0`；
- `decodedSampleHistogram={320=1612}`；
- `unexpectedDecodedSampleCount=0`；
- `canonicalFrames=1612`；
- `canonicalSamples=515840 = 1612×320`；
- `canonicalDurationMs=32240.000`；
- `sessions=1 / epochs=1`；
- `discontinuities=0 / transportBoundaries=0 / controlBoundaries=0 / decoderRecoveryBoundaries=0`；
- `gapCount=0 / overlapCount=0 / invalidEpochStartCount=0`；
- `peak ingress blocks=2 / 320 bytes`；
- `maxBlockProcessingMs=2.682`；
- `maxArrivalToEmitLagMs=4.384`；
- ingress overflow / malformed / drop / CRC 均为 0。

结论：

- **Stage 15.3 normal-path true-device gate PASS**；
- **Stage 15.4 normal-path true-device gate PASS**；
- pipeline snapshot 中原 `running=true` 仅表示 decoder worker coroutine 存活，不代表 realtime session 仍活动；15.5 Probe 显示时改称 `workerRunning`，真实 session 生命周期由 canonical consumer seam 的 `sessionActive` 表示。

控制/异常边界真机 gate 留到 15.5–15.7 联合矩阵。

## 15.5 — Canonical consumer / backpressure + STOP lifecycle

正式 Stage 16A seam：

`RealtimeCanonicalAudioPipeline → RealtimeCanonicalAudioSource → RealtimeCanonicalStreamEvent`

Stage 16A 以后只能消费 canonical stream event，不感知 BLE UUID、MTU、Opus packet、transport SEQ 或 GATT generation。

### 15.5.1 Lifecycle event contract

`RealtimeCanonicalStreamEvent` 包括：

- `SessionStarted(sessionId)`；
- `EpochStarted(sessionId, epochId, reason)`；
- `PcmFrame(RealtimeCanonicalPcmFrame)`；
- `ConsumerDiscontinuity(...)`；
- `SessionEnded(sessionId, finalEpochId, finalSampleIndexExclusive, reason, drainTimedOut)`。

规则：

- SessionStarted 在该 session 第一帧 PCM 前输出；
- EpochStarted 在该 epoch 第一帧 PCM 前输出；
- SessionEnded 排在该 session 已接受的所有 PCM 后；
- 若新的 session frame 到达而上一 session 未正常 STOP close，source 自动先发 `SessionEnded(SUPERSEDED_BY_NEW_SESSION)`，避免下游生命周期悬空；
- event 自带 session/epoch identity；Stage 16A 不从 wall clock 推断媒体状态。

### 15.5.2 Bounded PCM consumer queue

正式初始硬上限：

- `32 PCM frames`
- `10,240 canonical samples`
- 当前正常 320 samples/frame 时约 `640 ms` PCM backlog。

Backpressure 规则：

- pipeline `onFrame` 只做 non-blocking source offer，慢 consumer 不反向阻塞 decoder/GATT；
- count/sample 任一越界时，丢弃 queue 中 stale PCM frames，保留低频 lifecycle events；
- 当前 live-edge PCM 仍接收；
- live-edge PCM 前插入 `ConsumerDiscontinuity(BACKPRESSURE)`，包含 dropped frame/sample 数与 resume sample index；
- 不按 wall clock 补被丢 PCM，不伪造连续媒体时间；
- Stage 16A 收到 ConsumerDiscontinuity 必须 reset/finalize 当前 streaming recognizer continuity。

### 15.5.3 STOP drain contract

STOP command 成功写入后：

1. 不立即发送 SessionEnded；
2. 继续接受/解码已经到达的尾部 realtime audio；
3. canonical source 等待 `250 ms` 无新 canonical frame的 quiet window；
4. 然后按事件队列顺序追加 `SessionEnded(STOP)`；
5. hard drain cap 为 `1500 ms`，超时仍结束但 `drainTimedOut=true`，作为异常诊断；
6. quiet/drain wall clock **只决定 lifecycle finalize 时点**，绝不推进 canonical sample index。

当前真机 cadence ~80ms/body、历史 max arrival gap <154ms，因此 250ms quiet window 为保守的尾包 drain gate。

自动化覆盖：

- first frame → SessionStarted → EpochStarted → PcmFrame 顺序；
- 同 epoch 连续 frame 不重复生命周期事件；
- 新 epoch 明确 EpochStarted；
- PCM overflow → stale PCM drop + ConsumerDiscontinuity + live edge；
- STOP quiet drain → PCM 后 SessionEnded；
- 未 STOP 的旧 session 被新 session supersede 时自动闭合；
- independent Probe observer 校验 lifecycle count 与 SessionEnded final sample 一致性。

## 15.6 — Lifecycle / reconnect / FGS

复用现有 BLE reconnect 与 `ServiceBackedDeviceRepository` 单 connectedDevice FGS lease；禁止第二套 reconnect manager、第二个 realtime FGS 或 per-packet FGS 操作。

15.6/15.7 联合真机矩阵覆盖：

- START → STOP；
- START → PAUSE → RESUME → STOP；
- STOP 后同连接再次 START；
- user disconnect → reconnect → START；
- Bluetooth off/on；
- background / screen-off。

生命周期规则：

- Pause/Resume：同 realtime session、新 canonical epoch；
- 第二次 START：新 realtime session，epoch 从 1 开始；
- reconnect：新 realtime session；
- STOP：明确 SessionEnded，且 final sample 与最后 canonical frame end 对齐；
- 每个 epoch 内 sample index 从 0 连续推进；
- 不跨未知 discontinuity 拼接媒体时间。

## 15.7 — Diagnostics + true-device integration（COMPLETE）

Stage 15.7 已完成 diagnostics / fault / true-device integration，并额外完成 D0–D2 TYPE3 / TYPE1 控制面事实矩阵。

权威事实文档：

- `docs/STAGE_15_7_D0_D1_TEST.md`

D0–D2 最终结论：

- TYPE3 START 不会隐式启动 TYPE1；
- TYPE1 START 会同时开启 device recording/file lifecycle；
- TYPE1 STOP 会终止 device recording lifecycle；
- TYPE3 START while TYPE1 ACTIVE 因 production Idle guard 而 NO-OP；
- **TYPE3 SAVE while TYPE1 ACTIVE 会停止 device TYPE1 media，但 pre-15.8 App runtime 会错误保持 ACTIVE / gateOpen / sourceActive**。

D2 正式事实：

```text
D2 reverseEffect=DEVICE_MEDIA_STOPS_RUNTIME_STAYS_ACTIVE
D2 factStatus=RESOLVED
Stage15.7=COMPLETE
```

这个生产 lifecycle 缺口不在 15.7 继续增加协议 Probe，而进入 Stage 15.8 专项收口。

## 15.8 — Realtime / Recording Lifecycle Reconciliation

权威规划：

- `docs/STAGE_15_8_RECONCILIATION_PLAN.md`

目标：

```text
trusted FRESH Recording/Paused -> FRESH Idle
AND realtime runtime still ACTIVE/PAUSED
→ app-layer reconciliation
→ best-effort TYPE1 STOP
→ STOP write failure 时 local convergence fallback
→ canonical SessionEnded(DEVICE_RECORDING_ENDED)
→ runtime IDLE / gate closed / source inactive
```

约束：

- 不让 `core:ble` 反向依赖 app runtime；
- explicit realtime STOP 已进入 STOPPING/DRAINING/IDLE 时不得重复 reconciliation；
- TYPE3-only lifecycle 不得启动/停止 realtime runtime；
- stale/failed/not-synced recording state 不得触发；
- fallback 不得伪造 ordinary STOP success；
- canonical timeline 不补 wall clock / 不生成假 PCM；
- quiet drain / hard cap 继续为 250 ms / 1500 ms。

Stage 15.8 当前状态：实现候选已进入 CI，仍需真机 Gate：

1. realtime START → TYPE3 SAVE → **无需手工 realtime STOP** 自动收敛；
2. 同连接第二次 realtime START 能正常出流；
3. explicit realtime STOP 不触发 reconciliation request；
4. TYPE3-only START/SAVE 不触发 realtime reconciliation request。

Stage 15.8 真机通过前不得 Final Freeze/Handoff。

## 15.9 — Stability / final gate

Stage 15.8 reconciliation 真机通过后才进入最终稳定性收口：

- automated tests + Android PR CI + production-like QA APK；
- true-device 全功能矩阵；
- development 10–15 min；
- candidate 30 min / 60 min；
- final 120 min。

Final gate 要求 crash/ANR/native fatal = 0，queue/lag/heap 不持续单向增长，人工断线必须产生明确 discontinuity/new epoch，stop 后资源完全释放且可再次 START。

## 版本策略

Stage 15 开发分支使用：

- versionCode `90`
- versionName `1.1.0-stage15`

Room 保持 `v12`；production model channel 保持原 pin。Final Freeze 时再决定是否正式 `1.1.0`。

## Freeze / merge gate

只有 Stage 15 全链路自动化、真机 QA、30/60/120 分钟稳定性全部通过，且用户明确说“测试通过”，才创建 `STAGE_15_FREEZE.md` / `STAGE_15_HANDOFF.md`、跑 Final CI 并 merge。merge 后重新核验 main/version/Room/model pin 后才解锁 Stage 16A。
