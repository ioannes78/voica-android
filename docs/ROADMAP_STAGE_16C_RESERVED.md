# Stage 16C — Superseded Reservation

状态：**SUPERSEDED / MOVED TO STAGE 18 / NOT STARTED**

修订日期：2026-10-23

原 Stage 16C “Local Speech Quality & Latency Consolidation” 不再以 Stage 16C 编号执行。

其核心内容整体后移为：

**Stage 18 — Local Speech Quality & Latency Consolidation**

新文档：

- `docs/ROADMAP_STAGE_18_LOCAL_SPEECH_CONSOLIDATION.md`

迁移后的顺序仍保持：

1. Unified FSMN VAD；
2. Realtime Two-Pass ASR；
3. Sentence Composition V2；
4. Diarization Performance V2；
5. Full Speech Pipeline Regression。

Stage 16C 编号不再作为可启动开发阶段。

当前新顺序：

`Stage 16 Finalization`
→ `Stage 17 Fully Offline Intelligence`
→ `Stage 18 Local Speech Quality & Latency Consolidation`

旧 Stage 16C 的详细历史版本仍保留在 Git 历史中，当前工作树只保留本 superseded marker，防止后续窗口按旧编号启动。

权威路线：

- `docs/ROADMAP_STAGE_16_PLUS_FULLY_OFFLINE_AMENDMENT.md`
- `docs/ROADMAP_STAGE_17_FULLY_OFFLINE_INTELLIGENCE.md`
- `docs/ROADMAP_STAGE_18_LOCAL_SPEECH_CONSOLIDATION.md`

本文件不授权 Stage 18 implementation，也不改变当前 production VAD/ASR binding。
