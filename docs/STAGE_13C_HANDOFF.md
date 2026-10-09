# Voica Stage 13C Final Handoff

状态：**FINAL / ACCEPTED**

下一阶段：**Stage 14 — UNBLOCKED only after PR #23 merge + main verification**

日期：2026-10-09

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

Stage 14 开始任何修改前必须重新核对：

- `main` HEAD
- PR #23 最终 merged 状态
- GitHub Actions / checks
- `AGENTS.md`
- `docs/STAGE_13C_FREEZE.md`
- `docs/STAGE_13C_HANDOFF.md`
- `docs/STAGE_13B_FREEZE.md`
- `docs/STAGE_13B_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- Room v12 schema 与完整 migration lineage
- app versionCode / versionName
- production model-channel 当前 HEAD
- 当前 transcription / diarization / AI Summary durable lifecycle 与 notification 行为

聊天记录只能作为交接线索，不得替代 GitHub 当前事实。

## 2. Stage 13C 最终验收基线

最终功能 / 用户验收二进制基线：

`2afa6b80e3add368c8c7768e6aac4d571c9fddd0`

最终 QA：

- versionCode：75
- versionName：`0.13.2-stage13c-c8`
- QA versionName：`0.13.2-stage13c-c8-qa`
- QA package：`io.github.ioannes78.voica.qa`
- Room：v12
- ABI：arm64-v8a
- CI：#1033 / run `37908120901` — SUCCESS
- Artifact：`Voica-qa-apk`
- Artifact ID：`11605905693`
- Artifact ZIP digest：`sha256:9efdb81db9961caa3ac1368d62de18a2499dadf949ff64a640a1de2ab3521f04`
- Accepted APK size：`56,860,528 bytes`
- Accepted APK SHA-256：`f153421381c3bddc76ca5755d1bab5864fd3b37e231d1ff6d8253a7023c51c33`

用户已明确完成 Stage 13C C8 Final Candidate 真机验证：

**“测试通过”**

Freeze/Handoff/AGENTS 后续提交不得改变上述已验收二进制行为。

## 3. Stage 13C 最终生产链

正式说话人链继续为：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering/stitching → transcript alignment`

冻结参数：

- diarization window：60 s
- overlap：10 s
- product speaker embedding：CAM++
- default VAD max speech duration：30 s

Stage 13C 没有启用 C9 Fast Diarization，也没有把跳过 Pyannote 的实验链升级为正式链。

## 4. Persisted VAD Reuse

Stage 14 及后续不得破坏 C1 lineage-safe persisted VAD reuse：

- canonical lineage、VAD model revision 与 config 兼容时可复用；
- lineage/config 不兼容必须失效；
- absolute canonical sample index 继续是唯一媒体时间真值；
- 不得为了性能复用不兼容的旧 speech regions。

## 5. 文件级说话人数 / One-Speaker Fast Path

必须保护：

- 每个文件独立说话人数快照：`AUTO / 1 / 2 / 3 / 4 / 5+`；
- `1人` 是用户明确指定的 Fast Path，不得被 AUTO 隐式触发；
- 1 人模式复用 VAD，跳过 Pyannote/CAM++/clustering，但保留 timeline/alignment；
- 2/3/4/5+ 继续使用完整多人链；
- 相同说话人数允许再次显式“重新识别说话人”；
- Cancelled/Failed 不得让已选择模式失效；
- 重新转写保持 `REUSE_ONLY`，不得静默重跑重型 diarization。

## 6. Offline ASR 30 s Consumer Safety Bound

必须保护 C6B：

- 原始 VAD segment 仍是产品/timeline truth；
- 只有 offline ASR consumer request 内部分块；
- chunk 不超过安全边界；
- 结果合并回原 segment；
- 不改变 absolute sample range，不制造 gap/overlap；
- token timing 不完整时保守 fallback，不伪造时间；
- 30 s hard guard 保留。

如果 Stage 14 为 Release/R8 做代码优化，必须确保该内部 chunking 不被 shrink/重构回退。

## 7. Transcription / Diarization 生命周期

Stage 13C 最重要的产品生命周期冻结：

`ASR completed → transcript immediately usable → diarization background → speaker enrichment in place`

必须继续保证：

- durable Transcription COMPLETED 后正文立即发布；
- speaker 不 gate 正文首次显示；
- diarization/alignment 只补充 speaker metadata；
- speaker enrichment 不创建新 Transcription，不改 ASR 原文，不改变 Current/Candidate，不覆盖人工 Revision；
- diarization FAILED/CANCELLED/INTERRUPTED 时正文仍可播放、复制、编辑、分享、重新转写、AI 总结；
- AI Summary 默认不等待 speaker；每次任务输入使用冻结 snapshot；
- speaker 后续完成不得改写已冻结/已完成 Summary。

## 8. Durable Completion / Speaker Attention

Stage 13B + Stage 13C 最终 durable lifecycle 必须整体保护：

- Transcription、Diarization、AI Summary 普通完成各自有 Android + App 内完成通知；
- 普通完成通知跨进程保持到用户显式打开结果；
- 已有 Current 后的新 Transcription / AI Summary 仍使用 Candidate；
- Candidate 查看不等于 adopt/ignore；
- diarization/alignment FAILED / INTERRUPTED 是 Room durable attention；
- 用户主动 Cancelled 不是 failure attention；
- 页面可见/点击通知不得自动 resolve durable failure；
- replacement durable row 创建或明确 Ignore 才可 resolve；
- 首次自动 diarization 的 AUTO_START 可跨 process restart 恢复；
- 手动重新识别 process death 后不得擅自从头重跑，应暴露 durable INTERRUPTED。

## 9. Room / Migration

Stage 13C 最终 Room：**v12**。

后续必须：

- 保持 v1→v12 migration lineage；
- 不允许 destructive migration；
- 新 migration 只能 additive/受控并有明确产品需求；
- schema 必须由真实 Room/KSP 生成，不得手工伪造；
- CI 继续校验 committed schemas。

v12 新增的 diarization/alignment terminal acknowledgement 不得退化为 SharedPreferences 或纯 UI 状态。

## 10. Stage 13C Profiling 已退出生产运行链

Final Candidate 前已清理临时 diarization profiling：

- 无 `DiarizationBenchmarkExportController` runtime export；
- 无 `Stage13CBoundaryProfiler` runtime wrapper；
- normal runtime 不自动生成 `Downloads/Voica/Diagnostics` diarization JSON；
- normal runtime 不自动生成 app-private diarization profiler JSON；
- normal diarization provider 直接进入正式 coordinator。

Stage 14 不得因为 release diagnostics 又把 Stage 13C profiler 自动接回普通产品运行链。

`SpeechBenchmarkRunner` 属于另一条语音 benchmark 能力，不在 Stage 13C 清理范围。

## 11. C2/C3/C4/C5/C9 决策不得被误解

- C2 exact-range speaker embedding cache：真机 0 有效命中，已移除，不是待恢复功能；
- C3：完成 runtime/API 审计，没有 speculative batch 改动；
- C4：生产 60 s / 10 s 已确认，adaptive window/overlap 未进入产品；
- C5：按产品决策跳过；
- C9 Fast Diarization：未启用，仅允许未来在新证据 + 明确用户授权下独立实验。

## 12. CI / Build 约束

Stage 13C Final Candidate CI #1033 已通过：

- unit tests
- Debug/QA build
- stable QA signing identity
- Room v1–v12 schema gate
- artifact upload

完整 CI 继续使用 `--no-configuration-cache`。Stage 13B 对 restored Configuration Cache 路径不一致问题的冻结结论继续有效；没有新的可重复证据前不得删除该保护。

## 13. Production model channel

Stage 13C 不提升 production model channel。

Final Freeze 前核验：

- repo：`ioannes78/voica-model-channel`
- main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`

任何 Stage 14 release model manifest 冻结或 promotion 都必须独立重新核验并取得明确授权；不能把 Stage 13C 真机通过解释成 model-channel promotion 授权。

## 14. Stage 14 正式目标

Stage 14 — Voica V1.0 Release Freeze 只在 PR #23 合并并完成 main 核验后解锁。

权威范围读取 `docs/ROADMAP_STAGE_13C_PLUS.md`，至少包括：

- Release APK / AAB
- R8 / Proguard
- 权限 / 隐私
- Room schema / migrations
- production model manifest
- 当前正式文件 ASR / future streaming ASR / diarization 模型矩阵
- diarization 性能基线与默认参数
- 默认性能档位与高级参数 schema
- Benchmark 基线
- 30min / 1h / 2h 稳定性证据
- Test / Architecture / Freeze / Handoff

Stage 14 不承担新的大规模模型选型。

## 15. PR #23 merge 后门禁

Final Freeze/Handoff/AGENTS 提交后可以将 PR #23 标记 Ready 并合并，但 merge 后仍必须：

1. 核验新的 `main` HEAD 与 PR #23 merged 状态；
2. 核验适用的 post-merge CI / checks；若 workflow 没有 push trigger，必须明确记录，不伪造 run；
3. 确认 `AGENTS.md` 当前冻结基线推进为 Stage 13C、下一开发阶段推进为 Stage 14；
4. 确认 `docs/STAGE_13C_FREEZE.md` / `docs/STAGE_13C_HANDOFF.md` 已进入 `main`；
5. 确认 Room 仍为 v12；
6. 确认 versionCode / versionName 仍为 75 / `0.13.2-stage13c-c8`；
7. 再次确认 production model channel 未变化；
8. 完成这些核验后，Stage 14 才真正解锁。