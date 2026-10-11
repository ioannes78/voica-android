# Voica Post-V1 P0 Final Freeze

状态：**FINAL / ACCEPTED / FROZEN CANDIDATE**

日期：2026-10-11

## 1. 阶段结论

Post-V1 P0 — Canonical WAV Performance & UX 已完成实现、CI、真机功能回归与长音频性能基准。

用户最终明确确认：**“测试通过”**。

本阶段目标已达成：

- 长 MP3 / 压缩音频 Canonical WAV 生成未再出现随时长明显非线性恶化；
- Canonical 进度 UI 使用真实处理进度与显式终态，不再把 metadata duration 误当 decoder EOS；
- 已生成完成后播放器/波形/转写/说话人分析可正常继续使用；
- 设备下载 foreground notification 连续多次下载后可正确回到终态，不再残留旧百分比；
- 已有 canonical-compatible WAV Fast Path 保留；
- Room schema 与 production model channel 保持不变。

本文件冻结当前 Post-V1 P0 候选行为。最终是否进入 `main` 仍以本文件提交后的最终 PR CI 通过并完成 merge 为准。

## 2. GitHub / 产品身份

- repository：`ioannes78/voica-android`
- base：`main`
- development branch：`post-v1-canonical-audio-p0`
- PR：`#26` — `[APK] Post-V1 P0 — Canonical WAV Performance & UX`
- 开发基线 main：`e150589baffcebf03d8697c701dd716097cd70f6`
- 最后已真机验收 QA 候选 HEAD：`e5ee57abf213bd2c3d8c7de1656e65da4ac4a011`
- target versionCode：`89`
- target versionName：`1.0.1`
- Room：`v12`
- ABI：`arm64-v8a`
- production model channel：保持 Stage 14 V1.0 immutable pin，不执行 promotion

## 3. Canonical 性能优化冻结

本阶段冻结以下实现方向：

- coordinator 继续保留一份 authoritative source SHA / integrity verification；
- coordinator-owned compressed / PCM WAV / RAW Opus conversion 不再立即重复做第二次 source full SHA；
- MediaCodec / normalizer / PCM WAV / canonical writer 使用 bounded reusable buffers；
- PCM WAV streaming chunk 使用 64 KiB；
- canonical-compatible PCM16 / 16 kHz / mono WAV 保留 same-path Fast Path；
- compressed decode progress 优先使用 MediaCodec presentation timeline，decoded-frame time 作为 fallback；
- explicit decoder/work final 作为完成语义，metadata duration 不再等价于完成；
- metadata duration 被处理进度追平但 decoder 尚未 EOS 时，UI 不伪造完成；
- Canonical 生成不为匹配 metadata duration 而截断音频；
- `VoicaCanonicalPerf` 保留 source SHA、decode/read、normalize、write、sync、move、output SHA、pipeline、Room commit、wall、RTF 与 duration drift 诊断字段。

P0 没有引入 compressed-source → ASR 直通架构；该事项继续属于后续独立规划，不得由本 Freeze 推导为已支持。

## 4. Canonical UX 冻结

Recording Detail / Player 生成标准 WAV 的状态冻结为三态：

1. 未生成：显示 `生成标准 WAV`；
2. 生成中：显示真实阶段/进度、`已处理 / 总时长`、`已耗时`，安全可取消阶段提供紧凑 `取消`；
3. Ready：自动恢复正常波形播放器与后续处理入口。

冻结约束：

- 不恢复旧的独立整行大号取消按钮；
- UI 不自行估算伪造百分比；
- verify / commit 等不可安全即时取消区间不得提供误导性取消行为；
- 完成后不要求用户手动刷新；
- 录音信息卡继续显示真实 `录音时长`。

## 5. QA1 — MP3 duration overrun

真机最初发现 MP3 `已处理` 可以超过 UI 总时长，同时 decoder 尚未完成。

QA1-R1 修复后：

- 进度使用 source PTS timeline；
- display processed duration 不超过已知 metadata total；
- metadata total 被追平但未 EOS 时不把任务视为 terminal；
- decoder 仍完整处理到 EOS。

用户已对原复现文件明确确认 QA1-R1 通过。

## 6. QA2 — Device download notification terminal sync

### 6.1 已淘汰的中间方案

QA2-R1 仅修复了 repository 上游 stale business decision race，但真机连续下载仍可在第 3 次后残留 `92% / 99% / 100%` 等旧通知状态，因此 QA2-R1 不作为最终架构冻结。

### 6.2 QA2-R2 最终架构

最终冻结：

- 一个连续设备连接/session 只持有一个 connected-device FGS lease；
- foreground-service generation 表示 lease lifetime，而不是每一次通知文字 revision；
- Service ACTIVE 后，通知文字/进度通过同一 notification id 原位更新；
- Service STARTING 期间只保留最新业务真值，不重放历史 progress command；
- 下载 progress 进行 bounded coalescing；
- Completed / Failed / Cancelled / recording / reconnect / disconnect 等 terminal 或非-progress 状态立即处理，不受 progress throttle 阻塞；
- delayed progress reconciliation 重新读取当前业务状态，不携带旧百分比回放；
- diagnostics 区分 lease generation、desired revision/label、rendered revision/label。

自动化覆盖包含同一连接下 5 次连续下载、92/99/100 late-progress、每次 Completed 回到 `已连接`、lease acquire count 保持一次及 STARTING latest-state contract。

用户已对 QA2-R2 真机重复下载明确确认：**测试通过**。

## 7. 30 / 60 / 121 分钟同源 MP3 性能基准

为保证编码条件一致，使用同一约 121 分钟 MP3 源文件，并通过 MP3 stream copy 生成 30 分钟与 60 分钟样本，不重新编码。

真机 wall-clock 结果：

| 音频时长 | Canonical WAV 生成耗时 | RTF | 约实时速度 |
| --- | ---: | ---: | ---: |
| 30:00 | 2:26 | 0.0811 | 12.33× |
| 60:00 | 5:00 | 0.0833 | 12.00× |
| 121:07 | 8:55 | 0.0736 | 13.58× |

Scaling：

- `T60 / T30 = 2.055`，音频长度比 `2.000`；
- `T121 / T60 = 1.783`，音频长度比 `2.019`；
- `T121 / T30 = 3.664`，音频长度比 `4.037`。

结论：

- 30 → 60 分钟基本线性；
- 121 分钟未出现进一步放大的非线性退化；
- 原先“约 2 倍音频长度、耗时接近 3 倍”的观察在本候选中未复现；
- 长文件单位音频时长的处理效率没有随长度增加而恶化。

本轮未采集 RAM/PSS、CPU、thermal、storage I/O 的量化 telemetry；这些指标必须真实记录为 **N/A**，不得伪造成已测试。

## 8. 最终功能回归

用户最终确认 Post-V1 P0 功能回归通过。冻结范围包括：

- MP3 / 压缩音频生成 canonical WAV；
- 生成进度与 elapsed time；
- 取消与完成状态；
- 完成后播放器 / 波形恢复；
- 转写；
- 说话人分离；
- recording duration 显示；
- canonical-compatible WAV Fast Path；
- QS668 / CB08 连续设备下载 notification terminal sync。

没有用户报告新的 crash、ANR、数据损坏或 blocking regression。

## 9. 数据 / 模型 / 协议边界

Post-V1 P0 Final Freeze：

- Room 保持 `v12`；
- 不新增 Room migration；
- 不改变 Stage 14 V1 clean-baseline 决策；
- 不改变 production model channel pin；
- 不执行 model promotion；
- 不改变 QS668 / CB08 下载协议与 transfer accounting；
- 不改变原始音频 source / canonical lineage 基本契约；
- 不复制、迁移、cherry-pick 或机械翻译 `voice-card-android` 实现。

## 10. 最终 CI / Merge 门禁

在创建本 Final Freeze 后：

1. PR #26 最新 HEAD 必须重新通过 Fast PR Gate；
2. `[APK]` QA APK Gate 必须重新通过；
3. PR 必须仍基于未漂移的 `main`；
4. CI 成功后才可把 PR 从 Draft 标记为 Ready；
5. merge 使用受控 expected-head SHA；
6. merge 后重新核验 `main` HEAD、versionCode/versionName、Room v12 与 production model channel boundary。

## 11. 下一阶段

Post-V1 P0 合并 `main` 并完成 post-merge 核验后，正式解锁：

**Stage 15 — BLE 实时音频链路**。

Stage 15 开始编码前必须重新读取：

- `AGENTS.md`
- `docs/STAGE_14_FREEZE.md`
- `docs/STAGE_14_HANDOFF.md`
- `docs/POST_V1_P0_FREEZE.md`
- `docs/POST_V1_P0_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- 当前 `main` / open PR / CI / Room / app version
- production model channel 当前 pin
- 当前 BLE / protocol / audio / canonical timeline 实现

Stage 15 不得重新打开本阶段已冻结的 canonical 与 device-notification 问题，除非出现新的可复现回归证据。
