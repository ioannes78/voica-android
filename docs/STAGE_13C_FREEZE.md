# Voica Stage 13C Final Freeze

状态：**FINAL / ACCEPTED / FROZEN**

日期：2026-10-09

## 1. 最终结论

Stage 13C — Diarization Performance 已完成 C0–C8、Final Candidate 全量回归与真机验收。

用户对 Stage 13C C8 Final Candidate 明确确认：

**“测试通过”**

最终功能 / 用户验收二进制基线：

`2afa6b80e3add368c8c7768e6aac4d571c9fddd0`

后续 Final Freeze/Handoff/AGENTS 收口提交只允许包含文档与阶段元数据，不得改变上述已验收二进制行为。

最终候选：

- versionCode：75
- versionName：`0.13.2-stage13c-c8`
- QA versionName：`0.13.2-stage13c-c8-qa`
- QA package：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- Room schema：12
- Android PR CI：#1033 / run `37908120901` — SUCCESS
- Artifact：`Voica-qa-apk`
- Artifact ID：`11605905693`
- Artifact ZIP digest：`sha256:9efdb81db9961caa3ac1368d62de18a2499dadf949ff64a640a1de2ab3521f04`
- 用户验收 APK size：`56,860,528 bytes`
- 用户验收 APK SHA-256：`f153421381c3bddc76ca5755d1bab5864fd3b37e231d1ff6d8253a7023c51c33`

## 2. Stage 13C 目标与最终范围

Stage 13C 的问题定义固定为：

> 说话人分离处理时间过长，产品应降低不必要的重复计算，并让正文完成不再被说话人后处理阻塞。

本阶段没有重新进行大规模 speaker 模型选型，没有替换正式 Pyannote/CAM++ 生产链，也没有启用实验性 Fast Diarization。

正式链继续为：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering/stitching → transcript alignment`

生产窗口继续固定：

- diarization window：60 s
- overlap：10 s

## 3. C0–C5 最终决策

### C0 — Profiling Baseline

完成 Stage 13C 性能分段测量基线，用于后续决策。

### C1 — Persisted VAD Reuse

冻结 lineage-safe persisted VAD reuse：

- 同 canonical lineage、模型与 config 兼容时复用已有 VAD truth；
- lineage/config 不兼容时必须失效重算；
- absolute canonical sample timeline 保持唯一时间真值。

### C2 — Speaker Embedding Cache

exact-range embedding cache 真机未获得有效命中收益，已明确拒绝并移除，不进入正式产品链。

### C3 — Sherpa Runtime Audit

完成 Sherpa API/runtime 审计；没有为缺乏证据的 batch/thread 优化引入 speculative runtime 改动。

### C4 — Boundary / Overlap

完成 boundary/overlap profiling；正式生产参数继续维持 60 s / 10 s，不启用 adaptive overlap/window A/B。

### C5 — Threads / Thermal

按产品决策跳过，不为了理论优化扩大 Stage 13C 范围。

## 4. C6A — 文件级说话人数与 One-Speaker Fast Path

最终冻结：

- 每个录音支持独立说话人数快照：`AUTO / 1 / 2 / 3 / 4 / 5+`；
- 全局默认说话人数只作为新任务默认，不覆盖文件已快照选择；
- 用户明确选择 `1人` 时使用 Fast Path：复用有效 VAD speech regions，统一归为 Speaker 1，跳过 Pyannote / CAM++ / clustering 重型多人链；
- `AUTO` 不得隐式退化为 `1人`；
- 2/3/4/5+ 继续走完整多人 diarization 链并应用人数约束；
- 同一个说话人数可显式再次执行“重新识别说话人”；取消/失败不得让该模式失效；
- 重新转写固定使用 `REUSE_ONLY`：已有兼容 completed diarization 时只做 alignment；没有兼容结果时只发布正文，不得静默新跑 diarization。

## 5. C6B — Offline ASR Safety-Bound Partitioning

修复长连续语音触发 `speech segment exceeds offline ASR safety bound`：

- 原始 VAD SpeechSegment 继续作为产品/时间轴 truth；
- 只有 offline ASR consumer request 在内部按安全边界确定性分块；
- 每个 chunk 不超过安全上限；
- chunk 结果合并回一个原始产品 segment；
- 不制造 gap/overlap，不改变 canonical absolute sample timeline；
- token timing 不完整时保守回退，不伪造精确时间；
- 中文文本合并不强插空格，ASCII 邻接按需要插 separator；
- 原 30 s hard safety guard 保留。

## 6. C7 — Transcription / Diarization 生命周期解耦

产品生命周期正式冻结为：

`ASR COMPLETED → 正文立即可用 → Diarization 后台继续 → Speaker 信息完成后增量补充`

必须保持：

- durable Transcription 完成后立即发布基础 TranscriptDocument；
- 正文立即支持时间轴/阅读、播放、复制、分享、编辑、版本管理与 AI Summary；
- diarization/alignment 只作为同一 Transcription 的 speaker enrichment；
- speaker enrichment 不得创建新 Transcription、修改 ASR 原文、切换 Current/Candidate 或覆盖 Revision；
- diarization FAILED/CANCELLED/INTERRUPTED 不得让已完成正文失效；
- AI Summary 默认不等待 diarization；生成任务使用启动时冻结 input snapshot；
- speaker 后续完成不得静默改写已运行/已完成 Summary。

## 7. C7-R1 — Durable Completion / Speaker Attention

在 Stage 13B durable lifecycle 基础上冻结：

- Transcription、Diarization、AI Summary 普通成功都产生 Android 系统完成通知 + App 内完成通知；
- 普通完成通知跨进程保持，直到用户显式打开对应结果；
- 已有 Current 后的新 Transcription / AI Summary 继续使用 Candidate lifecycle，不与普通完成通知混淆；
- diarization/alignment FAILED / INTERRUPTED 使用 Room durable attention；
- 用户主动 Cancelled 不是 failure attention；
- 页面可见或点击系统通知本身不得自动 resolve durable failure attention；
- replacement durable row 真正创建或用户明确忽略后，旧 attention 才可退出；
- 首次自动 diarization 的 AUTO_START durable intent 可在 process restart 后恢复；
- 后续手动重新识别在 process death 后不擅自从头重算，应暴露 durable INTERRUPTED attention。

## 8. Room / Migration 冻结

Stage 13C 最终 Room schema：**v12**。

- 保持完整 v1→v12 migration lineage；
- 不允许 destructive migration；
- v12 为 additive migration，增加 diarization/alignment terminal acknowledgement；
- `12.json` 必须来自真实 Room/KSP 编译器生成；
- CI 已验证 committed v1–v12 schemas。

后续 Stage 不得把 speaker terminal attention 重新退化为纯进程内状态。

## 9. Stage 13C 临时 Profiling 清理

Final Candidate 前已清理 Stage 13C 临时 diarization profiling runtime：

- 删除公开 `DiarizationBenchmarkExportController`；
- 删除 `Stage13CBoundaryProfiler` 实现；
- 删除只服务 Stage 13C 测量的 diarization profiling implementation/tests；
- 正常 runtime 不再挂 benchmark / boundary decorator；
- 正常 diarization 不再自动生成 `Downloads/Voica/Diagnostics` JSON；
- 正常 runtime 不再自动写 app-private diarization profiling JSON；
- `SpeechBenchmarkRunner` 不属于该清理范围，继续保留。

保留的 deprecated null-only compatibility type alias 不能构造 runner，也不能进入运行链；后续可在独立 UI/API cleanup 时移除，不属于 Stage 13C 行为风险。

## 10. CI / Final Candidate 证据

Stage 13C Final Candidate CI：

- Android PR CI #1033
- run：`37908120901`
- unit tests：SUCCESS
- Debug/QA build：SUCCESS
- stable QA signing identity：SUCCESS
- Room v1–v12 schema gate：SUCCESS
- artifact upload：SUCCESS

CI 继续显式使用 `--no-configuration-cache`；Stage 13B 对 restored Configuration Cache 风险的冻结约束保持有效。

## 11. Production model channel

Stage 13C Final Freeze **不执行 production model-channel promotion**。

冻结前重新核验：

- repository：`ioannes78/voica-model-channel`
- main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- parent：`6f74d89f02a23a46a489de79757f0ed07d0daea2`

任何后续 promotion 都必须作为独立受控操作重新核验并取得明确授权。

## 12. 真机验收

Stage 13C C6A、C6B、C7/C7-R1 与 C8 Final Candidate 均完成真机验收。

最终 C8 Final Candidate 用户明确确认：

**“测试通过”**

Final Gate 覆盖了 Stage 13C 关键产品路径，包括：

- 正常 diarization 不再产生 Stage 13C profiling JSON；
- AUTO / 固定人数 / 1 人 Fast Path；
- 取消后可用相同人数重新识别；
- C6B 长连续语音 ASR safety-bound 回归；
- ASR 完成后正文立即可用；
- speaker 后台 enrichment 不覆盖正文；
- process recovery / durable interrupted attention；
- Transcription / Diarization / AI Summary 完成通知；
- Candidate 语义不回退。

CI SUCCESS 不能替代上述真机验收。

## 13. 明确未进入 Stage 13C 的事项

以下事项没有在 Stage 13C 启用：

- C9 Fast Diarization 实验链；
- 跳过 Pyannote 的正式产品链；
- 新 speaker embedding 模型；
- production model-channel promotion；
- Stage 14 Release/R8/隐私/发布冻结工作。

C9 只有在未来有新的性能证据和明确用户授权时，才可作为独立实验重新开启；不得因为 Stage 13C 已结束而自动启用。

## 14. 下一阶段

Stage 13C Final Freeze/Handoff 合并到 `main` 并完成 post-merge 核验后，下一阶段解锁为：

**Stage 14 — Voica V1.0 Release Freeze**

Stage 14 开始前必须重新读取：

- `AGENTS.md`
- `docs/STAGE_13C_FREEZE.md`
- `docs/STAGE_13C_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_13B_FREEZE.md`
- `docs/STAGE_13B_HANDOFF.md`
- 当前 `main` / CI / Room schema / app version
- production model channel 当前 HEAD

Stage 14 不承担新的大规模模型选型。