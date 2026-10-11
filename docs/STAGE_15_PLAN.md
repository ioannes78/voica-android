# Stage 15 — BLE 实时音频链路 开发规划 V1

状态：**已确认 / 15.0C packet-boundary fact gate 进行中**

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
- Gate G1：确认 `TYPE=1 CMD=1 body` 与真实 Opus packet boundary 的关系。
- Gate G2：确认 protocol SEQ 的 realtime domain / rollover / sharing semantics。

### 15.0 第一轮真机事实

第一轮 QS668 真机样本：

- `audioPackets=524`，`audioBytes=83840`
- CMD=1 body 长度：`160 bytes × 524`
- 对整个 160B body 执行 Opus inspector：`524 valid / 0 invalid`
- channels：全部 `1`
- totalSamples48k：全部 `960`
- inspector duration：全部 `20 ms`
- audio CMD=1 基本全部来自 AE22；START filename 从 AE23 返回
- protocol SEQ：所有 audio CMD=1 均为 `0`
- `probeQueueOverflow=0` / `genericObserverOverflow=0`
- AE22 / AE23 CRC error 均为 `0`
- negotiated MTU `517`

冻结结论：

- **G2 PASS（否定性事实）**：realtime audio 的 protocol SEQ 恒为 `0`，不可用于 duplicate / loss / reorder / timeline 判定。正式 ingress 只把 SEQ 作为原始诊断字段。
- 因没有 packet counter，Stage 15 不做基于 SEQ 的 PLC，也不声称精确 packet loss count。

第一轮曾暂定“160B body = 单一完整 Opus packet”，但该 G1 结论已被 15.0B cadence 事实重新打开，不能进入正式 decoder mainline。

### 15.0B — Cadence / silence semantics fact gate

15.0B Probe 增加：

- `protocolSeqHistogram`，彻底移除 duplicate/gap candidate 语义；
- whole-body inspector samples 累加出的 `mediaDurationMs`；
- START→STOP monotonic `wallElapsedMs` 与 `mediaWallRatio`；
- body inter-arrival histogram：`<25 / 25–49 / 50–99 / 100–249 / >=250 ms` 与最大 gap；
- `beforePause / paused / afterResume` body 计数；
- device-reported filename；
- START、first audio、PAUSE TX、device paused、RESUME TX、device resumed、first audio after resume、STOP、last audio 的 monotonic timeline offsets。

15.0B 真机矩阵已完成：

1. 静音样本：`401 bodies / 32.479 s / 12.346 bodies/s`；
2. 持续声音样本：`322 bodies / 26.186 s / 12.297 bodies/s`；
3. 声音→静音→声音样本：`486 bodies / 39.354 s / 12.349 bodies/s`。

三组共同事实：

- body 长度始终 `160 bytes`；
- protocol SEQ 始终 `0`；
- whole-body inspector 始终返回 `960 samples @48k = 20 ms`；
- body rate 稳定约 `12.3–12.35 / s`；
- `mediaWallRatio` 稳定约 `0.246–0.247`；
- 最大 inter-arrival gap 仅约 `147–154 ms`，静音阶段没有 `>=250 ms` transport gap；
- queue overflow / observer overflow / CRC error 均为 `0`。

15.0B 结论：

- **PASS：排除普通静音抑制 / VAD 停包假设**。静音、持续声音和声音/静音切换下 transport cadence 基本一致。
- `160B body` 的 arrival cadence 约 `80 ms/body`；这与现有设备文件链路已验证的 `40B Opus packet × 4 × 20 ms = 80 ms` 高度吻合。
- whole-body `inspect(160B)` 返回 valid/20ms 只能证明该 byte sequence 可被 libopus 语法解析，不能证明协议 packet boundary 就是 160B。
- 因此 **G1 第一轮结论撤回**，进入 15.0C 直接验证 `160B transport body = 4 × 40B Opus packet`。

### 15.0C — Packet boundary fact gate

15.0C 只扩展临时 Probe，不进入正式 realtime decoder pipeline。

对每个 `TYPE=1/CMD=1` body：

- 保留 whole-160B Opus inspect 作为对照；
- 仅当 body 恰好 `160 bytes` 时按固定边界切成 4 个 slice：`[0..39] / [40..79] / [80..119] / [120..159]`；
- 对 slot0/1/2/3 分别执行 Opus inspect；
- Probe worker 内创建一个 `16 kHz / mono` persistent Opus decoder，按 transport arrival order 连续 decode 四个 40B slice；
- 统计每 slot valid/invalid、channel、samples48k、duration、decodedSamples16k；
- 累加 `split40MediaDurationMs` 与 `split40DecodedDurationMs`；
- 同时输出 `split40MediaWallRatio` 与 `split40MediaArrivalSpanRatio`；
- 非 160B body 只记录 rejected，不做猜测性 framing。

15.0C G1 PASS 条件：

- 160B body 占本轮 realtime audio body 的全部或稳定绝大多数；
- 4 个 slot 均稳定 Opus valid；
- 各 slot 稳定 `channels=1`；
- 各 slot 稳定 `960 samples @48k = 20 ms`；
- persistent decoder 各 slot 稳定输出 `320 samples @16k = 20 ms`；
- `4 × 40B` 累计媒体时长与 body arrival span 接近 1:1；
- `probeQueueOverflow=0`、`genericObserverOverflow=0`、CRC error=0。

若通过，则正式冻结：

`TYPE1/CMD1 160B transport aggregation unit → 4 × fixed-40-byte Opus packets → persistent Opus decoder`

Probe 中的 decoder 只用于 packet-boundary 验证，不输出到播放器、Room、Stage 16A 或正式 consumer。

### 15.0 Probe candidate

- QA development identity：versionCode `90` / versionName `1.1.0-stage15`。
- 临时第二 launcher `Voica Stage15 Probe` 只服务真机事实采集；Final Freeze 前必须移除或并入正式诊断入口。
- Probe 启动时先 park 正常 `DeviceRepository`；若正常产品 session 仍持有活跃 transport，则主动断开后再创建独立 probe session，禁止同一设备双 GATT owner。
- 15.0C 允许在 worker 线程执行 temporary Opus inspect/decode 以验证 packet boundary；仍不形成正式 realtime pipeline。
- `probeQueueOverflow > 0` 或 `genericObserverOverflow > 0` 时，本轮数据不得用于冻结 packet boundary/cadence 语义。

## 15.1 — Dedicated realtime packet ingress

建立 generation-scoped realtime packet source，绕开 generic observer SharedFlow 作为音频 truth。BLE callback 只做 parse/classify/enqueue/counter。

待 15.0C G1 通过后，正式 ingress 目标顺序为：

`TYPE1/CMD1 160B arrival order → bounded transport-body queue → 4×40B deaggregation → Opus packet validation → persistent decoder`

protocol SEQ 不参与 audio ordering。

## 15.2 — Arrival / bounded encoded buffer

容量必须基于 15.0C 最终 transport/body 语义冻结。

若确认 `160B body = 80 ms media`，则低延迟 0.5–1.0 s encoded ingress 初始目标约为 `8–12 bodies`，而不是此前候选的 64 bodies。最终同时设置 packet/body count 与 encoded bytes 双重硬上限。

没有可用 audio packet sequence 时：

- 不做基于 SEQ 的 duplicate / loss / reorder；
- 不做 PLC；
- BLE disconnect、queue overflow、decoder error、无法解释的 transport stall 均产生 explicit discontinuity；
- 恢复后创建新 epoch，不猜测缺失 packet 数。

## 15.3 — Continuous Opus decode

复用现有 `engine:opus` persistent decoder。每个连续 epoch 维护 decoder state；decoder error / unknown loss 产生 discontinuity。PLC 仅在未来存在可验证 loss duration 与明确 API 支持时再考虑。

## 15.4 — Canonical PCM + timeline

输出固定 `16 kHz / mono / PCM16`。建立 `realtimeSessionId + epochId + epochStartSampleIndex`；sample index 为媒体时间唯一真值。disconnect/reconnect、pause/resume 默认创建新 epoch。

普通自然静音在 15.0B 中未表现为 transport discontinuity，因此不得因内容静音本身创建新 epoch。

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

Stage 15 开发分支使用：

- versionCode `90`
- versionName `1.1.0-stage15`

Final Freeze 时再决定是否正式 `1.1.0`。

## Freeze / merge gate

只有 15.0/15.0B/15.0C fact gate、自动化、真机 QA、30/60/120 分钟稳定性全部通过，且用户明确说“测试通过”，才创建 `STAGE_15_FREEZE.md` / `STAGE_15_HANDOFF.md`、跑 Final CI 并 merge。merge 后重新核验 main/version/Room/model pin 后才解锁 Stage 16A。
