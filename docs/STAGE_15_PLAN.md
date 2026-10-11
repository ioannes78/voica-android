# Stage 15 — BLE 实时音频链路 开发规划 V1

状态：**已确认**

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

- 再核验 main / PR / CI / version / Room / model pin。
- 增加 TYPE=1 START/STOP/PAUSE/RESUME command contract。
- 提供真机原始事实采集能力，不先假设 packet boundary 或 sequence domain。
- Gate G1：确认 `TYPE=1 CMD=1 body` 是否为完整 Opus packet。
- Gate G2：确认 protocol SEQ 的 realtime domain / rollover / sharing semantics。

未通过 G1/G2 前不进入正式 decoder continuity / loss classification。

### 15.0 Probe candidate

- QA development identity：versionCode `90` / versionName `1.1.0-stage15`。
- 临时第二 launcher `Voica Stage15 Probe` 只服务 G1/G2 真机事实采集；Final Freeze 前必须移除或并入正式诊断入口。
- Probe 启动时先 park 正常 `DeviceRepository`；若正常产品 session 仍持有活跃 transport，则主动断开后再创建独立 probe session，禁止同一设备双 GATT owner。
- Probe 只在 worker 线程执行 `OpusPacketInspector.inspect()`，不执行 Opus decode、不输出 PCM、不接 Stage 16A。
- 采集 CMD=1 body 长度、AE22/AE23 来源、SEQ transition、packet rate、Opus validity/channels/48k samples/duration，以及非音频 TYPE=1 frame 样本。
- `duplicate/gap` 在 G2 冻结前仅记为 candidate；不得提前解释为真实音频丢包。
- `probeQueueOverflow > 0` 或 `genericObserverOverflow > 0` 时，本轮数据不得用于冻结 packet 语义。

## 15.1 — Dedicated realtime packet ingress

建立 generation-scoped realtime packet source，绕开 generic observer SharedFlow 作为音频 truth。BLE callback 只做 parse/classify/enqueue/counter。

## 15.2 — Ordering + bounded encoded buffer

以真机 packet duration 冻结约 0.5–1 s 的 bounded queue。验证 rollover、duplicate、gap、bounded reorder、overflow 和 stale generation。

## 15.3 — Continuous Opus decode

复用现有 `engine:opus` persistent decoder。每个连续 epoch 维护 decoder state；decoder error / unknown loss 产生 discontinuity。PLC 仅在真机 loss duration 可确定且 API 明确扩展后考虑。

## 15.4 — Canonical PCM + timeline

输出固定 `16 kHz / mono / PCM16`。建立 `realtimeSessionId + epochId + epochStartSampleIndex`；sample index 为媒体时间唯一真值。disconnect/reconnect、pause/resume 默认创建新 epoch。

## 15.5 — Consumer / backpressure

建立 `RealtimeCanonicalAudioSource` 或等价 seam，供 Stage 16A 只消费 canonical frame；Stage 16A 不感知 BLE UUID、MTU、Opus 或 transport sequence。PCM consumer queue 保持 bounded、low-latency。

## 15.6 — Lifecycle / reconnect / FGS

复用现有 BLE reconnect 与 `ServiceBackedDeviceRepository` 单 connectedDevice FGS lease；禁止第二套 reconnect manager、第二个 realtime FGS 或 per-packet FGS 操作。

## 15.7 — Diagnostics + true-device integration

低频聚合 transport / queue / decoder / timeline / lifecycle / performance counters。增加最小实时音频诊断入口用于真机 START/PAUSE/RESUME/STOP 和 packet fact gate。完成 realtime BLE PCM 与 final device recording waveform alignment 实验。

## 15.8 — Stability / final gate

- automated tests + Android PR CI + QA APK
- true-device 功能矩阵
- development 10–15 min
- candidate 30 min / 60 min
- final 120 min

Final gate 要求 crash/ANR/native fatal = 0，queue/lag/heap 不持续单向增长，人工断线必须产生明确 discontinuity/new epoch，stop 后资源完全释放且可再次 START。

## 版本策略

进入 Stage 15 开发分支后计划使用：

- versionCode `90`
- versionName `1.1.0-stage15`

Final Freeze 时再决定是否正式 `1.1.0`。

## Freeze / merge gate

只有 15.0 fact gate、自动化、真机 QA、30/60/120 分钟稳定性全部通过，且用户明确说“测试通过”，才创建 `STAGE_15_FREEZE.md` / `STAGE_15_HANDOFF.md`、跑 Final CI 并 merge。merge 后重新核验 main/version/Room/model pin 后才解锁 Stage 16A。
