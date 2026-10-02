# Voica Stage 11 Test

状态：**用户真机验收通过**

日期：2026-10-03

## 1. 验收结论

Stage 11 — AI 智能总结 / 内容理解已完成 QA 真机验收。

用户最终明确反馈：

**“测试通过”**

因此 Stage 11 功能验收门已满足。

## 2. 最终 QA 基线

- implementation HEAD：`ff62e8f45ca43fc7e416775eaf187a13751b8172`
- versionCode：32
- versionName：`0.11.5-stage11-qa-fix5`
- QA Application ID：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Room schema：4
- QA CI：#307 / `37030260159` — **success**
- QA artifact：`Voica-qa-apk`
- QA APK SHA-256：`0cbb8c9f5bd1670d72c6c1ee6650e45c794a482d9df7803d8bbf961f0db8e089`

CI #307 已确认 Unit Test、Debug/QA build、稳定 QA 签名、Room schema gate 和 APK upload 全部通过。

## 3. 真机验收重点

本阶段真机迭代确认：

- Provider Profile 可保存多个配置并选择默认 Provider。
- Google Gemini / AI Studio Provider 可工作。
- xAI / Grok 可生成总结；默认中文输出问题已修复并通过复测。
- 火山引擎 DeepSeek V4.1 Flash 的结构化总结链路已修复并通过复测。
- 硅基流动 Qwen3-32B 的结构化总结链路已修复并通过复测。
- OpenRouter 可获取大规模模型列表；列表不再只显示前 100 个，并支持搜索。
- OpenRouter HTTP 429 被识别为 Provider rate-limit/quota 类问题，而不是伪装成连接成功。
- AI 总结历史、重新生成、失败状态和恢复入口可见。

## 4. QA 修复记录

### QA Fix 1

- Provider Profile 允许先保存 API Key/Base URL，再获取并选择模型。
- blank/missing defaultModel 可恢复读取，不要求清除 App 数据。

### QA Fix 2

- 连接测试在已选模型时真正发送 synthetic text probe。
- synthetic connection test 不上传真实转写。
- OpenRouter/大模型目录改为可搜索、虚拟化选择器。
- OpenAI-compatible response 支持字符串与 text-parts content。
- SiliconFlow reasoning-only 返回增加兼容处理。

### QA Fix 3

- 火山 reasoning-only 返回增加 provider-native thinking disable 路径。
- HTTP 429 用户提示区分 rate limit / quota。

### QA Fix 4

- AI 总结默认所有人类可读内容使用简体中文。
- Grok 中文输出问题修复。
- repair prompt 同样保持中文输出。
- Prompt version 升为 2。

### QA Fix 5

- 修复 SiliconFlow/Qwen3 与 Volcengine/DeepSeek 的结构化输出根因：
  Provider 不支持 native json_schema 时，完整 Voica Summary JSON Schema 也会明确发送给模型。
- 结构化总结对 SiliconFlow/Volcengine 预先关闭 thinking，避免推理占满最终 JSON 输出预算。
- 保留 json_object 基础格式约束。
- 兼容单层 ```json code fence，再进入严格 schema/evidence 校验。
- 增加对应回归测试。

## 5. 自动化覆盖

Stage 11 自动化覆盖至少包括：

- Provider model discovery / selected-model synthetic connection probe
- API Key 不进入 request body / Room / summary lineage
- 401/429 等 Provider 错误分类
- OpenAI / Gemini / OpenAI-compatible 请求契约
- SiliconFlow / Volcengine structured-output fallback
- reasoning disable 行为
- Provider Profile blank/missing defaultModel persistence
- Summary Prompt 默认简体中文
- JSON code-fence normalization
- structured result schema/evidence validation
- long transcript token budgeting / map-reduce / checkpoint reuse
- Room v1→v4 migration与 Stage 11 新表持久化

## 6. 未声明为已验证的事项

本次验收不扩张为以下结论：

- 所有预设 Provider 的全部模型都已经逐个真机验证。
- OpenRouter 免费模型 429 已由 App 消除；429 属于 Provider 侧限流/额度状态。
- Google Vertex AI OAuth 已完整实现；Stage 11 仅保留独立 Provider 契约，不在设备保存 service-account private key。
- Audio LLM 已可直接上传音频分析；Stage 11 仅冻结能力接口，完整功能仍在 Stage 19。
- 真实 30min / 1h / 2h 云端总结压力、费用与 Provider soak 已完成。

