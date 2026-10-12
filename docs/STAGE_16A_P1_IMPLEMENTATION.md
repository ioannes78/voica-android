# Stage 16A P1 — Core Streaming Infrastructure

状态：**IMPLEMENTED / CI PENDING**

本文件记录 Stage 16A P1 的实现边界；GitHub 当前分支仍是唯一事实来源。

## 1. 范围

P1 只建立本地 true streaming ASR 的 canonical consumer / bounded shaping 基础，不接 recognizer、VAD、Room、产品 UI 或手机麦克风。

实现主链：

`RealtimeCanonicalAudioSource → CanonicalRealtimeEventSource → RealtimeTranscriptCoordinator → StreamingAudioBuffer → StreamingChunkAssembler`

## 2. 单一 canonical consumer

`RealtimeTranscriptCoordinator` 是 Stage 16A 对 Stage 15 destructive `receive()` seam 的唯一生产消费入口。

后续 ASR、持久化、诊断和 UI 不得各自直接调用 Stage 15 canonical source `receive()`。

## 3. StreamingAudioBuffer

- engine/provider 无关；
- PCM frame / sample 双 hard limit；
- 生命周期事件保持有序；
- PCM overflow 时丢弃 stale PCM，保留 live edge；
- overflow 必须插入显式 `BUFFER_OVERFLOW` discontinuity；
- Stage 15 `ConsumerDiscontinuity` 映射为 `UPSTREAM_BACKPRESSURE`；
- 不补 silence，不使用 wall clock 伪造媒体时间；
- input finish 后先 drain 已排队事件再结束。

## 4. StreamingChunkAssembler

- target chunk 使用 sample count 表示，具体 40/80/100/160/200 ms 产品默认值留待 benchmark；
- chunk 保留 `sessionId + epochId + startSampleIndex`；
- sample index 继续是 epoch-local canonical truth；
- 不允许跨 session / epoch 静默拼接；
- sample gap / overlap 必须拒绝而不是补齐；
- epoch 结束可显式 flush short tail，再 reset。

## 5. P1 自动化覆盖

新增测试覆盖：

- canonical lifecycle / PCM 顺序；
- Stage 16 buffer overflow / dropped frames / dropped samples / live-edge recovery；
- upstream discontinuity 保留；
- finish-input drain；
- 多 frame 合并 chunk；
- 大 frame 拆分 full chunk + short tail flush；
- cross-epoch / sample gap 拒绝；
- reset 后新 epoch 从自己的 timeline 开始；
- coordinator 单次启动约束；
- Stage 15 discontinuity normalization。

## 6. 当前不修改

P1 不修改：

- Stage 15 BLE / Opus / canonical source 行为；
- versionCode / versionName；
- Room v12；
- production model channel；
- Small Bilingual / Large CTC runtime；
- 产品 UI。

P1 CI 通过后再进入 P2 Room v13 realtime durable domain。
