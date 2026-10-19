# Stage 16A+ 产品路线修订

状态：**APPROVED PRODUCT ROADMAP AMENDMENT / STAGE 16A.2 FINAL FREEZE & HANDOFF COMPLETE / STAGE 16B RESERVED**

日期：2026-10-19

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

## 4. Stage 16A.1.6 — Android Audio I/O Compatibility

状态：**FINAL FREEZE / HANDOFF COMPLETE**。

Stage 16A.1.5 Final Freeze/Handoff 后、Stage 16A.2 前执行。

目标是把 Android capture/playback route compatibility 从 Home/Notification presentation 与 Timeline Annotation 中拆出，独立处理真实设备 Audio I/O 差异，同时保持 Stage 16A/16A.1 已冻结的 canonical truth 与单 owner 架构。

权威文档：

- `docs/STAGE_16A_1_6_REQUIREMENTS_V1.md`
- `docs/STAGE_16A_1_6_DEVELOPMENT_PLAN_V1.md`
- `docs/STAGE_16A_1_6_PROGRESS.md`
- `docs/STAGE_16A_1_6_FREEZE.md`
- `docs/STAGE_16A_1_6_HANDOFF.md`

冻结问题域包括：

- phone actual recording level；
- phone realtime input source；
- `MIC` / `VOICE_RECOGNITION` source policy；
- USB / wired / Bluetooth microphone compatibility；
- Bluetooth output；
- QS668 BLE GATT 与 `AudioRecord` / `AudioTrack` coexistence；
- library playback while BLE connected；
- `AudioTrack` route diagnostics / evidence-gated recovery boundary。

产品边界继续保持：

- “手机”表示 Android capture source，不等于永久强制 built-in microphone；
- one `AudioRecord` owner；
- formal WAV recording truth 优先于 optional realtime ASR fan-out；
- canonical PCM = PCM16 / 16 kHz / mono；
- QS668/CB08 BLE GATT recorder 与 Bluetooth audio route 分离建模；
- playback single controller / single sink；
- manual selected microphone 消失时不得静默切到 built-in mic 并继续写正式录音；
- P9 AudioTrack bounded recovery 仍为 evidence-gated，Stage 16A.1.6 Final Freeze 时 `NOT TRIGGERED / NOT IMPLEMENTED`。

Stage 16A.1 S15 历史状态继续保持：

`30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

## 5. Stage 16A.2 — Timeline Annotation

状态：**FINAL FREEZE / HANDOFF COMPLETE**。

Stage 16A.1.6 完成后执行。

目标：建立 Recording Timeline Annotation，包括快速标记、文字笔记、拍照和相册附件，并绑定可靠媒体 sample timeline，而不是绑定某个转写版本的字符位置。

权威规划与收口文档：

- `docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`
- `docs/STAGE_16A_2_PROGRESS.md`
- `docs/STAGE_16A_2_FREEZE.md`
- `docs/STAGE_16A_2_HANDOFF.md`

最终冻结边界：

- Annotation hard owner = Recording；
- durable primary coordinate = sample index；
- anchor domains = `CANONICAL_RECORDING` / `REALTIME_MEDIA_SEGMENT`；
- transcript/revision/segment/speaker/realtime lineage 只作 provenance，不作 ownership；
- QS668 disconnect gap 不伪造连续 timeline；
- 无 exact mapping 时 retain Annotation + refuse precise seek；
- ordinary phone / QS668 recording 与 phone / QS668 realtime 共享统一 Annotation composer；
- Recording detail `标注`、waveform markers、transcript timeline/read projection 已冻结；
- Room 从 v13 迁移到 v14；
- 首版不包含 Range Annotation、OCR、image AI、global Annotation FTS/cloud collaboration。

用户已于 2026-10-19 明确确认 Final Freeze/Handoff。

## 6. 修订后的产品阶段顺序

规划顺序保持为：

`Stage 15 → Stage 16A → Stage 16A.1 → Stage 16A.1.5 → Stage 16A.1.6 → Stage 16A.2 → Stage 16B → Stage 17 → Stage 18 → Stage 19A/B/C/D → Stage 20`

其中：

- Stage 16A：录音卡本地 True Streaming ASR；
- Stage 16A.1：手机麦克风正式录音 + 实时转写；
- Stage 16A.1.5：主页 / 全局状态 / Android 通知体系收口；
- Stage 16A.1.6：Android Audio I/O compatibility；
- Stage 16A.2：时间轴标记 / 笔记 / 照片；
- Stage 16B：本地文件 ASR V2；
- Stage 18：云端 realtime ASR。

当前下一预留阶段：**Stage 16B — Local File ASR V2 = RESERVED / NOT STARTED**。

## 7. 架构约束

Stage 16A 保留 audio-source-independent 上层 seam，使 Stage 16A.1 接入相同 canonical event contract。

Stage 16A.1.5 的 unified projection 只能读取既有 runtime / Room / coordinator / durable journal truth，不得变成业务状态 owner。Notification/App Global UI 必须可以由底层 truth 重建。

Stage 16A.1.6 必须保持 one AudioRecord owner、single playback controller，并将 requested/preferred Android audio device 与 actual routed device 作为独立 truth。route change 只能通过已有 canonical continuity/discontinuity seam 影响 realtime ASR，不得建立第二套 ASR pipeline。QS668 GATT 与 Android Bluetooth Audio 必须保持独立状态域。

Stage 16A.2 已冻结 Annotation 属于 Recording/media sample timeline，不属于某一 TranscriptVersion。后续 Stage 16B 及更后阶段不得通过 re-ASR / transcript revision 改写 Annotation ownership 或 sample anchor。

## 8. UI 产品方向

Stage 16A realtime UI 继续保持“阅读器 + 极简录音控制器”。

Stage 16A.1.5 将主页收口为“当前正在发生什么 + 高频启动操作”：active 时一个 Primary Activity，Mini Player 继续只服务 playback。

Stage 16A.1.6 只提供 Audio I/O compatibility，不建立新的 Home IA 或 App output switch center。

Stage 16A.2 已冻结统一 `＋标注` 入口承载快速标记、文字笔记、拍照、相册；Annotation 不建立独立全局状态体系。Transcript reading mode 保持连续阅读/复制，已有 Annotation 只做 view-only projection。

## 9. AI 扩展边界

Timeline Annotation 首版不实现 OCR、图片理解或图片参与 AI 总结。未来 Stage 19+ 可以基于稳定 Annotation 数据结构扩展“录音 + 转写 + 笔记 + 图片”的会议纪要能力。

Stage 16B 不得借 Local File ASR V2 自动把 Annotation 引入 AI/OCR scope。

## 10. 后续接管门禁

Stage 16A.2 已完成需求确认、开发规划、实现、CI、真机 QA 与用户 Final Freeze/Handoff 确认。

开始 **Stage 16B — Local File ASR V2** 时必须以届时 GitHub 真实状态重新核验：

- `main` HEAD；
- PR #33 merge state；
- open PR / branch；
- latest CI；
- Stage 16A.2 Freeze/Handoff；
- current Room v14 / migrations / schema；
- current production model channel；
- current Recording / AudioAsset / canonical sample lineage；
- current Annotation ownership / sample-anchor contract。

然后必须重新输出 Stage 16B 修订需求并等待用户确认，再输出开发规划并等待用户确认；不得依据本 roadmap 自动编码。