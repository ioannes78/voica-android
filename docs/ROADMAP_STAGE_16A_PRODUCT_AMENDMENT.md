# Stage 16A+ 产品路线修订

状态：**APPROVED PRODUCT ROADMAP AMENDMENT**

日期：2026-10-12

本文件补充 `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`，不改写 Stage 15 历史 Freeze/Handoff，也不改变 Stage 16A V3 streaming contract 的权威性。

## 1. Stage 16A 核心保持聚焦

Stage 16A 只负责 QS668/CB08 canonical realtime audio 上的本地 True Streaming ASR 产品化：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Local Streaming ASR → PARTIAL/STABLE/FINAL → punctuation`

手机麦克风、笔记、照片不进入 Stage 16A 核心实现，以避免扩大首次 true streaming QA 面。

## 2. 新增 Stage 16A.1 — Phone Microphone Capture

Stage 16A 完成真机验收、Freeze/Handoff 后执行。

目标：没有录音卡时，通过 Android 手机麦克风完成正式本地录音 + 实时转写，并复用 Stage 16A streaming pipeline 和 realtime UI。

权威规划：`docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`。

## 3. 新增 Stage 16A.2 — Timeline Annotation

Stage 16A.1 后执行。

目标：建立 Recording Timeline Annotation，包括快速标记、文字笔记、拍照和相册附件，并绑定可靠媒体时间轴，而不是绑定某个转写版本的字符位置。

权威规划：`docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`。

## 4. 修订后的产品阶段顺序

规划顺序更新为：

`Stage 15 → Stage 16A → Stage 16A.1 → Stage 16A.2 → Stage 16B → Stage 17 → Stage 18 → Stage 19A/B/C/D → Stage 20`

其中：

- Stage 16A：录音卡本地 True Streaming ASR；
- Stage 16A.1：手机麦克风录音 + 实时转写；
- Stage 16A.2：时间轴标记 / 笔记 / 照片；
- Stage 16B：本地文件 ASR V2；
- Stage 18：云端 realtime ASR。

## 5. 架构约束

Stage 16A 必须保留 audio-source-independent 上层 seam，使 Stage 16A.1 可接入相同 canonical event contract，但不得为未来需求提前实现未使用的 AudioRecord 生产代码。

Stage 16A.2 的 Annotation 必须属于 Recording/media timeline，不属于某一 TranscriptVersion；Room 表结构在该阶段重新核验实际 schema 后设计，不在 Stage 16A 提前创建空表。

## 6. UI 产品方向

Stage 16A realtime UI 正式采用“阅读器 + 极简录音控制器”：正文优先、轻量波形、Pause/Resume/Stop、智能跟随、断联/恢复时间轴分隔。

Stage 16A.2 再增加统一 `+` 入口承载快速标记、文字笔记、拍照、相册，避免 Stage 16A 首版底栏功能堆叠。

## 7. AI 扩展边界

Timeline Annotation 首版不实现 OCR、图片理解或图片参与 AI 总结。未来 Stage 19+ 可以基于稳定 Annotation 数据结构扩展“录音 + 转写 + 笔记 + 图片”的会议纪要能力。
