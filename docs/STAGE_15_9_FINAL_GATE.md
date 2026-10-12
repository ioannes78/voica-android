# Stage 15.9 — Stability / Final Gate

状态：**需求已确认 / 开发规划已确认 / S1–S4 真机 PASS / QA1 Cross-generation Accounting CI PASS / 待 S5–S8 重测**

## 1. 目标

Stage 15.9 不新增 BLE / codec / canonical media 能力。它只建立统一 QA 测试面，并用同一候选版本完成 Stage 15 最终稳定性证明：

```text
QS668 / CB08
→ TYPE1 realtime transport
→ 4 × 40B Opus
→ persistent decode
→ PCM16 16 kHz mono
→ canonical lifecycle / timeline
→ recording / realtime reconciliation
```

进入 Stage 16 前必须证明该链路在真实 Android 前台、后台、熄屏、重连和长时间运行下稳定、可恢复，并且不伪造连续媒体时间。

## 2. QA 入口

Stage 15.9 不增加桌面 launcher。

QA / releaseQa 包：

```text
设置
→ 高级设置
→ Stage 15 Final Gate
```

入口显示条件：

- debuggable build；或
- applicationId 以 `.qa` 结尾。

正式 production release 不显示该入口。

`Stage159FinalGateActivity`：

- `exported=false`；
- 无 `MAIN`；
- 无 `LAUNCHER`；
- 只从 App 内显式 Intent 打开。

所有 Stage 15 历史 Probe 继续保持 internal / non-launcher。

## 3. Final Gate 原则

Final Gate 只调用现有 production API：

- `RealtimeAudioRuntime.start/pause/resume/stop`；
- `DeviceRepository.startRecording/saveRecording`；
- `DeviceRepository.disconnect/connect`；
- `syncRecordingState/refreshDeviceFiles`。

QA 页面不新增协议写入、不绕过 production guard、不持有第二个 GATT owner、不实现第二套 reconnect / FGS / decoder。

Final Gate QA session 持续消费现有 canonical source，并复用 `Stage15CanonicalProbeAccumulator` 检查：

- gap；
- overlap；
- invalid epoch start；
- SessionEnded final sample mismatch；
- drain timeout。

QA session 为 process-lifetime、仅在内部 Final Gate 首次打开后懒创建；正式 production release 没有入口，不会主动创建该 QA session。

## 4. Baseline / delta

测试必须在 runtime `IDLE` 且 canonical source inactive 时建立 baseline。

Baseline 固定：

- runtime / ingress / pipeline / source counters；
- canonical observer counters；
- reconciliation counters；
- device file count；
- transport generation；
- heap used；
- monotonic test start time。

后续结果全部输出 baseline-relative delta，避免把前一轮测试累计值误判成本轮异常。

### 4.1 QA1 — Cross-generation Counter Accounting

首轮 S5 / S6 真机暴露的是 Final Gate 诊断统计问题，不是 production realtime media failure：

- S5 reconnect 后 Activity-local baseline 丢失，结果为 `NO_BASELINE`；
- S6 transport generation 从 2 进入 3 后，新的 ingress / pipeline / command counters 从 0 重建，旧实现仍直接执行 `newCounter - oldBaseline`，因此出现 `acceptedBlocks=-33`、`opusPackets=-132`、`canonicalSamples=-42240`；
- 同轮 production 证据仍显示 owner / decoder 正确 release + recreate、source started / ended 成对、最终 runtime IDLE，且 decode / overflow / canonical gap / overlap 等 anomaly 全部为 0。

QA1 修订规则：

1. baseline、计时、heap window、canonical QA observer 与上次设备地址移入 process-lifetime QA session，不再由单个 Activity instance 持有；
2. Activity recreate / reconnect 不得清空同一轮 Final Gate baseline；
3. transport generation-owned counters 单独累计；generation 切换时先封存旧 generation delta，再从新 runtime 的 0 基线继续累计；
4. source / canonical observer / reconciliation / file count 等 process-lifetime counters 仍按原 baseline 直接做差，不被 transport generation 累计重复计算；
5. 输出新增：

```text
generationAccounting baselineGeneration=...
currentGeneration=...
generationTransitions=...
mediaDeltaAcrossGenerations=...
```

6. 单元 contract 固定覆盖：generation 1 累计 100 blocks，reconnect 后 generation 2 累计 40 blocks，最终必须为 140，不能出现 -60；
7. QA1 不修改 BLE protocol、RealtimeAudioRuntime production lifecycle、Opus decoder、canonical media algorithm、Room、production model channel 或 app version。

QA1 代码候选在 branch HEAD `b67475dbb109cfd050cdef69f984ac5f558dfa2e` 已通过 Android PR CI #1323：Fast PR Gate、unit/debug compile、production-like releaseQa build、APK verify/upload 均成功。文档提交后仍需以新的最终 HEAD CI 为准。

## 5. Memory / queue sampling

Memory 使用 process Java heap：

```text
used = Runtime.totalMemory - Runtime.freeMemory
max = Runtime.maxMemory
```

稳定性计时期间每 30 秒采样一次，只保留：

- start；
- current；
- peak；
- max。

不保存无界历史数组。

Queue 直接读取 production runtime snapshot：

- ingress current / peak blocks + bytes；
- canonical source current / peak PCM frames + samples。

Performance 读取：

- `maxBlockProcessingMs`；
- `maxArrivalToEmitLagMs`。

## 6. Normal-window anomaly gate

没有人工故障注入的连续运行窗口要求 delta：

```text
ingressOverflow = 0
malformedBlocks = 0
droppedBlocks = 0
decodeFailures = 0
invalidOpusPackets = 0
zeroSampleDecodeFailures = 0
sourceOverflow = 0
sourceDroppedFrames = 0
canonicalGap = 0
canonicalOverlap = 0
invalidEpochStart = 0
sessionEndMismatch = 0
drainTimeoutEnds = 0
reconciliationFailure = 0
```

断线 / Bluetooth off 属于显式 fault gate，不要求 discontinuity/boundary 为 0；反而要求断线事实形成明确 lifecycle boundary，并且重连后的媒体进入新 generation / new session，不拼接旧媒体时间轴。

## 7. Short Matrix

### S1 — START / STOP

```text
START → speak ~10s → STOP
```

要求：PCM 增长、无 normal-window anomaly、STOP 后 IDLE / gate closed / source inactive / lastEnd=STOP / drainTimedOut=false。

真机状态：**PASS**。acceptedBlocks / canonicalSamples 正常增长，normal-window anomaly 全 0，最终 IDLE / gate closed / source inactive / `lastEnd=STOP`。

### S2 — PAUSE / RESUME

```text
START → speak ~5s → PAUSE → wait ~3s → RESUME → speak ~5s → STOP
```

要求：同一个 realtime session；RESUME 产生新 canonical epoch/control boundary；不按暂停 wall clock 补媒体时间。

真机状态：**PASS**。同一 session 内 `epochs=2`、`discontinuities=1`、`control boundary=1`，gap / overlap / invalidEpochStart 全 0。

### S3 — second START

```text
START → STOP → START → STOP
```

要求：第二 session 有新 session identity，PCM 正常增长并最终释放。

真机状态：**PASS**。`start=2`、`stop=2`、`sessions=2`、source started / ended 均为 2，第二 session PCM 正常。

### S4 — TYPE3 SAVE reconciliation

```text
realtime START → TYPE3 SAVE
```

不手工 realtime STOP。要求自动 IDLE，`lastEnd=DEVICE_RECORDING_ENDED`，reconciliation completed +1。

真机状态：**PASS**。runtime / app reconciliation 均 completed +1、failure=0，自动 IDLE，`lastEnd=DEVICE_RECORDING_ENDED`，device file delta=1。

### S5 — user disconnect / reconnect

realtime ACTIVE 时使用 production `disconnect()`，再使用上次 Ready address 调 production `connect()`；重连后再次 START。

要求旧 session 明确结束；新 transport generation / new session；不跨断线拼 sample timeline。

首轮状态：**INVALID / 需 QA1 后重跑**。production owner / decoder release/recreate 正常、最终 IDLE 且 anomaly=0，但 Activity-local baseline 在 reconnect 后丢失，无法形成有效 gate 证据。

### S6 — Bluetooth off / on

realtime ACTIVE 时关闭系统 Bluetooth；重新开启并 reconnect / START。

要求同 S5。

首轮状态：**INVALID / 需 QA1 后重跑**。generation 2→3、owner / decoder release/recreate、source started / ended 成对且 anomaly=0；旧统计算法跨 generation 直接相减产生负 media delta，证据口径无效。

### S7 — background

realtime ACTIVE → Home → 后台约 3–5 分钟 → 回前台。

要求 connectedDevice lifecycle 正常，acceptedBlocks / canonicalSamples 持续增长，无重新 START 才恢复的假死。

首轮状态：**SHORT SANITY PASS / 完整 3 分钟 Gate 未执行**。37 秒窗口 media 正常增长、anomaly=0、最终 IDLE；不能代替正式 3–5 分钟 gate。

### S8 — screen off

realtime ACTIVE → 熄屏约 3–5 分钟 → 点亮。

要求 session 持续、PCM 持续增长、无异常 counters。

首轮状态：**SHORT SANITY PASS / 完整 3 分钟 Gate 未执行**。52 秒窗口 media 正常增长、anomaly=0、最终 IDLE；不能代替正式 3–5 分钟 gate。

### 7.1 QA1 后重测范围

S1–S4 证据继续有效，不要求重复执行。

QA1 releaseQa 只重跑：

```text
S5 — production disconnect / reconnect
S6 — Bluetooth off / on
S7 — background >= 3 min
S8 — screen off >= 3 min
```

S5 / S6 额外要求：

- baseline 在 reconnect / Activity recreate 后仍存在；
- `generationTransitions >= 1`；
- `mediaDeltaAcrossGenerations=true`；
- acceptedBlocks / opusPackets / canonicalSamples 等跨 generation delta 均为非负且有实际增长；
- old session 明确结束，新 generation / new session 正常 START；
- normal-window anomaly 仍为 0。

## 8. Development stability gate

S1–S8 全部通过后，连续 realtime 10–15 分钟；至少包含一次后台和一次熄屏。

结束：STOP → 等待 IDLE → 再 START ~10 秒 → STOP。

通过后才锁定 Stage 15 Final Stability Candidate。

## 9. Candidate lock

Candidate lock 后：

- 固定 commit SHA；
- 固定 production-like releaseQa APK；
- 固定统计口径；
- 30 / 60 / 120 分钟全部使用同一候选。

如任何生产代码变化，则受影响长测证据失效并重跑。

## 10. Long gates

### 30 min

正常连续 realtime，可包含前台 / 后台 / 熄屏。

### 60 min

同一 candidate，包含较长后台与熄屏运行。

### 120 min Final

同一 candidate，验证长时间 BLE notification、persistent Opus decoder、canonical queue、FGS、heap / lag 与 Android process 稳定性。

每轮结束：

```text
STOP
→ runtime IDLE
→ 等数秒
→ START ~10s
→ canonicalSamples 增长
→ STOP
```

## 11. File side effect

TYPE1 START 已确认会创建 device recording file。

Final Gate 记录：

- baseline file count；
- current / final file count；
- delta。

最终快照前必须执行一次“刷新设备事实”，避免 file count 使用旧列表缓存。

测试工具不自动删除设备已有文件。

## 12. Freeze gate

只有同时满足：

```text
S1–S8 PASS
10–15 min PASS
30 min PASS
60 min PASS
120 min PASS
final restart PASS
Final CI PASS
```

且用户明确回复 `测试通过`，才允许：

- 创建 `docs/STAGE_15_FREEZE.md`；
- 创建 `docs/STAGE_15_HANDOFF.md`；
- PR #27 转 Ready；
- Final production gate；
- merge main。

merge 后重新核验 main / version / Room / production model pin，再解锁 Stage 16。
