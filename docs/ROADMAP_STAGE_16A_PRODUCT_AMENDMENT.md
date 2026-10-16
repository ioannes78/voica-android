# Stage 16A+ 产品路线修订

状态：**APPROVED PRODUCT ROADMAP AMENDMENT / STAGE 16A.1.5 FROZEN**

日期：2026-10-16

本文件补充 `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`，不改写 Stage 15 / Stage 16A / Stage 16A.1 历史 Freeze/Handoff，也不改变 Stage 16A V3 streaming contract 的权威性。

## 1. Stage 16A 核心保持聚焦

Stage 16A 只负责 QS668/CB08 canonical realtime audio 上的本地 True Streaming ASR 产品化：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Local Streaming ASR → PARTIAL/STABLE/FINAL → punctuation`

手机麦克风、主页/通知收口、Android Audio I/O compatibility、笔记与照片不进入 Stage 16A 核心实现。

## 2. Stage 16A.1 — Phone Microphone Capture

状态：**FINAL FREEZE / HANDOFF COMPLETE**。

目标：没有录音卡时，通过 Android 手机麦克风完成正式本地录音 + 实时转写，并复用 Stage 16A streaming pipeline 和 realtime UI。

权威规划：`docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`。

Final Freeze/Handoff：

- `docs/STAGE_16A_1_FREEZE.md`
- `docs/STAGE_16A_1_HANDOFF.md`

Stage 16A.1 S15 `30-minute phone microphone + Small realtime + screen-off` 仍为 `NOT RUN / WAIVED BY USER`，不得改写为 PASS。

## 3. Stage 16A.1.5 — Home & Notification UX Consolidation

状态：**FINAL FREEZE / HANDOFF COMPLETE**。

Stage 16A.1 后执行，负责在不改 recording / ASR / diarization / AI Summary / model install / playback 业务 lifecycle 的前提下，收口 Homepage 信息架构、App 全局状态/Attention 与 Android notification projection/lifecycle，建立供后续阶段复用的 presentation seam。

通知子阶段为严格 presentation-only 边界：不得借通知优化修改 FGS ownership、WakeLock、AudioRecord、WorkManager、Room business schema、结果版本机制或任何录音/转写/说话人/AI 功能。

权威文档：

- `docs/STAGE_16A_1_5_REQUIREMENTS_V1.md`
- `docs/STAGE_16A_1_5_DEVELOPMENT_PLAN_V1.md`
- `docs/STAGE_16A_1_5_FREEZE.md`
- `docs/STAGE_16A_1_5_HANDOFF.md`

## 4. 新增预留 Stage 16A.1.6 — Android Audio I/O Compatibility

状态：**RESERVED / NOT STARTED**。

Stage 16A.1.5 Final Freeze/Handoff 后、Stage 16A.2 前执行。

目标是把 Android capture/playback route compatibility 从 Home/Notification presentation 与 Timeline Annotation 中拆出，独立处理真实设备 Audio I/O 差异，同时保持 Stage 16A/16A.1 已冻结的 canonical truth 与单 owner 架构。

预留问题域：

- phone actual recording level；
- phone realtime input source；
- `MIC` / `VOICE_RECOGNITION` source policy；
- USB microphone；
- wired microphone；
- usable Bluetooth microphone；
- Bluetooth output；
- QS668 BLE GATT 与 `AudioRecord` / `AudioTrack` coexistence；
- library playback while BLE connected；
- `AudioTrack` route recovery。

产品边界：

- “手机”表示 Android capture source，不等于永久强制 built-in microphone；
- 继续保持一个 `AudioRecord` owner；
- QS668/CB08 BLE GATT recorder 与 Bluetooth audio route 必须分离建模；
- playback 继续保持 single controller；
- 不得借兼容性修复改写 BLE protocol、ASR、Room、model channel 或 canonical sample truth。

Stage 16A.1.6 尚未完成需求确认和开发规划确认，因此本路线图只保留阶段位置和问题域，不构成编码授权。

## 5. Stage 16A.2 — Timeline Annotation

Stage 16A.1.6 完成后执行。

目标：建立 Recording Timeline Annotation，包括快速标记、文字笔记、拍照和相册附件，并绑定可靠媒体时间轴，而不是绑定某个转写版本的字符位置。

权威规划：`docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`。

## 6. 修订后的产品阶段顺序

规划顺序更新为：

`Stage 15 → Stage 16A → Stage 16A.1 → Stage 16A.1.5 → Stage 16A.1.6 → Stage 16A.2 → Stage 16B → Stage 17 → Stage 18 → Stage 19A/B/C/D → Stage 20`

其中：

- Stage 16A：录音卡本地 True Streaming ASR；
- Stage 16A.1：手机麦克风正式录音 + 实时转写；
- Stage 16A.1.5：主页 / 全局状态 / Android 通知体系收口；
- Stage 16A.1.6：Android Audio I/O compatibility；
- Stage 16A.2：时间轴标记 / 笔记 / 照片；
- Stage 16B：本地文件 ASR V2；
- Stage 18：云端 realtime ASR。

## 7. 架构约束

Stage 16A 保留 audio-source-independent 上层 seam，使 Stage 16A.1 接入相同 canonical event contract。

Stage 16A.1.5 的 unified projection 只能读取既有 runtime / Room / coordinator / durable journal truth，不得变成业务状态 owner。Notification/App Global UI 必须可以由底层 truth 重建。

Stage 16A.1.6 只能在重新核验当前 Android capture/playback truth 后设计 route policy；不得先建立第二套 `AudioRecord` / playback controller，也不得把 Bluetooth GATT 与 Bluetooth audio 混为同一连接事实。

Stage 16A.2 的 Annotation 必须属于 Recording/media timeline，不属于某一 TranscriptVersion；Room 表结构在该阶段重新核验实际 schema 后设计，不在 16A.1.5/16A.1.6 提前创建空表。

## 8. UI 产品方向

Stage 16A realtime UI 继续保持“阅读器 + 极简录音控制器”。

Stage 16A.1.5 已将主页收口为“当前正在发生什么 + 高频启动操作”：idle 不同时展示手机/录音卡 × 录音/realtime 四张同权大卡；active 时显示一个 Primary Activity，并保留可重建的 compact secondary/global presentation seam；Mini Player 继续只服务 playback。

Stage 16A.1.6 只处理 Audio I/O compatibility，不重新设计 Home IA。

Stage 16A.2 再增加统一 `+` 入口承载快速标记、文字笔记、拍照、相册，避免 Annotation 再创建独立全局状态体系。

## 9. AI 扩展边界

Timeline Annotation 首版不实现 OCR、图片理解或图片参与 AI 总结。未来 Stage 19+ 可以基于稳定 Annotation 数据结构扩展“录音 + 转写 + 笔记 + 图片”的会议纪要能力。

## 10. 后续接管门禁

开始 Stage 16A.1.6 前必须重新核验 GitHub `main`、PR #30 merge 状态、最新 CI、当前源码、Room v13、production model channel、Stage 16A / 16A.1 / 16A.1.5 Freeze/Handoff；随后先确认需求，再确认开发规划，才允许编码。

开始 Stage 16A.2 时同样必须以届时 GitHub 真实状态重新核验，不能把本文件中的 SHA 或问题列表当成未来事实来源。
