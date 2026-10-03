# Voica Stage 12A Handoff

状态：**Stage 12A 已完成 / 已真机验收 / 已冻结**

下一子阶段：**Stage 12B — 本地录音库增强**

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

接管 Stage 12B 前必须重新核对：

- 当前 branch / main HEAD
- open PR
- GitHub Actions
- `AGENTS.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_12A_TEST.md`
- `docs/STAGE_12A_FREEZE.md`
- 本文件
- Room v4 schema 及 Recording/AudioAsset/AudioDerivation DAO/Repository

聊天记录只能作为线索。

## 2. Stage 12A 验收基线

实现 QA HEAD：

`ad192aa74daf6383ac8205c33b9710eca6a336ba`

真机验收记录提交：

`ea8c1b8644a1d16ebd088ccedbc75a80925789ff`

最终 QA：

- versionCode 36
- `0.12.0-stage12a-uxv3-qa1`
- QA package `io.github.ioannes78.voica.qa`
- Room 4
- arm64-v8a
- CI `37096676732` success
- APK SHA-256 `9a0e3c995f1292dbc9f0e32fabccea730f1dff693f70a147ed4cf2b357fd3cb0`

用户明确：

**“测试通过，UI/UX 先这样，后续全部功能完成后再精细优化。”**

## 3. Stage 12A 产品结构

一级导航：

- 设备
- 录音库
- 设置

二级页隐藏一级 Bottom Navigation。

设备页：

- 统一设备状态卡
- Recorder-compatible BLE 优先
- 录音主控制
- 设备录音二级页
- 完整 BLE Diagnostics 二级页

Recording Detail：

- 播放
- 转写
- AI 总结

固定 header + fixed tabs，内容区独立滚动。

设置：

- 目录式首页
- AI Provider / 模型 / 主题 / 高级设置进入二级页

## 4. 设备录音与 destructive operation

设备录音列表已有多选与批量删除 UI。

批量删除不是新的协议：

仍复用 Stage 5 已冻结的单录音 remote delete，并严格顺序执行：

`delete → refresh → verify → next`

不得并发删除。

OutcomeUnknown 必须停止剩余批处理。

设备端删除不得联动删除手机本地 Recording。

Stage 12B 的“本地录音库批量删除”是另一条生命周期，不得复用 remote BLE delete 语义。

## 5. 播放 / 转写 / Summary

完整播放器：

- seek slider
- -10/+10s
- six-step speed slider

Mini Player：

- Transcript / Summary 可见
- 使用同一 PlaybackViewModel
- 继续以 Stage 10 absolute 16k canonical sample index 为时间事实

Transcript segment / Summary evidence 的 seek-to-play 契约不可改变。

播放高频 snapshot 状态应隔离，避免长转写整页高频 recomposition。

## 6. Room 事实

Stage 12A 没有改 Room：

**schema version = 4**

Stage 12B 一旦增加本地管理 metadata，预计进入 v5。

必须：

- additive explicit migration
- 提交 schema JSON
- migration test
- no destructive migration

不要为了 UI 方便修改已有 lineage。

## 7. Stage 12B 数据设计重点

Stage 12B 需要在既有 Recording 上增加产品 metadata，而不是建立第二套录音库。

建议重新核验后设计：

- favorite
- logical folder
- tags / recording-tag relation
- imported-source provenance
- 可恢复的 import state
- 批量操作 use case
- storage statistics

逻辑 folder/tag 不得通过移动 app-private 音频文件实现。

## 8. 本地删除边界

Stage 6 已有 `deleteLocalRecording` 生命周期：

- state → DELETING
- 删除 managed files
- 全部成功后删除 Recording
- reconcile pending delete

Stage 12B 多选删除必须通过 Repository/UseCase 顺序/受控执行。

UI 不得直接跨 DAO 删除 Recording/AudioAsset/Transcription/Summary。

删除 Recording 会影响关联图，确认 UI 应在实现前重新核对 cascade 关系并明确告知用户。

## 9. 手机音频导入

Stage 12A 没有实现导入。

Stage 12B/12E 设计时必须以实际 decoder 能力为准。

当前已知：

- canonical profile：16 kHz mono PCM16 WAV
- 设备 raw framed Opus 有独立转换链
- PCM16 WAV 可 canonicalize
- 不得在 UI 中先宣称 MP3/M4A/AAC/FLAC/Ogg Opus 支持，除非实际 decoder 路径和真机测试已完成

推荐导入流程：

`SAF URI → app-private staging → detect/validate → atomic commit → Recording/AudioAsset → canonical pipeline`

不要长期依赖外部 URI 作为唯一音频事实源。

## 10. 搜索与 Stage 12B/12C 边界

Stage 12B 可先完成 Recording metadata search/filter。

转写和 AI Summary 全文统一索引属于后续 Stage 12C/12D 规划。

中文全文搜索最终不能长期依赖 `LIKE '%keyword%'` 扫描长文本。

统一 FTS 设计时必须验证 Android minSdk 26 的 SQLite/Room 能力以及中文无空格匹配策略。

## 11. UI 精修边界

用户已经明确当前 UX/UI 先冻结。

Stage 12B：

- 复用现有 Voica Mint / typography / spacing / navigation pattern
- 新功能遵循高频主任务、低频 overflow/bottom sheet/二级页原则
- 不重新大规模改主题、Logo、全局布局
- 最终全部功能完成后统一做视觉与交互精修

## 12. Stage 12B 开发起点

Stage 12B 开始前先完成：

1. 重新读取 GitHub 当前真实状态。
2. 确认 Stage 12A Freeze/Handoff/Test。
3. 核对当前 PR 是否继续承载 Stage 12 全部子阶段，或按项目约定拆分。
4. 核对 Room v4 entity / DAO / migration。
5. 输出 Stage 12B 修订需求/开发规划（若用户要求重新确认）。
6. 只有确认后再进入数据库和功能实现。

Stage 12A 的 UI 代码不应因为 Stage 12B 功能加入而无关重构。
