# Stage 16+ Fully Offline Roadmap Amendment

状态：**APPROVED / AUTHORITATIVE ROADMAP AMENDMENT / IMPLEMENTATION NOT STARTED**

修订日期：2026-10-23

本文件记录 Voica 在 Stage 16B P5 R5 之后确认的新产品方向，并作为 Stage 16 之后路线的当前最高优先级规划修订。

GitHub 当前仓库仍是唯一事实来源。任何后续 Stage 开始前仍必须重新核验 main、当前 PR/branch、CI、源码、Room、model channel 和对应 Freeze/Handoff。

Voica 是独立项目，不得复制、迁移、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的实现。

---

## 1. 本修订覆盖的旧未来路线

自本文件生效后，下列旧规划中与 Stage 16 后未来路线冲突的部分被本文件取代：

- `docs/ROADMAP.md` 中 Stage 17 云端文件转写、Stage 18 云端实时转写、Stage 19 云端/Audio LLM 方向；
- `docs/ROADMAP_STAGE_16C_RESERVED.md` 的 Stage 16C 编号；
- `docs/STAGE_16B_SENSEVOICE_RICH_AUDIO_METADATA_PLAN.md` 的 Stage 16B Rich Metadata 必做安排；
- `docs/STAGE_18_CLOUD_STREAMING_CONTRACT_V1.md` 的未来云端实时 ASR 合同；
- `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md` 中仅与未来 Cloud Stage 18 有关的章节。

已完成/已冻结 Stage 的历史事实、Stage 15/16A canonical realtime audio 契约和 P5 R5 证据不因本修订被改写。

---

## 2. 新的产品方向

Voica 后续目标改为：

> **全离线录音、转写、说话人、内容理解、总结、问答与原始语音证据回链。**

核心能力在模型已安装后不得依赖网络：

`Recording / Realtime Audio`
→ `Local VAD`
→ `Local ASR`
→ `Canonical Timeline / CTC`
→ `Local Diarization`
→ `Structured Transcript`
→ `Local LLM`
→ `Summary / Q&A / Retrieval`
→ `Evidence segment`
→ `Jump to original audio`

网络只允许承担非核心职责，例如首次模型下载、应用/模型更新或用户显式执行的外部操作。核心转写和 AI 能力不设置云端 fallback。

---

## 3. Pre-release / Personal-use 开发策略

Voica 当前未发布，仅用于开发者本人使用。

在正式发布基线建立前，默认策略为：

- 不为旧开发版本数据库维护长期 migration compatibility；
- 不为历史测试数据做 backfill；
- 允许重大 schema 调整后清除 App 数据/卸载重装；
- 允许删除只服务旧版本兼容的字段、表、DAO、adapter、mapper、Preference key、feature flag、测试 fixture 和 dead code；
- 允许压平 pre-release Room schema，具体最终 version 在实施时按当前源码决定；
- 不保留已经废弃的 online provider / cloud ASR compatibility layer；
- 不因旧开发版本 JSON/config/preferences 继续承担兼容成本。

但以下内容必须保留：

- Git 历史；
- Freeze/Handoff/QA 证据；
- 当前仍承担真实 production/fallback 职责的实现；
- 仍被后续 Stage 明确复用的基础 contract。

原则：

> **删除运行时兼容债，不删除开发证据；删除死路径，不删除仍有真实职责的 fallback。**

正式发布前另行建立从 release baseline 开始的 migration/compatibility policy。

---

## 4. Stage 16 — Finalization / Pre-release Cleanup

Stage 16B P5 R5 已 CLOSED / REAL-DEVICE PASS；SenseVoice Rich Audio Metadata 不再实施。

Stage 16 剩余工作调整为：

1. 取消 SenseVoice Rich Audio Metadata；
2. 删除现有 Online AI Summary 及仅为其服务的 provider/API 配置与网络路径；
3. 执行 Pre-release Compatibility Cleanup；
4. 执行 Room/Data Schema Consolidation；
5. 清理 dead code / dependency / resource / legacy config；
6. 执行静态、Unit、Instrumented、核心真机回归；
7. 经用户明确授权后执行 Stage 16 Final Freeze/Handoff。

详细规划见：

- `docs/STAGE_16B_FINALIZATION_PRE_RELEASE_CLEANUP.md`

本文件本身不授权：

- merge PR #35；
- merge model-channel PR #21；
- promotion FunASR candidate/revision；
- 修改 production model channel；
- 自动执行 Final Freeze/Handoff。

---

## 5. Stage 17 — Fully Offline Intelligence

Stage 17 新定位：

**Fully Offline Intelligence — 全离线内容理解与可追溯问答**

规划顺序：

1. **17.1 Offline LLM Runtime Foundation**
2. **17.2 Offline Summary V1**
3. **17.3 Long Transcript Intelligence**
4. **17.4 Offline Q&A + Audio Citation**
5. **17.5 Local Retrieval / RAG**

首选模型方向为 **Gemma 3n E2B**。具体 LiteRT-LM/runtime/model artifact/revision/quantization 必须在 Stage 17 开始时重新核验并冻结，不以聊天中的临时版本号作为事实源。

核心设计原则：

> **LLM 负责语义，Voica 负责时间坐标。**

Gemma 不得生成或猜测可跳转 timestamp。Q&A 返回稳定 evidence/segment identity，由 Voica 使用 canonical transcript/audio timeline 映射到真实播放位置。

详细规划见：

- `docs/ROADMAP_STAGE_17_FULLY_OFFLINE_INTELLIGENCE.md`

---

## 6. Stage 18 — Local Speech Quality & Latency Consolidation

原 Stage 16C 的本地语音质量/延迟优化完整后移为 Stage 18，不删除其核心设计。

顺序：

1. **18.1 Unified FSMN VAD**
2. **18.2 Realtime Two-Pass ASR**
3. **18.3 Sentence Composition V2**
4. **18.4 Diarization Performance V2**
5. **18.5 Full Speech Pipeline Regression**

详细规划见：

- `docs/ROADMAP_STAGE_18_LOCAL_SPEECH_CONSOLIDATION.md`

Stage 18 不承担云端 realtime ASR。

---

## 7. 明确取消的未来方向

以下能力从当前产品主路线移除：

- Cloud File ASR；
- Cloud Realtime ASR；
- Online AI Summary；
- OpenAI/Gemini/Claude 等在线总结 provider 作为正式 backend；
- 云端 AI fallback；
- 本地失败后自动上传音频/文本；
- API Key 多 Provider 产品路线；
- Audio LLM 云端上传作为主路线。

如果未来重新评估上述能力，必须作为新的独立需求重新立项，不能依据旧 roadmap 自动恢复。

---

## 8. 当前阶段门禁

当前仍处于 Stage 16 收尾。

不得因为 Stage 17/18 已重新规划而：

- 提前创建 Stage 17/18 implementation branch；
- 提前编码 Gemma/LLM；
- 提前实施 Stage 18 FSMN realtime/two-pass；
- 绕过 Stage 16 cleanup/regression；
- 自动执行 Stage 16 Final Freeze/Handoff。

Stage 16 完成、用户明确确认 Freeze/Handoff 后，才允许重新核验 GitHub 并进入 Stage 17 requirements/development-plan Gate。
