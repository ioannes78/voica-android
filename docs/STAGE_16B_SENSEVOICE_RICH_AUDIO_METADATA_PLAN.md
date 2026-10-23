# Stage 16B — SenseVoice Rich Audio Metadata

状态：**CANCELLED / SUPERSEDED / NOT IMPLEMENTED**

修订日期：2026-10-23

该专项已从 Stage 16B 必做范围移除。

原因：当前 Voica 的优先目标调整为 Stage 16 收尾清理和后续 Fully Offline Intelligence。SenseVoice emotion/event 对核心转写、时间轴、断句、说话人和离线 AI 的直接收益不足以覆盖新增 schema、lineage、UI、export 和 QA 成本。

因此不再实施：

- emotion persistence；
- audio-event persistence；
- Speaker × Emotion/Event timeline；
- rich metadata export；
- rich metadata structured AI input；
- 为 rich metadata 新增 Room schema。

SenseVoice 本地文件 ASR 本身继续保留；本决定不删除 SenseVoice，也不改变当前 ASR/diarization/CTC pipeline。

旧规划详细内容可通过 Git 历史查阅，不在当前工作树继续作为待执行需求。

当前权威后续路线：

- `docs/ROADMAP_STAGE_16_PLUS_FULLY_OFFLINE_AMENDMENT.md`
- `docs/STAGE_16B_FINALIZATION_PRE_RELEASE_CLEANUP.md`
- `docs/ROADMAP_STAGE_17_FULLY_OFFLINE_INTELLIGENCE.md`
- `docs/ROADMAP_STAGE_18_LOCAL_SPEECH_CONSOLIDATION.md`

本文件不授权 Final Freeze/Handoff、PR merge、Room 修改或 model-channel promotion。
