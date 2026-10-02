# Voica Stage 11 Freeze

状态：**FROZEN / 用户验收通过**

日期：2026-10-03

## 1. Freeze 结论

Stage 11 — AI 智能总结 / 内容理解完成并冻结。

用户最终明确确认：

**“测试通过”**

Freeze 前 implementation HEAD：

`ff62e8f45ca43fc7e416775eaf187a13751b8172`

最终 QA：

- versionCode：32
- versionName：`0.11.5-stage11-qa-fix5`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：4
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- QA CI：#307 / `37030260159` — success
- QA APK SHA-256：`0cbb8c9f5bd1670d72c6c1ee6650e45c794a482d9df7803d8bbf961f0db8e089`

## 2. 默认 AI 总结链冻结

默认链固定为：

`Recording → selected Transcription version → structured transcript → Text LLM Provider → validated AI Summary`

普通“AI 总结”不得静默上传原始录音。

Text LLM 输入可包含：

- selected transcriptionId
- final transcript text
- speaker-aware evidence units
- absolute canonical sample ranges
- language / lineage metadata

AI 结果必须保留 recording/transcription/provider/model/template/input-mode lineage。

## 3. Summary 数据与证据冻结

Stage 11 使用独立结构化 AI Summary 数据，不把模型生成内容写回 ASR TranscriptSegment/Token，也不改变 Stage 10 时间事实。

AI Summary 基础结构：

- contentType
- title
- overview
- sections
- section type / label
- items
- evidenceRefs
- epistemicStatus
- attributes

TRANSCRIPT_STATED item 必须至少引用一个合法 evidenceRef。

模型不得制造不存在的 evidenceRef；结果进入 Room 前必须通过 schema/evidence validation。

## 4. Evidence / Timeline 冻结

evidenceRef 使用稳定 `S00001...` 形式映射回 Stage 8/9/10 的 source segment / speaker span 与 absolute canonical sample range。

AI evidence seek-to-play 必须继续使用 Stage 7/10 的 sample timeline，不建立第二套毫秒时间轴。

AI 合成内容不是 ASR timing 事实。

## 5. 总结模式冻结

Stage 11 提供：

- SMART：默认智能判断内容类型和适用章节
- PRESET：使用预设结构
- CUSTOM：使用用户自定义关注点/结构

内置预设包括通用总结、会议纪要、访谈、课程/培训、工作汇报、项目讨论、销售沟通、头脑风暴、个人语音笔记。

同一 Transcription 可生成多个 Summary version，不覆盖旧结果。

## 6. 输出语言冻结

Voica 默认产品语言为简体中文。

除非用户自定义明确要求其他语言，AI Summary 的：

- title
- overview
- section label
- item text
- 可读 attributes

默认输出简体中文。

专有名词、产品名、缩写、代码、标识符和精度敏感技术词可保留原文。

JSON keys、schema enum、evidenceRef 不翻译。

## 7. Provider 抽象冻结

Text LLM Provider 与 ASR Provider 独立。

Stage 11 Provider presets/contract 包括：

- OpenAI
- Google Gemini API / AI Studio
- Google Vertex AI contract
- xAI / Grok
- DeepSeek
- 阿里云百炼
- 火山引擎 / 豆包
- 硅基流动
- 智谱 GLM
- 月之暗面 Kimi
- OpenRouter
- Custom OpenAI-compatible

Provider Profile 支持多配置、本地默认选择、Base URL、API Key、Model、manual context window。

Provider 支持时可自动获取模型；始终保留手动模型 ID fallback。

## 8. Provider 安全冻结

API Key：

- 通过 Android Keystore + AES-GCM 保存
- 不进入 Room
- 不进入 Summary lineage
- 不回显到请求 body
- 不写入错误日志

连接测试只使用 synthetic content，不上传真实 transcript。

Provider URL 要求 HTTPS，禁止 URL 内 credentials/query/fragment。

## 9. 结构化输出兼容冻结

支持 native json_schema 的 Provider 优先使用 schema mode。

仅支持 json_object / OpenAI-compatible fallback 的 Provider：

- 仍必须把完整 Summary JSON Schema 明确提供给模型
- 返回结果仍经过本地严格 schema/evidence validation
- 单层 ```json code fence 可做兼容剥离
- 不因兼容而放宽 evidence 安全规则

SiliconFlow / Volcengine 的结构化总结默认关闭 reasoning/thinking，避免 reasoning 消耗最终 JSON 输出预算。

## 10. 长文本与恢复冻结

长转写按 token budget 决定 direct 或 hierarchical map/reduce。

支持：

- chunk planning
- map partial summary
- reduce
- Room checkpoint
- interrupted 后可继续
- 用户主动 cancel

进程重启后不得自动静默重新发送云端请求。

## 11. Room Freeze

Room schema 冻结为 **v4**。

Stage 11 为 AI Summary/template/evidence/checkpoint 等增加 additive persistence，并保留 v1→v4 migration coverage。

不得把 API Key 保存到 Room。

## 12. Audio LLM 边界

Stage 11 只冻结 `AudioUnderstandingProvider` 或等效能力契约。

完整“直接分析录音”高级模式仍属于 Stage 19。

普通 Text LLM 总结失败时，不得自动改为上传音频。

## 13. 真机证据

见：

- `docs/STAGE_11_TEST.md`

用户已明确确认最终 QA 候选测试通过。

## 14. 后续边界

下一阶段：

**Stage 12 — 产品 UI / UX 完整化 + 本地内容管理**

Stage 12 可以消费 Stage 11 已冻结的：

- AI Summary versions
- structured sections/items
- Provider/model/template lineage
- evidenceRefs
- Recording / Transcription relationship

Stage 12 可增加复制、分享、导出、删除、收藏、标签、全文搜索等产品能力，但不得破坏 Stage 11 summary/evidence lineage。

