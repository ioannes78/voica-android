# Stage 16 Final Handoff — Finalization → Stage 17

状态：**FINAL HANDOFF — Stage 16 用户已明确授权 Final Freeze/Handoff；Stage 17 RESERVED / NOT STARTED**

Freeze 文档提交：`5efbb3e8f4d0a0f3473171c8582f9c463e594c8d`

Handoff 日期：2026-10-24

## 1. Stage 16 最终状态

Stage 16 当前正式状态：

- Local File ASR V2：**CLOSED**；
- P5 revision-5：**REAL-DEVICE PASS**；
- SenseVoice Rich Audio Metadata：**CANCELLED / NOT IMPLEMENTED**；
- Pre-release Cleanup：**DONE**；
- Online AI runtime/provider/settings cleanup：**DONE**；
- Room/Data baseline consolidation：**DONE / v16**；
- Automated Regression：**PASS**；
- Finalization Real-device Regression：**PASS**；
- Final Freeze：**COMPLETE**；
- Final Handoff：**COMPLETE**。

Stage 16 不再处于 implementation/QA 状态。

## 2. 交接事实源

后续接手者必须以 GitHub 当前仓库为唯一事实来源，并重新核验 live state。

本 Handoff 记录的最后已确认事实：

- repository：`ioannes78/voica-android`
- production target：`main`
- Final Freeze 时 `main` HEAD：`988931ebed72a70ba799d909cd36a416a673eef5`
- Stage 16 branch：`stage16b-local-file-asr-v2`
- Android PR：#35，Freeze 时为 **Draft / Open / Unmerged**
- Final real-device-tested runtime/source：`ef753e8b624f9acfa397775f26ffbfb3e59c1937`
- pre-Freeze evidence/docs head：`f05e9c6b0f449f2d424f24f9fb4a7f5e5b3079f3`
- Freeze commit：`5efbb3e8f4d0a0f3473171c8582f9c463e594c8d`
- Room：v16
- model-channel PR #21：Freeze 时 **Draft / Open / Unmerged**
- model-channel PR #21 head：`eeb59214244f392f3e2eecdb90016efbe3a95401`
- model-channel mutable `main` production manifest：v7
- Android immutable production pin：commit `eeb59214244f392f3e2eecdb90016efbe3a95401` / manifest v9 / digest `a49da9fdf2dabf2408ba2bb14cf6b5c25c35563052cc6307db8f8095caac0b45`

任何 SHA/PR/CI/model state 都必须在下一阶段开始时重新核验，不得把本 Handoff 当成未来 live state。

## 3. Stage 16 冻结证据入口

权威文档：

- `docs/STAGE_16_FINAL_FREEZE.md`
- `docs/STAGE_16B_FINALIZATION_PRE_RELEASE_CLEANUP.md`
- `docs/STAGE_16B_P5_R5_CLOSURE.md`
- `docs/STAGE_16B_A3_H2_GATE_CLOSURE.md`
- `docs/STAGE_16B_CTC_PORTABILITY_GATE.md`
- `docs/ROADMAP_STAGE_16_PLUS_FULLY_OFFLINE_AMENDMENT.md`

关键 current-runtime-source Finalization CTC QA：

- GitHub Actions run `39981192114` — **SUCCESS**。

Finalization QA1–QA10：**全部 PASS**。

## 4. 冻结能力摘要

后续 Stage 默认继承且不得无需求退化：

- QS668 / CB08 BLE connect / record / list / download / delete；
- local/device delete separation；
- local playback / seek / duration；
- realtime ASR；
- Local File FSMN VAD primary / Silero fallback contract；
- SenseVoice file ASR；
- FunASR Nano high-quality file ASR；
- P5 R5 same-stream CTC；
- fine-grained transcript/audio playback sync；
- AUTO / fixed2 diarization；
- overlap/manual speaker assignment；
- transcript edit / persistence / export；
- canonical sample-index timeline；
- Timeline Annotation；
- Room v16 clean-create baseline；
- offline-core product boundary；
- background task lifecycle / result persistence。

## 5. Finalization 构建链注意事项

Stage 16 Finalization 曾发现：普通 stock `[APK]` `releaseQa` 不等价于 P5 patched-sherpa + FunASR revision-2 CTC runtime。

已新增专用 **Stage 16B CTC Finalization QA Gate**，用于在 revision-2 仍为 candidate 时构建/校验真实 CTC QA runtime。

后续若仍未完成 revision-2 production promotion：

- 不得用 stock QA APK 的“能构建/能转写”替代真实 CTC runtime 验证；
- CTC/diarization 回归必须确认 patched runtime + revision-2 candidate bundle 的 provenance；
- 不得把 `No FunASR CTC performance diagnostics yet` 的结果判为 CTC PASS。

## 6. Model-channel 边界交接

本 Handoff 没有执行：

- Android PR #35 merge；
- model-channel PR #21 merge；
- FunASR revision-2 promotion；
- mutable model-channel `main` production manifest 更新。

因此下一阶段若需要正式 promotion/merge，必须重新核验并获得独立明确授权。

不得把 Stage 16 Freeze/Handoff 自动解释为 merge 或 promotion 授权。

## 7. 下一阶段

下一规划阶段：

**Stage 17 — Fully Offline Intelligence**

状态：**RESERVED / NOT STARTED**。

规划方向：

1. 17.1 Offline LLM Runtime Foundation；
2. 17.2 Offline Summary V1；
3. 17.3 Long Transcript Intelligence；
4. 17.4 Offline Q&A + Audio Citation；
5. 17.5 Local Retrieval / RAG。

首选模型方向仍为 Gemma 4 E2B，但 runtime、Android artifact、模型 revision、quantization、内存/性能门槛在 Stage 17 开始时必须重新研究和冻结；不得依赖旧聊天版本号直接实施。

Stage 18 — Local Speech Quality & Latency Consolidation 保持 **RESERVED / NOT STARTED**，不得与 Stage 17 自动并行启动。

## 8. Stage 17 启动 Gate

开始 Stage 17 前必须：

1. 重新核验 `main` 当前 HEAD；
2. 重新核验 PR #35 最终状态；
3. 重新核验 model-channel PR #21 / production manifest；
4. 重新核验最新 CI；
5. 重新核验 source / resources / Room / model install/catalog；
6. 读取本 Stage 16 Freeze/Handoff；
7. 输出《Stage 17 修订需求》；
8. 等待用户明确确认需求；
9. 输出《Stage 17 开发规划》；
10. 再次等待用户明确确认规划；
11. 之后才允许创建 Stage 17 开发分支或编码。

不得因为 Stage 16 Handoff 已完成而跳过上述 Gate。

## 9. 独立项目规则

Voica 继续作为独立项目：

**严禁复制、迁移、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的实现。**

可以参考公开协议、公开模型/论文/SDK 文档和产品事实，但实现必须在 Voica 自身架构与需求下独立完成。

## 10. 用户授权

用户于 2026-10-24 明确指令：

> 执行 Stage 16 Final Freeze/Handoff

因此 Stage 16 Freeze/Handoff 正式完成；下一阶段仍必须停在 Stage 17 requirements Gate，不能自动进入 implementation。