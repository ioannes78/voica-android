# Stage 16A.1 Progress — 手机录音 / 手机实时转写

日期：2026-10-15

状态：**P1 CI PASS / P2 CI PASS / P3 CI PASS / P4 NEXT**

## 当前 GitHub 基线

- base `main`：`075bc9689b011678685e1046e3590a26d787067f`
- branch：`stage16a1-phone-microphone`
- PR：#29（Draft / WIP）
- P3 source candidate：`942fcdb176cec99f77083ac4a45d61f83c2c7c08`
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

## 当前明确未完成

- P4：`手机录音` / `手机实时转写` 用户入口、共享 realtime reader、权限 UX、状态展示；
- P5：notification deep link / Home / screen-off 完整后台生命周期产品化；
- P6：abnormal process interruption staging recovery、mic contention / route resilience；
- P7：Stage 16A.1 真机 S1–S15；
- Freeze / Handoff / merge。

进程异常恢复不得在 P3 被伪装为已完成；正式实现继续在 P6 收口，并执行 S13。

## 下一门禁

1. 进入 P4 UI / UX；
2. P4 必须提供两个独立产品入口：`手机录音`、`手机实时转写`；
3. `手机实时转写` 复用 Stage 16A reader，但不得把 recorder-card runtime 类型继续暴露为 UI 唯一控制接口；
4. 在 P4 有真实用户入口并通过 CI 前，不把 S1–S4 写成已执行；
5. CI 不能替代真机 S1–S15。
