# Voica Stage 13B Final Handoff

状态：**FINAL / ACCEPTED**

下一阶段：**Stage 13C — UNBLOCKED only after PR #17 merge + main verification**

日期：2026-10-08

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

Stage 13C 开始任何代码修改前必须重新核对：

- `main` HEAD
- PR #17 最终 merged 状态
- GitHub Actions / checks
- `AGENTS.md`
- `docs/STAGE_13B_FREEZE.md`
- `docs/STAGE_13B_HANDOFF.md`
- `docs/STAGE_13A_FREEZE.md`
- `docs/STAGE_13A_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- Room v11 schema 与完整 migration lineage
- app versionCode / versionName
- production model-channel 当前 HEAD
- 当前 transcription / diarization / AI Summary durable lifecycle 与 notification 行为

聊天记录只能作为交接线索，不得替代 GitHub 当前事实。

## 2. Stage 13B 最终验收基线

最终功能 / 用户验收二进制基线：

`458b2e83e99b573bf1104d94f90c282b0d01baa3`

最终 QA：

- versionCode：64
- versionName：`0.13.1-stage13b-ai-summary-qa4-r2`
- QA versionName：`0.13.1-stage13b-ai-summary-qa4-r2-qa`
- QA package：`io.github.ioannes78.voica.qa`
- Room：v11
- ABI：arm64-v8a
- CI：#914 / run `37767932643` — SUCCESS
- Artifact ID：`11546441561`
- Artifact ZIP digest：`sha256:001d8c681516d9620f381dfad048f5cb526afafc798c044aa6052abf3cab9654`
- Accepted APK SHA-256：`88a8a4bec2061f98dd642adf8ca0051efaa4181cceb3116a009daeb208f023ad`

用户已完成：

- QA4-R1 全量真机验证：**测试通过**
- QA4-R2 Final Gate 播放通知定向复测：**测试通过**

## 3. Durable task / attention 约束

Stage 13C 及后续阶段不得破坏 Stage 13B 已冻结的 durable lifecycle：

- Room 是 terminal attention / candidate lifecycle 的事实来源；
- 页面展示、App 内通知、Android 系统通知只是同一 durable state 的 projection；
- 点击或查看通知 / 页面不得等同于 resolve；
- AMBIGUOUS_REMOTE_RESULT 不得自动重发远程请求；
- ambiguous 手动重试必须复用原冻结 request snapshot；
- ordinary AI Summary FAILED 的 replacement 允许用户重新选择 Provider / Model / Template；
- chooser cancel 不得 resolve FAILED；
- 只有新的 durable row 真正创建后，旧 replacement failure 才可退出；
- Transcription 进程丢失必须 reconcile 为 INTERRUPTED，不提供伪 checkpoint continue；
- retranscribe 成功创建新 durable row 或明确 ignore 才能 resolve INTERRUPTED。

## 4. Current / Candidate / History 约束

Transcription 与 AI Summary 统一维持：

`Current / Candidate / History`

必须继续保证：

- 新 completed 在已有 Current 时只成为 Candidate；
- 查看 / 返回当前不切换 Current；
- 只有“使用新结果”切换 Current；
- Ignore 是 durable acknowledgement，但不得删除历史事实；
- Transcription 与 AI Summary 的 Candidate dismiss state 隔离；
- 后续新 Candidate 可再次提示。

## 5. Notification 约束

Stage 13B 最终通知规则：

- Running 长任务由适当的 Foreground Service / ongoing notification 负责 Android execution ownership；
- FAILED / INTERRUPTED / AMBIGUOUS 使用 durable attention；
- Transcription / AI Summary Candidate completed 同时提供 App 内与 Android 系统结果通知；
- 系统通知点击只 deep-link，不 resolve；
- 当前查看同一 Recording + destination 时，可隐藏对应 App 全局条，但不能改 durable state；
- 离开页面后未解决通知必须恢复；
- stale AI Summary 的 ignore 使用 durable fingerprint；相同 fingerprint 不复活，新的 lineage fingerprint 可以再次提示。

Playback 额外冻结：

- Mini Player 在 PLAYING / PAUSED 显示；
- 关闭播放器必须同步清除 detached Android playback notification；
- Service destroy 再做 notification cleanup 兜底；
- MediaSession user-facing error 必须为简体中文，不得暴露内部 `PlaybackErrorCode` 枚举。

## 6. Room / Migration 约束

Stage 13B 最终 Room：**v11**。

后续必须：

- 保持 v1→v11 migration lineage；
- 不允许 destructive migration；
- 不得手工伪造 Room schema；
- 新 schema 只能由真实 Room/KSP 编译生成并提交；
- CI 继续校验 committed schemas。

v10 / v11 新增的 durable attention / candidate / stale acknowledgement 不得重新退化为纯进程内状态。

## 7. CI / build 约束

Stage 13B 最终代码 CI #914 已通过完整 unit tests、QA build、stable QA signing、Room v1–v11 gate 与 APK artifact upload。

完整验证命令当前显式使用 `--no-configuration-cache`，原因是 CI #900 已重复证实 restored Configuration Cache 与 dependency/transform cache 可能出现路径状态不一致。除非有独立、可重复的新证据，不得删除此保护。

## 8. Production model channel

Stage 13B 不提升 production model channel。

Final Freeze 前核验：

- repo：`ioannes78/voica-model-channel`
- main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`

Stage 13C 不得把“性能优化”作为无授权 model-channel promotion 的理由。

## 9. Stage 13C 正式目标

Stage 13C 以 `docs/ROADMAP_STAGE_13C_PLUS.md` 为权威增量路线，目标是：

**说话人分离处理时间 / RTF / 资源消耗优化。**

默认不重新进行大规模模型选型，也不把提高准确率作为主目标；准确率是不得明显退化的约束。

基线链继续来自 Stage 13A：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering/stitching → transcript alignment`

优先工作顺序：

1. 对 diarization 各阶段做可重复 profiling；
2. 复用已有 VAD / segment truth，避免重复计算；
3. speaker embedding cache；
4. CAM++ bounded batching；
5. overlap 去重与短 segment 策略；
6. 线程数 / executor 真机 benchmark；
7. 可靠的一人录音 fast path；
8. ASR completed 与 diarization completed 生命周期解耦，避免 diarization 阻塞正文可用性。

只有上述优化后仍无法满足性能目标，才允许进入跳过 Pyannote 等实验链 benchmark；任何正式替换都必须先 A/B benchmark 并经用户确认。

## 10. Stage 13B 回归触发条件

若 Stage 13C 改变以下任一核心路径：

- diarization segmentation model
- speaker embedding model
- inference runtime
- VAD / segmentation contract
- clustering / stitching 主路径

必须重新执行受影响的 Stage 13B 真实长录音稳定性验证，至少覆盖受影响的 30 / 60 / 120 分钟档位，并重新观察 RTF、RAM/PSS、CPU、thermal、storage 与 crash/recovery 行为。

## 11. PR #17 merge 后门禁

Final Freeze/Handoff 提交后可以合并 PR #17，但 merge 后仍必须：

1. 核验新的 `main` HEAD 与 PR #17 merged 状态；
2. 核验适用的 post-merge CI / checks；若 workflow 不配置 push trigger，必须明确记录这一事实，不伪造 post-merge run；
3. 确认 `AGENTS.md` 保留 Stage 13A / Stage 13C+ 当前规划且没有被旧分支回退；
4. 将 `AGENTS.md` 当前冻结基线推进为 Stage 13B、下一开发阶段推进为 Stage 13C，同时保留既有 Stage 13C+ 增量路线内容；
5. 确认 Room 仍为 v11；
6. 确认 versionCode / versionName 仍为 64 / `0.13.1-stage13b-ai-summary-qa4-r2`；
7. 再次确认 production model channel 未变化；
8. 完成这些核验后，Stage 13C 才真正解锁。