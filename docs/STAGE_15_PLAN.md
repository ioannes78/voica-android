# Stage 15 — BLE 实时音频链路 开发规划 V1

状态：**已确认 / 15.0–15.0C 完成 / 15.1–15.2 开发中**

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

第一轮 whole-body 样本曾观察：

- `160B × 524` realtime CMD=1 body；
- whole-160B Opus inspector `524 valid / 0 invalid`；
- inspector 返回 mono / `960 samples @48k = 20 ms`；
- audio CMD=1 protocol SEQ 恒为 `0`。

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

### 15.0 Probe candidate

- QA development identity：versionCode `90` / versionName `1.1.0-stage15`。
- 临时第二 launcher `Voica Stage15 Probe` 继续作为 Stage 15 真机诊断面；Final Freeze 前必须移除或并入正式诊断入口。
- Probe 启动时 park 正常 `DeviceRepository`，禁止同一设备双 GATT owner。
- 15.0C temporary decoder 只用于 packet-boundary 验证，不输出到播放器、Room、Stage 16A。

## 15.1 — Dedicated realtime packet ingress

实现目标：generation-scoped realtime audio source，彻底取消 generic observer SharedFlow 作为 CMD=1 音频 truth。

正式路径：

`GATT notification → existing FrameParser/CRC → TYPE1/CMD1 classify → RealtimeAudioConsumer → RealtimeAudioIngress`

当前实现约束：

- `AndroidDeviceSession` 允许单一 `RealtimeAudioConsumer` 注册；同一 consumer 重复注册幂等，不允许两个不同 consumer 同时占用；
- realtime CMD=1 不再送 generic `notifications` SharedFlow；非音频 TYPE=1、文件、控制、KEY 行为保持原路径；
- realtime CMD=1 不再执行每 block `RX ...` 日志；
- notification diagnostics 对纯 realtime audio 约每 64 block 聚合刷新一次，避免每 80ms 高成本 StateFlow/log 更新；
- callback 只执行 parse/classify、一次 160B body copy、bounded offer/counter；不执行 JNI decode、Opus inspect、PCM、Room、ASR；
- stale GATT generation 仍由现有 `isCurrent(generation)` 在进入 realtime consumer 前拒绝；
- GATT generation release 会关闭该 generation 的 consumer ownership，并调用 `onSessionClosed(generation)`。

## 15.2 — Realtime deaggregation + bounded encoded buffer

正式 transport unit：`160 bytes / 80 ms`。

`RealtimeAudioIngress` 冻结初始硬上限：

- `12 blocks`
- `2048 encoded bytes`

正常最大 backlog：

- `12 × 80 ms = 960 ms`
- `12 × 160 B = 1920 B`

Ingress 行为：

- 保存 `sessionGeneration`、source characteristic、raw protocol SEQ、monotonic arrival timestamp（仅诊断）、continuityId；
- 不使用 protocol SEQ 排序/去重；
- body 必须恰好 160B；非 160B 不猜测 framing，标 malformed + continuity advance；
- queue count/byte 任一越界：记录 overflow，丢弃整个 stale backlog，continuityId++，当前 live block 仍接收并标记 `discontinuityBefore=true`；
- generation change / session close 同样清理旧 backlog，并形成 continuity boundary；
- callback 之外由 `RealtimeOpusPacketizer` 按固定顺序拆为：`[0..39] / [40..79] / [80..119] / [120..159]`；
- 只有第一个 40B packet 继承 block 的 `discontinuityBefore`；4 个 packet 共享 generation / continuity / arrival metadata。

当前 temporary Probe 已切换为实际注册 `RealtimeAudioIngress`，generic observer 只接收低频非音频 TYPE=1 response。这样后续真机数据直接覆盖正式 15.1/15.2 hot path。

自动化测试覆盖：

- 160B accept + metadata preservation；
- 4×40B 顺序 deaggregation；
- count cap overflow → stale backlog 全清 + live-edge discontinuity；
- byte cap overflow；
- malformed body 不猜 framing；
- generation transition；
- session close；
- non-audio TYPE=1 ignored。

## 15.3 — Continuous Opus decode

下一步复用现有 `engine:opus` persistent decoder：

`RealtimeOpusPacket(40B) → persistent decoder(16k/mono) → ShortArray`

约束：

- 每个 continuity/epoch 维护连续 decoder state；
- `discontinuityBefore` 时 reset decoder；
- 正常 QS668 packet 预期 320 samples，但 canonical timeline 必须使用**实际 decode 返回长度**；
- decoder error / unknown loss 产生 explicit discontinuity；
- PLC 仅在未来存在可验证 loss duration 与明确 API 支持时再考虑。

## 15.4 — Canonical PCM + timeline

输出固定 `16 kHz / mono / PCM16`。建立 `realtimeSessionId + epochId + epochStartSampleIndex`；sample index 为媒体时间唯一真值。

- 每成功 decode 一 packet，sample index += 实际 PCM sample count；
- disconnect/reconnect、overflow、decoder fatal、pause/resume（当前安全契约）形成 epoch boundary；
- 普通自然静音不形成 discontinuity；
- wall clock / arrival timestamp / protocol SEQ 仅诊断，不反推 canonical sample time。

## 15.5 — Consumer / backpressure

建立 `RealtimeCanonicalAudioSource` 或等价 seam，供 Stage 16A 只消费 canonical frame；Stage 16A 不感知 BLE UUID、MTU、Opus 或 transport sequence。PCM consumer queue 保持 bounded、low-latency。

## 15.6 — Lifecycle / reconnect / FGS

复用现有 BLE reconnect 与 `ServiceBackedDeviceRepository` 单 connectedDevice FGS lease；禁止第二套 reconnect manager、第二个 realtime FGS 或 per-packet FGS 操作。

## 15.7 — Diagnostics + true-device integration

低频聚合 transport / queue / decoder / timeline / lifecycle / performance counters。完成：

- START / PAUSE / RESUME / STOP；
- TYPE3 recording 与 TYPE1 realtime independence；
- overflow / reconnect / Bluetooth off / user disconnect；
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
