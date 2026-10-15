# Stage 16A.1 Progress — 手机录音 / 手机实时转写

日期：2026-10-15

状态：**P1 CI PASS / P2 CI PASS / P3 CI PASS / P4 CI PASS / P5 NEXT**

## 当前 GitHub 基线

- base `main`：`075bc9689b011678685e1046e3590a26d787067f`
- branch：`stage16a1-phone-microphone`
- PR：#29（Draft / WIP）
- P4 source candidate：`e263d8cf9c5e7bf4783710564a50dfbf4d391d41`
- Room：v13，未新增 migration
- versionCode：91
- versionName：`1.1.0-stage16a`
- production model channel：未修改

## P0 — Requirements / Contract / Docs

已完成并提交：

- 产品入口拆为 `手机录音` / `手机实时转写`；
- 两者共用一套 `PhoneMicrophoneCapture`；
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

验证：

- Android PR CI #1465 — SUCCESS；
- Unit tests and debug compile gate — SUCCESS。

## P3 — Stage 16A Realtime Integration

已实现并通过 CI：

### P3-A — source-neutral pipeline reuse

- `Stage16ARealtimePipelineBundle` 增加可选 local-audio persistence mode；
- recorder-card 默认仍为 ENABLED，既有 Stage 16A 行为保持；
- phone realtime 显式 DISABLED，避免生成第二份 realtime WAV；
- ASR buffer / durable output / waveform branch 保持不变。

### P3-B — recording truth first fan-out

正式数据路径：

```text
AudioRecord
  → formal WAV writer（必须成功）
  → best-effort realtime canonical bridge
  → Stage 16A streaming ASR
```

- realtime bridge 使用 bounded canonical source；
- ASR overflow / drop / bridge failure 不得回滚已经写入的 formal PCM；
- synthetic realtime failure 自动化已证明正式 WAV 仍完整保存；
- bridge 自动化覆盖 backpressure/overflow 与 epoch-local boundary。

### P3-C — phone realtime runtime / persistence

- 新增 phone-specific realtime runtime，不复用 recorder-card BLE lifecycle owner；
- realtime model availability 在 microphone capture 启动前校验；
- model missing/unloadable 时阻止 `手机实时转写` 启动，不静默退化为 `手机录音`；
- realtime run 使用 `audioSourceType=PHONE_MICROPHONE`；
- run 创建时 `recordingId=null`，formal WAV 入库后再 attach，满足现有 Room FK；
- Pause 使用 exact epoch-local canonical boundary 收口 recognizer；
- realtime runtime failure after recording start 只降级 realtime，formal recording 继续；
- Stop 先完成正式 WAV，再结束 realtime、attach recording、finalize run、投影到现有 Transcription 产品模型。

### P3-D — Pause/Resume canonical timeline correctness

发现并修复一个 P3 正确性问题：realtime ASR 在新 epoch 把 sample index 重置到 0，而正式 WAV 把多个 epoch 无静音连续拼接；若直接投影 epoch-local index，第二 epoch 文本会错误指向 WAV 开头。

当前规则：

- 利用 Room v13 已有 `PAUSE` / `RESUME` timeline markers 证明各 epoch 的真实 media length；
- 计算 epoch-local sample → canonical WAV absolute sample offset；
- 证据完整时允许精确 `CANONICAL_WAV` alignment；
- marker 缺失、顺序矛盾、epoch 不匹配或样本越界时，禁止伪造 canonical 对齐，退回 `SEGMENTED_CAPTURE`；
- 无 Room schema / migration 变更。

自动化覆盖：

- 单 epoch offset=0；
- Pause/Resume 后 epoch2 正确偏移；
- RESUME 缺失 PAUSE 证据时拒绝 canonical alignment；
- terminal sample count 与 durable boundary 不一致时拒绝 alignment；
- Room integration 验证 epoch2 最终 `TranscriptSegmentEntity` 真正落在正式 WAV 后半段；
- Room integration 验证缺失 PAUSE 证据时最终 transcription profile 降级为 `SEGMENTED_CAPTURE`。

验证：

- P3-C visibility compile fix：`b268fed7d49eab081e0749fdea503a41602cb778`；
- #1471 首次因 runner NDK ZIP 损坏在 Gradle 前失败，原 job rerun 后 `Unit tests and debug compile gate` SUCCESS；该基础设施失败未修改生产代码/CI；
- P3-D source candidate：`942fcdb176cec99f77083ac4a45d61f83c2c7c08`；
- Android PR CI #1475 — SUCCESS；
- Unit tests and debug compile gate — SUCCESS。

## P4 — UI / UX / Product Entry

已实现并通过 CI：

- realtime reader 改为 source-neutral `RealtimeTranscriptionUiController`，不再把 recorder-card runtime 作为 reader 唯一控制类型；
- recorder-card Stage 16A 历史入口通过薄适配保持既有行为；
- 新增两个独立产品入口：`手机录音`、`手机实时转写`；
- 两个手机入口在 BLE 未连接和已连接时都可用，不依赖录音卡连接状态；
- `手机录音` 展示正式媒体时长、16 kHz / mono、waveform、Pause / Resume / Stop；
- `手机实时转写` 复用 Stage 16A reader / waveform / PARTIAL / STABLE / FINAL / controls；
- 两个入口共用 P1 permission policy：首次申请、rationale、永久拒绝、Settings recovery；
- application-scope phone recording/realtime runtime 在 `AppContainer` 统一持有，Compose 不创建 `AudioRecord`；
- phone recording 与 phone realtime 共用同一个正式录音 runtime，保持单一 AudioRecord owner；
- realtime runner 在正式录音已开始后失败时，UI 投影为 `录音仍在继续 · 实时转写已停止`，仍保留 Pause / Resume / Stop，不把正式录音误判为结束；
- Stage 16A recorder-card notification overlay / deep-link 在 P4 未改动，P5 再独立接手机后台导航；
- Room v13 / version / production model channel 均未修改。

验证：

- P4-A source-neutral reader：Android PR CI #1480 — SUCCESS；
- recording-only degraded state：Android PR CI #1484 — compile/unit SUCCESS；
- P4-B full AppContainer + permission + two-entry wiring：Android PR CI #1489 — SUCCESS；
- #1489 `Unit tests and debug compile gate` — SUCCESS。

P4 CI 只证明代码/单测/Debug 编译通过；手机麦克风真实权限、Home、screen-off、notification、route / contention 仍必须在 P5/P6/P7 真机验证。

## 当前明确未完成

- P5：notification deep link / Home / screen-off 完整后台生命周期产品化；
- P6：abnormal process interruption staging recovery、mic contention / route resilience；
- P7：Stage 16A.1 真机 S1–S15；
- Freeze / Handoff / merge。

进程异常恢复不得在 P4 被伪装为已完成；正式实现继续在 P6 收口，并执行 S13。

## 下一门禁

1. 进入 P5 background lifecycle；
2. microphone FGS / WakeLock 必须独立于 ASR 生命周期，ASR 停止不能终止仍在进行的正式录音；
3. 增加手机录音 / 手机实时转写 notification 与 deep-link，并保持 recorder-card Stage 16A 历史 deep-link 不回归；
4. 验证 Home / screen-off 生命周期代码路径，但真机 PASS 仍留给 P7；
5. P5 CI 通过前不进入 P6；
6. CI 不能替代真机 S1–S15。
