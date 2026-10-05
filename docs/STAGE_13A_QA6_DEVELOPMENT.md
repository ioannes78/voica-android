# Voica Stage 13A QA6 Development

状态：**ACCEPTED / CLOSED**

日期：2026-10-05

## 1. 治理状态

- Stage 13A：**FINAL ACCEPTED**
- QA5：**ACCEPTED**
- QA6：**ACCEPTED**
- Stage 13A Final Freeze：**DONE**
- Stage 13A Final Handoff：**DONE**
- Stage 13B：**UNBLOCKED after PR #16 merge + main verification**

本文件最初用于重新打开 Stage 13A，完成 QA6「录音详情 / 转写 / AI 总结内容生命周期产品化收口」。

用户于 2026-10-05 对 QA6 v48 明确确认：

**“测试通过”**

因此 QA6 development 状态正式关闭，后续 Stage 13A 权威状态以 `docs/STAGE_13A_FREEZE.md` 与 `docs/STAGE_13A_HANDOFF.md` 为准。

## 2. 最终 QA6 基线

开发分支：`stage13a-qa6-productization`

原始起点：

`7b624bc0ca5c2723655798314dacc7bb77960a7e`

最终功能 / 真机 QA 基线：

`e008e90939b13cf273b14a6e323fa1307b960b24`

最终候选：

- versionCode：48
- versionName：`0.13.0-stage13a-qa6`
- Room schema：7
- sherpa-onnx：1.13.8
- Android PR CI：#712 / run `37290584023` — success
- Artifact ID：`11336208987`
- Artifact digest：`sha256:a279eda54678717ef78dbb31262dc42cd7f9b1f692dc39619c3c611914ab01d0`
- Accepted APK SHA-256：`87e5920fea6eb48f31aa4d4170d0e4735e926bc92363ab04aa361cf79ca2799f`
- production model-channel：QA6 全程只读，未执行 promotion

## 3. QA6 最终产品契约

1. Recording 只有一个 Current Effective Transcription 与一个 Current Effective Summary。
2. 第一个 completed 可自动成为 current；已有 current 后，新 completed 只能成为“新结果”，不得自动抢占 current。
3. 用户明确“使用新结果”后才切换 current。
4. 人工编辑保存为 Revision；原始模型 / AI 结果保持不可变事实。
5. AI Summary 输入冻结任务开始时的 Effective Transcription，包括当前人工 Revision。
6. Summary stale 同时比较 transcriptionId 与 transcriptionRevisionId。
7. 无 audio anchor 的人工文本允许进入总结，但不得伪造 evidence / timestamp。
8. 普通搜索只暴露 Recording + Current Effective Transcription + Current Effective Summary。
9. 录音库搜索已接通 Current Effective 转写 / 总结 FTS；candidate / 历史内容不得命中。
10. Room 保持 v7。

## 4. UI / UX 最终状态

录音详情一级 Tab：`录音 / 转写 / 总结`。

录音页完成：真实全宽 waveform、圆形 seek point、±10s 图标、大圆形播放 / 暂停、固定五档速度、图标化录音信息卡、重命名 / 分享 / 导出 / 删除操作。

转写页默认连续全文阅读，正文为连续可选择区域，允许跨段选择复制；时间轴继续用于原音校对。

总结页显示当前有效 Summary；stale 时提示基于较早转写并提供重新生成；新结果必须明确采用。

Mini Player 只在 PLAYING / PAUSED 显示。

## 5. 最终门禁状态

QA6 已完成代码、测试、Build、PR CI、QA APK 与用户真机验收。

允许执行：

1. Final Freeze / Handoff；
2. merge PR #16；
3. 核验新的 main HEAD 与 post-merge CI；
4. 完成后解锁 Stage 13B。
