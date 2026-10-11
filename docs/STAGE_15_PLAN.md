# Stage 15 — BLE 实时音频链路 开发规划 V1

状态：**已确认 / 15.0–15.2 完成 / 15.3–15.4 开发中**

## 基线

Stage 15 从 main `d82210cf2b8ce25df92f4f07df200019c501f8ea` 开始。

进入开发时基线：

- versionCode `89`
- versionName `1.0.1`
- Room `v12`
- production model channel main `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion `7`
- Stage 16 Streaming Contract V2 保持冻结

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

异常 overflow 真机故障注入留到 15.7 fault matrix，不阻塞 15.3/15.4。

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

正式统计包括：

- received blocks / packetized packets；
- invalid Opus / shape anomaly；
- decode success / failure / zero-sample failure；
- decoded sample histogram / unexpected sample count；
- decoder reset / recreate / create failure；
- block processing time / arrival-to-emit lag（仅性能诊断）。

## 15.4 — Canonical PCM + timeline

`core:audio` 新增 BLE/Opus 无关的 `RealtimeCanonicalPcmFrame` contract：

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

Probe 从 15.3/15.4 起不再自行执行 temporary decoder，而是直接观察正式 `RealtimeCanonicalAudioPipeline`。另有独立 `Stage15CanonicalProbeAccumulator` 在输出端验证：

- frame/sample 总数；
- session/epoch 数；
- epoch 内 sample-index gap；
- epoch 内 overlap；
- epoch start 是否从 0 开始并携带 reason。

正常真机 gate 预期：

- `receivedBlocks == ingress.acceptedBlocks`；
- `packetizedPackets == receivedBlocks × 4`；
- invalidOpus / shapeAnomalies / decodeFailures / zeroSampleFailures = 0；
- decoded sample histogram 全部为 `{320=...}`；
- `canonicalFrames == decodeSuccess`；
- `canonicalSamples == decodeSuccess × 320`（仅对当前已验证 QS668 正常 packet）；
- normal START→STOP：session=1 / epoch=1 / discontinuity=0；
- independent canonicalProbe gap=0 / overlap=0 / invalidEpochStart=0。

## 15.5 — Consumer / backpressure

下一阶段建立 `RealtimeCanonicalAudioSource` 或等价 bounded seam，供 Stage 16A 只消费 canonical frame；Stage 16A 不感知 BLE UUID、MTU、Opus 或 transport sequence。PCM consumer queue 保持 bounded、low-latency。

## 15.6 — Lifecycle / reconnect / FGS

复用现有 BLE reconnect 与 `ServiceBackedDeviceRepository` 单 connectedDevice FGS lease；禁止第二套 reconnect manager、第二个 realtime FGS 或 per-packet FGS 操作。

## 15.7 — Diagnostics + true-device integration

低频聚合 transport / queue / decoder / timeline / lifecycle / performance counters。完成：

- START / PAUSE / RESUME / STOP；
- TYPE3 recording 与 TYPE1 realtime independence；
- synthetic slowdown overflow；
- reconnect / Bluetooth off / user disconnect；
- background / screen-off；
- realtime BLE PCM 与 final device recording waveform cross-correlation alignment。

## 15.8 — Stability / final gate

- automated tests + Android PR CI + QA APK
- true-device 功能矩阵
- development 10–15 min
- candidate 30 min / 60 min
- final 120 min

Final gate 要求 crash/ANR/native fatal = 0，queue/lag/heap 不持续单向增长，人工断线必须产生明确 discontinuity/new epoch，stop 后资源完全释放且可再次 START。

## 版本策略

Stage 15 开发分支使用：

- versionCode `90`
- versionName `1.1.0-stage15`

Room 保持 `v12`；production model channel 保持原 pin。Final Freeze 时再决定是否正式 `1.1.0`。

## Freeze / merge gate

只有 Stage 15 全链路自动化、真机 QA、30/60/120 分钟稳定性全部通过，且用户明确说“测试通过”，才创建 `STAGE_15_FREEZE.md` / `STAGE_15_HANDOFF.md`、跑 Final CI 并 merge。merge 后重新核验 main/version/Room/model pin 后才解锁 Stage 16A。
