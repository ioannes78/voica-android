# Voica Stage 13A Handoff

> **SUPERSEDED / PREMATURE CLOSURE**
>
> 本文件记录 QA5 后曾发生的 Stage 13A 过早 Handoff，作为历史证据保留，不删除、不回滚。
> 自 2026-10-05 QA6 开发重新打开后，当前治理状态以 `docs/STAGE_13A_QA6_DEVELOPMENT.md` 为准：Stage 13A = IN PROGRESS，QA5 = ACCEPTED，QA6 = IN DEVELOPMENT，Stage 13B = BLOCKED。
> QA6 经用户真机明确“测试通过”并重新完成 Final Freeze/Handoff 前，不得按本文件启动 Stage 13B。

状态：**历史记录 / 已被 QA6 reopening supersede**

下一阶段：**Stage 13B — 当前 BLOCKED**

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

开始 Stage 13B 前必须重新核对：

- `main` HEAD
- PR #15 最终合并状态
- GitHub Actions
- `docs/STAGE_13A_FREEZE.md`
- `docs/STAGE_13A_QA5_FINAL_DECISIONS.md`
- `docs/STAGE_13A_FROZEN_ROADMAP_NOTE.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- Room v7 schema
- production model-channel 当前 HEAD
- 当前 APK/version/ABI
- transcription / diarization / AI Summary cancellation lifecycle

聊天记录只能作为线索。

## 2. Stage 13A 最终基线

功能 / 真机 QA 基线：

`6543d9f45ed73bb3a815456968518f0d3641b774`

Freeze 内容基线：

`230aa3304b76781a34d2ec5bd74ea0c4b7990aab`

最终 QA：

- versionCode 46
- versionName `0.13.0-stage13a-qa5-fix1`
- QA package `io.github.ioannes78.voica.qa`
- Room v7
- arm64-v8a
- sherpa-onnx 1.13.8
- CI #693 / run `37264265999` — success
- Artifact ID `11326236293`
- Artifact digest `sha256:e86a9379fe5f5d08d1978cd9e1e9598898c5ce93609e0304fba00dd2274f8d66`
- Accepted APK SHA-256 `cebc706417abf881ecc3360b922e8fe98369320f16d4f0c4bdad5955ec62bc05`

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

## 4. Recording-file transcription 约束

产品层只有一个入口：

`开始离线转写`

当前选择来自“设置 → 本地语音识别 → 离线转写”。

Qwen 正文不可被 Small Bilingual reference text 替换。

Small Bilingual 只可做 Qwen 的可选时间轴辅助；缺失或失败时，Qwen 正文仍必须可以完成。

时间轴 activity 使用“正在生成时间轴… XX%”，不新增持久化 Room state。

## 5. Stage 16 streaming contract

Stage 16 必须复用：

`docs/STAGE_16_STREAMING_CONTRACT_V2.md`

真正实时模型只冻结：

- Small Bilingual
- Chinese Large CTC

SenseVoice / Qwen3-ASR 不得通过“分块离线识别”被伪装成 true streaming。

Stage 16 仍负责完整 live stabilizer、BLE/live audio 接入、PARTIAL/STABLE/FINAL UI 与实时标点策略；Stage 13A 只冻结 engine/capability contract。

## 6. Diarization 约束

Stage 13B 不得重新把 ERes2Net 作为普通产品选项。

冻结链：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering/stitching`

Stage 13B 可以做稳定性与长录音验证，但不得在没有新明确决策的情况下重新做大规模 speaker embedding 选型。

若必须改动 clustering/stitching 默认参数，应记录改动原因并补单人、双人、3–4 人回归。

## 7. AI Summary cancellation 约束

Fix 1 已成为冻结行为：

- 用户取消必须终止 active LLM request。
- CANCELLED 必须可靠落库。
- late response / late progress 不得复活任务。
- CANCELLED/FAILED 不计正式 Summary 版本。
- completed transaction 一旦提交，completed 为权威终态。

Stage 13B 的 process recovery / background testing 必须继续保护这些语义。

## 8. Room / 内容管理边界

Room v7 为 Stage 13A 冻结 schema。

不得破坏 Stage 12C 的：

- 原始 Transcription / Segment / Token 不可变事实
- User Revision 独立保存
- SearchDocument / FTS 仅是可重建派生数据
- AI Summary 原始 structured result / evidence 与人工 revision 分离
- 当前版本选择持久化

媒体时间事实继续唯一使用 canonical 16 kHz absolute sample index。

## 9. Model channel

Stage 13A Freeze 时 production model-channel 仍为：

`ioannes78/voica-model-channel@e4e64d29b8c92b97de4298ec6e292c33273f3ba4`

本次 Freeze/Handoff 不执行 production promotion。

后续如要提升 production manifest，应单独重新核对 model-channel main、候选 manifest、最终 8 模型清单与下载/校验事实，并取得明确授权后再做。

## 10. 历史候选文档的优先级

如果 `docs/ROADMAP.md`、`AGENTS.md`、Stage 12C Handoff 仍出现：

- FireRedASR2 High Quality
- Large Transducer realtime
- ERes2Net product embedding
- Benchmark 产品化

这些都属于 Stage 13A pre-freeze 候选历史，不能覆盖 Stage 13A Freeze。

Stage 13B 接管优先级：

1. 当前 GitHub `main`
2. `docs/STAGE_13A_FREEZE.md`
3. `docs/STAGE_13A_HANDOFF.md`
4. `docs/STAGE_13A_QA5_FINAL_DECISIONS.md`
5. `docs/STAGE_13A_FROZEN_ROADMAP_NOTE.md`
6. `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
7. 旧 ROADMAP/历史 Stage 文档

## 11. Stage 13B 目标

Stage 13B 正式负责：

- 真实 30 分钟 / 1 小时 / 2 小时录音专项
- ASR / diarization RTF、RAM/PSS、CPU、thermal、storage、battery（可测时）
- BLE 长时连接与 soak
- Foreground Service
- App 后台/锁屏文件下载
- 系统通知中的真实进度与取消
- 断连/失败/低存储恢复
- App 进程回收后的任务状态恢复
- playback / seek / timeline / revision / search / AI Summary 长文本回归

过去 virtual 30/60/120min 自动化不能替代真实长时真机证据。

## 12. Stage 13B 开发门禁

本节为历史 Handoff 原门禁。QA6 reopening 后附加门禁优先：

1. QA6 完成代码、测试、PR CI 与 QA APK；
2. 用户明确真机“测试通过”；
3. 重新创建 Stage 13A Final Freeze/Handoff；
4. merge QA6 PR 并核验 main；
5. Stage 13B 才可解锁。

在此之前不得启动 Stage 13B 编码。
