# Voica Stage 12B Handoff

状态：**Stage 12B 已完成 / 已真机验收 / 已冻结**

下一子阶段：**Stage 12C — 转写 / AI 总结内容管理 + 统一全文搜索**

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

接管 Stage 12C 前必须重新核对：

- main / branch HEAD
- open PR / merged PR
- GitHub Actions
- `AGENTS.md`
- `docs/ROADMAP.md`
- Stage 13C 起还必须读取 `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_12B_TEST.md`
- `docs/STAGE_12B_FREEZE.md`
- 本文件
- Room v5 schema
- Recording / AudioAsset / AudioDerivation / Transcription / AiSummary repositories

聊天记录只能作为线索。

## 2. Stage 12B 最终基线

实现 QA HEAD：

`d161cf0404bcc8fd8c3a0a9817377789a10c5598`

真机验收记录提交：

`35501477be7366007d23514335ec47a0bc885fce`

冻结文档提交：

`65fc9f09878d2ba8d263d9467162304dd1bbc766`

最终 QA：

- versionCode 39
- `0.12.3-stage12b-qa3`
- QA package `io.github.ioannes78.voica.qa`
- Room 5
- arm64-v8a
- sherpa-onnx 1.13.8
- CI run `37142129654` / #570 success
- Artifact ID `11280668651`
- APK SHA-256 `a6ec8350683f055ecf8e87ff61bd8e67d9ae75ea85c7dddc0d53e8922350e2fd`

用户最终明确：

**“测试通过”**

## 3. Room v5 数据边界

Stage 12B 已把本地管理 metadata 加入既有 Recording 图，不建立第二套录音库。

新增核心数据：

- RecordingImportProvenance
- RecordingUserMetadata
- Folder
- Tag
- RecordingTagCrossRef
- LOCAL_IMPORT
- IMPORTED_ORIGINAL
- mediaDurationMs

Stage 12C 必须继续复用这些对象。

不要：

- 新建第二套 Recording 表
- 通过移动物理音频文件实现文件夹
- 覆盖原始导入 provenance
- 破坏 v4→v5 migration lineage

## 4. 本地库查询边界

Stage 12B 搜索只覆盖 Recording metadata。

Stage 12C 的统一全文搜索才负责：

- 转写正文
- AI Summary 正文
- 结构化待办/决策等文本
- 标签与录音 metadata 的统一入口

不要把现有 metadata LIKE/query 直接扩展为大量长文本全表扫描。

优先设计 Room FTS 或等效本地索引，并明确验证中文无空格查询行为。

## 5. 导入 / canonical 边界

Stage 12B 已冻结：

`SAF → app-private imported original → canonical WAV`

通用 Android 媒体链支持：

- WAV
- MP3
- M4A/AAC
- ADTS AAC
- FLAC
- Ogg Opus

QS668 raw framed Opus 继续走专用链。

Canonical WAV 是可再生成派生资产；Imported/Device original 是可追溯源资产。

Stage 12C 文本管理不得把 canonical 清理与转写/总结文本删除混为一谈。

## 6. 本地删除边界

Recording 删除仍必须由 LocalRecordingDeleteCoordinator 协调：

- playback
- canonical
- transcription
- diarization/alignment
- AI Summary
- files
- Room cascade

Stage 12C 若允许删除单个 Transcription/Summary version，应建立**版本级删除**，不要调用 Recording 删除生命周期。

版本级删除不得删除原始 Recording 音频。

## 7. 播放 / 时间轴事实

Stage 7/10 冻结的唯一媒体时间仍是：

**absolute canonical 16 kHz PCM sample index**

Stage 12C 文本编辑、搜索结果定位、分享/导出：

- 不得把 wall-clock 毫秒作为新的唯一时间事实
- 不得因人工修改文本重写原始 token/sample timeline
- 需要定位音频时继续通过现有 sample mapping

## 8. 转写人工修订要求

Stage 12C 必须明确区分：

- 模型原始 ASR 版本
- 用户人工修订层/版本

人工编辑不得直接覆盖原始 ASR 输出。

必须保留：

- transcriptionId
- model lineage
- segment/token 原始时间信息
- speaker/alignment lineage
- 原始 finalText 可恢复性

建议人工修订保存为独立 revision/edit layer，而不是修改原始模型结果。

## 9. AI Summary 人工编辑要求

Stage 12B 已冻结每个 Summary version 的：

- providerProfileId
- providerNameSnapshot
- model
- baseUrlSnapshot
- template/mode
- input lineage
- structured payload
- evidence refs

Stage 12C 人工编辑不得覆盖这些模型原始结果。

建议：

`AI 原始 Summary → User Revision`

人工新增内容必须与模型 evidence 明确区分，不能伪装成模型原始 evidence。

## 10. AI Provider / Model 行为

每次新生成 AI Summary 可临时选择 Provider / Model。

这次选择：

- 只影响当前生成
- 不修改全局默认
- 保存真实 Provider/Model lineage

历史版本 UI 必须继续显示该版本实际 Provider + Model。

Interrupted resume 必须继续使用原版本 Provider/Model，不得自动切到当前默认模型。

## 11. Structured Output Reliability

Stage 12B 已增加：

- model-level compatibility probe
- strict JSON Schema 优先
- JSON Object / prompt-compatible fallback
- validation error classification
- bounded targeted repair
- truncation detection
- evidence strict validation

Stage 12C 不应为了文本编辑需求降低这些校验。

Grok strict success path 已有回归测试；其它 Provider 的兼容逻辑继续通过统一适配层处理，不回到每家 Provider 随意特判。

未来 Stage 19A 的本地 Gemma 4 Thinking 不得绕过这些结构化输出/evidence 校验；如 Thinking 与 constrained decoding 在当时 runtime 不兼容，应采用分析阶段 + 严格结构化阶段，而不是降低 schema。

## 12. 全局状态行为

全局 Mini Player：

- 播放完成自动消失
- 暂停保留
- 点击回到真实播放录音

全局任务：

- 在对应录音/对应 Tab 不重复显示
- 离开对应页面才显示 Running
- Completed / Failed 保留为未读通知
- 点击或自行进入对应页面后清除

Stage 12C 新增长任务若需要全局状态，应复用这套 App-level status pattern，不建立独立重复任务系统。

## 13. UI 边界

Stage 12A 已冻结总体产品 UI/UX。

Stage 12C：

- 可增加文本编辑、分享、导出、搜索所需页面
- 继续使用当前高频效率工具风格
- 不进行无功能必要的全局 redesign
- 最终全部主要核心功能完成后，在 **Stage 19D — 全 App UI / UX 最终精修** 统一进行视觉与交互收口

Stage 19D 仍必须继承 Stage 12A/12B 已确认的方向：**简洁、便捷、交互自然、美观、高频效率工具**；Stage 19D 主要做一致性和体验精修，不重新引入大规模核心功能。

## 14. Stage 12C 开发起点

Stage 12C 开始前：

1. 重新读取 GitHub 当前真实状态。
2. 确认 Stage 12B 已合并 main。
3. 重新核对 Room v5。
4. 设计 Transcription edit/revision schema。
5. 设计 AI Summary edit/revision schema。
6. 设计统一全文索引及中文查询策略。
7. 设计版本级删除与导出/分享 use case。
8. 输出 Stage 12C 修订需求。
9. 等用户确认。
10. 输出 Stage 12C 开发规划。
11. 再次确认后才编码。

不得因为 Stage 12C 文本管理而修改 Stage 8 本地转写 pipeline 本身。
