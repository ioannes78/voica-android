# Voica Stage 13A Freeze

状态：**FROZEN / 用户真机验收通过**

日期：2026-10-05

## 1. Freeze 结论

Stage 13A — 本地语音引擎增强与产品化收口完成并冻结。

用户最终明确确认：

**“测试通过”**

功能 / QA 验收基线：

`6543d9f45ed73bb3a815456968518f0d3641b774`

Freeze 内容基线提交：

`230aa3304b76781a34d2ec5bd74ea0c4b7990aab`

最终 QA：

- versionCode：46
- versionName：`0.13.0-stage13a-qa5-fix1`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：7
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Android PR CI：#693 / run `37264265999` — success
- Artifact：`Voica-qa-apk` / ID `11326236293`
- Artifact digest：`sha256:e86a9379fe5f5d08d1978cd9e1e9598898c5ce93609e0304fba00dd2274f8d66`
- 用户验收 APK SHA-256：`cebc706417abf881ecc3360b922e8fe98369320f16d4f0c4bdad5955ec62bc05`

## 2. 冻结模型矩阵

### 录音文件离线转写

- SenseVoice INT8：快速 / 默认
- Qwen3-ASR 0.6B INT8：高质量
- FireRedASR2：移出产品矩阵

录音文件产品层只有一个动作：`开始离线转写`。

Qwen 最终正文是权威文本。Small Bilingual 仅可作为可选时间轴对齐辅助，不得替换 Qwen 正文，也不得成为 Qwen 正文成功的硬依赖。

### 未来实时转写

- Small Bilingual Zipformer INT8：轻量 / 默认
- Chinese Large CTC INT8：高质量中文实时选项
- Chinese Large Transducer：移出产品矩阵
- 不提供 AUTO 产品路由

冻结 streaming contract：

`docs/STAGE_16_STREAMING_CONTRACT_V2.md`

### 说话人分离

冻结链：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering / stitching`

- CAM++ 为唯一产品 speaker embedding 模型
- ERes2Net 移出产品选择
- 用户侧保留预计说话人数与高级聚类/拼接参数能力

### 辅助模型

- Silero VAD
- CT-Transformer zh-en punctuation

## 3. 本地语音识别产品面冻结

`设置 → 本地语音识别` 冻结为四个区域：

1. 离线转写
2. 实时转写
3. 说话人分离
4. 性能与语音检测

离线只暴露 SenseVoice / Qwen3-ASR。

实时只暴露 Small Bilingual / Large CTC，并明确说明用于未来实时转写，不影响录音文件离线转写。

说话人分离不再暴露 CAM++ / ERes2Net 模型选择器，CAM++ 为固定产品路径。

性能/VAD 高级设置保持 capability 驱动、合法范围与可追溯 config snapshot。

内部 FAST/HIGH_QUALITY、FIRST_PASS/SECOND_PASS 等历史持久化/状态名可继续存在，但不得重新作为产品概念暴露。

## 4. Qwen 时间轴冻结

Qwen 负责最终正文；当 Small Bilingual 可用时，可在 Qwen 主识别后内部生成 token timing/reference timeline。

运行期必须明确显示：

`正在生成时间轴… XX%`

该 activity 不新增 Room 持久化状态，继续复用既有 SECOND_PASS 状态边界，避免 schema/state migration。

Small Bilingual 缺失或时间轴对齐失败不得使 Qwen 正文任务整体失败。

## 5. SenseVoice / 标点冻结

SenseVoice：

- ITN 开启：使用模型原生 ITN/标点能力，不重复跑外部 CT 标点。
- ITN 关闭：允许 CT-Transformer 执行外部标点恢复。
- punctuation-only token 不参与 speaker/timeline 伪时间定位。

用户可见文案不得再暴露“第一遍/第二遍/二遍 ASR”等工程术语。

## 6. AI Summary cancellation Fix 1 冻结

Stage 13A QA5 Fix 1 同时冻结 AI Summary 取消语义：

- 取消 coroutine 的同时主动断开当前 LLM HTTP request。
- `CancellationException` 不得映射成普通 NETWORK failure。
- 取消终态使用 `NonCancellable` 可靠写入 Room。
- Room 使用 ACTIVE-state CAS；迟到 progress / provider response 不得把 CANCELLED/FAILED 任务复活成 COMPLETED。
- 只有 COMPLETED Summary 计入正式“版本 N”。
- CANCELLED / FAILED 不增加正式版本数，也不抢占已有 completed 内容。
- INTERRUPTED 仅在无 completed 内容时作为可恢复任务入口。

## 7. Benchmark 产品边界

Speech Benchmark / Diarization Benchmark 是 Stage 13A 选型期间的临时开发能力，不属于冻结产品功能。

冻结产品中不提供 Benchmark 卡片、按钮或 runner。

继续保留：

- 模型安装/完整性验证
- isolated native smoke
- runtime capability 检查
- model/config snapshot
- deterministic regression tests

## 8. Room / 数据边界

Room schema 冻结为 v7。

Stage 13A 不改变以下核心事实：

- canonical 16 kHz mono PCM16 absolute sample index 是唯一媒体时间真值
- Transcription version 继续保存真实 model/runtime/config lineage
- 原始 Segment/Token/Speaker alignment 不被人工 Revision 覆盖
- Stage 12C Revision / Search 派生数据边界保持不变
- 不允许 destructive migration

## 9. Model channel 状态

本次 Freeze **不提升 production model-channel**。

冻结时 production：

`ioannes78/voica-model-channel@e4e64d29b8c92b97de4298ec6e292c33273f3ba4`

Stage 13A 真机验收通过的是 candidate/debug 模型源 + App 最终模型过滤路径。

production manifest promotion 是独立受控操作，需单独明确授权；不得把 Stage 13A Freeze/merge 自动等同为 production promotion。

## 10. 路线覆盖说明

`docs/ROADMAP.md`、`AGENTS.md`、Stage 12C Handoff 中仍可能保留 Stage 13A pre-freeze 候选文字，例如：

- FireRedASR2
- Chinese Large Transducer
- ERes2Net
- Speech/Diarization Benchmark 产品化

这些历史候选描述已被以下冻结文件覆盖：

- `docs/STAGE_13A_QA5_FINAL_DECISIONS.md`
- `docs/STAGE_13A_FROZEN_ROADMAP_NOTE.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- 本文件

Stage 13B 不得恢复已移出冻结矩阵的候选项，除非形成新的明确变更决策并重新做受影响的真机回归。

## 11. 真机验收

最终用户确认：

**“测试通过”**

验收包含 Stage 13A QA5 与 Fix 1 收口后的核心功能回归。CI success 仅作为自动化证据，不替代本次用户真机验收。

## 12. 下一阶段

下一阶段：

**Stage 13B — 稳定性、后台与真实长录音专项**

Stage 13B 必须以本 Stage 13A 冻结模型矩阵与默认参数为基线，重点覆盖：

- 真实 30 分钟 / 1 小时 / 2 小时录音
- ASR / diarization RTF、RAM/PSS、CPU、thermal、storage、battery（可测时）
- BLE soak
- Foreground Service
- 后台 / 锁屏文件下载
- cancellation / interruption / process recovery
- 低存储与临时文件恢复
- playback / seek / timeline / revision / search / AI Summary 长文本回归
