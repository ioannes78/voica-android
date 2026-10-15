# Stage 16A+ 产品路线修订

状态：**APPROVED PRODUCT ROADMAP AMENDMENT**

日期：2026-10-16

本文件补充 `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`，不改写 Stage 15 / Stage 16A / Stage 16A.1 历史 Freeze/Handoff，也不改变 Stage 16A V3 streaming contract 的权威性。

## 1. Stage 16A 核心保持聚焦

Stage 16A 只负责 QS668/CB08 canonical realtime audio 上的本地 True Streaming ASR 产品化：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Local Streaming ASR → PARTIAL/STABLE/FINAL → punctuation`

手机麦克风、主页/通知收口、笔记与照片不进入 Stage 16A 核心实现。

## 2. Stage 16A.1 — Phone Microphone Capture

Stage 16A 完成真机验收、Freeze/Handoff 后执行，现已完成 Final Freeze/Handoff 并合并 main。

目标：没有录音卡时，通过 Android 手机麦克风完成正式本地录音 + 实时转写，并复用 Stage 16A streaming pipeline 和 realtime UI。

权威规划：`docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`。

## 3. 新增 Stage 16A.1.5 — Home & Notification UX Consolidation

Stage 16A.1 后、Stage 16A.2 前执行。

目标：在不改 recording / ASR / diarization / AI Summary / model install / playback 业务 lifecycle 的前提下，收口 Homepage 信息架构、App 全局状态/Attention 与 Android notification projection/lifecycle，建立可供 Stage 16A.2、16B、17、18 复用的 presentation seam。

通知子阶段为严格 presentation-only 边界：不得借通知优化修改 FGS ownership、WakeLock、AudioRecord、WorkManager、Room business schema、结果版本机制或任何录音/转写/说话人/AI 功能。

权威需求：`docs/STAGE_16A_1_5_REQUIREMENTS_V1.md`。

权威开发规划：`docs/STAGE_16A_1_5_DEVELOPMENT_PLAN_V1.md`。

## 4. Stage 16A.2 — Timeline Annotation

Stage 16A.1.5 完成后执行。

目标：建立 Recording Timeline Annotation，包括快速标记、文字笔记、拍照和相册附件，并绑定可靠媒体时间轴，而不是绑定某个转写版本的字符位置。

权威规划：`docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`。

## 5. 修订后的产品阶段顺序

规划顺序更新为：

`Stage 15 → Stage 16A → Stage 16A.1 → Stage 16A.1.5 → Stage 16A.2 → Stage 16B → Stage 17 → Stage 18 → Stage 19A/B/C/D → Stage 20`

其中：

- Stage 16A：录音卡本地 True Streaming ASR；
- Stage 16A.1：手机麦克风录音 + 实时转写；
- Stage 16A.1.5：主页 / 全局状态 / Android 通知体系收口；
- Stage 16A.2：时间轴标记 / 笔记 / 照片；
- Stage 16B：本地文件 ASR V2；
- Stage 18：云端 realtime ASR。

## 6. 架构约束

Stage 16A 保留 audio-source-independent 上层 seam，使 Stage 16A.1 接入相同 canonical event contract。

Stage 16A.1.5 的 unified projection 只能读取既有 runtime / Room / coordinator / durable journal truth，不得变成业务状态 owner。Notification/App Global UI 必须可以由底层 truth 重建。

Stage 16A.2 的 Annotation 必须属于 Recording/media timeline，不属于某一 TranscriptVersion；Room 表结构在该阶段重新核验实际 schema 后设计，不在 Stage 16A.1.5 提前创建空表。

## 7. UI 产品方向

Stage 16A realtime UI 继续保持“阅读器 + 极简录音控制器”。

Stage 16A.1.5 将主页收口为“当前正在发生什么 + 高频启动操作”：idle 不同时展示手机/录音卡 × 录音/realtime 四张同权大卡；active 时仅一个 Primary Activity，其他并发 activity 使用 compact secondary projection；Mini Player 继续只服务 playback。

Stage 16A.2 再增加统一 `+` 入口承载快速标记、文字笔记、拍照、相册，避免 Annotation 再创建独立全局状态体系。

## 8. AI 扩展边界

Timeline Annotation 首版不实现 OCR、图片理解或图片参与 AI 总结。未来 Stage 19+ 可以基于稳定 Annotation 数据结构扩展“录音 + 转写 + 笔记 + 图片”的会议纪要能力。
