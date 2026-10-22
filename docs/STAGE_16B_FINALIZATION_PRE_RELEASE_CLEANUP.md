# Stage 16B Finalization — Pre-release Cleanup Plan

状态：**APPROVED DIRECTION / PLANNED / NOT IMPLEMENTED / FINAL FREEZE NOT PERFORMED**

修订日期：2026-10-23

归属：**Stage 16 — Finalization**

当前基线：P5 revision-5 已 `CLOSED / REAL-DEVICE PASS`。本文件只定义 Stage 16 最终收尾和清理方向，不表示清理源码已经完成，也不自动授权 Final Freeze/Handoff、PR merge 或 production model promotion。

---

## 1. 收尾目标

在进入 Stage 17 Fully Offline Intelligence 前，将当前开发仓库收敛为一个尽可能干净的 pre-release baseline：

- 不继续实现 SenseVoice Rich Audio Metadata；
- 删除 Online AI Summary 与只为在线 AI 服务的产品/网络路径；
- 移除没有继续价值的 pre-release compatibility debt；
- 压平/整理 Room 与 data contract；
- 删除 dead code、dead resources、dead dependencies 和 obsolete config；
- 保护当前已通过的本地录音/ASR/CTC/diarization/timeline 能力；
- 完成 final regression；
- 最后由用户明确授权 Stage 16 Final Freeze/Handoff。

---

## 2. SenseVoice Rich Audio Metadata

结论：**CANCELLED / NOT REQUIRED FOR STAGE 16 CLOSURE**。

不实施：

- emotion persistence；
- audio-event persistence；
- Speaker × Emotion timeline；
- rich metadata export；
- rich metadata AI input；
- 为上述功能新增 Room schema。

保留现有 SenseVoice 正文 ASR 能力，不因取消 Rich Metadata 删除 SenseVoice 本身。

---

## 3. Online AI Summary 直接移除

Stage 16 收尾直接删除当前在线 AI 总结实现，不采用“先并存、离线稳定后再删除”的过渡策略。

实施审查至少覆盖：

- Online LLM provider interface/implementation；
- OpenAI-compatible endpoint 支持；
- Base URL / API Key / remote model 设置；
- online summary HTTP client / DTO / serializer；
- remote retry / timeout / connectivity handling；
- cloud fallback；
- provider/model selector UI；
- 只为在线总结存在的 strings/resources；
- 只为在线总结存在的 network dependency / permission / R8 keep rule；
- 对应 Unit/UI tests；
- architecture/requirements/docs 中的在线 AI 产品承诺。

可以保留：

- 与 provider 无关、仍适合 Stage 17 复用的总结展示 UI；
- 与 provider 无关的 Summary domain/entity（若结构合理）；
- 用户创建/删除/查看总结版本的通用交互（若不依赖云端 contract）。

是否保留上述通用层必须按真实调用图决定，不为“可能以后有用”保留无调用抽象。

---

## 4. Pre-release Compatibility Cleanup

Voica 当前未发布且仅开发者自用。Stage 16 finalization 明确允许删除只为旧开发版本升级兼容存在的代码和 schema。

审查并清理：

### 4.1 Room / database

- 已不需要的旧表；
- 已不需要的旧字段；
- obsolete index / relation；
- historical-only DAO/query；
- 只为旧 app version 服务的 migration chain；
- old-data backfill；
- migration-only converter/mapper；
- historical migration fixture/test；
- 已废弃 enum/status value。

允许建立新的 pre-release schema baseline。开发机/真机旧数据允许通过清除 App 数据或卸载重装处理。

不要求：

- v1 → ... → v14 的长期升级兼容；
- 历史测试数据无损迁移；
- 旧转写/旧总结 backfill。

具体 baseline Room version 在实施时根据当前 schema 和 KSP 输出决定；不为了“看起来干净”机械重置成 version 1。

### 4.2 Preferences / settings

清理：

- 已废弃 SharedPreferences/DataStore key；
- legacy key migration；
- 已不用的 feature flag；
- 已废弃 provider/model setting；
- 双格式 config parser；
- old-default compatibility shim。

### 4.3 Domain / repository / adapters

清理：

- deprecated adapter/mapper；
- 旧 pipeline compatibility branch；
- 永远不会再进入的 fallback；
- 无调用 use case/repository/service；
- 旧 JSON/data format compatibility；
- cloud-only abstraction；
- obsolete model capability/config fields。

### 4.4 Resources / dependencies / native glue

清理：

- 无引用 drawable/string/raw/assets；
- 无用 Gradle dependency；
- 无用 permission；
- 无用 R8/ProGuard keep rule；
- 无现役调用的 JNI/native glue；
- 已不使用的 model asset/catalog reference。

---

## 5. 明确禁止误删的能力

清理不能因为“历史较久”而删除仍承担真实职责的代码。

至少保护：

- Silero VAD 当前有效 fallback；
- FSMN Local File VAD primary；
- Zipformer realtime ASR；
- SenseVoice file ASR；
- FunASR Nano high-quality file ASR；
- P5 R5 same-stream CTC 路径；
- current diarization/stitching/AUTO/fixed speaker logic；
- overlap/manual speaker assignment；
- canonical sample-index timeline；
- Timeline Annotation；
- Stage 18 明确需要复用的 streaming engine contracts；
- QA/Freeze/Handoff evidence docs。

---

## 6. Production model channel 边界

本清理计划不自动：

- merge model-channel PR #21；
- promotion FunASR revision-2；
- 修改 production manifest；
- 删除仍被 production manifest 引用的模型；
- 改变当前 production model binding。

任何 model-channel 变更必须独立核验和授权。

---

## 7. 回归门禁

清理完成后至少执行：

1. compile / lint / static checks；
2. Unit tests；
3. Room schema/current-create test；
4. Instrumented tests（适用时）；
5. releaseQa / R8 build；
6. model/catalog verification；
7. 关键真机回归。

真机至少保护：

- 设备连接/录音/下载；
- 本地播放；
- realtime ASR；
- Local File FSMN primary / Silero fallback；
- SenseVoice file ASR；
- FunASR file ASR；
- CTC 时间轴；
- AUTO / fixed2 diarization；
- overlap/manual assignment；
- transcript edit/export；
- app clean-install / clean-database baseline。

在线 AI 不再属于回归通过条件，因为其目标状态是删除。

---

## 8. Stage 16 最终顺序

`P5 R5 CLOSED`
→ `Cancel SenseVoice Rich Metadata`
→ `Remove Online AI Summary`
→ `Pre-release Compatibility Cleanup`
→ `Room/Data Schema Consolidation`
→ `Dead Code/Dependency/Resource Cleanup`
→ `Automated Regression`
→ `Real-device Regression`
→ `User approval`
→ `Stage 16 Final Freeze/Handoff`

Stage 17 不得在上述流程完成前开始 implementation。
