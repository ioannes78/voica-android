# Stage 16A.1 Progress — 手机录音 / 手机实时转写

日期：2026-10-15

状态：**P1 CI PASS / P2 CORE IMPLEMENTED / P2 CI VALIDATION**

## 当前 GitHub 基线

- base `main`：`075bc9689b011678685e1046e3590a26d787067f`
- branch：`stage16a1-phone-microphone`
- PR：#29（Draft / WIP）
- Room：v13，未新增 migration
- versionCode：91
- versionName：`1.1.0-stage16a`
- production model channel：未修改

## P0

已完成并提交：

- 产品入口拆为 `手机录音` / `手机实时转写`；
- 两者共用一套 PhoneMicrophoneCapture；
- recording truth > ASR truth；
- Requirements / Development Plan / Product Plan 已收口；
- `AGENTS.md` 当前接管状态已刷新，历史冻结事实保持不变。

## P1 — Platform Foundation

已实现：

- `RECORD_AUDIO`；
- `FOREGROUND_SERVICE_MICROPHONE`；
- dedicated microphone FGS；
- capture-scoped PARTIAL_WAKE_LOCK；
- 单一 `AudioRecord` owner；
- PCM16 / 16 kHz / mono；
- `VOICE_RECOGNITION` → controlled `MIC` fallback；
- 80 ms / 1280 sample read chunk；
- buffer headroom；
- route / client-silenced / read / FGS / WakeLock diagnostics；
- permission first-request / rationale / permanently-denied / Settings policy。

验证：

- Android PR CI #1462 — SUCCESS；
- Fast PR Gate — SUCCESS；
- Unit tests and debug compile gate — SUCCESS。

P1 的 Home / screen-off 真机行为仍属于后续 UI/FGS 真机 QA，不得据 CI 写成 PASS。

## P2 — Recording Truth / Formal WAV / Library

当前核心实现 commit：`ae0711a450de9656bfa3372772392523f2cd79d6`

已实现：

- 手机正式录音主路径直接同步写 `RealtimePcmWavSegmentWriter`；
- 正式 WAV 不经过 Stage 16A bounded ASR side-sink；
- Pause 停止当前 capture epoch，不写 silence；
- Resume 创建新的 `CONTROL_BOUNDARY` epoch；
- 多 epoch 连续写入同一正式 WAV；
- Stop patch RIFF / fsync / atomic finalize；
- 0 PCM sample 不注册 44B 空 WAV；
- `RecordingSourceType.PHONE_MICROPHONE`；
- 手机正式 WAV 直接注册为 `CANONICAL_WAV`；
- 不伪造 DEVICE asset；
- 不伪造 AudioDerivation；
- 复用现有 Recording Room / SearchIndex / Playback / PcmSourceResolver 生命周期；
- immediate second recording 自动化覆盖；
- formal WAV 注册自动化覆盖。

当前明确未完成：

- P2/P6 abnormal process interruption staging recovery；
- P3 realtime ASR fan-out；
- P4 用户入口/UI；
- P5 notification deep link / full background lifecycle；
- 真机 S1–S15。

进程异常恢复不得在此阶段被伪装为已完成；正式实现继续在 P6 收口，并执行 S13。

## 下一门禁

1. 当前 P2 commit 通过 Android PR CI；
2. CI 失败先修复，不进入 P3；
3. P2 CI 通过后进入 P3 Stage 16A realtime integration；
4. 在 P4 有用户入口前，不把 S1–S4 写成已执行。
