# Stage 16A 修订开发规划 V1

状态：**APPROVED — 用户已确认；允许进入开发**

日期：2026-10-12

目标：**Stage 16A — 本地 True Streaming ASR**

开发分支：`stage16a-local-true-streaming-asr`

## 1. P0 接管基线

开发启动时重新核验：

- `main` HEAD：`c16ae69bfb3c106ae7ed0f832a38f34b78e70394`
- 当前 open PR：无
- Final Stage 15 Android PR CI：#1327 / SUCCESS
- Final Stage 15 Android Production Gate：#187 / SUCCESS
- app：versionCode 90 / versionName `1.1.0-stage15`
- Room：v12，schema 1..12 在库
- sherpa-onnx：1.13.8
- production model channel：Stage 15 冻结 pin 不变
- Stage 15 Final Freeze / Handoff 已存在
- Stage 16A 当前权威 streaming contract：V3

Stage 15 merge commit 明确记录 60/120 分钟 soak 为用户 waiver，不得改写成 PASS。

P0 只允许文档/规划同步，不修改 production 逻辑。

## 2. 版本与数据库策略

若正式编码前版本仍为 90 / `1.1.0-stage15`，Stage 16A 候选计划提升至：

- versionCode 91
- versionName `1.1.0-stage16a`

正式修改前再次核验。

Stage 16A 计划引入 Room v13，用于 realtime durable domain；必须提供 `MIGRATION_12_13`，保留全部 1..12 schema/migration lineage，禁止 destructive migration。

production model channel 默认不变；只有模型包/manifest 存在真实缺口时才另行提出 promotion 决策。

## 3. 核心架构

```text
RealtimeCanonicalAudioSource
        ↓
CanonicalRealtimeEventSource adapter
        ↓
RealtimeTranscriptCoordinator   ← 唯一生产 consumer
        ├─ realtime local audio persistence
        ├─ diagnostics / metrics
        ↓
StreamingAudioBuffer
        ↓
StreamingChunkAssembler
        ↓
Streaming VAD
        ↓
RealtimeAsrController
        ↓
HypothesisStabilizer
        ↓
RealtimePunctuationPolicy
        ├─ durable FINAL persistence
        └─ conflated UI StateFlow
```

`RealtimeTranscriptCoordinator` 必须是 Stage 15 canonical source 的唯一生产 `receive()` consumer，内部再 fan-out。QA probe 不得与生产消费者并发竞争同一 destructive stream。

## 4. 输入扩展 seam

Stage 16A 增加上层统一输入抽象，例如 `CanonicalRealtimeEventSource`，当前实现由 Stage 15 canonical source adapter 提供。

该抽象仅用于上层解耦，不改变 Stage 15 frozen contract。

Stage 16A.1 后续可以增加手机麦克风 canonical source，复用同一 buffer/chunk/VAD/ASR/UI，不在 Stage 16A 实现 `AudioRecord`。

## 5. StreamingAudioBuffer

要求：

- bounded frame/sample/duration limit；
- producer/consumer 调度解耦；
- 保留 sessionId/epochId/sample range；
- overflow 显式形成 streaming discontinuity；
- 丢弃 stale backlog 后恢复 live edge；
- 统计 current/peak depth、overflow、dropped samples；
- 绝不反压 BLE callback / Stage 15 decoder。

## 6. StreamingChunkAssembler

候选 chunk duration benchmark：40 / 80 / 100 / 160 / 200 ms。

每个 chunk 保留：sessionId、epochId、startSampleIndex、endSampleIndexExclusive。

禁止跨 epoch 组 chunk。terminal / discontinuity 时支持 tail flush/drop policy。

最终默认粒度由 Small 与 Large CTC 同批真机 latency/CPU/PSS/thermal 数据确定，不在规划文档硬编码。

## 7. Streaming VAD

优先复用当前正式 Silero VAD 能力并增加 streaming adapter，不重新开启 VAD 模型选型。

VAD 只决定 speech activity / utterance boundary，不改变 canonical sample truth。

## 8. Recognizer 与模型

正式产品模型：

- Small Bilingual Zipformer INT8：默认；
- Chinese Large CTC INT8：中文高质量。

两者使用现有 sherpa 1.13.8 true streaming session API：openSession → acceptSamples/decode → finishInput → reset/close。

Stage 16A 不恢复 Large Transducer / AUTO，也不把文件 ASR 模型伪装成 streaming。

active session 模型固定；设置修改仅在下一 realtime session 生效。

## 9. Recognizer lifecycle state machine

建议：

`CLOSED → OPEN → ACCEPTING → FINALIZING → FINALIZED → CLOSED`

终止路径必须幂等；`finishInput()` 在上层逻辑 exactly-once。

STOP、DEVICE_RECORDING_ENDED、TRANSPORT_CLOSED、重复 Stop、异常 close 不能生成重复 FINAL。

## 10. Hypothesis Stabilizer

内部状态：`stablePrefix + mutablePartialTail`。

必须覆盖：

- 中文连续扩展/回缩/修正；
- 英文 token / leading whitespace；
- repeated characters/tokens；
- empty hypothesis；
- duplicate hypothesis；
- final correction；
- duplicate final。

Stable prefix 正常单调增长；PARTIAL 只更新 mutable tail；FINAL 做最终 reconcile，不能再次追加整段导致重复。

## 11. Punctuation

- PARTIAL：不跑外部标点；
- STABLE：低频标点先 benchmark；如果 latency/CPU/churn 不合格则退化为不标点；
- FINAL：正式 CT-Transformer punctuation；
- punctuation failure 退化为未标点 FINAL，不使 ASR 失败。

## 12. Lifecycle 映射

### SessionStarted

创建 realtime run/context，snapshot 模型与参数，启动当前 local segment。

### EpochStarted(SESSION_START)

创建新的 recognizer continuity。

### CONTROL_BOUNDARY

PAUSE 时 flush/best-effort final/关闭当前 continuity；RESUME 新 epoch、新 recognizer continuity。

### DISCONTINUITY / DECODER_RECOVERY / ConsumerDiscontinuity

保存已确定内容，丢弃不可靠 partial tail，显式记录 boundary，从 live edge 新建 continuity；禁止补 silence 或伪连续。

### SessionEnded(STOP / DEVICE_RECORDING_ENDED)

统一 graceful finalization：drain → assembler flush → VAD close → finishInput exactly once → final reconcile → punctuation → persistence → audio segment finalize → close。

### TRANSPORT_CLOSED

finalize/save 当前手机 segment，设备本机录音继续。

Reconnect 后仅在确认同一设备录音 + 断联前 realtime intent ACTIVE + 用户未主动关闭时自动恢复；恢复必须新 transport generation / canonical session / segment / recognizer。

## 13. Room v13 计划

正式 schema 在 P2 基于当前 v12 再核验后冻结。规划实体：

### RealtimeRunEntity

保存 recording/device identity、audio source、model/runtime/config snapshot、start/end/status/endReason。

### RealtimeMediaSegmentEntity

保存 runId、segmentIndex、transportGeneration、canonicalSessionId、sample count、local path/filename、device filename reconciliation、endReason、complete-device-recording flag。

### RealtimeUtteranceEntity

只持久化 FINAL；保存 run/segment/utterance index、sample range、rawFinalText、finalText。

### RealtimeTimelineMarkerEntity

保存 PAUSE / RESUME / DISCONTINUITY / TRANSPORT_CLOSED / RECONNECTED 等 durable boundary。

Realtime domain 不破坏现有文件 `TranscriptionEntity` canonical-file lineage；realtime transcript 与文件 ASR 独立共存。

## 14. UI / UX

采用“阅读器 + 极简录音控制器”。

核心页面：

- 顶部：录音状态、时长、当前 realtime 模型、连接状态；
- 轻量动态波形 32–44dp 级；
- 主体：连续阅读正文；
- STABLE/FINAL 正常正文；PARTIAL 为底部浅色 mutable tail；
- 底部：Pause/Resume + Stop。

自动滚动：

- 用户在 live bottom：FOLLOW_LIVE；
- 用户上滑：READING_HISTORY，停止自动拉底；
- 显示“回到实时”按钮恢复 FOLLOW_LIVE。

Pause/Resume、断联/恢复使用轻量 timeline separator。

Stop 后显示“正在完成最后内容…”，finalization 完成后进入录音详情。

Stage 16A 不加入笔记/照片 `+` 按钮；该入口在 Stage 16A.2 实现。

## 15. Mini Live Bar / Notification / Background

Realtime runtime 不属于 Activity/Compose 生命周期。

离开 realtime 页面后主页显示 Mini Live Bar，可返回当前 live transcript。

优先复用/扩展 Stage 15 foreground execution，不创建互相竞争的新 FGS。

Notification 提供录音+实时转写状态，并支持进入 live page；Pause/Stop action 是否直接暴露以现有 command/lifecycle 安全性实现为准。

## 16. 并发与性能边界

必须隔离：

`BLE callback != Opus decode != canonical consumer != ASR inference != punctuation != Room/file IO != Compose render`

ASR inference 使用独立 bounded execution；punctuation 与 persistence 不得阻塞 canonical ingest。

UI 使用 conflated/deduplicated StateFlow；native decode 高频 partial 不直接触发整屏重组。

## 17. 错误降级

- model missing/load fail：设备录音继续；
- punctuation fail：未标点 FINAL 可用；
- ASR backlog overflow：explicit discontinuity + live edge；
- Room write fail：不得停止设备录音或阻塞 BLE；
- UI recreation/navigation：runtime 继续；
- transport close：保存当前 part，设备录音继续；
- reconnect：符合条件才自动恢复；
- repeated Stop/finalize：幂等。

## 18. 开发分段

### P0 — Baseline / Docs / Gate

重新核验 main/PR/CI/AGENTS/Freeze/Handoff/V3/version/Room/model pin/runtime；创建分支；记录已确认需求与规划；不改 production code。

### P1 — Core Streaming Infrastructure

实现 source adapter、coordinator、StreamingAudioBuffer、StreamingChunkAssembler、diagnostics contracts 与 unit tests。

### P2 — Persistence / Room v13

实现 12→13 migration、realtime entities/DAO/repository/schema tests。

### P3 — Small Bilingual Bring-up

完成 canonical PCM → buffer → chunk → VAD → Small recognizer → PARTIAL/FINAL 真正 realtime 主链。

### P4 — Stability Layer + Large CTC

实现 stabilizer/STABLE/final reconcile/token timing，并接入 Large CTC；补齐两个 engine lifecycle tests。

### P5 — Punctuation + Lifecycle Recovery

实现 final/stable punctuation policy、Pause/Resume、discontinuity、transport close/reconnect、TYPE3/device end、segment split/auto recovery。

### P6 — Product UI / UX + Background

实现 realtime screen、smart autoscroll、waveform、Mini Live Bar、notification/background/screen-off、错误 UI、模型切换语义。

### P7 — Performance / Final Candidate

只做 bugfix、profiling、benchmark、stability 与 regression，不增加新产品范围。

## 19. 自动化测试

至少覆盖：

- buffer FIFO/bounds/overflow/live-edge；
- chunk exact samples/tail flush/no cross-epoch；
- stabilizer 中文/英文/回缩/修正/重复/final；
- Small recognizer open/accept/partial/final/reset/close；
- Large CTC 同等完整 lifecycle；
- START/STOP、PAUSE/RESUME、TYPE3 end、discontinuity、transport close、reconnect、supersede、runtime close；
- FINAL exactly once；
- Room v12→v13 migration / fresh install / cascade / multiple segments。

## 20. 真机 QA

至少：

S1 Small START→PARTIAL/STABLE/FINAL→STOP；
S2 Large CTC 同流程；
S3 Pause/Resume；
S4 TYPE3 SAVE；
S5 second START；
S6 App disconnect/reconnect；
S7 Bluetooth OFF/ON；
S8 background；
S9 screen-off；
S10 artificial ASR slowdown；
S11 model missing；
S12 active session model setting change。

必须同时确认设备正式录音文件与 Stage 15 anomaly counters 不回归。

## 21. 长时与性能 Gate

Small 与 Large CTC 分别记录：

- 30 min
- 60 min
- 120 min

除非用户再次明确批准 waiver，否则不跳过。

每轮至少记录 model、chunk、threads、partial/stable/final latency、RTF、buffer depth/overflow、CPU、RAM/PSS、thermal、battery（可测时）、Stage 15 anomaly counters。

## 22. Freeze / Handoff 门禁

仅当自动化测试、CI、Production Gate、两个模型真机 QA、Pause/Resume、断联恢复、segment persistence、background/screen-off、长时与性能证据、Stage 15 regression 全部满足，且用户明确回复“测试通过”后，才允许创建 Stage 16A Final Freeze/Handoff 并合并 main。

在此之前不得宣称 Stage 16A 完成。
