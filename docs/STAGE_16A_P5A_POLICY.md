# Stage 16A P5A — Realtime Punctuation and Recovery Policy

状态：**CORE IMPLEMENTATION / CI PENDING**

## 1. 本轮目标

在进入产品 runtime 接线前，先冻结两个 engine-independent policy：

1. realtime 外部标点策略；
2. transport reconnect 自动恢复判定策略。

本轮不修改 Stage 15 frozen transport/runtime，也不接正式 UI。

## 2. Realtime punctuation

新增 `RealtimePunctuationProcessor`：

- PARTIAL 永不运行外部标点；
- STABLE 默认不运行外部标点；只有真机 benchmark 证明 latency/CPU/churn 可接受后才启用 `STABLE_AND_FINAL`；
- FINAL 在 punctuation engine 可用时运行外部标点；
- native hypothesis 已声明 `PunctuationCapability.RELIABLE` 时跳过外部标点，防止 double punctuation；
- punctuation model 缺失时保留原始文本；
- punctuation runtime failure 时回退原始 FINAL，不把 ASR 判为失败；
- coroutine cancellation 不吞掉；
- 同时保留 `rawText` 与 `displayText`，为 Room `rawFinalText/finalText` 写入准备。

## 3. Reconnect recovery policy

新增 `RealtimeAutoRecoveryPolicy`。自动恢复必须同时满足：

- 断联前 realtime intent 为 ACTIVE；
- 用户断联后没有主动关闭 realtime；
- 同一物理设备；
- 当前设备仍在录音；
- 上层已经确认是同一次 physical recording；
- transport generation 已变化。

未知 recording identity 不允许猜测为同一次录音；必须返回 `RECORDING_IDENTITY_UNCONFIRMED`。

## 4. P5 生命周期 seam 核验发现

Stage 15 `RealtimeCommandFence` 的 audio gate 默认关闭，并且当前只有成功提交 START / RESUME command 才会打开。

因此 transport close 后，即使 Stage 16 上层已经确认“同一设备、同一次录音仍在继续”，当前 Stage 15 runtime 也没有一个**不向设备发送 START/RESUME**、仅重新挂接手机 realtime intake 的安全入口。

P5B 必须解决这个 seam，否则 reconnect 自动恢复只能错误地向已经在录音的设备再次发送 START，违反 Stage 16 V3 的 Device Recording Independence。

P5B 约束：

- 不伪造 START/RESUME BLE command success；
- 必须有 generation-aware、本地-only 的 confirmed-existing-recording attach；
- 只有 `RealtimeAutoRecoveryPolicy.RESUME` 后才允许调用；
- attach 必须原子地创建新的 canonical session boundary 并打开新 generation audio gate；
- 必须单独提交 Stage 15 seam 修改并重跑 Stage 15 regression tests。

## 5. 下一步

P5A CI 通过后进入 P5B：

1. 增加 confirmed-existing-recording attach seam；
2. Pause 成功后即时 flush/final 当前 recognizer continuity，而不是等到 Resume 的 CONTROL_BOUNDARY 才 final；
3. 接 durable PAUSE/RESUME/DISCONTINUITY/TRANSPORT_CLOSED/RECONNECTED marker；
4. TYPE3 / STOP / transport close 的 finalization 幂等收口。
