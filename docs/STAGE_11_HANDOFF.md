# Voica Stage 11 Handoff

状态：**Stage 11 已完成 / 已真机验收 / 已冻结**

下一阶段：**Stage 12 — 产品 UI / UX 完整化 + 本地内容管理**

## 1. 接管原则

GitHub 合并后的 `main` 是唯一事实来源。

新 Agent 必须重新读取：

- `AGENTS.md`
- `docs/ROADMAP.md`
- Stage 13C 起还必须读取 `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_11_TEST.md`
- `docs/STAGE_11_FREEZE.md`
- 本文件
- 当前 main HEAD / Actions / open PR
- Room v4 schema
- `:core:ai` / `:engine:llm`
- Stage 8/9/10 transcript/speaker/timeline 契约

聊天记录仅是线索。

## 2. Stage 11 实现基线

Freeze 前 implementation HEAD：

`ff62e8f45ca43fc7e416775eaf187a13751b8172`

最终 QA：

- versionCode 32
- `0.11.5-stage11-qa-fix5`
- QA package `io.github.ioannes78.voica.qa`
- Room 4
- sherpa-onnx 1.13.8
- arm64-v8a
- CI #307 / `37030260159` success
- APK SHA-256 `0cbb8c9f5bd1670d72c6c1ee6650e45c794a482d9df7803d8bbf961f0db8e089`

用户明确：

**“测试通过”**

## 3. Stage 11 核心链

默认总结：

`selected Transcription → StructuredTranscriptInput → TextLlmProvider → AiSummaryEngine → validated structured result → Room v4`

关键原则：

- 默认只上传转写文本/必要结构化 metadata。
- 不上传原始音频。
- AI 内容不能反写成 ASR 时间事实。
- evidenceRef 必须可追溯到真实 transcript/speaker source range。

## 4. 新模块

Stage 11 新增核心模块：

- `:core:ai`
- `:engine:llm`

`:core:ai` 负责：

- summary contracts
- template/prompt
- token budget
- chunk/map/reduce
- structured result codec
- evidence validation
- checkpoint contract

`:engine:llm` 负责：

- Provider adapter
- HTTP transport
- Provider Profile persistence
- Android Keystore credential storage
- model discovery
- connection test
- OpenAI-compatible / Gemini 等适配

UI/Coordinator 在 `:app` 中消费这些模块。

## 5. Room v4

Stage 11 从 v3 additive migration 到 v4。

Room 保存：

- summary generation/version
- template snapshot
- evidence lineage
- chunk/checkpoint 等必要运行数据

Room **不保存 API Key**。

API Key 使用 app-private credential store + Android Keystore AES-GCM。

## 6. Provider 事实

支持多 Provider Profile。

连接测试：

- 没选模型但 model discovery 成功时，可验证 endpoint/auth。
- 已选模型后必须做 synthetic generation probe。
- 不上传真实 transcript。

大模型目录使用 searchable picker，不截断为前 100 项。

OpenRouter HTTP 429 代表 Provider rate-limit/quota 类状态；不要在 Stage 12 把它改成“连接成功”。

后续从 Stage 17 起，Provider Profile 可扩展为共享 credential/region/base metadata + 独立 capability adapter：Text LLM / File ASR / Realtime ASR / Audio LLM。不同 capability 的模型列表、连接测试、协议与参数必须独立，不能因为属于同一厂商就合并成一个模糊接口。

## 7. Structured Output

native json_schema 可用时优先使用。

Fallback Provider 即便只支持 json_object，也必须：

- 发送完整 Summary schema
- 本地严格 decode
- 严格 evidence validation

SiliconFlow/Volcengine structured summary 默认禁用 thinking/reasoning。

该历史规则只针对当时云 Provider 的 structured-output 兼容性；未来 Stage 19A 的本地 Gemma 4 Thinking 必须按当时 LiteRT-LM 能力重新验证，不能机械沿用为全局禁用规则。

## 8. 默认中文

Prompt version 2。

除非用户明确自定义其他语言，人类可读总结默认简体中文。

Stage 12 做复制/分享/导出时直接使用已持久化 Summary 内容，不要在展示层偷偷二次翻译并造成历史版本不一致。

## 9. Stage 12 可直接消费

Stage 12 可使用：

- Recording
- Transcription versions
- Stage 10 Timeline / sample seek
- AI Summary versions
- sections/items/evidenceRefs
- provider/model/template lineage

推荐 Stage 12 在同一既有数据库和对象生命周期上实现：

- AI 总结复制/分享/导出
- 标题管理
- 收藏/标签
- 删除与历史版本
- 转写与 AI 总结全文搜索
- 从 evidenceRef 跳回转写/音频位置

## 10. Stage 12 不得破坏

- Stage 6 Recording/AudioAsset 生命周期
- Stage 8 Transcription version history
- Stage 9 run-local Speaker identity
- Stage 10 absolute canonical sample timeline
- Stage 11 evidenceRefs / Summary lineage
- Provider credential 隔离
- 默认 Text LLM 不上传音频的隐私边界

## 11. 已知后续项

Stage 13C 以后以 `docs/ROADMAP_STAGE_13C_PLUS.md` 为准。

- 云端文件 ASR / 音频上传转写：Stage 17
- 云端实时 ASR：Stage 18
- 离线 AI 总结：Stage 19A，Gemma 4 E2B / E4B；E4B 默认高质量，E2B 轻量 fallback；Thinking Budget 作为高级设置，开发时重新核验 runtime 能力
- 离线 Audio LLM：Stage 19B，Gemma 4 E2B 默认、E4B 高质量；Audio LLM 不替代专业 ASR
- 云端 Audio LLM + 高级 AI / 语义能力：Stage 19C
- 全 App UI / UX 最终精修：Stage 19D
- 长时稳定性/后台下载：Stage 13B
- 说话人分离性能专项：Stage 13C
- UI/内容管理/本地全文搜索：Stage 12

普通 AI Summary 的默认隐私边界保持：**基于转写文本生成，不因为 Text LLM 失败而自动上传原始音频。**

## 12. Stage 12 接管顺序

1. 读取合并后最新 main HEAD。
2. 核对 Stage 11 Freeze/Handoff/Test。
3. baseline validation。
4. 输出《Stage 12 修订需求》。
5. 等用户确认。
6. 输出《Stage 12 修订开发规划》。
7. 再等用户确认。
8. 确认后才创建 Stage 12 development branch 并编码。
