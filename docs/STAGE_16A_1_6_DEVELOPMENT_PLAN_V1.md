# Stage 16A.1.6 — Android Audio I/O Compatibility 开发规划 V1

状态：**APPROVED / DEVELOPMENT AUTHORIZED**

日期：2026-10-16

## 1. 开发目标

本阶段不建立新的录音或播放系统，而是在现有架构上增加 Android Audio I/O compatibility layer。

```text
Android Audio Input
  ├─ Built-in mic
  ├─ Wired mic
  ├─ USB mic
  └─ supported Bluetooth mic
            ↓
     ONE AudioRecord owner
            ↓
 canonical PCM16 / 16 kHz / mono
            ↓
      Formal WAV truth
            ↓
   optional realtime ASR
```

播放保持：

```text
Recording Library
      ↓
ServiceBackedPlaybackController
      ↓
ONE AndroidPlaybackController
      ↓
StreamingPlaybackController
      ↓
ONE AudioTrack sink
      ↓
Android system media route
```

QS668 / CB08 BLE GATT 继续属于独立 transport domain。

## 2. 开发分支与基线

开发分支：

`stage16a1_6-android-audio-io-compatibility`

创建前重新核验 `main`，实际 base：

`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`

创建前无 open PR，且不存在同名开发分支。

开发过程中任何合并/重基线前必须再次核验 `main`。

## 3. P0 — Stage contract / 文档与基线锁定

新增：

- `docs/STAGE_16A_1_6_REQUIREMENTS_V1.md`
- `docs/STAGE_16A_1_6_DEVELOPMENT_PLAN_V1.md`
- `docs/STAGE_16A_1_6_PROGRESS.md`

同步：

- `AGENTS.md` 当前接管状态；
- `docs/ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md` Stage 16A.1.6 状态。

P0 不改变 production behavior。

P0 Gate：

- base SHA 准确；
- Room v13；
- model channel 未变；
- exact 9 notification channels 未变；
- Stage 16A.1 S15 继续 `NOT RUN / WAIVED BY USER`。

## 4. P1 — Audio I/O diagnostics foundation

### 4.1 Input device registry

新增 Android input-device registry，建议职责名：

`PhoneMicrophoneAudioDeviceRegistry`

来源：

`AudioManager.getDevices(GET_DEVICES_INPUTS) + AudioDeviceCallback`

runtime descriptor 至少包括：

- deviceId；
- deviceType；
- productName；
- address（允许时）；
- isSource；
- generation；
- connected。

不得加入 QS668 GATT identity。

### 4.2 Input request

新增明确模型：

- `AUTO`
- `SPECIFIC_DEVICE(deviceId)`

首版不持久化 physical deviceId 跨重启；进程重启默认 AUTO。

### 4.3 Requested 与 actual route 分离

Capture snapshot 增加：

- requestedInput；
- actualInput；
- routeGeneration；
- routeChangeCount；
- lastRouteChangeReason；
- preferredDeviceAccepted。

`setPreferredDevice() == true` 不等价于实际 routed device 已等于 requested device。

### 4.4 Signal diagnostics

增加纯观测 signal meter：

- peak sample / dBFS；
- RMS / dBFS；
- clipping sample count / ratio；
- zero sample ratio。

不得修改 PCM。

### 4.5 Source diagnostics

继续记录：

- VOICE_RECOGNITION / MIC；
- AudioSessionId；
- routed device；
- clientSilenced；
- read counters；
- buffer；
- source fallback reason。

## 5. P2 — 手机低音量 Calibration Gate

在 P2 真机 A/B 完成前：

- 不改变 production 默认 source policy；
- 不加入 software gain。

诊断/QA path 提供：

- AUTO；
- FORCE_VOICE_RECOGNITION；
- FORCE_MIC。

正式 Home 不暴露该工程选项。

A/B 要求同一手机、同一位置、同一音量、相同内容和相近环境，比较：

- RMS dBFS；
- Peak dBFS；
- noise floor；
- clipping ratio；
- 听感；
- actual route；
- ASR 结果。

source 决策不以“音量最大”单一指标决定。

如果无明确优势，维持当前 `VOICE_RECOGNITION → MIC fallback`。

如果真机证明 MIC 显著更适合，再调整 production AUTO policy。

### Software Gain Gate

只有确认 PCM 确实偏低，且 route/source/UI 无法解释时才提出 gain amendment；先提交 range/step/default/clipping protection，等待用户确认后编码。

## 6. P3 — Input device selection

手机 source 下增加轻量输入选择：

`输入设备：自动 >`

Bottom Sheet 动态显示当前 `isSource=true` 的输入设备。

### AUTO

- 不强制 preferred device；
- Android 决定 route；
- start 后读取 actual route。

### Manual

`SPECIFIC_DEVICE → setPreferredDevice() → startRecording() → getRoutedDevice() → verify`

用户明确选 USB/Bluetooth/wired mic 而 actual route 不是目标设备时，Start 必须失败；不得静默继续录 built-in mic。

## 7. P4 — Active recording route transition

在 capture 侧建立有顺序保证的 route event seam：

`detect old→new → emit route boundary → first PCM frame of new route`

不得先把新 route PCM 写入 WAV 后才补 route event。

内部原因至少区分：

- ROUTE_CHANGED；
- ROUTE_LOST；
- ROUTE_MISMATCH。

### AUTO route change

若 AudioRecord 继续有效：

- formal WAV 继续；
- 不补 silence；
- new route 首帧前建立 `DISCONTINUITY`；
- realtime recognizer continuity reset。

若 AudioRecord 必须 recreate：

`stop old → release → no overlap → create new → verify actual route → DISCONTINUITY → same formal WAV continue`

## 8. P5 — Manual selected input disconnect

用户明确选择 USB/wired/Bluetooth mic 后设备消失，不得静默回落 built-in mic。

successful read 后、formal writer 前增加：

`clientSilenced guard + manual actual-route guard`

若 actual route 已不等于 selected route：

- 该新-route PCM 不进入 WAV/waveform/ASR；
- stop/release current AudioRecord；
- 保留 formal WAV writer；
- 进入可恢复暂停状态。

用户可以重新连接、选择其它 input、切 AUTO 或停止保存。

普通 Pause/Resume 使用 `CONTROL_BOUNDARY`；route interruption recovery 使用 `DISCONTINUITY`。

## 9. P6 — Wired / USB / Bluetooth input compatibility

### Wired

覆盖：start-before-connected、hot connect、disconnect、realtime。

### USB

支持系统暴露为 source 的适用 USB device/headset。

client canonical 仍为 PCM16 / 16 kHz / mono。

只有真实设备证明 framework 无法提供该 client format 时，才单独评估 native capture → canonical adapter。

### Bluetooth

首轮只支持 Android 正常暴露给 AudioRecord 的 Bluetooth source，统一走 AudioDeviceInfo + preferred/actual route。

不在首轮自动引入 MODE_IN_COMMUNICATION、startBluetoothSco、setCommunicationDevice 或 MODIFY_AUDIO_SETTINGS。

若真实 Classic HFP/SCO 设备证明普通路径不可用，另行提交 Bluetooth Compatibility Amendment。

## 10. P7 — Realtime integration

不修改 Stage 16A ASR engine。

链保持：

`AudioRecord → route validation → optional approved gain → formal WAV → realtime fan-out`

route discontinuity：

`DISCONTINUITY → finalize/reset current continuity → new recognizer continuity`

formal recording 继续优先，ASR 不得反压 WAV。

## 11. P8 — Playback route diagnostics

补 actual output route 与 recovery diagnostics：

- routed output device；
- route generation；
- route change reason；
- sink state；
- underrun；
- write failure；
- recovery attempt/result。

真正 playback route 必须以当前 AudioTrack actual route 为准，不能把“设备存在”直接等价为“正在输出”。

## 12. P9 — AudioTrack safe route recovery

继续 `USAGE_MEDIA + CONTENT_TYPE_SPEECH`。

### Healthy migration

若 Android route migration 后 AudioTrack 继续正常：

- 更新 actual route；
- rebase presentation tracker；
- 继续；
- 不重建 sink。

### Broken sink recovery

`last presented canonical sample → stop writer/observer → close broken AudioTrack → create ONE new AudioTrack → reapply speed → cursor = last presented sample → reset tracker → resume iff previously PLAYING`

恢复基准使用 last presented sample，不用 submitted sample。

同一 route generation 最多自动 recovery 一次；再次失败进入 recoverable pause/error，禁止无限 recreate loop。

## 13. P10 — Noisy output behavior

保留现有 `ACTION_AUDIO_BECOMING_NOISY → pause`。

耳机/蓝牙输出移除时不得自动切 speaker 并继续播放。

## 14. P11 — QS668 BLE coexistence

不先修改 BLE。

Case A：QS668 connected + recorder idle + local playback → 必须正常。

Case B：QS668 actual Recording / fresh START/RESUME edge → 保留现有 playback pause interlock。

Case A 失败定位顺序：

`interlock reason → playback state → Audio Focus → actual AudioTrack route → AudioTrack state/write → Playback FGS → MediaSession → source resolver`

目标组合还包括：

- QS668 GATT + Bluetooth headphones playback；
- 硬件允许时 QS668 GATT + Bluetooth mic phone recording。

## 15. P12 — UI / UX integration

保持极简。

Idle 手机区域增加：

`输入设备    自动 >`

Active recording/realtime 显示 actual input route。

Manual selected mic 中断时提示：

`所选麦克风已断开；录音已暂停，未写入其它麦克风声音。`

提供重新选择输入与停止保存。

Playback 默认不增加 output selector。

## 16. P13 — Automated tests

Capture 至少覆盖：

- AUTO routing；
- manual preferred device；
- manual start actual-route mismatch；
- manual route loss rejects new-route PCM；
- AUTO route change boundary-before-PCM；
- AudioRecord recreate no owner overlap；
- clientSilenced regression；
- source fallback；
- RMS / peak / clipping math。

Recording runtime：

- user Pause/Resume = CONTROL_BOUNDARY；
- route recovery = DISCONTINUITY；
- no fake silence；
- same WAV continues；
- formal writer remains before ASR fan-out。

Realtime：

- DISCONTINUITY resets recognizer continuity；
- ASR failure/slowdown 不影响 formal recording。

Playback：

- healthy route change only rebase；
- broken sink recreate exactly once；
- recover at presented sample；
- playback speed restored；
- PLAYING resumes / PAUSED stays paused；
- no duplicate sink；
- second failure becomes recoverable error；
- noisy output still pauses。

BLE coexistence：

- connected + idle != playback pause；
- recording state == playback pause；
- fresh START/RESUME == playback pause。

## 17. P14 — Static regression gates

继续通过：

- Room schema verification；
- full migration lineage；
- model-channel security pin；
- notification channel registry；
- sherpa packaging；
- release security；
- existing phone microphone/realtime tests；
- playback tests；
- BLE tests。

Room 默认 v13；不得产生 14.json。

Production model channel 不变。

Physical notification channel 仍严格 9 个。

## 18. P15 — CI / QA APK

开发完成后执行适用：

1. Fast PR Gate；
2. unit tests；
3. debug compile；
4. production-like releaseQa；
5. APK verification；
6. artifact upload；
7. 当前 policy 适用的 Production Gate。

CI PASS 不等于真机 PASS。

## 19. 真机 QA 执行规则

进入 QA 后每次只发送一条测试项。

计划矩阵：

- A1 VOICE_RECOGNITION vs MIC calibration；
- A2 built-in AUTO recording；
- A3 built-in realtime；
- A4 waveform vs actual audio level；
- I1–I3 wired start/hot-connect/disconnect；
- I4–I6 USB start/disconnect/realtime；
- I7–I10 Bluetooth mic/disconnect/manual mismatch/clientSilenced；
- O1–O6 speaker/wired/Bluetooth/output migration/noisy/AudioTrack recovery；
- B1–B4 QS668 idle playback/recording interlock/GATT+BT output/GATT+BT mic；
- R1–R3 background/screen-off/stop-save-playback-offline-ASR regression。

没有相应硬件必须标 `NOT RUN / NOT VERIFIED`，不得写 PASS。

## 20. A1 Calibration 特殊中间 Gate

流程：

`P0/P1 diagnostics → calibration QA APK → A1 真机 → 分析 → source policy/gain decision → 产品收口`

如果 A1 需要修改默认 source 或加入 gain：先输出真机数据、原因与修改方案，等用户明确确认后再编码。

## 21. Software Gain 条件分支

状态：**OPTIONAL / EVIDENCE-GATED**。

若后续批准：

`AudioRecord.read → clientSilenced guard → route guard → optional gain → canonical accepted PCM → formal WAV → waveform → ASR`

必须：

- wide intermediate arithmetic；
- PCM16 saturation；
- clipping counter；
- 0 dB default。

range / step / UI 在 A1 后确定。

## 22. 预计修改范围

重点：

- `PhoneMicrophoneCapture.kt`
- `PhoneMicrophoneRecordingRuntime.kt`
- `PhoneMicrophoneRealtimeRuntime.kt`
- `VoicaApplication.kt`
- phone/realtime UI / diagnostics
- playback Android sink/controller/runtime
- 必要时少量 source-neutral canonical event seam

可能新增：

- `PhoneMicrophoneAudioDeviceRegistry`
- `PhoneMicrophoneInputSelection`
- `PhoneMicrophoneRoutePolicy`
- `PhoneMicrophoneSignalMeter`
- `AndroidPlaybackRouteDiagnostics`

Android-specific route logic 应保留在 Android/app/playback 层，不把 `AudioDeviceInfo` 泄漏给 ASR/core contract。

## 23. 明确不修改

未经另行批准不修改：

- QS668 protocol / TYPE1/2/3 / GATT command semantics；
- Stage 15 Opus/canonical transport；
- VAD / punctuation / diarization；
- ASR model implementation / model catalog/channel；
- AI Summary；
- Timeline Annotation；
- Room v13 business schema；
- notification channel identity；
- WorkManager ownership；
- FGS / WakeLock 基本 ownership；
- Home IA。

## 24. Stage 16A.1 S15

永久保持：

`S15 30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

不得被 Stage 16A.1.6 任何测试改写为 PASS。

## 25. Final Freeze 条件

只有同时满足：

- development complete；
- automated tests PASS；
- CI PASS；
- QA APK；
- 实际具备硬件的本阶段必测项完成；
- 未测试硬件明确 NOT VERIFIED；
- QS668 idle BLE playback 问题关闭；
- playback interlock 未退化；
- Room v13 未漂移；
- model channel 未漂移；
- notification registry 未漂移；
- S15 waiver 保持；
- 用户明确确认本阶段整体真机测试通过；

才允许创建：

- `docs/STAGE_16A_1_6_FREEZE.md`
- `docs/STAGE_16A_1_6_HANDOFF.md`

并进入最终 merge Gate。

## 26. 实施顺序

```text
P0 Docs/contracts
→ P1 device registry + diagnostics
→ P2 calibration foundation
→ A1 real-device calibration
→ P3 input selection
→ P4 AUTO route transition
→ P5 manual route loss/recovery
→ P6 wired/USB/Bluetooth input
→ P7 realtime discontinuity
→ P8 playback route diagnostics
→ P9 AudioTrack recovery
→ P10 noisy-output preservation
→ P11 QS668 coexistence
→ P12 UI/UX
→ P13 tests
→ P14 regression gates
→ P15 CI + QA APK
→ one-by-one real-device QA
→ Final Freeze/Handoff
```
