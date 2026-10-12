# Stage 15 — BLE 实时音频链路 修订需求 V1

状态：**已确认 / Stage 15.0 fact gate 已冻结**

## 目标

建立 QS668 / CB08 的实时音频基础链路：

`BLE TYPE=1 Audio → Opus → PCM16 → canonical 16 kHz mono PCM16 → realtime canonical timeline`

Stage 15 只负责可靠的实时 canonical PCM source，不实现 Stage 16A 的完整 streaming ASR 产品逻辑或实时文字 UI。

## 权威链路

1. GATT notification 经现有 FrameParser / CRC 解析。
2. TYPE=1 / CMD=1 使用专用、generation-scoped、bounded realtime ingress，不能以 generic SharedFlow 作为音频 truth。
3. BLE callback 只允许 parse / classify / bounded enqueue / counter，不执行 JNI decode、resample、Room 写入或高频 UI 更新。
4. 已冻结 transport framing：`TYPE1/CMD1 160B body → 4 × fixed-40-byte Opus packets`。
5. 每个 40B Opus packet 为 mono、`960 samples @48k = 20 ms`；16k mono decoder 真机验证输出 `320 samples`。
6. 一个 160B transport block 对应 `80 ms / 1280 canonical samples @16k`。
7. Opus 使用连续 decoder session，输出 canonical `16 kHz / mono / PCM16`。
8. canonical 时间以实际 decoder 输出 sample count 为唯一媒体时间真值；禁止以 wall clock、UI 毫秒或 BLE sequence 作为最终 timeline。

## 已冻结 Packet / sequence facts

Stage 15.0 / 15.0B / 15.0C 真机 fact gate 已完成：

- CMD=1 body 固定 `160 bytes`；
- steady-state cadence 约 `80 ms/body ≈ 12.5 bodies/s`；
- 160B body 可稳定拆为 `4 × 40B`；
- 15.0C 样本 `417/417` body 合格，`1668/1668` 40B packet Opus inspect valid；
- `1668/1668` 40B packet persistent decode 成功，全部输出 `320 samples @16k`；
- split40 media duration 与 first→last audio arrival span 比值约 `1.003`；
- 普通静音期间 transport 仍连续，不存在已观察到的 VAD/silence-suppression 停包；
- realtime audio CMD=1 基本来自 AE22；START filename response 可来自 AE23；
- audio CMD=1 protocol SEQ 恒为 `0`，**不是** audio packet counter/order domain；
- Probe queue overflow / generic observer overflow / CRC error 在 fact gate 样本中均为 `0`。

因此：

- protocol SEQ 只能保留为原始诊断字段；
- 禁止基于 SEQ 做 duplicate、reorder、loss count、PLC 或 timeline；
- whole-body `inspect(160B)` 返回 valid/20ms 不能作为 packet boundary，正式 framing 以 `4×40B` 真机事实为准。

## Epoch / discontinuity

- realtime session 与 durable recording timeline 分离。
- BLE disconnect/reconnect 必须产生新 epoch，不能伪造连续 timeline。
- pause/resume 初始安全契约同样结束旧 epoch、恢复时创建新 epoch，除非后续真机事实证明可以安全连续。
- unknown/unquantifiable loss、buffer overflow、decoder error 等需要显式 discontinuity + decoder reset + new epoch。
- 普通自然静音本身不是 discontinuity；15.0B 已证明静音时 transport 持续。
- Stage 16A 在 epoch change / discontinuity 时必须 finalize/reset/cancel 当前 streaming ASR session，不能静默跨越。

## Buffer / backpressure

- encoded ingress 以 160B/80ms transport block 为单位，同时限制 block count 与 bytes。
- Stage 15.1/15.2 初始冻结上限：`12 blocks + 2048 encoded bytes`；12 blocks ≈ `960 ms / 1920 bytes`。
- deaggregation 在 callback 之外执行：`160B block → 4 × 40B packet`。
- consumer PCM queue 同样保持低延迟、bounded。
- overflow 不允许静默 DROP_OLDEST / DROP_LATEST 后继续假装连续；必须记录 overflow、丢弃 stale backlog、产生 discontinuity、从 live edge 恢复；后续 decoder 层在 discontinuity 处 reset/new epoch。

## Lifecycle

- recording(TYPE=3) 与 realtime(TYPE=1) 初始保持独立，不假设互相自动启动。
- 复用现有 `ServiceBackedDeviceRepository` / connectedDevice FGS 单 lease；禁止第二个 realtime FGS，禁止 per-packet `startForegroundService`。
- Activity recreation / background / screen-off 不应中断有效 device-session realtime worker。
- user disconnect / Bluetooth off 必须停止 ingress/worker、关闭 decoder、结束 session/epoch。
- process death 不承诺无缝恢复；新进程重新连接视为新 realtime session/epoch。

## Persistence / model

- Room 保持 v12；realtime packet/PCM/progress/diagnostics 默认 process-local transient，不高频写 Room。
- production model channel 保持现有 pin，不在 Stage 15 promotion。

## True-device alignment

建议同一录音同时采集 BLE realtime canonical PCM 与设备最终录音文件，统一 canonicalize 后做 waveform cross-correlation，测量固定 delay/offset、pause/resume gap、stop tail，以及 BLE loss 是否影响最终 durable file。

## Final gate

Stage 15 必须经过自动化测试、真机功能 QA、30/60/120 分钟稳定性；只有用户明确“测试通过”后才允许 Freeze/Handoff/Final CI/merge。
