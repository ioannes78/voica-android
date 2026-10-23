# Stage 16 Finalization — Pre-release Cleanup & Regression Record

状态：**CLEANUP IMPLEMENTED / AUTOMATED REGRESSION PASS / REAL-DEVICE REGRESSION PASS / FINAL FREEZE & HANDOFF COMPLETE**

修订日期：2026-10-24

归属：**Stage 16 — Finalization**

本记录取代本文件此前的 `APPROVED DIRECTION / PLANNED / NOT IMPLEMENTED` 与 `FINAL FREEZE NOT PERFORMED` 状态。Stage 16 Finalization 的实际代码清理、自动化回归、真机回归以及用户明确授权的 Final Freeze/Handoff 均已完成。PR merge 与 model-channel promotion 仍不包含在本次授权内。

正式冻结/交接文档：

- `docs/STAGE_16_FINAL_FREEZE.md`
- `docs/STAGE_16_FINAL_HANDOFF.md`

---

## 1. 当前事实基线

- `main` 仍保持 Stage 16A.2 已冻结基线；Stage 16B / Finalization 尚未合并。
- Android PR #35 仍为 **Draft / Open / Unmerged**。
- Finalization 开发分支：`stage16b-local-file-asr-v2`。
- Finalization 真机回归所验证的 runtime/source head：`ef753e8b624f9acfa397775f26ffbfb3e59c1937`。
- 随后的提交仅更新 Finalization / Freeze / Handoff 文档，不改变已真机验证的 runtime/source。
- Room 当前版本：**v16**。
- P5 revision-5：**CLOSED / REAL-DEVICE PASS**。
- SenseVoice Rich Audio Metadata：**CANCELLED / NOT IMPLEMENTED**。
- Stage 16 Final Freeze/Handoff：**COMPLETE**。
- Stage 17 / Stage 18 implementation：**NOT STARTED**。

---

## 2. SenseVoice Rich Audio Metadata

结论：**CANCELLED / NOT REQUIRED FOR STAGE 16 CLOSURE**。

未实施：

- emotion persistence；
- audio-event persistence；
- Speaker × Emotion timeline；
- rich metadata export；
- rich metadata AI input；
- 为上述功能新增 Room schema。

SenseVoice 正文 ASR 能力保留，并已完成 Finalization 真机回归。

---

## 3. Online AI Summary 清理结果

Stage 16 已按 fully-offline 产品方向移除现役 Online AI Summary 产品/runtime 路径。

清理覆盖：

- 在线 Provider/runtime 调用路径；
- OpenAI-compatible endpoint / Base URL / API Key / remote model 产品配置；
- provider/model selector 产品入口；
- online summary 网络调用、重试/超时/云端 fallback 相关现役路径；
- 仅服务于在线 AI 的设置/兼容代码；
- 退役 direct-audio summary mode；
- 退役 provider export metadata。

允许并实际保留的通用层：

- provider 无关的 summary 展示/历史结构；
- 可供 Stage 17 本地智能复用的通用 summary domain/history/evidence/revision 数据结构；
- 不依赖网络 provider contract 的通用交互。

Finalization 设置审计确认：当前设置 UI 不再暴露 OpenAI / OpenRouter / 火山 / Grok / Google / 硅基流动等 Provider，也不再暴露 API Key、Base URL、云端模型选择或在线 AI 总结入口。

`INTERNET` 权限仍保留，因为现役 model catalog / model download 需要网络；该权限不能因为 Online AI 移除而删除。

---

## 4. Pre-release Compatibility / Room / Preferences 清理结果

### 4.1 Room / database

- 当前 Room baseline 已收敛到 **v16**。
- exported schema 只保留当前 pre-release baseline 所需版本。
- 已增加 current-create regression，验证 clean database 可直接创建当前 v16 schema，并可访问全部当前 DAO。
- 旧开发版本长期迁移兼容不再作为 pre-release 发布约束。
- Stage 5 legacy import runtime / historical-only writeback 已移除。

### 4.2 Model-channel compatibility

- 旧 model-channel migration compatibility 已移除。
- 当前 production pin 保持不可变 commit + manifest version + digest 校验。
- QA/debug candidate override 仍受限于显式 QA/debug 边界。

### 4.3 Preferences / settings

已审计当前 Preferences 与设置树：

- 保留现役本地 ASR / VAD / diarization / playback / export / diagnostics / model management 配置；
- Online AI provider / API / remote-model 配置已不在现役设置 UI；
- 未为“清理得更彻底”而冒险删除已经通过真机验证、仍可能承担本地语音兼容职责的历史 enum/config value。

### 4.4 有界残留

Finalization 有意保留少量不影响产品路径的 cosmetic/dead residual，例如历史字符串或大调用文件中的低价值未使用参数；这些残留不构成在线 AI 产品暴露，也不值得在最终真机通过后用高风险大文件改写继续扩大变更面。

---

## 5. Protected capability 结果

以下能力在 Finalization 后仍受保护并完成相应自动化或真机回归：

- BLE 设备连接 / 录音 / 文件列表 / 下载；
- 本地播放 / seek / duration；
- realtime ASR；
- Local File FSMN VAD primary；
- Silero VAD fallback contract 保留；
- SenseVoice file ASR；
- FunASR Nano high-quality file ASR；
- P5 R5 same-stream CTC；
- AUTO / fixed2 diarization；
- overlap/manual speaker assignment；
- transcript edit / persistence；
- timeline annotation；
- export；
- local/device delete separation；
- reconnect / refresh；
- clean-install / clean-database baseline；
- background task / result persistence。

---

## 6. Finalization QA build issue and closure

Finalization 初次普通 `[APK]` QA 包暴露出一个构建链问题：普通 `QA APK Gate` 只执行 stock `releaseQa`，没有带入 P5 R5 已验证的 patched sherpa + FunASR revision-2 CTC runtime。

真机症状：

- FunASR 只出现粗粒度长时间段；
- 播放无逐字/细粒度 CTC 同步；
- diarization native 计算存在，但 transcript speaker mapping 大量/全部显示“说话人未确定”；
- diagnostics 显示 `No FunASR CTC performance diagnostics yet.`。

隔离验证：重新生成已知 P5 baseline 的 patched sherpa + revision-2 QA APK 后，同一 118 秒双人样本恢复正常：

- 9/9 chunks `alignmentSucceeded=true`；
- `sameStream=true`；
- `outputTokenCount=569`；
- 569/569 token 对齐；
- AUTO diarization `localSpeakerCounts=[3, 3, 2]`，产品层两位主要说话人结果正常。

为防止再次产出“能编译但缺真实 CTC runtime”的 QA 包，Finalization 新增专用 **Stage 16B CTC Finalization QA Gate**：

- 使用当前 Android HEAD；
- 校验 immutable revision-2 candidate manifest；
- pin sherpa-onnx v1.13.8；
- 应用 A0/A1/A2 native CTC patch；
- 构建 patched arm64 JNI / AAR；
- 校验 CTC protocol 与 diarization JNI；
- 通过 isolated Maven repository 注入标准依赖图；
- 在 patched sherpa 下运行核心单测；
- 构建当前 HEAD production-like `releaseQa`；
- 最终 APK 再校验 CTC native symbol、diarization JNI、R8、安全性与 ABI；
- artifact retention 保持 1 天。

当前 runtime/source head 专用 Gate run `39981192114`：**SUCCESS**。

当前 runtime/source head 真机复测：**PASS**。

因此该问题已闭环为 **QA build-chain defect corrected / protected runtime verified on current Finalization runtime head**，不是 FunASR CTC 核心算法或 diarization 算法回归。

---

## 7. Real-device Finalization regression matrix

最终真机回归结果：

| QA | 范围 | 结果 |
| --- | --- | --- |
| QA1 | clean install / first launch / current Room baseline | **PASS** |
| QA2 | QS668/CB08 connect / record / refresh / download | **PASS** |
| QA3 | local playback / pause / seek / duration | **PASS** |
| QA4 | realtime ASR | **PASS** |
| QA5-A | P5 known-good FunASR revision-2 + CTC + diarization isolation | **PASS** |
| QA5-B | current Finalization runtime head FunASR + CTC + diarization | **PASS** |
| QA6 | SenseVoice + AUTO / fixed2 diarization + normal FSMN VAD path | **PASS** |
| QA7 | transcript edit / manual speaker assignment / annotation / export / persistence | **PASS** |
| QA8 | local delete vs device delete / re-download / reconnect refresh | **PASS** |
| QA9 | model management / local settings / offline boundary / restart persistence | **PASS** |
| QA10 | background task lifecycle / completion / persistence / restart stability | **PASS** |

Finalization 真机回归结论：**PASS**。

Silero fallback 在 Finalization 阶段未通过人为破坏 FSMN 环境强制触发；其现役 fallback contract 保留，未因最终清理被删除。P5 R5 已通过的深度 FunASR/hotword/threads/retry/CTC/diarization 测试不在 Finalization 中重复扩大测试范围。

---

## 8. Automated regression evidence

Finalization 自动化门禁已经覆盖并通过：

- Fast PR Gate；
- core / engine / app unit tests；
- Room schema/current-create regression；
- FSMN candidate runtime boundary；
- FunASR CTC provenance checks；
- patched sherpa dependency graph；
- patched-sherpa core unit tests；
- production-like `releaseQa`；
- R8 output verification；
- release artifact/security verification；
- sherpa package verification；
- current-runtime-head CTC / diarization native symbol verification。

Finalization runtime/source head 的 CTC Finalization QA run `39981192114`：**SUCCESS**。

---

## 9. Production model-channel 边界

Finalization 与 Final Freeze/Handoff 没有自动执行任何 model promotion。

当前边界：

- model-channel PR #21：**Draft / Open / Unmerged**；
- PR #21 head：`eeb59214244f392f3e2eecdb90016efbe3a95401`；
- model-channel `main/manifests/production.json` 仍是 manifest **v7**；
- Android production model binding 仍 pin 到 immutable commit `eeb59214244f392f3e2eecdb90016efbe3a95401`、manifest **v9**、固定 digest；
- FunASR revision-2 CTC bundle 仍作为 QA/candidate runtime 使用，**未因 Finalization 或 Freeze/Handoff 自动进行 model-channel promotion**；
- 不 merge model-channel PR #21；
- 不修改 mutable `main` production manifest。

任何最终 model-channel merge/promotion 仍需独立授权。

---

## 10. Stage 16 final state

最终流程：

`P5 R5 CLOSED`
→ `SenseVoice Rich Metadata CANCELLED`
→ `Online AI Runtime/Settings Cleanup DONE`
→ `Pre-release Compatibility Cleanup DONE`
→ `Room v16 Baseline DONE`
→ `Automated Regression PASS`
→ `Real-device Regression PASS`
→ `USER FINAL FREEZE/HANDOFF AUTHORIZATION RECEIVED`
→ **`Stage 16 FINAL FREEZE COMPLETE`**
→ **`Stage 16 FINAL HANDOFF COMPLETE`**

Stage 16 到此封存。

仍不得因为 Freeze/Handoff 完成而自动：

- merge Android PR #35；
- merge model-channel PR #21；
- promote revision-2 / model-channel；
- 开始 Stage 17 / Stage 18 implementation。

下一步若进入 Stage 17，必须重新核验 GitHub 并重新完成 Stage 17 requirements/development-plan Gate。