# Voica Stage 13A QA6 Development

状态：**IN DEVELOPMENT**

日期：2026-10-05

## 1. 治理状态

- Stage 13A：**IN PROGRESS**
- QA5：**ACCEPTED**
- QA6：**IN DEVELOPMENT**
- Stage 13A Final Freeze：**NOT DONE**
- Stage 13B：**NOT STARTED / BLOCKED**

本文件重新打开 Stage 13A，用于完成 QA6「录音详情 / 转写 / AI 总结内容生命周期产品化收口」。

此前 `docs/STAGE_13A_FREEZE.md` 与 `docs/STAGE_13A_HANDOFF.md` 是 QA5 后的过早关闭记录，保留作为历史证据，但不再代表当前阶段最终状态。QA6 真机验收通过前不得创建新的最终 Freeze/Handoff，也不得解锁 Stage 13B。

## 2. 开发基线

开发分支：`stage13a-qa6-productization`

起点：

`7b624bc0ca5c2723655798314dacc7bb77960a7e`

核验结果：

- versionCode：46
- versionName：`0.13.0-stage13a-qa5-fix1`
- Room schema：7
- sherpa-onnx：1.13.8
- QA5 CI：#693 / run `37264265999` — success
- PR #15 closing CI：#694 / run `37266118140` — success
- production model-channel：`ioannes78/voica-model-channel@e4e64d29b8c92b97de4298ec6e292c33273f3ba4`
- QA6 对 production model-channel 保持只读

## 3. QA6 产品契约

QA6 不重新做模型选型。核心契约：

1. Recording 只有一个 Current Effective Transcription 与一个 Current Effective Summary。
2. 第一个 completed 结果可自动成为 current；已有 current 后，新 completed 结果只能成为「新结果」，不得自动抢占 current。
3. 人工编辑继续保存为 Revision；恢复模型/AI 原始结果只清除对应 currentRevisionId。
4. AI Summary 输入必须冻结当前 Effective Transcription，包括人工 Revision；任务运行期间不得随 UI 切换而改变。
5. Summary lineage V2 继续复用 `sourceLineageSnapshot`，记录 transcriptionId、transcriptionRevisionId、input digest 与 canonical/alignment lineage。
6. Summary 是否 stale 只比较生成时的 transcriptionId/revisionId 与当前 Effective Transcription。
7. 无 audio anchor 的人工文本可以进入 LLM 输入，但不得制造虚假 Audio Evidence。
8. 普通搜索只索引 Recording + Current Effective Transcription + Current Effective Summary；历史底层结果不进入普通搜索。
9. Room 保持 v7，除非实现证明现有 schema 无法满足一致性；当前未发现需要 v8 的理由。

## 4. UI / UX 收口

录音详情一级 Tab：`录音 / 转写 / 总结`。

录音：带 waveform overview 的播放器、圆形进度点、±10s、固定分段速度 `0.5× / 0.75× / 1.0× / 1.5× / 2.0×`、录音信息与重命名/分享/导出/删除。

转写：默认连续全文阅读；正文为一个连续可选择区域，支持跨段选择复制。时间轴用于听原音 + 校对。普通 UI 不再暴露 Version/Revision/Candidate 等工程概念。

总结：显示当前有效 Summary；旧 lineage 时显示「当前总结基于较早的转写内容」并提供重新生成入口；新完成结果显示为「新结果」，仅用户明确“使用新结果”后切换 current。

Mini Player：PLAYING/PAUSED 显示；STOPPED/COMPLETED 且无 active session 时消失。

## 5. 实现顺序

QA6.0 governance reopen
→ QA6.1 Effective Content lifecycle
→ QA6.2 Transcription candidate/adopt
→ QA6.3 Summary candidate/adopt
→ QA6.4 Effective Revision → AI input
→ QA6.5 Summary lineage + stale
→ QA6.6 Search effective-only
→ QA6.7 Recording Detail + waveform + segmented speed
→ QA6.8 Transcript continuous reading + timeline
→ QA6.9 Summary UI
→ QA6.10 Mini Player / global UI consistency
→ QA6.11 Unit Test / regression
→ candidate → PR CI → QA APK

## 6. 门禁

QA6 完成代码、测试、Build、PR CI 后只提供 QA APK 与真机测试清单，**不得 merge main**。

只有用户明确“测试通过”后才允许：

1. 创建真正的 Stage 13A Final Freeze；
2. 创建真正的 Stage 13A Final Handoff；
3. merge QA6 PR；
4. 核验新的 main HEAD；
5. 解锁 Stage 13B。
