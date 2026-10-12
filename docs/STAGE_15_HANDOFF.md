# Stage 15 Final Handoff — BLE 实时音频链路 → Stage 16A 本地实时 ASR

状态：**FINAL HANDOFF — Stage 15 用户验收完成；待 Final CI / production gate / merge**

冻结文档提交：`99a207960b3e79078728698c4e8302faa9d8829f`

## 1. 接管目标

下一阶段是：

**Stage 16A — 本地 true streaming ASR**

Stage 16A 开始任何需求修订、开发规划或编码前，必须重新核验 GitHub 当前：

1. `main` HEAD；
2. PR #27 是否已经 merged；
3. 当前 open PR；
4. 最新 GitHub Actions / CI；
5. `AGENTS.md`；
6. `docs/STAGE_15_FREEZE.md`；
7. `docs/STAGE_15_HANDOFF.md`；
8. `docs/STAGE_16_STREAMING_CONTRACT_V2.md`；
9. `docs/ROADMAP_STAGE_13C_PLUS.md`；
10. app versionCode / versionName；
11. Room 当前版本、migration、schema files；
12. production model channel pin / manifest version / digest；
13. 当前 realtime canonical source/runtime 代码与 tests。

GitHub 当前仓库继续是唯一事实来源；本文件中的 SHA / CI / version 只作为 Stage 15 交接线索。

## 2. Stage 15 最终候选事实

Final Freeze 前的已验证 source branch HEAD：

`cbdc3f6a44e5c3eb9d68b415603af29b9473dba7`

对应 production-like releaseQa 真机候选：

- PR merge-candidate / BuildConfig commit：`99c2d809bf7830cbf95bfd4306e4e5f04051119d`
- versionCode：`90`
- versionName：`1.1.0-stage15`
- releaseQa：`1.1.0-stage15-export-qa`
- applicationId：`io.github.ioannes78.voica.qa`
- APK SHA-256：`18121240a83e8df9231f51a7c1824a9fa1d0eea5ecbf4a3eaec6e52180a131b5`
- CI artifact id：`11721457371`
- artifact digest：`sha256:a7338a6cd7a493b2f03673460a8fe475cae8b380191e9673b3fadd93f05910c8`

Pre-freeze latest Android PR CI：#1324 / run `38190133961` — SUCCESS。

Freeze/Handoff 写入后必须以新的最终 HEAD 重新跑 Final CI，因此 Stage 16A 接管时不得只依赖 #1324。

## 3. Stage 15 frozen realtime media contract

Stage 16A 的输入必须来自 Stage 15 canonical seam：

```text
RealtimeCanonicalAudioSource
→ RealtimeCanonicalStreamEvent
```

Stage 16A 不得直接依赖：

- BLE UUID；
- Android `BluetoothGatt`；
- MTU；
- TYPE1 raw frame；
- 160B transport block；
- 40B Opus packet；
- protocol SEQ；
- persistent decoder implementation；
- transport generation internal mechanics。

这些都属于 Stage 15 transport/media ownership。

## 4. Canonical event contract

Stage 16A 需要处理：

- `SessionStarted(sessionId)`；
- `EpochStarted(sessionId, epochId, reason)`；
- `PcmFrame(frame)`；
- `ConsumerDiscontinuity(...)`；
- `SessionEnded(...)`。

Canonical PCM 固定：

- 16 kHz；
- mono；
- PCM16；
- sample timeline 是媒体时间真值。

Stage 16A 不得使用 wall clock / BLE arrival timestamp / protocol SEQ 替代 canonical sample timeline。

## 5. Session / epoch 对 streaming ASR 的含义

Stage 16A 必须把 Stage 15 生命周期边界映射为 deterministic recognizer lifecycle。

最低要求：

- `SessionStarted`：建立新的 streaming-ASR recording session context；
- `EpochStarted(SESSION_START)`：开始该 session 的首个连续 media epoch；
- `EpochStarted(CONTROL_BOUNDARY)`：不得静默把 pause/resume 前后的 session-relative timeline 当成无边界连续输入；
- `EpochStarted(DISCONTINUITY)`：必须 finalize/reset/cancel 当前 recognizer continuity；
- `EpochStarted(DECODER_RECOVERY)`：同样视为不可静默跨越的 continuity reset；
- `ConsumerDiscontinuity`：慢 consumer 已丢 stale PCM，ASR 必须明确结束/重置受影响连续上下文，不得假装音频完整；
- `SessionEnded(STOP)`：正常 finalize；
- `SessionEnded(DEVICE_RECORDING_ENDED)`：设备 recording 已结束，应确定性 finalize；
- superseded / transport termination 等其它 end reason：不得生成伪连续 FINAL。

具体 PARTIAL / STABLE / FINAL 规则必须遵循 `docs/STAGE_16_STREAMING_CONTRACT_V2.md`。

## 6. Stage 16 Streaming Contract V2 继续冻结

Stage 16A 仍必须遵循 Stage 13A 已冻结的 V2 contract：

- canonical input = 16 kHz / mono / PCM16；
- true streaming 使用 `StreamingAsrEngine` / `StreamingAsrSession`；
- `AsrHypothesisStability` = PARTIAL / STABLE / FINAL；
- token timing 是 session-relative，再映射到 canonical absolute sample truth；
- 不可靠 timing 不得伪造；
- discontinuity 必须 deterministic finalize/reset/cancel；
- PARTIAL/STABLE 不得持久化成 FINAL；
- 不得建立第二套 incompatible streaming session API。

当前冻结 realtime product matrix：

- Small Bilingual Zipformer zh-en INT8 — light / default；
- Chinese Large CTC INT8 — high-quality Chinese realtime option。

不得静默恢复：

- Chinese Large Transducer；
- FireRedASR2；
- AUTO model routing。

SenseVoice / Qwen3-ASR 属于文件 offline ASR，不得通过 chunking 伪装为 true streaming engine。

## 7. Stage 15 transport/media frozen facts

### Framing

- TYPE1/CMD1 realtime body = 160B；
- 160B = 4 × 40B Opus；
- 每 40B packet 正常 20ms；
- decode 正常输出 320 samples @ 16k mono；
- protocol SEQ realtime 恒 0，不参与 media ordering/timeline。

### Ingress

- GATT callback 只做 parse/classify/bounded offer；
- decode / PCM / ASR 不在 callback；
- ingress overflow / generation close 形成 explicit continuity boundary。

### Canonical queue

- bounded 32 frames / 10,240 samples；
- 慢 consumer 不反压 decoder/GATT；
- overflow 丢 stale PCM + `ConsumerDiscontinuity` + live edge。

### STOP drain

- quiet window 250ms；
- hard cap 1500ms；
- drain timing 不推进 sample index；
- timeout 通过 `drainTimedOut` 明示。

Stage 16A 不得绕过这些边界自行从 BLE 重建一套 audio path。

## 8. Recording / realtime lifecycle

Stage 15 真机确认 TYPE1 与 TYPE3 设备 lifecycle 不是完全独立：

- TYPE1 START 会让设备进入 Recording；
- TYPE1 STOP 可让设备 Recording → Idle；
- realtime active 时 production TYPE3 SAVE 可终止 device recording。

因此 Stage 15.8 已建立 `RealtimeRecordingLifecycleReconciler`，并冻结：

- FRESH Recording/Paused → Idle；
- active realtime 自动 reconciliation；
- canonical end reason `DEVICE_RECORDING_ENDED`；
- best-effort STOP + fallback local drain；
- exactly one SessionEnded；
- TYPE3-only 不启动 realtime；
- explicit STOP 不递归 reconciliation。

Stage 16A 应消费 canonical lifecycle 结果，不重复实现 recording-state reconciliation。

## 9. 真机 QA 交接

Stage 15.9 short matrix：

```text
S1 START / STOP                         PASS
S2 PAUSE / RESUME                       PASS
S3 second START                         PASS
S4 TYPE3 SAVE reconciliation            PASS
S5 app disconnect / reconnect           PASS
S6 Bluetooth OFF / ON                   PASS
S7 background 3m33s                     PASS
S8 screen-off 4m32s                     PASS
```

Development Stability Gate：13m23s PASS，并验证 long-run STOP 后 restart recovery。

Candidate sustained run：29m50s，anomaly=0、heap/queue 无明显持续增长；经用户明确接受作为 30-min candidate evidence。

## 10. 60 / 120 分钟豁免与剩余风险

用户最终明确确认：

> 测试通过，60/120分钟测试跳过并接受风险

所以：

```text
60 min Stability Gate = NOT RUN / WAIVED BY USER
120 min Final Soak Gate = NOT RUN / WAIVED BY USER
```

绝不能在后续文档中写成 PASS。

Stage 16A / 后续长时测试应记住以下尚未被 Stage 15 soak 证明的风险：

- >1h BLE notification 稳定性；
- 长时间 background / Doze + FGS / BLE lifecycle；
- 2h persistent Opus decoder；
- 2h heap / queue / latency 慢性漂移；
- 低频 OEM process / Bluetooth stack 长时行为。

这些是“未执行的 soak 风险”，不是当前已观察到的生产缺陷。

## 11. 数据 / 模型 /版本边界

Stage 15 没有改变：

- Room schema v12；
- migration lineage 1..12；
- production model channel commit `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`；
- production manifestVersion 7；
- production manifestDigest `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`。

Stage 16A 如果需要 version / Room / model-channel 变化，必须作为 Stage 16A 明确需求重新确认，不能借 Stage 15 Freeze 静默推进。

## 12. QA / diagnostics

Stage 15 Final Gate：

- QA / releaseQa 内部入口；
- production release 隐藏；
- non-launcher / exported=false；
- process-lifetime baseline 支持 cross-generation accounting；
- 仅用于 Stage 15 诊断，不是 Stage 16 产品 UI。

Stage 16A 可以保留它作为 transport/canonical 回归工具，但不得把 Final Gate 页面改造成 streaming ASR 产品页面。

## 13. 与 voice-card-android 隔离

硬性规则继续有效：

不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的实现。

Stage 16A 必须在 Voica 当前仓库的 canonical seam 和 frozen streaming contract 上独立实现。

## 14. Stage 16A 第一轮工作顺序

建议固定：

```text
重新核验 main / PR / CI / version / Room / model pin
→ 阅读 Stage 15 Freeze/Handoff
→ 阅读 Streaming Contract V2
→ 核验现有 StreamingAsrEngine / Session runtime
→ 核验 Stage 15 canonical source consumer semantics
→ 输出 Stage 16A 修订需求
→ 用户确认
→ 输出 Stage 16A 开发规划
→ 用户确认
→ 再编码
```

在确认需求与规划前，不修改 Stage 16A production code。

## 15. Stage 15 GitHub 收口待办

本 Handoff 创建后当前 Stage 15 仍需：

1. Final CI on final Freeze/Handoff HEAD；
2. PR #27 Ready；
3. Android Production Gate SUCCESS；
4. merge `main`；
5. merge 后重新核验 main / version / Room / production model pin。

完成后 Stage 15 才从“验收完成”进入“主线冻结完成”。
