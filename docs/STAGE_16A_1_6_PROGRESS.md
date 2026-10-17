# Stage 16A.1.6 — Android Audio I/O Compatibility Progress

状态：**IN DEVELOPMENT — P6-5 IMPLEMENTED / REAL-DEVICE QA PENDING**

日期：2026-10-17

## 1. 开发授权

用户已明确确认：

1. `Stage 16A.1.6 — Android Audio I/O Compatibility 修订需求 V1`；
2. `Stage 16A.1.6 — Android Audio I/O Compatibility 开发规划 V1`；
3. A1 真机结论：普通产品 AudioSource 策略采用 `MIC → VOICE_RECOGNITION fallback`，本阶段不加入 software gain；
4. P6 开发规划 V1；
5. 在 Galaxy Buds Live 普通 AudioRecord 路径出现全零 PCM 后，单独确认《P6-5 Bluetooth HFP/SCO Compatibility Analysis》与《P6-5 开发规划 V1》。

P8/P9 播放问题已发现，但用户明确要求先解决蓝牙耳机录音；因此当前不修改 playback / AudioTrack 链路。

## 2. GitHub 基线 / 当前开发面

- repository：`ioannes78/voica-android`
- production branch：`main`
- base main HEAD：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- development branch：`stage16a1_6-android-audio-io-compatibility`
- Draft PR：`#31` — `[APK] Stage 16A.1.6 — Android Audio I/O Compatibility`
- P6-5 implementation/test HEAD（docs 更新前）：`d8b807d291bddace84cd6036d572267802e067a6`
- Android PR CI：`#1691` — **SUCCESS**

PR #31 继续保持 Draft/Open；Stage 16A.1.6 尚未达到 Final Freeze/Handoff。

## 3. 上一阶段冻结基线

Stage 16A.1.5 已完成 implementation、automated CI、real-device QA、Final Freeze/Handoff 并 merge 到 `main`。

不得借 Stage 16A.1.6 重新设计 Stage 16A.1.5 已冻结 Home IA 或 notification projection。

## 4. P0 技术锁定

### Room

当前 Room：**v13**。

Stage 16A.1.6 默认不得提升 Room；继续保持完整 1..13 migration/schema lineage。

### Production model channel

- channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- manifestVersion：7
- manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

本阶段不得自动 promotion 或修改。

### Canonical audio / ownership

继续固定：

- PCM16 / 16 kHz / mono；
- exactly one `AudioRecord` owner；
- formal WAV truth before optional ASR fan-out；
- same AudioRecord feeds formal WAV / waveform / optional realtime；
- `clientSilenced` guard 位于正式 consumer 前；
- 不写入伪造静音；
- pause/resume canonical boundary = `CONTROL_BOUNDARY`；
- route/recovery boundary = `DISCONTINUITY`；
- one playback controller；
- one `AudioTrack` sink at a time；
- QS668/CB08 BLE GATT 与 Android Bluetooth Audio route 完全分离。

## 5. Notification channel freeze

继续保持 Stage 16A.1.5 冻结的 9 个 physical channel ID：

1. `voica-device-session`
2. `voica-phone-microphone`
3. `voica-playback`
4. `voica-media-processing`
5. `voica-ai-summary`
6. `model-install`
7. `voica-task-result`
8. `voica-task-attention`
9. `voica-diarization-attention`

Stage 16A.1.6 不创建 route-specific notification channel。

## 6. Stage 16A.1 S15 waiver

永久保持：

`S15 30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

本阶段任何测试不得改写为 PASS。

## 7. P1 / P2 / A1 — 输入基础与 AudioSource Calibration

已实现：

- Android input inventory：`GET_DEVICES_INPUTS + AudioDeviceCallback`；
- runtime-only device descriptor；
- `AUTO` / `SPECIFIC_DEVICE(deviceId)`；
- requested input / preferred acceptance / actual route 分离；
- route generation / change counter / reason；
- manual route mismatch 在正式 WAV/ASR consumer 前拒绝错误 route PCM；
- read-only PCM peak / RMS / dBFS / clipping / zero ratio；
- 整段 signal summary；
- QA-only `AUTO / FORCE_VOICE_RECOGNITION / FORCE_MIC` calibration。

A1 真机：`RMX3041 / BUILTIN_MIC / id=16 / address=bottom`。

### FORCE_VOICE_RECOGNITION

- overall RMS：`-27.9 dBFS`
- peak：`-6.0 dBFS`
- noiseFloorCandidate：`-57.0 dBFS`
- clipping：0

### FORCE_MIC

- overall RMS：`-26.6 dBFS`
- peak：`-2.2 dBFS`
- noiseFloorCandidate：`-72.3 dBFS`
- clipping：0

用户主观反馈：音量基本一样，MIC 底噪更干净、清晰度更好。

A1：**PASS**。

产品普通 AudioRecord 策略固定为：

`MIC → VOICE_RECOGNITION fallback`

MIC-first 只定义 AudioSource，不等于 built-in-mic-only；物理输入继续独立由 AudioDeviceInfo / input request / actual route 决定。

## 8. P3 — Product input selector

已完成并进入产品 UI：

- AUTO；
- 内置麦克风；
- 有线耳麦；
- USB input；
- Android 暴露的 Bluetooth Audio input；
- 手动选择使用 runtime deviceId，不跨进程持久化；
- 手动设备消失时保持 MANUAL request，不偷偷切 AUTO。

## 9. P4 — AUTO route transition

真机已通过：

- built-in → 录音中插入 wired headset：平台自动 reroute；
- wired → 录音中拔出：自动返回 built-in；
- route boundary / discontinuity epoch 与同一 WAV 连续性正常；
- 无 read / consumer error。

AudioRecord true invalidation + AUTO serial rebuild 已有自动化覆盖；当前真机未触发，状态保持 **NOT VERIFIED**。

## 10. P5 — Manual selected route loss

真机已通过：

- 手动 wired → 拔出：自动进入 recoverable PAUSED；
- 不把手机麦克风 PCM 冒充原手动 route 写入；
- 可重新连接 / 选择其他输入 / 选择 AUTO / stop+save；
- 手动 wired → 拔出 → PAUSED → 选择 AUTO → resume：同一 WAV 继续，增加 `DISCONTINUITY` epoch；
- `manualRouteLossPauses=1`，无 capture interruption。

P5 语义继续作为 P6-5 Bluetooth disconnect 的硬边界。

## 11. P6-1 — Android input capability / route diagnostics

已加入设备诊断页 `Android 音频输入 / P6`：

- deviceId / type / typeLabel / productName / address / isSource；
- sampleRates / channelCounts / encodings；
- AUTO / MANUAL；
- requested input / `setPreferredDevice` acceptance / actual route；
- selectedDeviceMissing；
- routeGeneration / routeChanges；
- clientSilenced / readErrors / consumerErrors；
- peak / RMS / dBFS / zero ratio / clipping / signal summary。

诊断文本可滚动、选择，并进入“复制完整诊断信息”。

## 12. P6-4 — Galaxy Buds Live 普通 AudioRecord 证据

真机 Galaxy Buds Live 被 Android 暴露为标准输入：

- type：`BLUETOOTH_SCO(7)`；
- sampleRates：`[8000, 16000]`；
- PCM16；
- 手动选择成功；
- `preferredAccepted=true`；
- `actualRoute=BLUETOOTH_SCO / Galaxy Buds Live`；
- read 持续成功；
- `readErrors=0`；
- `consumerErrors=0`。

但 P6-4.4 信号证据为：

- frames：293；
- samples：374080；
- peak：0 / `-120.0 dBFS`；
- RMS：0 / `-120.0 dBFS`；
- zero：374080 / **100%**。

结论：`AudioRecord + setPreferredDevice()` 虽报告 SCO actual route，但 HFP/SCO communication audio session 未真正建立；普通路径在该手机/耳机组合上 **FAIL**，触发 P6-5 compatibility gate。

## 13. P6-5 — Bluetooth HFP/SCO compatibility

### 已确认设计

只对手动选择 `TYPE_BLUETOOTH_SCO` 输入启用独立 compatibility adapter；内置 / wired / USB / AUTO 普通路径不进入该 adapter。

API 31+：

1. 保存进入前 audio mode；
2. `MODE_IN_COMMUNICATION`；
3. 从 `availableCommunicationDevices` 选择匹配 Bluetooth SCO communication sink；
4. `setCommunicationDevice(...)`；
5. bounded wait，确认 actual communication device；
6. 成功后才创建/启动现有唯一 AudioRecord；
7. Bluetooth capture source order：`VOICE_COMMUNICATION → MIC → VOICE_RECOGNITION`；
8. `setPreferredDevice()` 与 actual AudioRecord route verification 仍保留。

API 26–30：

- isolated legacy `startBluetoothSco()` path；
- 等待 `SCO_AUDIO_STATE_CONNECTED` 后才允许 AudioRecord 启动；
- 当前只完成实现/自动化编译覆盖，尚无旧 Android 真机验证，状态 **NOT VERIFIED**；
- legacy HFP 若后续证明系统只提供 8 kHz，而 canonical pipeline 仍要求 16 kHz，则需单独证据化评估，不得悄悄改变 canonical contract。

新增 manifest permission：

`android.permission.MODIFY_AUDIO_SETTINGS`

该权限仅用于 Android communication/SCO route control；不改变 QS668 GATT 权限与生命周期。

### 生命周期

- Start：communication session 成功后才启动 AudioRecord；
- user Pause：AudioRecord 完整 stop/release，但 communication session 可保留；
- Resume：同一 Bluetooth communication session 可复用，仍创建单个新的 capture epoch AudioRecord；
- final Stop：AudioRecord stop/release 后清理 communication device / SCO，并恢复进入前 audio mode；
- route loss / capture failure：不保留 communication session；继续使用 P5 recoverable pause 语义；
- close：强制 cleanup。

### 诊断

新增 `[P6 Bluetooth communication]`：

- phase / path；
- requested Bluetooth input；
- modeBefore / modeActive / modeRestored；
- requested / actual communication device；
- `setCommunicationDeviceAccepted`；
- legacy SCO state；
- setup latency；
- cleanup result；
- failure code/detail。

最终真机有效性仍以现有 AudioRecord signal evidence 为准；`setCommunicationDevice=true` 本身不能替代非零 PCM 证据。

## 14. P6-5 automated candidate

P6-5 implementation/test HEAD（docs 更新前）：

`d8b807d291bddace84cd6036d572267802e067a6`

Android PR CI：

- run number：`#1691`
- run id：`39020915793`
- Fast PR Gate：**SUCCESS**
- `Unit tests and debug compile gate`：**SUCCESS**
- QA APK Gate：**SUCCESS**
- `Build production-like QA APK only`：**SUCCESS**
- `Verify QA APK`：**SUCCESS**
- `Upload QA APK`：**SUCCESS**

自动化覆盖新增：

- P6-5 communication diagnostics；
- Bluetooth compatibility capture source order 为 `VOICE_COMMUNICATION` first；
- 普通 A1 product source policy 仍为 `MIC → VOICE_RECOGNITION`，未被 Bluetooth path 改写。

CI SUCCESS 只表示自动化 Gate 通过；**P6-5 Galaxy Buds Live 真机仍为 NOT VERIFIED**。

## 15. 已知但延期的 playback 问题

Galaxy Buds Live 连接时，用户发现一条此前确认正常的旧录音无法启动播放、进度不走；断开 Buds 后，同一旧录音立即恢复播放。

当前记录为 P8/P9 后续输入：

- 不归因于刚才全零 WAV；
- 当前 P6-5 不修改 AudioTrack / playback controller；
- 用户明确要求先解决录音，播放 route/recovery 后续单独测试和修复。

## 16. 当前待办

- [x] Requirements V1 用户确认
- [x] Development Plan V1 用户确认
- [x] Room v13 / model channel / notification / S15 invariants 核验
- [x] P1 diagnostics foundation
- [x] P2 A1 calibration
- [x] A1 real-device PASS
- [x] A1 MIC-first product policy
- [x] P3 product input selector
- [x] P4 AUTO wired route transitions real-device PASS
- [x] P5 manual route loss/recoverable pause real-device PASS
- [x] P6-1 input capability / route diagnostics
- [x] P6-4 Galaxy Buds ordinary AudioRecord evidence collected
- [x] P6-5 Analysis 用户确认
- [x] P6-5 Development Plan V1 用户确认
- [x] P6-5 implementation + automated CI candidate
- [ ] P6-5 Galaxy Buds Live real-device non-zero PCM gate
- [ ] P6 USB hardware QA（无硬件则 NOT RUN / NOT VERIFIED）
- [ ] P8 playback route diagnostics
- [ ] P9 AudioTrack bounded recovery（仅在证据需要时）
- [ ] QS668 coexistence QA
- [ ] Final Freeze/Handoff

## 17. 下一步

只进行一项 P6-5 真机测试：

1. 连接 Galaxy Buds Live；
2. 手动选择 Galaxy Buds Live 输入；
3. 录音约 5–10 秒并正常说话；
4. stop/save；
5. 复制 `[P6 Bluetooth communication]` 与 `[P6 last AudioRecord route]`；
6. 首要通过条件：`signalSummary` 不再是 `peak=0 / rms=0 / zero=100%`。

在该 gate 通过前不得把 P6-5 标记为 PASS；暂不要求播放验证。