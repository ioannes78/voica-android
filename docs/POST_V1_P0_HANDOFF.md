# Post-V1 P0 — Canonical WAV Performance & UX Final Handoff

状态：**FINAL ACCEPTED / FROZEN CANDIDATE / AWAITING FINAL CI + MERGE**

日期：2026-10-11

本文件是 Post-V1 P0 的最终交接记录。产品代码与真机 QA 已完成；当前只允许 Final Freeze/Handoff、CI 与 merge 收口，不得再静默修改生产行为。

## 1. GitHub truth

- repository：`ioannes78/voica-android`
- production branch：`main`
- development branch：`post-v1-canonical-audio-p0`
- PR：`#26` — `[APK] Post-V1 P0 — Canonical WAV Performance & UX`
- 开发基线 main：`e150589baffcebf03d8697c701dd716097cd70f6`
- 最后完成真机验收的 QA candidate HEAD：`e5ee57abf213bd2c3d8c7de1656e65da4ac4a011`
- QA2-R2 implementation-code HEAD：`1c268608eba2454f0669c9e82e1ee7410d5feef9`
- Final Freeze 文档首提交：`08e2df36309a8913f443fdcd0490a0bab9f008db`
- Final Freeze：`docs/POST_V1_P0_FREEZE.md`

继续操作前必须重新核验 PR 当前 HEAD、main HEAD 与最新 CI，不得把以上 SHA 当作未来不变事实。

## 2. 产品 / schema 基线

- target versionCode：`89`
- target versionName：`1.0.1`
- production applicationId：`io.github.ioannes78.voica`
- QA applicationId：`io.github.ioannes78.voica.qa`
- Room：**v12 unchanged**
- no Post-V1 P0 Room migration
- canonical profile：`CANONICAL_PCM16_16000_MONO_WAV_V1`
- canonical output：PCM16 / 16 kHz / mono WAV
- ABI：`arm64-v8a`
- production model channel：保持 Stage 14 V1.0 immutable pin，不执行 promotion

## 3. Final accepted implementation

### 3.1 Canonical performance

冻结：

- coordinator 保留一份 authoritative source SHA / integrity verification；
- compressed / PCM WAV / RAW Opus coordinator-owned conversion 去除紧邻的重复 source full SHA；
- Android MediaCodec、normalizer、PCM WAV path 与 canonical writer 采用 bounded reusable buffers；
- PCM WAV streaming chunk 为 64 KiB；
- canonical-compatible PCM16 / 16 kHz / mono WAV same-path Fast Path 保留；
- compressed decoder progress 优先 MediaCodec presentation timeline，decoded-frame time fallback；
- metadata duration 不再当作 decoder completion；
- explicit final/EOS 控制任务完成，不为匹配 metadata duration 截断音频；
- `VoicaCanonicalPerf` 保留 source SHA、decode/read、normalize、write、sync、move、output SHA、pipeline、Room commit、wall、RTF 与 duration drift 诊断。

P0 未实现 compressed source → ASR 直通解码。

### 3.2 Player / Recording Detail UX

冻结为三态：

1. 未生成：`生成标准 WAV`；
2. 生成中：真实阶段/进度、`已处理 / 总时长`、`已耗时`，安全阶段显示紧凑 `取消`；
3. Ready：自动恢复波形播放器与转写/说话人入口。

旧的独立整行 canonical cancel 按钮不恢复。Recording info 保留真实 `录音时长`。

## 4. QA1-R1 — compressed duration progress

原真机问题：MP3 `已处理` 可以超过 metadata 总时长，而 decoder 仍未 EOS。

最终修复：

- progress 使用 source PTS timeline；
- display processed duration 不超过已知 total；
- total 被追平但未 EOS 时不把任务视为 terminal；
- decoder 继续完整处理到 EOS。

用户对原复现 MP3 明确确认：**QA1-R1 PASSED**。

## 5. QA2 — device download foreground notification

### QA2-R1

上游 stale business decision serialization 修复后，真机仍可在连续下载第 3 次起残留 `92% / 99% / 100%`，因此 QA2-R1 不作为最终冻结架构。

### QA2-R2 — FINAL ACCEPTED

最终架构：

- 一个连续设备 connection/session 只 acquire 一个 FGS lease；
- lease generation 不随每次通知文字变化而递增；
- ACTIVE service 使用同一 notification id 原位更新；
- STARTING 期间不排队历史 progress，只在 ACTIVE 后重新投影最新业务真值；
- download progress bounded coalescing；
- Completed / Failed / Cancelled / recording / reconnect / disconnect 等终态立即处理；
- delayed progress reconciliation 重新读取当前状态，不回放旧百分比；
- diagnostics 区分 lease generation、desired revision/label、rendered revision/label。

自动化覆盖同一连接 5 次连续下载、92/99/100 late progress、每次 Completed 回到 `已连接`、单 lease acquire count 及 STARTING latest-state contract。

CI：

- Android PR CI `#1223` — Fast PR Gate **SUCCESS**（QA2-R2 implementation head）
- Android PR CI `#1224` — Fast PR Gate **SUCCESS**
- Android PR CI `#1224` — QA APK Gate **SUCCESS**

QA2-R2 candidate artifact：

- artifact：`Voica-1.0.1-export-qa-apk`
- artifact ID：`11690442570`
- artifact ZIP digest：`sha256:1c948672a9960da534f6f1a5cf1d4f4144f4856ee5e9a1e641899c5b2c7feb12`

用户明确确认：**QA2-R2 测试通过**。

## 6. 30 / 60 / 121 分钟 Canonical 性能验收

测试条件：同一约 121 分钟 MP3 源文件；30 分钟与 60 分钟样本通过 MP3 stream copy 截取，不重新编码，因此编码条件一致。

真机 wall-clock：

| 音频时长 | Canonical WAV 生成耗时 | RTF | 约实时速度 |
| --- | ---: | ---: | ---: |
| 30:00 | 2:26 | 0.0811 | 12.33× |
| 60:00 | 5:00 | 0.0833 | 12.00× |
| 121:07 | 8:55 | 0.0736 | 13.58× |

Scaling：

- `T60 / T30 = 2.055`，duration ratio `2.000`；
- `T121 / T60 = 1.783`，duration ratio `2.019`；
- `T121 / T30 = 3.664`，duration ratio `4.037`。

验收结论：

- 30→60 基本线性；
- 121 分钟没有随时长增加而继续放大的非线性退化；
- 原 Stage 14 观察到的“约 2 倍时长、约 3 倍 canonical wall time”未在本候选复现；
- 长音频 canonical 性能目标通过。

RAM/PSS、CPU、thermal、storage I/O 定量 telemetry 本轮未采集，真实记录为 **N/A**。

## 7. Final true-device functional acceptance

用户在完成长音频 benchmark 后继续执行最终功能回归，并明确确认：**“测试通过”**。

最终接受范围：

- compressed audio canonical generation；
- progress / elapsed / cancellation / completion；
- completion → waveform/player ready；
- transcription；
- diarization；
- recording duration display；
- canonical-compatible WAV Fast Path；
- QS668/CB08 repeated download notification terminal sync；
- 无新增用户报告的 crash / ANR / data corruption / blocking regression。

因此 Post-V1 P0 的产品 QA 门禁已全部满足。

## 8. Final Freeze / CI rule

`docs/POST_V1_P0_FREEZE.md` 已创建。

Freeze/Handoff 文档提交后必须：

1. 重新核验 PR #26 当前 HEAD；
2. 最新 Fast PR Gate 必须 **SUCCESS**；
3. `[APK]` QA APK Gate 必须 **SUCCESS**；
4. main 不得在本阶段收口期间发生未处理漂移；
5. CI 全绿后把 PR 从 Draft 标记为 Ready；
6. 使用 expected-head SHA 执行受控 merge；
7. merge 后重新核验 main HEAD / versionCode / versionName / Room v12 / production model channel boundary。

CI 不能替代已经完成的真机验收；真机验收也不能替代 Final CI。

## 9. Next — Stage 15

Post-V1 P0 merge `main` 并完成 post-merge 核验后，正式解锁：

**Stage 15 — BLE 实时音频链路**。

Stage 15 开始任何编码前必须重新读取：

- `AGENTS.md`
- `docs/STAGE_14_FREEZE.md`
- `docs/STAGE_14_HANDOFF.md`
- `docs/POST_V1_P0_FREEZE.md`
- `docs/POST_V1_P0_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- GitHub 当前 main / open PR / CI / app version / Room
- production model channel 当前 pin
- BLE / protocol / audio / canonical timeline 当前实现

Stage 15 必须继续遵守：不得复制、迁移、继承、cherry-pick 或机械翻译 `ioannes78/voice-card-android` 的任何实现。
