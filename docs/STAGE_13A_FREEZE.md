# Voica Stage 13A Final Freeze

状态：**FINAL / ACCEPTED / FROZEN**

日期：2026-10-05

## 1. 最终结论

Stage 13A — 本地语音引擎增强与产品化收口，已完成最终真机验收并冻结。

用户最终明确确认：

**“测试通过”**

最终功能 / 真机 QA 基线：

`e008e90939b13cf273b14a6e323fa1307b960b24`

最终候选：

- versionCode：48
- versionName：`0.13.0-stage13a-qa6`
- QA package：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- Room schema：7
- sherpa-onnx：1.13.8
- Android PR CI：#712 / run `37290584023` — success
- Artifact：`Voica-qa-apk`
- Artifact ID：`11336208987`
- Artifact digest：`sha256:a279eda54678717ef78dbb31262dc42cd7f9b1f692dc39619c3c611914ab01d0`
- 用户验收 APK SHA-256：`87e5920fea6eb48f31aa4d4170d0e4735e926bc92363ab04aa361cf79ca2799f`

QA5 后的早期 Freeze 曾因 QA6 reopening 被判定为 premature closure；本文件现已更新为真正的 Stage 13A Final Freeze。

## 2. 冻结模型矩阵

### 录音文件离线转写

- SenseVoice INT8：快速 / 默认
- Qwen3-ASR 0.6B INT8：高质量
- FireRedASR2：移出产品矩阵

产品层只保留一个离线转写入口。Qwen 最终正文为权威文本；Small Bilingual 仅允许作为可选时间轴辅助，缺失或失败不得使 Qwen 正文整体失败。

### 未来实时转写

- Small Bilingual Zipformer INT8：轻量 / 默认
- Chinese Large CTC INT8：高质量中文实时选项
- Chinese Large Transducer：移出产品矩阵
- 不提供 AUTO 产品路由

实时 contract 继续以 `docs/STAGE_16_STREAMING_CONTRACT_V2.md` 为准。

### 说话人分离

冻结链：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering / stitching`

CAM++ 为唯一产品 speaker embedding 模型；ERes2Net 不进入产品选择。

### 辅助模型

- Silero VAD
- CT-Transformer zh-en punctuation

## 3. QA6 内容生命周期冻结

每条 Recording 只存在一个 Current Effective Transcription 与一个 Current Effective Summary。

- 第一个 completed 可自动成为 current。
- 已存在 current 后，新 completed 结果只能成为 candidate / “新结果”，不得自动抢占 current。
- 用户必须明确执行“使用新结果”才能切换 current。
- 人工编辑作为 Revision 保存，不覆盖原始模型事实。
- AI Summary 输入必须冻结当前 Effective Transcription，包括当前人工 Revision。
- Summary lineage 记录 transcriptionId + transcriptionRevisionId，并据此判断 stale。
- 无 audio anchor 的人工文本可以进入总结输入，但不得制造虚假时间戳或音频证据。
- 普通搜索只索引 Recording + Current Effective Transcription + Current Effective Summary；candidate / 历史底层结果不进入普通搜索。

## 4. QA6 产品 UI / UX 冻结

录音详情一级 Tab：`录音 / 转写 / 总结`。

### 录音

- 全宽真实波形 overview
- 已播放 / 未播放波形区分
- 圆形进度点，可 seek
- ±10s 图标控制
- 大圆形播放 / 暂停主按钮
- 固定速度：`0.5× / 0.75× / 1.0× / 1.5× / 2.0×`，1.0 居中
- 录音信息卡：录制时间、文件大小、来源 / 格式，图标与文字列对齐
- 重命名 / 分享 / 导出 / 删除使用图标化操作按钮；删除使用错误色强调

### 转写

- 默认连续全文阅读
- 正文作为一个连续可选择区域，允许跨段选择复制
- 时间轴保留用于听原音与校对
- 普通 UI 不再向用户暴露 Version / Revision 等工程概念

### 总结

- 只显示当前有效 Summary
- 当当前总结基于较早转写 lineage 时显示 stale 提示，并提供重新生成入口
- 新完成结果作为“新结果”，只有明确“使用新结果”后才成为 current

### Mini Player

仅在 PLAYING / PAUSED 时显示；COMPLETED / READY / ERROR / 无 active recording 时不得残留。

## 5. 搜索冻结

录音库搜索同时覆盖：

- 录音名称 / 原始文件名
- 文件夹 / 标签
- 导入来源元数据
- Current Effective Transcription
- Current Effective Summary

中文正文检索统一使用现有 CJK FTS 规则。QA6 最终真机已确认转写正文关键词可从录音库命中对应录音。

## 6. AI Summary cancellation 冻结

QA5 Fix 1 的取消语义继续属于 Stage 13A 冻结行为：

- 取消 coroutine 的同时主动断开当前 LLM HTTP request
- `CancellationException` 不映射为普通 NETWORK failure
- CANCELLED 可靠落库
- late progress / late provider response 不得复活任务
- CANCELLED / FAILED 不计正式 Summary 版本

## 7. Room / 数据边界

Room schema 最终冻结为 v7。

继续保护：

- canonical 16 kHz mono PCM16 absolute sample index 是唯一媒体时间真值
- 原始 Transcription / Segment / Token / Speaker alignment 不被人工 Revision 覆盖
- SearchDocument / FTS 是可重建派生数据
- Current selection 与 Revision lineage 必须保持一致
- 不允许 destructive migration

## 8. Model channel

本次 Final Freeze **不提升 production model-channel**。

production manifest promotion 是独立受控操作，不得因 Stage 13A merge 自动执行。

## 9. 真机验收

用户于 2026-10-05 对 QA6 v48 明确确认：

**“测试通过”**

最终真机验收包含 QA6 内容生命周期、录音库搜索修复、播放器 / 波形 / 录音信息 UI 收口及相关回归。CI success 仅作为自动化证据，不替代本次真机验收。

## 10. 下一阶段

Stage 13A Final Freeze 完成后，下一阶段解锁为：

**Stage 13B — 稳定性、后台与真实长录音专项**

Stage 13B 开始前仍必须以合并后的 GitHub `main` 当前真实状态为唯一事实来源重新核验。