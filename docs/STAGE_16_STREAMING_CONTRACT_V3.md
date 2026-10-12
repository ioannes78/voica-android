# Stage 16 Streaming ASR Contract V3

状态：**PLANNED / APPROVED FOR FUTURE STAGE 16A DEVELOPMENT**

本文件创建于 Stage 15 开发期间，作为 `docs/STAGE_16_STREAMING_CONTRACT_V2.md` 的显式版本化后继。

V2 仍是 Stage 13A 的历史冻结事实；V3 不改写其历史验收结论。未来 Stage 16A 开发若与 V2 在 realtime audio input、buffering、chunk shaping 或 lifecycle seam 上冲突，以 V3 为准。Stage 13A 已冻结的 realtime 模型矩阵继续保留，除非未来另有明确产品决策与版本化契约。

## 1. Stage 16A 输入边界

Stage 16A 不直接消费 BLE notification、Opus packet、GATT generation、MTU 或 protocol sequence。

权威输入为 Stage 15 提供的：

`RealtimeCanonicalStreamEvent`

其中 canonical audio 固定为：

- 16 kHz
- mono
- PCM16
- canonical sample index 为媒体时间真值

Stage 16A 不得以 wall clock、BLE arrival timestamp 或 UI 毫秒替代 canonical sample timeline。

## 2. 正式音频整形层

Stage 16A 主链修订为：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Streaming ASR → PARTIAL/STABLE/FINAL → punctuation`

禁止：

`BLE callback → StreamingAsrEngine.acceptSamples()`

### 2.1 StreamingAudioBuffer

职责：

- bounded canonical PCM buffering；
- 吸收 producer/consumer cadence mismatch；
- 吸收 Android 调度产生的短时 burst/jitter；
- 保持 session / epoch / canonical sample identity；
- 显式传播 discontinuity；
- consumer 慢时不得反向阻塞 Stage 15 BLE callback / Opus decoder。

约束：

- 必须有 frame/sample/duration hard limit；
- overflow 不允许静默假装连续；
- overflow 后必须产生显式 streaming discontinuity，并从 live edge 恢复；
- 不允许通过 wall clock 补 PCM；
- 不允许无限缓存。

### 2.2 StreamingChunkAssembler

职责：

- 将 Stage 15 canonical PCM frame 聚合为 recognizer/runtime 需要的 chunk；
- chunk size 由 engine capability/config 决定；
- 支持例如 40/80/100/160/200 ms 等经过 benchmark 证明的粒度；
- chunk 必须保留 canonical sample range；
- 不改变 Stage 15 canonical timeline。

20 ms canonical frame 只是当前 Stage 15 正常 decoder 输出事实，不得要求所有后续 engine 以 20 ms 为唯一调用粒度。

## 3. 本地 Streaming ASR 调度

本地 engine 不强制按 wall-clock cadence sleep。

当足够 canonical PCM 可组成 engine 所需 chunk 时，可以尽快执行：

`acceptSamples(...) / decode()`

调度目标是：

- 合理 JNI/native 调用粒度；
- Streaming VAD window；
- recognizer decode cadence；
- CPU/RAM/thermal 平衡；
- 低 latency。

不得为了模拟“实时感”故意让可处理 PCM 长时间排队。

## 4. Lifecycle 映射

Stage 16A 必须按 Stage 15 lifecycle event 驱动 recognizer：

- `SessionStarted`：建立新的 streaming logical session；
- `EpochStarted`：建立新的 audio continuity epoch；
- `PcmFrame`：进入 StreamingAudioBuffer；
- `ConsumerDiscontinuity`：finalize/reset/cancel 当前 recognizer continuity，禁止静默跨越；
- `SessionEnded`：flush buffer/assembler，执行 finalization，再关闭该 logical session。

Stage 15 的 `STOP`、`TRANSPORT_CLOSED`、`SUPERSEDED_BY_NEW_SESSION`、`RUNTIME_CLOSED` 等 terminal reason 可以映射为不同 finalization policy，但不得让 unrelated epoch 共用一个连续 token timeline。

### 4.1 Device Recording Independence Invariant

录音卡本机录音与手机端 realtime transport/session 必须视为两个独立 lifecycle domain。

冻结原则：

- App disconnect、BLE transport loss、Bluetooth Off、App process failure、手机异常不得因为 realtime 链路失败而主动停止录音卡本机正在进行的录音；
- `TRANSPORT_CLOSED` 只终止手机端当前 canonical/realtime logical session；
- transport close 不得为了“收口”而向设备补发 recording STOP；
- 设备重新连上后，必须先把它视为新的 transport generation；不得把断联前后的 PCM 伪造成同一条连续 canonical timeline。

设备录音可以从头到尾连续存在，而手机端 realtime audio 可以因为断联形成多个独立 media segment。

### 4.2 Reconnect recovery orchestration

Stage 15 runtime 只负责 generation-safe 的底层媒体 lifecycle，不负责决定产品层是否自动恢复实时转录。

未来 Stage 16A 应增加上层 realtime session coordinator：

`断联前 realtime transcription ACTIVE → transport close → finalize current segment/session → reconnect → 确认设备仍处于同一次 recording → 自动调用新的 realtime START → 建立新的 canonical logical session → 恢复实时转录`

必须同时满足：

- reconnect 后的 START 是新的 logical session，不是旧 session continuity；
- canonical sample index 从新 session 自己的 0/起始点重新建立；
- 不补断联期间 silence；
- 不以 wall clock 跨段拼接媒体；
- 若无法确认仍是同一次设备录音，不得自动恢复旧 realtime task；
- 用户主动关闭实时转录后不得自动重新开启。

### 4.3 Realtime local audio segment persistence

一次设备物理录音允许对应手机端一个或多个 realtime local audio segment。

产品行为冻结：

- 全程无断联：录音结束后，手机端 realtime 本地音频应在设备正式文件名确定后，尽量以与录音卡一致的文件名保存；
- 中途断联：断联前已收到的 realtime 音频立即形成一个独立本地 segment；
- 重连并恢复 realtime 后，再建立新的本地 segment；
- 最终每个 segment 必须可区分，不能把缺失区间拼成一个伪完整文件；
- 文件名只是展示/导出语义，内部关联必须依赖稳定 ID，而不是依赖文件名匹配。

建议未来持久化实体至少包含：

- `deviceRecordingIdentity`
- `segmentId`
- `segmentIndex`
- `transportGeneration`
- `logicalSessionId`
- `startedAt`
- `endedAt`
- `endReason`
- `canonicalSampleCount`
- `localFilename`
- `deviceFilename`（设备正式文件名确定后回填）
- `isCompleteDeviceRecording`

分段展示文件名可以采用 `part01 / part02` 等可读后缀，但具体命名规则在正式产品化阶段再冻结。

## 5. Streaming session API

继续保留 V2 冻结语义：

1. `openSession()`
2. zero or more `acceptSamples(...)` + `decode()`
3. `finishInput()`
4. `reset()` 或 `close()`

`AsrHypothesisStability`：

- `PARTIAL`
- `STABLE`
- `FINAL`

`FINAL` iff `isFinal=true`。

PARTIAL/STABLE 不得持久化冒充 FINAL。

## 6. Token timing

Token timing 继续使用 session-relative sample timing，再通过已知 canonical session/epoch sample range映射到产品 timeline。

规则：

- 有可靠 model timing 才保存 timing；
- 不得伪造 token timestamp；
- UI 毫秒只是 presentation；
- discontinuity / new epoch 后必须重建 timeline relation。

## 7. Frozen realtime model matrix

继承 Stage 13A V2 冻结事实：

- Small Bilingual Zipformer zh-en INT8：light/default；
- Chinese Large CTC INT8：high-quality Chinese realtime。

继续禁止：

- 把 SenseVoice / Qwen3-ASR 文件模型通过 chunking 冒充 true streaming；
- 静默恢复 Chinese Large Transducer；
- 静默增加 AUTO model routing。

新增 realtime model 仍需 capability/runtime/real-device validation 和明确产品决策。

## 8. Punctuation

继续使用两层原则：

- PARTIAL tail：避免不稳定标点 churn；
- STABLE：稳定范围可做轻量标点；
- FINAL：执行最终 punctuation/finalization 后再持久化。

模型已有可靠 native punctuation 时不得重复处理。

## 9. Backpressure 与 discontinuity

Stage 16A 必须显式测试：

- StreamingAudioBuffer overflow；
- engine artificial slowdown；
- Stage 15 `ConsumerDiscontinuity`；
- epoch transition；
- transport close；
- repeated session；
- final flush。

任何 overflow/drop 都不能通过伪造 silence、wall-clock padding 或 sample index 跳跃来隐藏。

## 10. 与 Stage 18 的共享边界

`StreamingAudioBuffer` / `StreamingChunkAssembler` 应设计为 engine/provider 无关的媒体整形能力。

Stage 18 可以复用这一 canonical shaping contract，但云端额外增加：

- `ChunkScheduler`
- `NetworkSendQueue`
- `CloudStreamingSession`
- WebSocket heartbeat/reconnect

这些网络职责不得反向进入 Stage 15 canonical source。

Device Recording Independence Invariant 同样适用于 Stage 18：云端 Provider、WebSocket、网络或手机故障不得停止设备本机录音；只能终止或切分手机/云端 realtime session。

## 11. Stage 16A 验收

至少覆盖：

- START→STOP 正常实时识别；
- Pause/Resume epoch transition；
- reconnect/new session；
- reconnect 后同一设备录音的自动 realtime recovery；
- reconnect 前后本地 segment 分段持久化；
- 全程无断联时本地文件与设备正式文件名对齐；
- buffer overflow explicit discontinuity；
- local engine slowdown；
- PARTIAL/STABLE/FINAL order；
- finalization；
- 30/60/120 分钟稳定性；
- CPU/RAM/PSS/thermal/latency。

## 12. Compatibility rules

Stage 16A 不得：

- 直接绑定 BLE/GATT；
- 建立第二套 canonical timeline；
- 用 wall clock 作为媒体真值；
- 无限缓存 PCM；
- 把网络 cadence/heartbeat 逻辑塞进本地 engine contract；
- 绕过 Model Manager capability/lineage；
- 因 transport/network/App 异常主动停止设备本机录音；
- 跨 transport generation 拼接伪连续本地音频文件。

## 13. Authority

历史冻结：`docs/STAGE_16_STREAMING_CONTRACT_V2.md`

后续 Stage 16A 当前权威契约：**本文件 V3**。

Stage 18 云端实时 ASR 另见：`docs/STAGE_18_CLOUD_STREAMING_CONTRACT_V1.md`。
