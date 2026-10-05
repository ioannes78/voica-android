# Voica Stage 13A Final Handoff

状态：**FINAL / ACCEPTED**

下一阶段：**Stage 13B — UNBLOCKED after PR #16 merge + main verification**

日期：2026-10-05

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

Stage 13B 开始编码前必须重新核对：

- `main` HEAD
- PR #16 最终合并状态
- GitHub Actions
- `docs/STAGE_13A_FREEZE.md`
- `docs/STAGE_13A_HANDOFF.md`
- `docs/STAGE_13A_QA5_FINAL_DECISIONS.md`
- `docs/STAGE_13A_FROZEN_ROADMAP_NOTE.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- Room v7 schema
- production model-channel 当前 HEAD
- 当前 APK / version / ABI
- transcription / diarization / AI Summary lifecycle

聊天记录只能作为线索。

## 2. Stage 13A 最终验收基线

最终功能 / 真机 QA 基线：

`e008e90939b13cf273b14a6e323fa1307b960b24`

最终 QA：

- versionCode 48
- versionName `0.13.0-stage13a-qa6`
- QA package `io.github.ioannes78.voica.qa`
- Room v7
- arm64-v8a
- sherpa-onnx 1.13.8
- CI #712 / run `37290584023` — success
- Artifact ID `11336208987`
- Artifact digest `sha256:a279eda54678717ef78dbb31262dc42cd7f9b1f692dc39619c3c611914ab01d0`
- Accepted APK SHA-256 `87e5920fea6eb48f31aa4d4170d0e4735e926bc92363ab04aa361cf79ca2799f`

用户最终明确：

**“测试通过”**

## 3. 冻结模型矩阵

### 离线录音文件

- SenseVoice INT8：快速 / 默认
- Qwen3-ASR 0.6B INT8：高质量
- FireRedASR2：不进入产品矩阵

### 未来实时

- Small Bilingual Zipformer INT8：轻量 / 默认
- Chinese Large CTC INT8：高质量
- Chinese Large Transducer：不进入产品矩阵
- 不提供 AUTO 产品路由

### 说话人

- Pyannote Segmentation 3.0 INT8 + CAM++
- CAM++ 固定为产品 embedding 模型
- ERes2Net 不进入产品选择

### 其它

- Silero VAD
- CT-Transformer zh-en punctuation

## 4. 内容生命周期约束

Stage 13B 不得破坏 QA6 已冻结的内容生命周期：

- Recording 只有一个 Current Effective Transcription / Summary。
- 新 completed 结果在已有 current 时只能成为 candidate；不得自动覆盖 current。
- 只有用户明确“使用新结果”才切换 current。
- 人工 Revision 与原始模型事实分离保存。
- AI Summary 输入必须来自任务开始时冻结的 Current Effective Transcription。
- Summary stale 判断必须同时比较 transcriptionId 与 transcriptionRevisionId。
- 无音频锚点的人工文本不得生成虚假时间戳 / evidence。
- 普通搜索只能暴露 Current Effective 转写 / 总结；candidate 和历史底层结果不能泄漏到普通搜索。

## 5. Recording UI / Playback 约束

Stage 13A 最终录音详情冻结为产品化播放器：

- 全宽真实波形
- 圆形 seek point
- ±10s 图标控制
- 大圆形播放 / 暂停主按钮
- 固定速度 `0.5× / 0.75× / 1.0× / 1.5× / 2.0×`
- 录音信息图标化对齐
- 重命名 / 分享 / 导出 / 删除图标化操作
- Mini Player 只在 PLAYING / PAUSED 显示

Stage 13B 做后台、长录音、恢复时不得造成播放器生命周期倒退。

## 6. Search 约束

录音库搜索现已同时覆盖基础元数据与 Current Effective 转写 / 总结内容。

Stage 13B 若改动索引重建、进程恢复或长文本路径，必须回归：

- Current 转写可搜
- Current 总结可搜
- candidate / 历史独有文本不可搜
- 修改 current 后旧索引不会残留
- 中文 CJK 查询保持可用

## 7. Recording-file transcription 约束

产品层离线转写保持单入口。

Qwen 正文不可被 Small Bilingual reference text 替换。Small Bilingual 仅做可选时间轴辅助；缺失或失败时 Qwen 正文仍必须完成。

## 8. Stage 16A streaming contract

原 Stage 16 的 true streaming 工作在后续路线中记为 **Stage 16A**，必须复用：

`docs/STAGE_16_STREAMING_CONTRACT_V2.md`

真正实时模型只冻结 Small Bilingual 与 Chinese Large CTC。SenseVoice / Qwen3-ASR 不得通过分块离线识别伪装成 true streaming。

文件型 ASR 新增模型评估另行放入 Stage 16B，不得污染 Stage 16A streaming contract。

## 9. Diarization 约束

冻结链：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering / stitching`

Stage 13B 可以做长录音稳定性与参数验证，但不得无新决策重新做 speaker embedding 大规模选型。

后续 Stage 13C 将在此冻结链上单独处理 diarization **性能过慢**问题；Stage 13C 默认优化 total time / RTF / RAM/PSS / CPU / thermal，不以重新提升准确率或重新选模为首要目标。

## 10. AI Summary cancellation 约束

必须继续保护：

- 用户取消会终止 active LLM request
- CANCELLED 可靠落库
- late response / late progress 不得复活任务
- CANCELLED / FAILED 不计正式 Summary 版本
- completed transaction 一旦提交，completed 为权威终态

## 11. Room / 媒体时间边界

Room v7 为 Stage 13A 最终冻结 schema。

不得破坏：

- 原始 Transcription / Segment / Token 不可变事实
- User Revision 独立保存
- SearchDocument / FTS 仅是可重建派生数据
- AI Summary 原始 structured result / evidence 与人工 revision 分离
- Current selection 持久化
- canonical 16 kHz absolute sample index 为唯一媒体时间真值

## 12. Model channel

Stage 13A Final Freeze / merge 不执行 production model-channel promotion。

任何 production manifest 提升必须作为独立受控操作重新核对并取得明确授权。

## 13. Stage 13B 正式目标

Stage 13B 解锁后负责：

- 真实 30 分钟 / 1 小时 / 2 小时录音专项
- ASR / diarization RTF、RAM/PSS、CPU、thermal、storage、battery（可测时）
- BLE 长时连接与 soak
- Foreground Service
- App 后台 / 锁屏文件下载
- 系统通知真实进度与取消
- 断连 / 失败 / 低存储恢复
- App 进程回收后的任务状态恢复
- playback / seek / timeline / revision / search / AI Summary 长文本回归

过去 virtual 30/60/120min 自动化不能替代真实长时真机证据。

## 14. Stage 13B 开发门禁

Stage 13A QA6 已取得用户明确“测试通过”，Final Freeze/Handoff 可提交并合并 PR #16。

PR #16 合并后还必须：

1. 核验新的 `main` HEAD；
2. 核验 post-merge CI；
3. 确认 Room 仍为 v7、model channel 未被提升；
4. 然后才开始 Stage 13B 编码。

## 15. Stage 13B 之后的新增路线门禁

Stage 13B 当前范围保持不变。本次规划只改变 Stage 13B 完成后的后续顺序：

`Stage 13B → Stage 13C → Stage 14`

Stage 13C：**说话人分离性能专项**。

Stage 13C 必须在 Stage 13B Final Freeze/Handoff 后开始，详细范围统一读取：

`docs/ROADMAP_STAGE_13C_PLUS.md`

核心约束：

- 先分阶段 profiling，再优化；
- 优先做 VAD 复用、embedding cache、CAM++ bounded batching、overlap 去重、短 segment 策略、线程 benchmark、1-speaker fast path；
- ASR 正文完成后应立即可用，diarization 后台继续，不让 speaker 后处理阻塞已完成正文；
- 若更换核心 diarization model/runtime/segmentation/embedding 路径，必须重新执行受影响的 Stage 13B 30/60/120min 稳定性验证；
- Stage 13C 真机验收并 Freeze/Handoff 后，才允许 Stage 14 V1.0 Release Freeze。

从 Stage 13C 起，如旧 `docs/ROADMAP.md` 与 `docs/ROADMAP_STAGE_13C_PLUS.md` 的后续编号冲突，以后者及后续实际 Freeze/Handoff 为准。
