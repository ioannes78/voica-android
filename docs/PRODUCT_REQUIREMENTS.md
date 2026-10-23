# Voica Android 当前产品需求

状态：**PRE-RELEASE / PERSONAL USE / FULLY OFFLINE DIRECTION**

修订日期：2026-10-23

## 1. 产品定义

Voica 是 QS668 / CB08 录音卡的 Android 原生配套应用，同时支持手机本地录音与本地语音处理。

当前核心链路：

`录音 / 实时音频 → Android 本地音频 → 本地 VAD → 本地 ASR → 说话人/时间轴 → 本地 AI 总结/问答 → 原始语音证据定位`

产品当前未发布，仅开发者本人使用。开发阶段优先保证架构干净、数据正确、真机可靠和全离线能力，不为旧开发版本升级兼容增加不必要的长期负担。

Voica 是一个全新项目，**不继承 `voice-card-android` 的任何代码或模块实现**。

## 2. 产品语言

- 默认 App UI：简体中文；
- 默认错误提示、状态提示、设置项和操作确认：简体中文；
- 首先完整保证简体中文体验；
- 国际化能力可保留扩展点，但不是当前优先目标。

## 3. 核心产品原则

### 3.1 Fully Offline

模型完成安装后，核心功能应可在无网络/飞行模式下运行：

- 录音；
- 本地文件管理；
- 播放；
- VAD；
- realtime/file ASR；
- 时间轴；
- diarization；
- transcript edit/export；
- AI 总结；
- AI 问答；
- 本地检索；
- evidence → 原始语音定位。

网络只用于非核心职责，例如首次模型下载、应用/模型更新或用户显式选择的外部操作。

不得因本地识别/总结失败自动上传音频或文本。

### 3.2 Canonical Timeline

canonical 16 kHz PCM sample timeline 继续作为本地媒体/语音处理的唯一时间真值。

- ASR、CTC、speaker、annotation、Q&A evidence 均应最终回到稳定 canonical range；
- LLM 不得生成或猜测可跳转 timestamp；
- AI evidence 使用稳定 segment/range identity，由 App 映射到真实播放位置。

### 3.3 Pre-release compatibility policy

正式发布 baseline 建立前：

- 不要求长期 Room migration compatibility；
- 不要求旧开发数据无损迁移；
- 不要求旧 summary/transcript backfill；
- 重大 schema 调整允许清除 App 数据/卸载重装；
- 允许删除仅服务旧版本兼容的字段、表、DAO、adapter、Preference key、旧 config parser 和 migration test；
- 允许按当前架构重新建立干净 pre-release schema baseline。

Git 历史、Freeze/Handoff 和 QA 证据仍必须保留。

正式发布前再建立 release baseline 之后的 migration/compatibility policy。

## 4. 设备与录音

- 扫描并连接支持的录音卡；
- 连接状态、断连与重连；
- 电量/充电/容量/固件/时间同步；
- 设备录音控制与状态；
- 设备文件列表、下载、取消、失败恢复和单文件删除；
- 本地录音库；
- 手机麦克风本地录音；
- canonical audio 生成和持久化；
- 播放、seek、倍速；
- Timeline Annotation。

## 5. 本地语音处理

### 5.1 Local ASR

- Android 本地 ASR；
- 中文优先，按模型能力支持中英混合/多语言；
- realtime 与 file 使用统一 capability contract；
- 文件 VAD 当前为 FSMN primary → Silero fallback；
- realtime VAD 后续收敛到 FSMN true-streaming primary → Silero fallback；
- 不支持 true streaming 的模型不得伪装为实时模型；
- FunASR high-quality、SenseVoice balanced/efficiency 和 Zipformer realtime 按阶段冻结的正式绑定执行；
- 长录音必须 bounded-memory；
- 支持取消和明确失败状态。

### 5.2 Timeline / CTC

- 转写正文保存可信时间边界；
- 支持真实 CTC/timestamp 时优先使用；
- 不为排版人为伪造 timestamp；
- 点击文本可跳转原始音频；
- 播放可高亮当前 transcript range。

### 5.3 Diarization

- 单录音内稳定 Speaker ID；
- AUTO / fixed speaker count；
- overlap/ambiguous 显式保留；
- manual speaker assignment；
- 当前不宣称跨录音识别真实人物。

## 6. Local AI Intelligence

当前目标模型方向：**Gemma 4 E2B**。当前 Google AI Edge Gallery 已列出 Android 可部署的 `Gemma-4-E2B-it` LiteRT-LM 模型；具体 Android artifact/runtime/revision 仍在 Stage 17 开始时重新核验并冻结。

AI 使用结构化 transcript 作为默认输入，不重复承担专业 ASR/VAD/diarization 职责。

### 6.1 Offline Summary

至少支持：

- 标题；
- 摘要；
- 关键点；
- 决策；
- 待办；
- 会议纪要/结构化整理。

### 6.2 Long Transcript Intelligence

长录音使用 hierarchical summarization / retrieval，不依赖一次性全文 prompt。

### 6.3 Offline Q&A + Audio Citation

用户可对当前录音/转写提问。

回答必须尽可能附带 evidence range，并可点击跳到原始音频。

原则：

> **LLM 负责语义，Voica 负责坐标。**

### 6.4 Local Retrieval / RAG

首版优先使用 SQLite FTS5 / lexical retrieval / neighbor expansion / local rerank。

只有真实效果证明需要时再加入本地 embedding model；不默认引入云向量数据库。

## 7. 明确移除的产品方向

当前主路线不包含：

- Online AI Summary；
- OpenAI-compatible API 配置；
- Base URL / API Key / remote model selector；
- Gemini/OpenAI/Claude 等在线总结 backend；
- cloud AI fallback；
- cloud file ASR；
- cloud realtime ASR；
- 自动上传原始录音；
- 本地失败后自动切换到云端；
- Audio LLM 云端上传作为主流程。

未来若重新引入，必须独立立项，不依据历史 roadmap 自动恢复。

## 8. Stage 16 收尾要求

Stage 16 finalization 需要在 Freeze 前：

- 取消 SenseVoice Rich Audio Metadata；
- 删除 Online AI Summary；
- 清理 pre-release compatibility debt；
- 整理 Room/data schema；
- 删除 dead code/resource/dependency；
- 完成 automated + real-device regression；
- 经用户明确授权后 Final Freeze/Handoff。

## 9. 验收原则

“可以编译”不等于“功能完成”。

设备、语音、长录音和本地 AI 等关键能力必须有对应自动化与真实设备/真实录音证据后才能冻结。

在正式发布前，clean install / clean database 是允许且应覆盖的验收场景。
