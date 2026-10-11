# Stage 15 — BLE 实时音频链路 修订需求 V1

状态：**已确认**

## 目标

建立 QS668 / CB08 的实时音频基础链路：

`BLE TYPE=1 Audio → Opus → PCM16 → canonical 16 kHz mono PCM16 → realtime canonical timeline`

Stage 15 只负责可靠的实时 canonical PCM source，不实现 Stage 16A 的完整 streaming ASR 产品逻辑或实时文字 UI。

## 权威链路

1. GATT notification 经现有 FrameParser / CRC 解析。
2. TYPE=1 / CMD=1 使用专用、generation-scoped、bounded realtime ingress，不能以 generic SharedFlow 作为音频 truth。
3. BLE callback 只允许 parse / classify / bounded enqueue / counter，不执行 JNI decode、resample、Room 写入或高频 UI 更新。
4. Opus 使用连续 decoder session，输出 canonical `16 kHz / mono / PCM16`。
5. canonical 时间以 sample index 为唯一媒体时间真值；禁止以 wall clock、UI 毫秒或 BLE sequence 作为最终 timeline。

## Epoch / discontinuity

- realtime session 与 durable recording timeline 分离。
- BLE disconnect/reconnect 必须产生新 epoch，不能伪造连续 timeline。
- pause/resume 初始安全契约同样结束旧 epoch、恢复时创建新 epoch，除非真机事实证明可以安全连续。
- unknown/unquantifiable loss、buffer overflow、decoder error 等需要显式 discontinuity + decoder reset + new epoch。
- Stage 16A 在 epoch change / discontinuity 时必须 finalize/reset/cancel 当前 streaming ASR session，不能静默跨越。

## Packet / sequence fact gate

正式 decoder 主链路前必须通过 QS668 真机 Stage 15.0 fact gate，确认：

- TYPE=1 CMD=0 body；
- TYPE=1 CMD=1 body length histogram；
- CMD=1 notification source characteristic；
- one CMD=1 body 是否等于 one complete Opus packet；
- Opus packet duration / channels / bandwidth；
- SEQ 是否为 realtime 独立连续 domain、是否跨 characteristic / command 共享；
- pause/resume/stop tail/reconnect 行为。

在 SEQ 语义确认前，只允许记录 sequence anomaly，不能宣称 packet loss。

## Buffer / backpressure

- encoded queue 同时限制 packet count 与 bytes；目标 backlog 约 0.5–1.0 s，精确容量由 Stage 15.0 真机 packet duration 决定。
- consumer PCM queue 同样保持低延迟、bounded。
- overflow 不允许静默 DROP_OLDEST / DROP_LATEST 后继续假装连续；必须记录 overflow、丢弃 stale backlog、产生 discontinuity、reset decoder、从 live edge 新 epoch 恢复。

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
