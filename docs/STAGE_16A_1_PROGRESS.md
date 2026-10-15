# Stage 16A.1 Progress — 手机录音 / 手机实时转写

日期：2026-10-15

状态：**P1–P6 CI PASS / P7 QA CANDIDATE NEXT**

## 当前 GitHub 基线

- base `main`：`075bc9689b011678685e1046e3590a26d787067f`
- branch：`stage16a1-phone-microphone`
- PR：#29（Draft / WIP；进入 P7 时切换 `[APK]` QA 标题）
- P6 final source candidate：`ab23a468e145dec9f489a6d100f015e59911f1a6`
- Room：v13，未新增 migration
- versionCode：91
- versionName：`1.1.0-stage16a`
- production model channel：未修改

## P0 — Requirements / Contract / Docs

已完成：

- 产品入口拆为 `手机录音` / `手机实时转写`；
- 两者共用一套 `PhoneMicrophoneCapture`；
- recording truth > ASR truth；
- Requirements / Development Plan / Product Plan 已收口；
- `AGENTS.md` 当前 Stage 接管状态已刷新，历史冻结事实保持不变。

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

## P2 — Recording Truth / Formal WAV / Library

已实现：

- 手机正式录音主路径同步写 `RealtimePcmWavSegmentWriter`；
- 正式 WAV 不经过 Stage 16A bounded ASR side-sink；
- Pause 不写 silence，Resume 创建新的 `CONTROL_BOUNDARY` epoch；
- 多 epoch 连续写入同一正式 WAV；
- Stop patch RIFF / fsync / atomic finalize；
- 0 PCM sample 不注册空 WAV；
- `RecordingSourceType.PHONE_MICROPHONE` + `CANONICAL_WAV`；
- 复用 Recording Library / SearchIndex / Playback / PcmSourceResolver 生命周期；
- immediate second recording 与 formal WAV 注册自动化覆盖。

验证：

- Android PR CI #1465 — SUCCESS；
- Unit tests and debug compile gate — SUCCESS。

## P3 — Stage 16A Realtime Integration

已实现：

- source-neutral Stage 16A pipeline reuse；
- phone realtime 禁用 Stage 16A 第二份 local WAV，正式 WAV 仍是唯一 recording truth；
- 数据顺序固定为 `AudioRecord → formal WAV writer → best-effort realtime bridge → Stage 16A ASR`；
- bridge bounded overflow / ASR failure 不回滚 formal PCM；
- phone-specific realtime runtime，不复用 recorder-card BLE lifecycle owner；
- 启动前模型不可用时拒绝 realtime 启动；
- 已开始后 ASR runtime 异常只降级 realtime，正式录音继续；
- realtime run 使用 `PHONE_MICROPHONE`，Stop 后 attach formal recording；
- Pause/Resume 使用 epoch-local boundary，并把 transcript 时间轴精确投影到连续 canonical WAV；证据不足时退回 `SEGMENTED_CAPTURE`，不伪造 canonical alignment。

验证：

- #1471 runner NDK ZIP 基础设施失败，原 job rerun 后 compile/unit SUCCESS；
- Android PR CI #1475 — SUCCESS；
- Unit tests and debug compile gate — SUCCESS。

## P4 — UI / UX / Product Entry

已实现：

- realtime reader 抽成 source-neutral `RealtimeTranscriptionUiController`；
- recorder-card Stage 16A 通过薄适配保持既有行为；
- 首页新增独立 `手机录音` / `手机实时转写`；
- BLE 未连接/已连接均可使用手机入口；
- 手机录音页：时长、来源、waveform、Pause / Resume / Stop；
- 手机实时转写页：复用 Stage 16A reader、waveform、PARTIAL / STABLE / FINAL / controls；
- 两个入口共用 permission policy；
- application-scope runtime 持有 capture/realtime lifecycle，Compose 不创建 `AudioRecord`；
- realtime runner 异常后 UI 保留正式录音控制并显示 `录音仍在继续 · 实时转写已停止`。

验证：

- Android PR CI #1480 — SUCCESS；
- Android PR CI #1484 — compile/unit SUCCESS；
- Android PR CI #1489 — SUCCESS；
- #1489 `Unit tests and debug compile gate` — SUCCESS。

## P5 — Background Lifecycle / Notification / Deep Link

已实现并通过 CI：

- microphone FGS 继续作为正式手机 capture 的后台 owner；
- capture-scoped WakeLock 与 Stage 16A ASR execution lease 分离；
- formal capture 的 terminal `finally` 统一释放 AudioRecord / microphone FGS / capture WakeLock；
- notification target 明确区分 `手机录音` / `手机实时转写`，不靠文案推断；
- notification PendingIntent 固化 product target；
- `MainActivity` 增加 phone live overlay，并与 recorder-card realtime overlay 互斥；
- notification 点击可回到对应手机 live 控制页；
- Activity recreation 不拥有 capture lifecycle；
- `POST_NOTIFICATIONS` 不作为麦克风录音启动前置条件；通知权限被拒绝不能错误阻断 formal recording；
- recorder-card `AppRealtimeNavigation` 历史路径保持独立。

验证：

- Android PR CI #1495 — SUCCESS；
- `Unit tests and debug compile gate` — SUCCESS。

Home / screen-off / 通知点击仍属于 P7 真机验证，不能由 CI 写成 PASS。

## P6 — Resilience / Contention / Recovery

已实现并通过 CI：

### P6-A — abnormal media recovery

- startup recovery 只扫描 `recordings/phone/<recordingId>/phone_*.wav.part`；
- 根据实际 PCM payload/block alignment 修复 RIFF 长度；
- 合法 staging fsync 后 promote 为 WAV，并以 `手机录音（异常恢复）` 注册；
- 44B/header-only staging 丢弃，不制造空录音；
- 已注册 recordingId 跳过，避免重复入库；
- Room lookup 失败时保留媒体并记录 recovery failure，不把数据库异常当作“未注册”；
- malformed staging 保留供诊断，不伪造可播放媒体。

### P6-B — live capture interruption truth

- capture 已 FAILED / unexpected STOPPED/CLOSED 时，用户 Stop 仍保存中断前可用 WAV；
- 该 Stop 返回 abnormal result，不得伪装正常 `Saved/COMPLETED`；
- 入库显示名明确标记 `手机录音（异常中断）`；
- 如果用户 `失败 → 暂停 → 继续` 恢复成功，最终录音仍保留曾发生 interruption 的 provenance；
- pure recording UI 直接观察 capture StateFlow，失败时显示 `采集中断`，waveform 停止活动但保留恢复/停止控制；
- phone realtime reader 同样观察 capture state，mic failure 时显示 `麦克风采集中断`，不会仅表现为“波形冻结”。

### P6-C — process-death realtime reconciliation

- process death 后旧 `PHONE_MICROPHONE` realtime `ACTIVE/FINALIZING` run 在 startup 标记 `INTERRUPTED`；
- ACTIVE realtime segment 同步标记 `INTERRUPTED`；
- 已持久化文字保留；
- 不猜测把 stale run attach 到恢复 WAV；
- 恢复 WAV 独立以 `异常恢复` 进入录音库，避免伪造 realtime ↔ recording lineage；
- recorder-card realtime runs 不受该 reconciliation 影响；
- Room 仍为 v13，无 migration。

验证：

- P6 第一批 recovery/interruption：Android PR CI #1501 — SUCCESS；
- P6 final source candidate：`ab23a468e145dec9f489a6d100f015e59911f1a6`；
- Android PR CI #1518 — SUCCESS；
- #1518 `Fast PR Gate` — SUCCESS；
- #1518 `Unit tests and debug compile gate` — SUCCESS。

## P7 — QA / Candidate Closure

下一步：生成 production-like QA APK，然后严格按用户要求**每次只执行一个真机测试项**。

测试矩阵：

```text
S1  手机录音：Start → Stop → WAV 可播放 / Library 正常
S2  手机录音：Pause / Resume，暂停时间不进入 WAV
S3  手机录音：Home/background 持续
S4  手机录音：screen-off 持续
S5  手机实时转写：Small realtime
S6  手机实时转写：Stop → WAV + durable Transcript
S7  手机实时转写：Pause / Resume / CONTROL_BOUNDARY
S8  artificial slowdown 1.2s / 2.5s：ASR 可 overflow，正式录音零丢样
S9  Chinese Large CTC realtime
S10 realtime model missing/unavailable
S11 RECORD_AUDIO 首次拒绝/重试/永久拒绝/Settings recovery
S12 immediate second recording
S13 process interruption → staging recovery，不伪造正常完成
S14 microphone contention / camera/VoIP / basic route change
S15 30-minute phone recording stability，优先 Small realtime + screen-off
```

S1–S4 必须在不依赖 realtime model 的条件下可通过。

S8 核心证据：

```text
capture samples
= primary recording accepted samples
= finalized WAV samples
```

允许 `ASR dropped/overflow samples > 0`，但必须 `recording dropped samples = 0`。

## Freeze / Handoff Gate

当前**不得**创建 Freeze/Handoff，也不得 merge。

只有同时满足以下条件才允许：

1. QA candidate CI / APK gate 全绿；
2. S1–S15 按用户真实执行/豁免结果准确记录；
3. 未执行项不得写成 PASS；
4. 用户明确确认 Stage 16A.1 整体测试通过；
5. 记录最终 source HEAD / version / Room / CI / artifact / model pin；
6. 然后才允许 Freeze / Handoff / merge。
