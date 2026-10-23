# Stage 16 Final Freeze — Local File ASR V2 / Pre-release Finalization

状态：**FINAL FREEZE — 用户已明确授权；自动化回归与 Finalization 真机回归均已通过**

冻结日期：2026-10-24

## 1. 冻结结论

Stage 16 现正式冻结。

本 Freeze 覆盖：

- Stage 16B Local File ASR V2 已完成能力；
- P5 revision-5 已 CLOSED / REAL-DEVICE PASS；
- Stage 16 Finalization / Pre-release Cleanup 已完成；
- Online AI Summary 现役 runtime/provider/settings 产品路径已移除；
- SenseVoice Rich Audio Metadata 已取消且不实施；
- Room pre-release baseline 已收敛到 v16；
- 自动化回归通过；
- QA1–QA10 Finalization 真机回归全部通过；
- 当前 Finalization runtime/source 已验证可保持 BLE、播放、实时 ASR、本地文件 ASR、CTC、说话人分离、编辑/标注/导出和离线持久化能力。

本 Freeze 不等于 merge 授权，也不等于 model-channel promotion 授权。

## 2. Final Freeze 时 GitHub 事实基线

用户授权 Final Freeze/Handoff 前重新核验：

- repository：`ioannes78/voica-android`
- target branch：`main`
- `main` HEAD：`988931ebed72a70ba799d909cd36a416a673eef5`
- development branch：`stage16b-local-file-asr-v2`
- Android PR：#35
- PR #35：**Draft / Open / Unmerged / mergeable**
- Finalization 真机验证 runtime/source head：`ef753e8b624f9acfa397775f26ffbfb3e59c1937`
- pre-Freeze evidence/docs head：`f05e9c6b0f449f2d424f24f9fb4a7f5e5b3079f3`
- Room：**v16**
- model-channel PR：`ioannes78/voica-model-channel#21`
- model-channel PR #21：**Draft / Open / Unmerged**
- model-channel PR #21 head：`eeb59214244f392f3e2eecdb90016efbe3a95401`
- model-channel mutable `main/manifests/production.json`：manifest **v7**
- Android production pin：immutable commit `eeb59214244f392f3e2eecdb90016efbe3a95401`
- Android pinned production manifestVersion：**9**
- Android pinned production manifestDigest：`a49da9fdf2dabf2408ba2bb14cf6b5c25c35563052cc6307db8f8095caac0b45`

Freeze/Handoff 文档提交后的 branch HEAD 会晚于已真机验证的 runtime/source head；这些 docs-only 提交不得被描述为重新生成或重新真机验证过的新 App binary。

## 3. Local File ASR V2 冻结能力

### VAD

- Local File primary：**FSMN VAD INT8**；
- Local File fallback：**Silero VAD INT8**；
- Finalization 正常主路径已真机回归；
- Finalization 未通过人为破坏 FSMN 环境再次强制触发 Silero fallback；
- Silero fallback contract 继续冻结，不得因未重复强测而删除。

### ASR

- SenseVoice INT8：平衡/效率角色；
- FunASR Nano INT8：高质量角色；
- realtime Zipformer 路径继续冻结；
- Stage 16 不再实施 SenseVoice Rich Audio Metadata。

### FunASR CTC

冻结 P5 R5 same-stream CTC 路径：

- revision-2 CTC candidate bundle；
- patched sherpa-onnx v1.13.8 runtime；
- A0/A1/A2 native CTC patch；
- same-stream token alignment；
- token/细粒度 playback sync；
- diarization → transcript speaker mapping。

Finalization 专用真机隔离样本结果：

- 9/9 chunks `alignmentSucceeded=true`；
- `sameStream=true`；
- `outputTokenCount=569`；
- 569/569 token aligned；
- AUTO diarization 正常映射两位主要说话人。

## 4. Diarization 冻结

冻结：

- AUTO speaker count；
- fixed-2 speaker count；
- current clustering/stitching；
- low-evidence false-speaker suppression；
- overlap/manual speaker assignment；
- speaker assignment persistence；
- re-open/restart 后结果持久化。

不得因为后续 Stage 18 有 Diarization Performance V2 规划而在未重新立项前修改当前冻结语义。

## 5. Timeline / playback / content management 冻结

继续冻结：

- canonical sample-index timeline；
- CTC timed transcript playback sync；
- local playback / seek / duration；
- Recording Timeline Annotation；
- transcript edit；
- manual speaker assignment；
- export；
- local delete 与 device delete 分离；
- reconnect / refresh；
- background task lifecycle 与结果持久化。

Stage 16 Finalization 不建立第二套 playback/timeline owner。

## 6. Offline product boundary 冻结

Stage 16 结束后的产品方向为 fully offline。

已冻结清理结果：

- Online AI provider runtime 移除；
- API Key / Base URL / remote-model 产品设置移除；
- online summary 网络路径与 cloud fallback 移除；
- provider/model selector 产品入口移除；
- provider-neutral summary history/domain/evidence/revision 可保留供未来本地智能复用；
- `INTERNET` 权限继续保留，仅因 model catalog / model download 等现役非核心职责仍需要网络。

核心录音、播放、已安装模型的 realtime/local-file speech、编辑和本地持久化均已在离线状态真机回归通过。

## 7. Room / compatibility / settings 冻结

- Room current baseline：**v16**；
- clean-create current-schema regression 已建立；
- historical pre-release migration compatibility 不再作为发布约束；
- Stage 5 legacy import runtime 已移除；
- obsolete model-channel migration compatibility 已移除；
- 当前设置只保留现役本地语音、模型管理、存储/导出、显示、诊断等产品配置；
- 少量低价值 cosmetic/dead residual 有意有界保留，不在最终真机通过后继续扩大高风险清理面。

## 8. Final automated regression 冻结证据

Finalization 自动化覆盖：

- Fast PR Gate；
- app/core/engine unit tests；
- Room schema/current-create regression；
- FSMN candidate runtime boundary；
- FunASR CTC provenance；
- patched sherpa dependency graph；
- patched-sherpa core tests；
- production-like `releaseQa`；
- R8 / release artifact / security / sherpa package verification；
- final APK CTC / diarization native symbol verification。

关键 current-runtime-source CTC Finalization QA：

- run `39981192114` — **SUCCESS**。

普通 Finalization `[APK]` 曾暴露 stock `releaseQa` 不含完整 P5 patched-sherpa + revision-2 CTC runtime 的构建链缺口；该问题已通过新增专用 **Stage 16B CTC Finalization QA Gate** 闭环。后续在 revision-2 尚未 production promotion 前，不得把普通 stock QA APK 作为 CTC 真机验收权威包。

## 9. Final real-device QA 冻结

Finalization QA1–QA10：**全部 PASS**。

覆盖：

- clean install / Room current baseline；
- QS668/CB08 connect / record / refresh / download；
- local playback / pause / seek / duration；
- realtime ASR；
- FunASR revision-2 / same-stream CTC / diarization；
- SenseVoice；
- AUTO / fixed2 diarization；
- FSMN normal path；
- transcript edit / manual speaker assignment / annotation / export / persistence；
- local delete vs device delete / re-download / reconnect；
- model management / local settings / offline boundary / restart persistence；
- background task / completion / persistence / restart stability。

CI PASS 不替代上述真机证据；真机 PASS 也不扩大为未测试硬件/场景的声明。

## 10. Model-channel hard boundary

本 Freeze **不执行**：

- merge model-channel PR #21；
- merge Android PR #35；
- promotion FunASR revision-2；
- 修改 mutable model-channel `main` production manifest；
- 把 candidate CTC bundle 宣称为已正式 production promotion。

Android 当前 immutable production pin 与 model-channel repository 的 mutable `main` 是两个不同事实，后续不得混淆。

## 11. Hard Boundary / 不得回归

后续阶段未重新需求确认前，不得退化或暗改：

- `voice-card-android` 完全隔离规则；
- QS668 / CB08 BLE protocol / GATT semantics；
- local/device delete separation；
- canonical audio / sample-index timeline truth；
- one playback chain；
- realtime ASR frozen behavior；
- FSMN primary / Silero fallback contract；
- SenseVoice file ASR；
- FunASR high-quality file ASR；
- P5 R5 same-stream CTC；
- AUTO / fixed2 diarization 与 speaker mapping；
- transcript edit/export；
- Timeline Annotation；
- Room v16 pre-release baseline；
- offline product boundary；
- immutable production model pin；
- QA evidence / Freeze/Handoff history。

## 12. 下一阶段边界

Stage 16 后的下一规划阶段：

**Stage 17 — Fully Offline Intelligence**

状态：**RESERVED / NOT STARTED**。

Stage 18 — Local Speech Quality & Latency Consolidation 同样保持 **RESERVED / NOT STARTED**。

开始 Stage 17 前必须重新核验 GitHub 当前 `main`、PR/branch、CI、source/resources、Room、model channel、Stage 16 Freeze/Handoff；必须重新执行需求确认与开发规划 Gate。不得因为本 Freeze 已完成而自动编码 Gemma、本地总结、Q&A/RAG 或 Stage 18 speech changes。

## 13. 用户最终批准

用户于 2026-10-24 明确指令：

> 执行 Stage 16 Final Freeze/Handoff

因此 Stage 16 product/runtime behavior 允许 Final Freeze，并允许创建 Final Handoff 文档与完成 docs-only final gate。