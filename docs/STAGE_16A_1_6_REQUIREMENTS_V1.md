# Stage 16A.1.6 — Android Audio I/O Compatibility 修订需求 V1

状态：**APPROVED / DEVELOPMENT AUTHORIZED**

日期：2026-10-16

## 1. 阶段目标

Stage 16A.1.6 负责 Android capture / playback route compatibility，解决真实 Android 设备 Audio I/O 差异，同时保持 Stage 16A / 16A.1 已冻结的 canonical truth、single owner 与 recording-first 架构。

目标结构：

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

播放继续保持：

```text
Recording Library
      ↓
Single PlaybackController
      ↓
ONE AudioTrack sink
      ↓
Android system media route
```

QS668 / CB08 BLE GATT 继续属于独立 recorder transport domain，不得与 Android Bluetooth audio route 合并建模。

## 2. 当前核验基线

开发授权前重新核验：

- repository：`ioannes78/voica-android`
- base `main`：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- PR #30：已 merge；当前无 open PR
- Stage 16A.1.5：Final Freeze/Handoff complete
- Room：v13；schema lineage 必须继续保持 1..13
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`
- canonical audio：PCM16 / 16 kHz / mono
- Stage 16A.1 S15：`30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

当前 capture 已使用单 `AudioRecord` owner，并以 `VOICE_RECOGNITION → MIC` 为 controlled fallback；当前 capture 尚无 `setPreferredDevice()` 输入选择。当前 playback 为 single controller / single `AudioTrack` sink；已有 AudioDeviceCallback 但 route add/remove 主要只做 position rebase，尚无完整 sink recreation/recovery。

## 3. R1 — 手机录音低音量先诊断、后决策

不得直接通过数字放大掩盖根因。

必须区分：

1. capture PCM 本身电平偏低；
2. waveform 显示比例偏低；
3. `VOICE_RECOGNITION` / `MIC` source policy 差异；
4. 实际 routed input 不符合预期；
5. 厂商音频预处理 / effect 差异。

同机、同距离、同内容比较 `VOICE_RECOGNITION` 与 `MIC`：

- RMS / dBFS；
- peak / dBFS；
- noise floor；
- clipping ratio；
- actual routed device；
- 实际听感；
- ASR 质量。

不得假设 `VOICE_RECOGNITION` 或 `MIC` 在所有 Android 设备上永久更优。

## 4. R2 — Software Gain 仅为证据驱动的最后手段

默认 0 dB / 1.0x，即不启用数字增益。

只有真机证明 PCM 真实偏低，且 source / route / UI waveform 不能解释问题时，才允许进入 software gain 子方案。

若实施，首版只允许有界 static digital gain；不得顺带自研复杂 AGC / compressor / limiter / loudness normalizer。

必须使用安全宽位中间值、PCM16 saturation，并记录 clipping count / ratio。

若启用，位置必须统一在 accepted `AudioRecord` PCM 进入 canonical truth 前，使正式 WAV、waveform、realtime ASR 看到同一份 resulting canonical PCM。不得建立多套独立 gain。

具体 gain range / step 必须基于 A/B 真机数据后再确认。

## 5. R3 — 输入设备发现必须 capability-driven

动态读取当前 Android `AudioDeviceInfo` input sources，不使用静态设备假设。

首版产品选择候选：

- 自动（推荐）；
- 手机/本机麦克风；
- 当前实际存在的 USB microphone；
- 当前实际存在的 wired headset microphone；
- 当前实际存在且 Android 暴露为 source 的 Bluetooth microphone。

多个同类设备使用 productName 等 runtime 信息区分。

“耳机存在”不等于“该设备有输入 source”。

## 6. R4 — AUTO 与 manual input truth 分离

### AUTO

默认不强制 preferred device，由 Android 选择 route；启动后读取并展示 actual routed device。

### Manual

用户明确选择某个 input 时可请求 `AudioRecord.setPreferredDevice()`。

requested/preferred device 与 actual routed device 是两个独立事实。

如果用户明确选择 USB/Bluetooth/wired microphone，但实际 route 最终落到手机内置麦克风，不得静默宣称成功；Start 必须失败并明确提示。

manual selected device 在录音过程中消失时，也不得静默 fallback 到 built-in microphone 并把声音写入正式 WAV。

## 7. R5 — Wired microphone

支持 Android 实际暴露为 input source 的有线麦克风。

必须覆盖：

- 开始前已连接；
- active recording 时插入；
- active recording 时拔出；
- realtime recording。

只有耳机输出、没有麦克风 source 的 wired device 不得显示为输入设备。

## 8. R6 — USB microphone

支持 Android 暴露为 source 的 USB input，包括适用的 `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`。

Voica client canonical profile仍固定 PCM16 / 16 kHz / mono。

不得因为 USB hardware native sample rate 不同就修改 canonical contract。

优先让 Android audio framework 提供当前 client format；只有真实硬件证明无法满足时，才单独评估 native capture → canonical resample/downmix adapter，且仍不得建立第二套 capture lifecycle。

## 9. R7 — Bluetooth 两个领域必须彻底分离

A. QS668 / CB08 BLE GATT：

- recorder protocol / transport；
- 不等于 Android microphone route；
- 不等于 Android playback output route。

B. Android Bluetooth Audio：

- `AudioManager` / `AudioDeviceInfo` / `AudioRecord` / `AudioTrack` route domain。

两个 state machine 必须保持独立。

## 10. R8 — BLE Audio microphone

API 31+ 若系统暴露 `TYPE_BLE_HEADSET` 且 `isSource=true`，可作为普通 Android audio input 候选。

优先沿用 AudioRecord + preferred device / actual route verification。

A2DP-only headphone 不得伪装为 microphone。

## 11. R9 — Classic Bluetooth HFP/SCO

Stage 16A.1.6 V1 不因为“Bluetooth”就全局启用：

- `MODE_IN_COMMUNICATION`；
- `startBluetoothSco()`；
- `setCommunicationDevice()`；
- speakerphone mode。

首轮优先普通 AudioRecord device route。

只有真机证据证明 Classic Bluetooth microphone 在当前普通 route 上不可用时，才单独提交 HFP/SCO compatibility amendment，重新设计 API 26–30 / 31+ lifecycle、permission 与副作用。

当前 V1 不预授权增加 `MODIFY_AUDIO_SETTINGS`。

## 12. R10 — Active recording route change

route change 是明确 continuity boundary。

如果现有 AudioRecord 仍继续提供有效 PCM：

- 正式 WAV 可以继续写真实 PCM；
- 不写 wall-clock fake silence；
- canonical sample index 只统计 accepted PCM；
- realtime 不得把前后 acoustic route 静默视为同一 continuity。

如果 AudioRecord 无法继续：

- 同一个 PhoneMicrophoneCapture owner 可以顺序 release/recreate；
- 不允许重叠两个 AudioRecord；
- 恢复成功后建立新的 discontinuity boundary；
- 恢复失败必须明确暴露并保护已录正式媒体。

## 13. R11 — clientSilenced guard 保持

Stage 16A.1 已冻结的 `clientSilenced` 防护必须继续适用于所有 built-in / wired / USB / Bluetooth route 以及 AudioRecord recreate。

`clientSilenced=true` 的 PCM 不得写入正式 WAV、waveform 或 ASR。

## 14. R12 — 正式录音输入 = realtime 输入

手机 realtime 不得另开第二 AudioRecord。

同一次录音中不得出现：

- WAV 来自 USB mic；
- ASR 却来自 built-in mic。

正式 recording truth 与 realtime 必须复用同一 accepted canonical PCM。

## 15. R13 — Playback 当前问题域

现有 playback 保持 single controller 与 single sink。

已有：

- Audio Focus；
- `USAGE_MEDIA` + speech content；
- `ACTION_AUDIO_BECOMING_NOISY` pause；
- AudioDeviceCallback。

本阶段需要补足 actual output route 诊断和必要的 AudioTrack sink recovery，而不是重建播放器体系。

## 16. R14 — Playback output policy

继续采用 Android normal media routing。

目标支持系统能够正常路由到的：

- speaker；
- wired output；
- Bluetooth A2DP / BLE Audio；
- USB output。

Stage 16A.1.6 V1 不建立 Voica 自己的 output switch center。

不得把 playback 改成 communication audio。

## 17. R15 — AudioTrack route recovery

### Noisy removal

耳机/蓝牙输出突然移除继续保持 pause，避免音频突然外放。

### Healthy route migration

若 Android 自身完成 route migration，当前 AudioTrack 仍有效：

- 更新 actual route；
- rebase presentation tracker；
- 继续播放；
- 不无条件重建 sink。

### Broken sink

若出现 sink invalid、write failure 或 route change 后输出失效，执行 bounded recovery：

`last presented canonical sample → close broken sink → recreate ONE AudioTrack → current Android media route → rebase → safe resume`

不得：

- 从 0 重新播放；
- 使用 submitted cursor 造成大段跳过；
- 建第二 playback controller；
- 同时保留双 sink。

## 18. R16 — Playback diagnostics

runtime/diagnostics 至少记录：

- actual routed output device id/type/productName；
- address（系统允许时，仅本地诊断）；
- audio focus state/change；
- route generation/reason；
- AudioTrack init/write error；
- underrun；
- recreate count；
- recovery outcome；
- recovery 前后 presented sample。

不得因此修改 Room schema。

## 19. R17 — GATT/audio invariant

硬性不变量：

`QS668 GATT CONNECTED != Bluetooth Audio route selected`

QS668 仅连接、recorder idle 时不得导致 Voica：

- 修改 AudioManager mode；
- 启用 SCO；
- 强制 communication device；
- 修改 speakerphone；
- 强制 microphone route；
- 强制 media output route；
- 阻塞本地 playback。

## 20. R18 — BLE connected library playback

Case A：QS668 已连接、recorder idle：本地录音库播放必须正常。

Case B：QS668 真正开始 Recording，或出现已冻结 fresh START/RESUME hardware edge：继续保留现有 playback pause interlock。

若 Case A 失败，必须按 interlock → focus → AudioTrack → route → FGS/MediaSession → source lifecycle 定位；没有真机证据不得直接归因于“BLE 抢占音频”。

## 21. R19 — GATT + Bluetooth Audio 共存

硬件允许时应支持：

- QS668 GATT + Bluetooth headphone playback；
- QS668 GATT + Bluetooth microphone phone recording。

系统 Bluetooth OFF 可能同时触发 GATT disconnect 与 Android audio device disappearance；两套事件仍必须分别处理。

## 22. R20 — Device address 仅用于 runtime diagnostics

Audio device address 只在 Android/系统允许时用于当前 runtime/diagnostics。

不得把临时 audio address 当成 Room 业务 identity、长期用户身份或 analytics identity。

## 23. R21 — Android API compatibility

minSdk 26 继续作为基线。

基础能力优先使用当前 minSdk 可用的：

- AudioDeviceInfo；
- AudioDeviceCallback；
- AudioRecord preferred/routed device。

API 31+ BLE headset / communication route API 如有必要必须 version guard。

API 36+ 新 route 能力不得变成本阶段最低基础。

## 24. R22 — UI 边界

允许增加轻量 Audio I/O UI：

- 当前 input；
- input selector；
- route unavailable / route changed 状态；
- 若 A/B 证明确有必要，再增加 software gain；
- diagnostics。

不得重新设计 Stage 16A.1.5 已冻结 Home IA。

Home 的“手机”继续表示 Android local capture capability，而不是“永远只能 built-in microphone”。

## 25. R23 — Notification channels 不变

Stage 16A.1.5 冻结的 9 个 physical channel ID 必须保持：

1. `voica-device-session`
2. `voica-phone-microphone`
3. `voica-playback`
4. `voica-media-processing`
5. `voica-ai-summary`
6. `model-install`
7. `voica-task-result`
8. `voica-task-attention`
9. `voica-diarization-attention`

不得为 route change 创建新的 Android notification channel。

## 26. R24 — Room 保持 v13

本阶段 route selection、route diagnostics、A/B source diagnostics 优先使用 runtime state / app preference。

不得增加空业务表或为了 Audio I/O compatibility 自动提升 Room。

如后续出现不可避免 schema 需求，必须停止并另行确认。

## 27. R25 — Canonical PCM contract 不变

所有输入最终 client canonical 均继续：

`PCM16 / 16 kHz / mono`

优先由 Android framework 完成 client format 适配。

只有真机证据证明必要，才允许另行评估 canonicalization adapter；不得把设备 native sample rate 泄漏为新的业务 truth。

## 28. R26 — 不修改 ASR engine

本阶段不得为了 USB/Bluetooth route 改写 ASR/VAD/punctuation/diarization engine。

route discontinuity 必须通过已有 source-neutral canonical discontinuity seam 表达。

## 29. R27 — S15 永久保持 WAIVED

Stage 16A.1：

`S15 30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

Stage 16A.1.6 的任何短测、USB/Bluetooth 测试或 screen-off compatibility 测试都不得把历史 S15 改写成 PASS。

## 30. R28 — 真机硬件事实必须真实记录

wired mic、USB mic、Classic Bluetooth mic、BLE Audio mic、Bluetooth output、USB output 只有真实硬件执行后才允许写 PASS。

无对应硬件时必须记录：

- `NOT RUN / NOT VERIFIED`；或
- `UNSUPPORTED ON TEST DEVICE`。

API documentation、CI、单元测试不能替代硬件 PASS。

## 31. 必须关闭的问题

### Capture

- 手机录音低电平真实根因；
- production `MIC` / `VOICE_RECOGNITION` policy；
- software gain 是否真正需要；
- requested input 与 actual routed input；
- wired / USB / usable Bluetooth mic；
- active route change continuity；
- manual selected mic disconnect safety。

### Playback

- speaker / wired / Bluetooth / USB output；
- playback position continuity；
- broken AudioTrack recovery；
- noisy removal pause；
- QS668 GATT connected + recorder idle 时本地播放；
- actual device recording 时现有 interlock 不退化；
- GATT 与 Bluetooth Audio coexistence。

## 32. 硬边界

除非另行明确批准，Stage 16A.1.6 不修改：

- BLE protocol / GATT semantics；
- Stage 15 canonical BLE pipeline；
- VAD / punctuation / diarization；
- ASR model implementation / model install / model channel；
- AI Summary；
- Room business schema；
- Timeline Annotation；
- cloud realtime ASR；
- WorkManager ownership；
- FGS ownership 基本架构；
- WakeLock ownership 基本架构；
- Stage 16A.1.5 Home IA / notification projection 基本结构。

Room 默认保持 v13。
