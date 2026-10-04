# Voica Stage 12C Freeze

状态：**FROZEN / 用户真机验收通过**

日期：2026-10-04

## 1. Freeze 结论

Stage 12C — 转写 / AI 总结内容管理 + 统一全文搜索完成并冻结。

用户最终明确确认：

**“测试通过”**

冻结前功能实现基线：

`3f7a6a372e561c14090e5c51868a4c11600a01e6`

QA / 文档候选 HEAD：

`d1244f1b00747d6b03d1099d41666e7f6adf0f42`

最终 QA：

- versionCode：40
- versionName：`0.12.4-stage12c-qa1`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：6
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- 自动化 QA：run `37167326317` / #592 — success
- 最终 QA：run `37169848489` / #593 — success
- Artifact：`Voica-qa-apk` / ID `11291100984`
- Artifact digest：`sha256:3d10191d1456e9f639def7218d47c42813e354f024a6a28db3e4d9e09deba393`
- APK SHA-256：`89a9df3a610019f8fe9d750966ea4497b09ab71a19f60816b8144866d6942d49`

## 2. Room v6 冻结

Stage 12C 从 Room v5 显式 additive migration 到 v6。

新增核心数据：

- transcription_user_metadata
- transcription_revisions
- transcription_revision_paragraphs
- ai_summary_user_metadata
- ai_summary_revisions
- recording_content_selection
- search_documents
- search_documents_fts
- search_index_state

冻结约束：

- 不允许 destructive migration。
- 原 Recording / Audio / Transcription / Segment / Token / Diarization / Alignment / AiSummary / Evidence / Chunk / Folder / Tag 图必须完整保留。
- v5→v6 migration 只建立 schema 与 REBUILD_REQUIRED；中文分词和全文索引由 App 启动后重建。
- CI 固定校验 Room schema 1..6。

## 3. 转写人工修订冻结

模型原始 ASR 事实不可变：

- Transcription version
- Segment
- Token
- model lineage
- speaker/alignment lineage
- absolute canonical sample index

人工编辑保存为独立 TranscriptionRevision 全量快照。

支持：

- 正文修改
- 段落合并
- 段落拆分
- 规则化“整理段落”
- 修订历史
- 删除修订
- 恢复模型原文

自动整理不得跨明确不同 speaker 合并。

Timing quality：

- EXACT：仍可精确映射原 token/sample。
- ANCHORED：人工修改后只保证来源段/音频范围。
- APPROXIMATE：拆分/重排无法可靠得到新边界时明确降级。

不得为人工文本伪造 token timestamp。

## 4. 阅读 / 时间轴双视图冻结

阅读模式：

- 当前有效人工稿/模型原文的文档式排版
- 无时间戳
- 无逐词播放高亮
- 可显示 speaker
- 适合复制/分享/导出

时间轴模式：

- 继续复用 Stage 10 原始 Segment/Token/SpeakerSpan
- 唯一媒体时间仍是 canonical 16 kHz absolute sample index
- 播放同步、当前句/当前词高亮不受人工修订重写

## 5. Transcription version 管理冻结

- 每次模型运行仍是独立 Transcription version。
- 人工 Revision 与 Transcription version 是两个概念。
- 支持版本命名、切换、删除。
- 当前 completed Transcription version 持久化到 RecordingContentSelection。
- 重新进入/重启优先恢复用户选择。
- 删除当前版本后安全回退到仍存在 completed version。
- 若 AiSummary 仍引用 Transcription，删除必须阻止；不得利用 Room cascade 静默删除 Summary。

## 6. AI Summary Revision 冻结

原始 AiSummary 保留：

- structuredPayloadJson / displayText
- Provider/Profile/Base URL/Model snapshot
- Template/mode
- input transcription lineage
- Evidence
- Chunk/checkpoint

人工编辑保存为独立 AiSummaryRevision。

支持标题、overview、section、item 修改/增删/排序与修订历史。

Provenance：

- AI_ORIGINAL
- USER_EDITED
- USER_ADDED

USER_EDITED 可保留原 item/evidence 作为来源快照，但 UI 不得把原 Evidence 宣称为修改后文字的模型证据。

USER_ADDED 不得写入模型 ai_summary_evidence。

## 7. AI Summary version 管理冻结

- 每个真实 LLM run 仍是独立 AiSummary version。
- 当前 completed Summary version 持久化。
- Interrupted 版本可用于恢复任务，但不得写成当前 completed 选择。
- 删除 Summary version 只删除该 Summary / Evidence / Chunk / Revision / 派生搜索数据。
- 不得删除 Recording 或 Transcription。
- 删除当前版本后安全回退。

## 8. 文本复制 / 分享 / 导出冻结

转写与 AI 总结均支持：

- Android 文本选择复制
- 整份复制
- Android Sharesheet
- TXT
- Markdown

长文本分享使用受控 FileProvider 临时文件。

Android 10+ 写入 `Downloads/Voica`；API 26–28 使用系统 SAF 另存为。

导出只使用当前有效内容，不覆盖或修改模型原始数据。

## 9. 统一全文搜索冻结

独立于 Stage 12B Recording metadata 筛选。

覆盖：

- Recording
- Folder
- Tag
- 当前有效 Transcription 文本
- 当前有效 AI Summary 标题 / overview / item

架构：

`authoritative content → effective projection → SearchIndexRebuilder → SearchDocument → Room FTS4 → UnifiedSearchRepository`

规则：

- SearchDocument/FTS 是可重建派生数据。
- 旧 Revision 保留历史但不重复进入“当前内容”索引。
- 首次 v6 打开按 REBUILD_REQUIRED 重建。
- 录音改名、Folder/Tag 变更、转写/总结完成、Revision 保存/切换/删除、版本删除后增量刷新。
- 中文通过应用侧 CJK normalization/tokenization 生成安全 MATCH。
- 多条件使用 Android FTS4 基础隐式 AND。
- 引号、星号、AND/OR、括号等用户输入不得作为原始 FTS 语法执行。
- 转写命中可打开对应 Recording + Transcription version 并定位段落。
- Summary 命中打开对应 Summary version。
- Folder/Tag 命中回录音库筛选。

## 10. Stage 12C 明确不包含

- PDF / DOCX 作为验收要求
- Summary 专属第二套收藏/标签
- semantic/vector search
- 云同步/账号/团队
- Audio LLM
- 云端/实时 ASR
- MediaSession / Foreground Service / 后台 BLE 下载
- speaker voiceprint
- 对原始 Stage 8/10 token/sample timeline 的人工重写

## 11. 真机证据

见：

- `docs/STAGE_12C_TEST.md`

用户已明确确认 Stage 12C QA1：

**“测试通过”**

## 12. 下一阶段

**Stage 13 — 稳定性与长录音专项**

重点包括：

- BLE soak
- 多文件 / 大文件 / 断连下载
- 30min / 1h / 2h 真实录音
- 长转写 / diarization / AI Summary 资源压力
- RAM / CPU / thermal
- 低存储
- Foreground Service
- 后台 / 熄屏 / 锁屏可靠下载
- 进程回收与任务状态恢复
