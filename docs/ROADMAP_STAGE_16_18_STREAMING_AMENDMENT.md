# Stage 16A / Stage 18 Streaming Architecture Roadmap Amendment

状态：**APPROVED / AUTHORITATIVE AMENDMENT**

日期：2026-10-12

本文件是 `docs/ROADMAP_STAGE_13C_PLUS.md` 中 Stage 16A 与 Stage 18 部分的版本化修订说明。

它不改写 Stage 13A 历史 Freeze；为 realtime streaming 开发补充 Stage 15 已验证后的 canonical media seam、audio shaping、Stage 16A 产品化与 cloud socket lifecycle 约束。

如果本文件与 `ROADMAP_STAGE_13C_PLUS.md` 旧的 Stage 16A / Stage 18 简略描述冲突，以本文件以及下列版本化契约/修订为准：

- Stage 16A streaming contract：`docs/STAGE_16_STREAMING_CONTRACT_V3.md`
- Stage 16A approved requirements：`docs/STAGE_16A_REQUIREMENTS_V1.md`
- Stage 16A approved development plan：`docs/STAGE_16A_DEVELOPMENT_PLAN_V1.md`
- Stage 16A+ product amendment：`docs/ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md`
- Stage 18：`docs/STAGE_18_CLOUD_STREAMING_CONTRACT_V1.md`

Stage 13A 历史冻结文件 `docs/STAGE_16_STREAMING_CONTRACT_V2.md` 继续保留为历史事实。

---

## 1. 跨阶段总架构

未来 realtime audio 主链正式规划为：

`QS668/CB08 → BLE TYPE=1 → RealtimeAudioIngress → Opus deaggregation → persistent Opus decode → canonical PCM16 16k mono → RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler`

之后分叉：

### 本地 Stage 16A

`StreamingChunkAssembler → Streaming VAD → Local Streaming ASR → PARTIAL/STABLE/FINAL → punctuation`

### 云端 Stage 18

`StreamingChunkAssembler → ChunkScheduler → NetworkSendQueue → CloudStreamingSession → Provider WebSocket → PARTIAL/STABLE/FINAL normalization`

禁止任何后续阶段直接绕过 Stage 15 canonical source，从 BLE callback 直接调用本地 recognizer 或 WebSocket send。

---

## 2. Stage 15 边界保持不变

Stage 15 负责：

- BLE realtime transport
- Opus framing/decode
- canonical PCM16 16k mono
- canonical sample timeline
- bounded encoded/PCM buffering
- lifecycle / discontinuity
- reconnect generation
- background/FGS/resource ownership

Stage 15 不负责：

- recognizer chunk size
- Provider chunk cadence
- WebSocket
- heartbeat
- cloud socket reconnect
- transcript PARTIAL/STABLE/FINAL product logic
- reconnect 后是否自动恢复 realtime transcription 的产品策略
- realtime local audio segment 的最终文件命名/持久化产品策略

跨阶段冻结原则：**Device Recording Independence**。

App disconnect、BLE loss、Bluetooth Off、App/手机异常只能终止手机端当前 realtime media session，不得主动停止录音卡本机正在进行的录音。设备录音 lifecycle 与手机 realtime transport/session lifecycle 独立。

当前 Stage 15.6 实施规划：`docs/STAGE_15_6_PLAN_V2.md`。

---

## 3. Stage 16A 修订

原路线图中 Stage 16A 的 `BLE PCM → Streaming VAD → Streaming ASR` 修订为：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Streaming ASR`

Stage 16A 核心只实现 QS668/CB08 本地 True Streaming ASR；手机麦克风与 Timeline Annotation 分别后移到 Stage 16A.1 / 16A.2，避免扩大首次 true-streaming QA 面。

### 3.1 StreamingAudioBuffer

- engine/provider无关；
- bounded；
- 吸收 producer/consumer jitter/burst；
- 保留 canonical sample identity；
- overflow必须显式 discontinuity；
- 不允许无限缓存；
- 不允许 wall-clock padding。

### 3.2 StreamingChunkAssembler

- 将 Stage 15 canonical frames 聚合为 engine需要的 chunk；
- engine capability/config决定 chunk duration；
- chunk保留 canonical sample range；
- 不改变 Stage 15 timeline。

### 3.3 Local scheduling

本地 streaming engine 不要求 wall-clock pacing；chunk ready后可尽快 feed recognizer，重点优化 JNI/runtime调用粒度、VAD window、decode cadence、CPU/RAM/thermal和latency。

### 3.4 Lifecycle

必须消费 Stage 15：

- SessionStarted
- EpochStarted
- PcmFrame
- ConsumerDiscontinuity
- SessionEnded

任何 epoch/discontinuity 都不能静默跨越一个连续 recognizer token timeline。

### 3.5 Device recording / realtime segment recovery

同一次录音卡物理录音允许对应手机端多个独立 realtime media segment。

正式产品语义：

- 设备本机录音在 App/BLE/手机断联时继续；
- 断联立即终止当前手机 canonical/realtime logical session，并保存断联前已收到的 realtime 音频 segment；
- reconnect 后先确认设备仍处于同一次 recording；
- 若断联前 realtime transcription 为 ACTIVE 且用户未主动关闭，则由 Stage 16 上层 coordinator 自动重新 START realtime；
- reconnect 后必须创建新的 transport generation / canonical logical session / local segment；
- 不得把断联期间缺失的媒体用 silence、wall clock 或 sample-index 跳跃补成连续文件；
- 全程无断联时，录音结束后手机本地 realtime 音频应在设备正式文件名可确认后尽量使用与录音卡一致的文件名；
- 发生断联时，各 segment 以可区分的分段文件保存，例如 `part01 / part02`；具体命名规则后续产品化阶段冻结；
- 内部关联必须依赖 recording/segment/session ID，而不是依赖文件名字符串。

Stage 15 runtime 本身继续保持底层职责：transport close 后安全进入 clean terminal/IDLE boundary，不自行决定产品层是否自动恢复 realtime transcription。

### 3.6 Stage 16A UI / UX

正式产品方向冻结为：**阅读器 + 极简录音控制器**。

- 正文优先；
- PARTIAL 只作为底部 mutable tail；
- STABLE/FINAL 为稳定正文；
- 智能自动跟随，用户上滑后停止强制滚动；
- 轻量波形；
- Pause/Resume + Stop；
- 断联/恢复使用 timeline separator；
- Mini Live Bar / notification 返回当前实时页；
- 工程诊断信息只进入 diagnostics UI。

Stage 16A 详细权威契约：`docs/STAGE_16_STREAMING_CONTRACT_V3.md`。

已确认开发规划：`docs/STAGE_16A_DEVELOPMENT_PLAN_V1.md`。

---

## 4. Stage 18 修订

原路线图中 Stage 18 的：

`BLE Audio → Cloud Realtime ASR`

修订为：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → ChunkScheduler → NetworkSendQueue → CloudStreamingSession → Cloud Realtime ASR Provider`

新增正式要求：

### 4.1 ChunkScheduler

- provider-specific chunk duration；
- cadence shaping；
- final flush；
- write backlog control；
- rate-limit/capability aware。

### 4.2 NetworkSendQueue

必须 bounded。WebSocket慢不能反向阻塞 Stage 15 canonical pipeline/BLE callback。

### 4.3 CloudStreamingSession

至少具备：

- CONNECTING/CONNECTED/STREAMING/FINALIZING/DISCONNECTED/RECONNECT_WAIT/CLOSED/ERROR；
- heartbeat；
- socket error/done统一处理；
- reconnect/backoff；
- socket-only close与logical-session close分离。

### 4.4 socketGeneration

WebSocket每次连接生成新的 socket generation；所有 callback必须做 stale-generation reject。

BLE transport generation、canonical logical session、cloud socket generation 是三个独立 identity domain。

### 4.5 Heartbeat

Provider capability驱动：

- WebSocket Ping/Pong
- application heartbeat
- NONE

不得对全部Provider硬编码统一heartbeat消息。

### 4.6 Reconnect media policy

至少支持 capability-driven：

- RESUMABLE
- NEW_SESSION_WITH_BUFFER
- LIVE_EDGE_ONLY

Reconnect PCM buffer必须 bounded；buffer overflow/长断网必须显式 CloudStreamingDiscontinuity，从 live edge恢复，禁止伪造连续媒体时间。

Cloud/WebSocket failure 不得停止设备本机录音。BLE transport generation 被切断时，Stage 18 只结束或切分相应手机/云端 realtime session；设备录音继续独立运行。

### 4.7 Privacy

用户必须明确知道 realtime audio 上传到第三方 Provider。不得因本地 ASR 失败静默开启云端实时上传。

Stage 18详细权威契约：`docs/STAGE_18_CLOUD_STREAMING_CONTRACT_V1.md`。

---

## 5. 官方 App 参考的使用边界

官方 App 中观察到的 pending PCM queue、flush/feed层、heartbeat、socket error/done与 reconnect 行为可以作为产品/架构事实参考，说明在 BLE 与云 ASR之间进行 bounded audio shaping 和 network lifecycle 管理是合理方向。

新增已确认的官方 App 产品行为参考：

- 实时转录期间断联时，录音卡继续录音；
- 手机保存断联前已收到的一段 realtime audio，并用文件名区分该 segment；
- 设备重新连接后，官方 App 自动恢复实时转录；
- 重连到最终结束的 realtime audio 再保存为另一独立 segment；
- 全程无断联时，录音结束后手机本地 realtime audio 使用与录音卡一致的正式文件名；
- 没有录音卡时可使用手机麦克风录音/转录；
- 转录过程中可做笔记、标记和添加照片。

Voica 只参考产品/架构事实，不复制官方源码或机械翻译实现。新增的手机麦克风与笔记/照片功能在 Voica 中分别产品化为 Stage 16A.1 与 Stage 16A.2，并采用独立的 canonical-source 与 Recording Timeline Annotation 设计。

---

## 6. Stage 16A.1 — Phone Microphone Capture

Stage 16A Final Freeze/Handoff 后执行。

目标：没有录音卡时使用 Android 手机麦克风完成正式本地录音 + 实时转写，并复用 Stage 16A buffer/chunk/VAD/ASR/stabilizer/punctuation/UI。

Stage 16A 只预留 audio-source-independent seam，不提前实现 `AudioRecord`。

权威规划：`docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`。

---

## 7. Stage 16A.2 — Timeline Annotation

Stage 16A.1 后执行。

目标：建立 Recording Timeline Annotation，支持快速标记、文字笔记、拍照、相册附件，并绑定 Recording/media timeline，而不是绑定某个 TranscriptVersion 的字符位置。

Annotation Room schema 在 Stage 16A.2 开始时基于届时真实 schema 重新设计；Stage 16A 不提前创建空表。

权威规划：`docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`。

---

## 8. Stage 顺序

更新为：

`Stage 15 → Stage 16A → Stage 16A.1 → Stage 16A.2 → Stage 16B → Stage 17 → Stage 18 → Stage 19A/B/C/D → Stage 20`

本修订不会把 Stage 18 WebSocket逻辑提前进入 Stage 15/16A，也不会把手机麦克风或 Annotation 混入 Stage 16A 核心首次 QA。

---

## 9. 后续开发接管门禁

开始 Stage 16A 前必须至少重新读取：

- Stage 15 Final Freeze/Handoff
- `docs/STAGE_16_STREAMING_CONTRACT_V3.md`
- `docs/STAGE_16A_REQUIREMENTS_V1.md`
- `docs/STAGE_16A_DEVELOPMENT_PLAN_V1.md`
- 本路线图修订
- 当前 model/runtime capability

开始 Stage 16A.1 / 16A.2 时均必须重新核验 main、open PR、CI、Room、版本、当前 Stage 16A Freeze/Handoff，并再次完成各自需求/规划确认门禁。

开始 Stage 18 前必须至少重新读取：

- Stage 15 Final Freeze/Handoff
- Stage 16A Final Freeze/Handoff
- `docs/STAGE_18_CLOUD_STREAMING_CONTRACT_V1.md`
- Provider最新官方 realtime协议/capability
- 当前 privacy/security约束

任何具体 Provider heartbeat interval、chunk duration、reconnect buffer秒数都必须在对应 Stage 开发时重新核验官方协议并通过真机/网络故障测试，不能把规划中的示例值当成永久协议事实。
