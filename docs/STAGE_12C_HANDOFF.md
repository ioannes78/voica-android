# Voica Stage 12C Handoff

状态：**Stage 12C 已完成 / 已真机验收 / 已冻结**

下一阶段：**Stage 13 — 稳定性与长录音专项**

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

开始 Stage 13 前必须重新核对：

- main HEAD
- open / merged PR
- GitHub Actions
- `AGENTS.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_12C_TEST.md`
- `docs/STAGE_12C_FREEZE.md`
- 本文件
- Room v6 schema
- 当前 APK/version/ABI
- Recording / playback / transfer / transcription / diarization / AI Summary task lifecycle

聊天记录只能作为线索。

## 2. Stage 12C 最终候选基线

功能实现基线：

`3f7a6a372e561c14090e5c51868a4c11600a01e6`

QA / 文档候选 HEAD：

`d1244f1b00747d6b03d1099d41666e7f6adf0f42`

Freeze 内容提交：

`0c052ead13bf83826707c1e390ca4d3b0345b280`

最终 QA：

- versionCode 40
- `0.12.4-stage12c-qa1`
- QA package `io.github.ioannes78.voica.qa`
- Room 6
- arm64-v8a
- sherpa-onnx 1.13.8
- CI #592 / run `37167326317` success
- CI #593 / run `37169848489` success
- Artifact ID `11291100984`
- Artifact digest `sha256:3d10191d1456e9f639def7218d47c42813e354f024a6a28db3e4d9e09deba393`
- APK SHA-256 `89a9df3a610019f8fe9d750966ea4497b09ab71a19f60816b8144866d6942d49`

用户最终明确：

**“测试通过”**

## 3. 三层数据边界

Stage 13 必须保持：

1. 模型原始事实：ASR / token / speaker alignment / AI Summary / Evidence。
2. 用户修订事实：TranscriptionRevision / AiSummaryRevision / 当前版本选择。
3. 可重建派生数据：SearchDocument / FTS / SearchIndexState。

不得用搜索索引反向修改原始内容。

## 4. 时间轴边界

唯一媒体时间事实仍是：

**absolute canonical 16 kHz PCM sample index**

Stage 13 的长录音/压力测试必须围绕该 sample truth 验证：

- playback
- seek
- transcription
- diarization
- timeline
- search navigation

不得引入第二套 wall-clock 时间轴。

## 5. 转写 / Revision 边界

Transcription version = 模型运行。

Revision = 用户人工整理历史。

Stage 13 稳定性测试不得：

- 覆盖原始 Segment/Token
- 为人工文本伪造 token timestamp
- 把 Revision 当成新的模型版本
- 破坏版本删除依赖保护

长录音编辑应继续使用 LazyColumn/分段编辑，不建立单个超大 TextField。

## 6. AI Summary 边界

AI Summary version = 一次实际 LLM run。

User Revision = 对该版本的人工修改。

Stage 13 不得降低 Stage 11/12 的：

- structured-output validation
- Evidence validation
- Provider/Model lineage
- interrupted resume 原模型选择
- USER_EDITED / USER_ADDED 与 Evidence 区分

## 7. 搜索边界

SearchDocument / FTS 是可删可重建派生数据。

Stage 13 应重点压力测试：

- 500 / 1000 Recording
- 30min / 60min / 120min 转写
- 大量 Summary item
- 索引首次 rebuild
- 索引中途进程退出
- 低存储
- 大批量删除后的索引清理

中文继续使用现有安全 tokenization + FTS4，不在 Stage 13 切换 SQLite runtime，除非真机证据证明必须。

## 8. Stage 13 后台下载目标

Stage 13 正式负责：

- Android Foreground Service 承载设备文件传输
- App 后台继续下载
- 熄屏/锁屏继续下载
- 常驻通知显示文件名、OPUS/WAV、真实百分比
- 通知中取消
- BLE 后台连接与 Android 系统限制适配
- 断连恢复/失败策略
- App 进程被系统回收后的状态恢复

录音控制优先级仍必须高于文件下载。

## 9. Stage 13 长时专项

必须真实覆盖：

- 30 分钟
- 1 小时
- 2 小时

至少记录：

- BLE 稳定性
- download throughput / retry
- playback seek
- transcription RTF
- diarization RTF
- RAM/PSS
- CPU
- thermal
- storage growth
- cancellation
- task interruption/recovery

过去 virtual 30/60/120min 测试不能替代真实长时真机证据。

## 10. 开发门禁

开始 Stage 13 时：

1. 重新读取 GitHub main。
2. 确认 Stage 12C 已合并。
3. 重新核对 Room v6 和 migration lineage。
4. 输出 Stage 13 修订需求。
5. 等用户确认。
6. 输出 Stage 13 修订开发规划。
7. 再次确认后才创建开发分支和编码。

不得因为稳定性专项顺便重写 Stage 12C 已冻结内容管理或搜索架构。
