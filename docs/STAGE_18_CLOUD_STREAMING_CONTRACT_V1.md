# Stage 18 Cloud Realtime ASR Contract V1

状态：**CANCELLED / SUPERSEDED / NOT IMPLEMENTED**

修订日期：2026-10-23

该合同对应的“Stage 18 Cloud Realtime ASR”未来方向已从 Voica 当前产品主路线移除。

Voica 后续改为 Fully Offline：

- 不实施 Cloud Realtime ASR；
- 不实施 Cloud File ASR；
- 不因本地 ASR 失败自动上传音频；
- 不保留 cloud realtime provider/WebSocket lifecycle 作为当前产品必需能力。

原 Stage 18 编号现改为：

**Stage 18 — Local Speech Quality & Latency Consolidation**

见：

- `docs/ROADMAP_STAGE_16_PLUS_FULLY_OFFLINE_AMENDMENT.md`
- `docs/ROADMAP_STAGE_18_LOCAL_SPEECH_CONSOLIDATION.md`

本文件原有 CloudStreamingSession、NetworkSendQueue、heartbeat、provider reconnect 等规划只作为 Git 历史证据，不再是待实现 contract。

已完成 Stage 15/16A 的 canonical realtime audio、buffer/chunk、discontinuity 等本地通用基础合同不因取消 cloud roadmap 而失效。

未来若重新评估 cloud realtime ASR，必须作为新的独立需求重新立项，不能直接恢复本旧合同。
