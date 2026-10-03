# Voica Stage 12B Freeze

状态：**FROZEN / 用户真机验收通过**

日期：2026-10-04

## 1. Freeze 结论

Stage 12B — 本地录音库增强完成并冻结。

用户最终明确确认：

**“测试通过”**

实现 QA HEAD：

`d161cf0404bcc8fd8c3a0a9817377789a10c5598`

真机验收记录提交：

`35501477be7366007d23514335ec47a0bc885fce`

最终 QA：

- versionCode：39
- versionName：`0.12.3-stage12b-qa3`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：5
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- 最终实现 CI：run `37142129654` / #570 — success
- Artifact：`Voica-qa-apk` / ID `11280668651`
- Artifact digest：`sha256:6a8bbdfe1d50c96fddd2e6bcbeccb6ee650db07b036e9c7d25d8d70bd343140b`
- APK SHA-256：`a6ec8350683f055ecf8e87ff61bd8e67d9ae75ea85c7dddc0d53e8922350e2fd`

## 2. Room v5 冻结

Stage 12B 从 Room v4 additive migration 到 v5。

新增本地录音管理数据包括：

- Recording import provenance
- Recording user metadata
- logical folder
- tag
- recording-tag cross reference
- LOCAL_IMPORT source lineage
- IMPORTED_ORIGINAL asset role
- mediaDurationMs 与 deviceReportedDurationMs 分离

约束：

- migration 必须显式、不可 destructive
- 既有 Recording / AudioAsset / AudioDerivation / Transcription / Diarization / AI Summary 图保持不丢失
- folder 删除仅 SET_NULL，不删除 Recording
- tag 删除仅删除 relation，不删除 Recording
- imported source URI 不作为长期唯一媒体源

提交 Room schema 1..5，CI schema gate 通过。

## 3. 本地录音库查询与组织冻结

Recording Library 使用数据库 projection 处理：

- 录音名称/来源/文件夹/标签/导入元数据搜索
- 日期搜索
- 录制 / 下载 / 加入录音库 / 最近修改 / 名称 / 大小 / 收藏优先排序
- 收藏 / 文件夹 / 标签 / 是否已转写 / 是否已有总结 / 来源 / 日期筛选
- 多标签筛选使用 AND 语义
- 物理大小按 relativePath 去重
- 250–300ms 搜索 debounce
- Compose recomposition 不做磁盘/Room IO
- 500 / 1000 Recording 规模回归

逻辑组织不得通过移动 app-private 音频文件实现。

## 4. 手机音频导入冻结

导入链：

`SAF → staging → size/SHA/free-space/cancel → container/codec detect → decode/validate → atomic source commit → Recording + IMPORTED_ORIGINAL → canonical`

当前冻结支持：

- PCM WAV
- MP3
- M4A / AAC
- AAC / ADTS
- FLAC
- Ogg Opus
- QS668 raw framed Opus

通用容器/编码使用 Android MediaExtractor / MediaCodec；QS668 raw framed Opus 继续走现有专用验证/解码链。

重复判定使用 exact SHA-256：

- 默认不重复导入
- 用户明确选择后可保留重复副本
- 不自动合并 Recording

非法输入不得留下半完成 Recording。

## 5. Canonical / 原始资产冻结

用户原始文件继续作为可追溯资产保存。

Canonical WAV 是可再生成的标准处理音频，不等同于原始录音。

清理 canonical 必须满足：

- Recording ACTIVE
- canonical VERIFIED / VALID
- source VERIFIED / VALID
- source lineage path / size / SHA 一致
- source 实际存在
- 非共享同一路径
- 当前没有不可安全释放的依赖

数据库先安全 detach；物理删除失败必须补偿恢复数据库资产/derivation。

不得删除唯一原始音频。

## 6. 本地删除生命周期冻结

所有本地删除必须通过 LocalRecordingDeleteCoordinator / repository lifecycle：

`ACTIVE → DELETING → cancel/wait consumers → files → Recording cascade`

需处理：

- playback
- canonical generation
- transcription
- diarization/alignment
- AI Summary

批量删除顺序执行。

删除手机本地 Recording 不得发送设备端 BLE delete。

启动时继续 reconcile 可恢复 DELETING。

## 7. 导出与分享冻结

默认导出 canonical WAV。

Android 10+：

`MediaStore.Downloads → Downloads/Voica/`

API 26–28：

SAF document/tree fallback。

支持：

- 单个 WAV 导出
- 原始文件导出
- 批量导出
- 同名 `(2)` 等冲突名
- 单项失败继续并汇报
- 当前未完成项取消，不撤销已完成项

分享：

- FileProvider + ACTION_SEND
- temporary read grant
- 默认 canonical `audio/wav`
- 标准原始文件使用真实 MIME
- QS668 raw Opus 使用保守 MIME，不伪装 Ogg Opus

FileProvider 不暴露整个 recordings 根目录。

## 8. 存储管理冻结

设置中提供存储统计：

- 原始音频
- 标准化音频
- 下载模型
- 数据库 + WAL/SHM + 文本数据
- staging / .part / cache / temp

安全清理：

- 临时文件可直接清理
- canonical 清理需要明确确认并满足可再生成条件
- 模型清理由 ModelManager / ModelStorage 执行
- active / previous / in-use 模型受保护
- 原始录音、转写、总结等用户内容不得静默删除

## 9. Recording Detail / 全局播放冻结

详情页任务状态必须按 recordingId 作用域显示。

不得把 A 的转写/说话人状态显示到 B/C 录音详情。

进入已有转写的录音：

- 默认加载最后一次已完成 Transcription version
- 用户仍可切换历史版本

全局 Mini Player：

- 离开详情仍明确显示真实播放录音
- PREPARING / READY / PLAYING / PAUSED / SEEKING / ERROR 可显示
- COMPLETED / IDLE / RELEASED 隐藏
- 播放结束后自动消失
- 暂停时保留

## 10. 全局任务通知冻结

转写 / 说话人分离 / AI Summary 使用现有 Coordinator 作为唯一任务事实，不创建第二套任务系统。

规则：

- 当前就在对应 Recording + 对应 Tab 时，不重复显示全局任务条
- 离开对应页面后显示全局 Running 状态
- Completed / Failed 转为未读任务通知
- 点击任务通知进入对应 Recording/Tab 后标记已读
- 用户自行进入对应 Recording/Tab 也标记已读
- 完成时本来就在对应页面则直接视为已读

不在 Stage 12B 引入系统级后台通知 / Foreground Service。

## 11. AI Summary Reliability V2 冻结

结构化输出能力按 Provider / Model 使用最强可用模式：

- STRICT_JSON_SCHEMA
- JSON_OBJECT
- PROMPT_ONLY

连接测试除基础连通性外增加 synthetic structured-summary compatibility probe，不上传真实录音或真实转写。

AI Summary 保持严格本地 validation：

- JSON
- schemaVersion / contentType
- section/item structure
- enum
- evidenceRefs
- epistemicStatus
- duplicate id
- empty output

对失败分类并执行有界定向 repair。

输出达到 token limit 时识别 truncation，并允许更大预算/合适 fallback，而不是统一误报普通 schema 错误。

Grok 已验证 strict path 保持不变；成功结果第一次校验通过即直接入库，不触发额外 repair。

不得为了提高成功率伪造 evidence 或降低 evidence validation。

## 12. AI Summary Provider / Model lineage 冻结

每个 Summary version 必须显示并保存实际生成时：

- providerProfileId
- providerNameSnapshot
- baseUrlSnapshot
- model
- template/mode
- input transcription lineage

历史总结标题下和版本列表显示该版本真实 Provider + Model，不读取当前默认 Provider 冒充历史 lineage。

每次生成 AI Summary 可临时选择：

- Provider Profile
- Model

本次选择不修改全局默认。

Interrupted resume / 原模板重新生成继续使用原 Summary 保存的 Provider / Model lineage。

## 13. Stage 12A 回归边界

Stage 12B 不改变已冻结：

- QS668 / CB08 BLE protocol
- remote file delete semantics
- device OPUS/WAV download
- canonical 16k mono PCM16 profile
- Stage 7 absolute canonical sample timeline
- Stage 8 FAST/HQ local transcription pipeline
- Stage 9 speaker run identity
- Stage 10 transcript/playback mapping
- Stage 11 evidence lineage
- Stage 12A overall visual system

用户已确认 Stage 12B QA3 真机功能测试通过。

## 14. 真机证据

见：

- `docs/STAGE_12B_TEST.md`

## 15. 下一阶段

下一子阶段：

**Stage 12C — 转写 / AI 总结内容管理 + 统一全文搜索**

重点：

- 转写复制/分享/导出
- 转写人工修订且保留原 ASR lineage
- AI Summary 人工编辑且保留原模型结果/evidence lineage
- Summary/Transcript 历史版本管理
- 统一离线全文搜索
- 中文搜索行为与索引生命周期
