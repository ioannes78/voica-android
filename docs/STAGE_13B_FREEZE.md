# Voica Stage 13B Final Freeze

状态：**FINAL / ACCEPTED / FROZEN**

日期：2026-10-08

## 1. 最终结论

Stage 13B — 稳定性、后台执行、长录音与恢复能力收口，已完成最终真机验收并冻结。

用户先后明确确认 Stage 13B.5 QA4-R1 完整真机验证及 QA4-R2 Final Gate 定向播放通知复测：

**“测试通过”**

最终功能 / 用户验收二进制基线：

`458b2e83e99b573bf1104d94f90c282b0d01baa3`

后续 Final Freeze/Handoff 提交只允许包含文档或用于避免合并回退的元数据收口，不改变上述已验收二进制行为。

最终候选：

- versionCode：64
- versionName：`0.13.1-stage13b-ai-summary-qa4-r2`
- QA versionName：`0.13.1-stage13b-ai-summary-qa4-r2-qa`
- QA package：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- Room schema：11
- Android PR CI：#914 / run `37767932643` — SUCCESS
- Artifact：`Voica-qa-apk`
- Artifact ID：`11546441561`
- Artifact ZIP digest：`sha256:001d8c681516d9620f381dfad048f5cb526afafc798c044aa6052abf3cab9654`
- 用户验收 APK SHA-256：`88a8a4bec2061f98dd642adf8ca0051efaa4181cceb3116a009daeb208f023ad`

## 2. Stage 13B.4 Durable Model Install

Stage 13B.4 已在本阶段内先行完成独立 Freeze/Handoff；其 durable model-install 状态机继续属于 Stage 13B 最终冻结基线。

必须保护：

- 下载、校验、解压、验证与安装状态可持久恢复；
- 进程终止后恢复不得伪装为重新开始；
- 已下载 artifact 与安装状态不得因 UI 生命周期丢失；
- 删除 / 重新下载 / 继续安装语义必须由持久状态决定；
- 用户可见错误保持简体中文。

权威历史记录：

- `docs/STAGE_13B_4_DURABLE_MODEL_INSTALL.md`
- `docs/STAGE_13B_4_FREEZE.md`
- `docs/STAGE_13B_4_HANDOFF.md`

## 3. Durable Transcription / AI Summary 冻结

Stage 13B.5 将长任务状态从进程内事件收口为 Room durable truth。

### AI Summary AMBIGUOUS_REMOTE_RESULT

- 远程请求状态无法确认时不得自动重发，避免重复生成或重复计费；
- 查看页面、点击 App 内通知、点击 Android 系统通知都不得 resolve；
- 手动重试必须复用原冻结 request snapshot，包括 source lineage、source content、Provider、Base URL、Model、Template、request config 与 prompt/schema version；
- 只有成功创建新的 durable retry child row 后，旧 ambiguous attention 才能退出；
- “忽略”是 durable acknowledgement；
- 显式采用新的有效结果可以结束相关 attention。

### 普通 FAILED

- Provider / HTTP / Model 等普通失败不得直接按旧失败模型自动重试；
- “生成新总结”进入现有生成选择流程，允许重新选择 Provider / Model / Template；
- 打开选择器后取消，不得 resolve 旧 FAILED；
- 只有新的 durable summary row 真正创建后才 resolve 被替代 FAILED；
- 同一 replacement chain 连续失败只暴露最新未解决 FAILED，但历史记录保留；
- 普通 FAILED replacement 不得顺带清除 AMBIGUOUS duplicate-billing boundary。

### Transcription process recovery

- 进程在转写完成前结束，启动 reconcile 必须把未完成任务收敛为 `INTERRUPTED`；
- Stage 13B 不提供转写 checkpoint continue，产品行为为“重新转写 / 忽略”；
- 查看页面或通知不得 resolve；
- 只有新的 durable transcription row 成功创建，或用户明确忽略，旧 INTERRUPTED 才退出；
- 忽略状态跨进程 / 重启保持。

## 4. Current / Candidate / History 冻结

Transcription 与 AI Summary 统一采用：

`Current / Candidate / History`

- 已存在 Current 时，新 completed 结果只能成为 Candidate，不得自动抢占 Current；
- “查看”只预览 Candidate，不切换 Current；
- “返回当前”不 resolve Candidate；
- “使用新结果”才切换 Current；
- “忽略”只消除当前 Candidate attention，不删除底层 Candidate/History；
- Ignore 必须 durable，重启后不得复活同一 Candidate；
- 后续新的 Candidate 必须能够重新出现；
- Transcription Candidate 与 AI Summary Candidate 的 dismiss state 必须完全隔离。

## 5. App 内 / Android 系统通知冻结

通知是 durable state 的投影，不是事实来源。

- Running 长任务继续由适用的 Foreground Service / ongoing notification 承担 Android execution ownership；
- FAILED / AMBIGUOUS / INTERRUPTED 等需要用户处理的终态由 Room durable attention 驱动；
- Transcription / AI Summary Candidate 完成后同时具备 App 内和 Android 系统结果通知；
- 点击任何通知只负责 deep link，不得视为 acknowledge；
- 当前正在查看同一 Recording 的对应详情页时，只临时隐藏该任务的 App 内全局通知，页面内状态卡继续显示；
- 离开该详情页后，只要 durable state 尚未解决，全局通知应恢复；
- 页面可见性不得写入 Room acknowledgement。

AI Summary stale 提示使用 durable fingerprint acknowledgement：

- 用户忽略当前 stale relationship 后，同一 fingerprint 跨重启不再提示；
- Current Transcription / Revision / Summary 关系变化形成新 fingerprint 后，可以再次提示。

## 6. Playback Final Gate 冻结

Final Gate review 处理了两个旧 Codex P2：

- `PlaybackForegroundService.stop(context)` 必须同步取消已 detach 的播放通知，`onDestroy()` 再做 foreground removal + notification cancel 兜底；
- MediaSession 用户可见错误不得暴露 `AUDIO_READ_FAILED`、`SOURCE_NOT_AVAILABLE` 等内部枚举；全部 `PlaybackErrorCode` 映射为简体中文，枚举仅保留诊断用途。

用户对 QA4-R2 定向测试“播放 → 转写/总结页 Mini Player → 暂停 → 点击 × → 系统播放通知立即消失”明确确认：

**“测试通过”**

## 7. Room / Migration 冻结

Stage 13B 最终 Room schema：**v11**。

- 保持完整 v1→v11 migration lineage；
- 不允许 destructive migration；
- v10 持久化 transcription terminal acknowledgement 与 Transcription / Summary Candidate 独立 dismiss state；
- v11 additive migration 增加 stale-summary acknowledgement fingerprint；
- `11.json` 必须来自真实 Room/KSP 编译器生成，不得手工伪造；
- CI 必须校验 committed Room schema v1–v11。

## 8. CI 冻结事实

CI #900 暴露了 GitHub-hosted runner 恢复的 Gradle Configuration Cache 与 dependency/transform cache 不一致问题，表现为已声明 JUnit 依赖却 unresolved、多个 AAR 路径同时 `FileNotFoundException`。

最终全量验证命令因此显式使用 `--no-configuration-cache`，同时保留其它测试、QA signing、Room schema gate 与 artifact 上传。

在没有新的可重复证据和独立 CI 验证前，不得为了速度擅自移除该保护。

最终二进制 CI #914 全部 SUCCESS。

## 9. Production model channel

Stage 13B Final Freeze **不执行 production model-channel promotion**。

最终冻结前核验：

- repository：`ioannes78/voica-model-channel`
- main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- parent：`6f74d89f02a23a46a489de79757f0ed07d0daea2`

任何后续 promotion 都必须作为独立受控操作重新核验和授权。

## 10. 真机验收

Stage 13B.5 QA4-R1 完整真机验证通过；Final Gate 两个旧 playback notification P2 修复后，QA4-R2 定向真机复测再次通过。

CI SUCCESS 不能替代上述真机验收。

## 11. 下一阶段

Stage 13B Final Freeze/Handoff 合并到 `main` 并完成 post-merge 核验后，下一阶段解锁为：

**Stage 13C — Diarization Performance**

Stage 13C 开始前必须重新读取：

- `AGENTS.md`
- `docs/STAGE_13B_FREEZE.md`
- `docs/STAGE_13B_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- Stage 13A Final Freeze/Handoff
- GitHub 当前 `main`、CI、Room schema 与 production model channel

Stage 13C 的默认目标是说话人分离性能优化，不是重新做模型选型。若改变核心 diarization model / runtime / segmentation / embedding 路径，必须重新执行受影响的 Stage 13B 30/60/120min 稳定性验证。