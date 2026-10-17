# Stage 16A.1.6 — Android Audio I/O Compatibility Progress

状态：**REAL-DEVICE QA COMPLETE FOR AVAILABLE HARDWARE — FINAL FREEZE/HANDOFF PENDING USER CONFIRMATION**

日期：2026-10-17

## 1. 开发授权

用户已明确确认：

1. `Stage 16A.1.6 — Android Audio I/O Compatibility 修订需求 V1`；
2. `Stage 16A.1.6 — Android Audio I/O Compatibility 开发规划 V1`；
3. A1 真机结论：普通产品 AudioSource 策略采用 `MIC → VOICE_RECOGNITION fallback`，本阶段不加入 software gain；
4. P6 开发规划 V1；
5. 在 Galaxy Buds Live 普通 AudioRecord 路径出现全零 PCM 后，单独确认《P6-5 Bluetooth HFP/SCO Compatibility Analysis》与《P6-5 开发规划 V1》；
6. P8 Playback Route Diagnostics 开发方案 V1。

P6、P8、P11 在现有可用硬件上的真机验证已完成。P9 继续保持 evidence-gated：当前没有 broken/stale AudioTrack 证据，因此不实现恢复逻辑。

## 2. GitHub 基线 / 当前开发面

- repository：`ioannes78/voica-android`
- production branch：`main`
- base main HEAD：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- development branch：`stage16a1_6-android-audio-io-compatibility`
- Draft PR：`#31` — `[APK] Stage 16A.1.6 — Android Audio I/O Compatibility`
- P8 implementation/test candidate HEAD：`bb149e663444ef498b5b278c961720d4f1f02e5a`
- Android PR CI：`#1699` / run `39043456516` — **SUCCESS**

PR #31 继续保持 Draft/Open；Stage 16A.1.6 尚未执行 Final Freeze/Handoff，也尚未 merge。

## 3. 上一阶段冻结基线

Stage 16A.1.5 已完成 implementation、automated CI、real-device QA、Final Freeze/Handoff 并 merge 到 `main`。

不得借 Stage 16A.1.6 重新设计 Stage 16A.1.5 已冻结 Home IA 或 notification projection。

## 4. P0 技术锁定

### Room

当前 Room：**v13**。

Stage 16A.1.6 未提升 Room；继续保持完整 1..13 migration/schema lineage。

### Production model channel

- channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- manifestVersion：7
- manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

本阶段未执行 model promotion 或 channel 修改。

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

Stage 16A.1.6 未创建 route-specific notification channel。

## 6. Stage 16A.1 S15 waiver

永久保持：

`S15 30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

不得改写为 PASS。

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

普通产品 AudioSource 策略固定为：

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

真机：**PASS**。

- built-in → 录音中插入 wired headset：平台自动 reroute；
- wired → 录音中拔出：自动返回 built-in；
- route boundary / discontinuity epoch 与同一 WAV 连续性正常；
- 无 read / consumer error。

AudioRecord true invalidation + AUTO serial rebuild 已有自动化覆盖；当前真机未触发，状态保持 **NOT VERIFIED**。

## 10. P5 — Manual selected route loss

真机：**PASS**。

- 手动 wired → 拔出：自动进入 recoverable PAUSED；
- 不把手机麦克风 PCM 冒充原手动 route 写入；
- 可重新连接 / 选择其他输入 / 选择 AUTO / stop+save；
- 手动 wired → 拔出 → PAUSED → 选择 AUTO → resume：同一 WAV 继续，增加 `DISCONTINUITY` epoch；
- `manualRouteLossPauses=1`，无 capture interruption。

P5 语义继续作为 Bluetooth disconnect 的硬边界。

## 11. P6-1 — Android input capability / route diagnostics

设备诊断页 `Android 音频输入 / P6` 已包含：

- deviceId / type / typeLabel / productName / address / isSource；
- sampleRates / channelCounts / encodings；
- AUTO / MANUAL；
- requested input / `setPreferredDevice` acceptance / actual route；
- selectedDeviceMissing；
- routeGeneration / routeChanges；
- clientSilenced / readErrors / consumerErrors；
- peak / RMS / dBFS / zero ratio / clipping / signal summary。

诊断文本可滚动、选择，并进入“复制完整诊断信息”。

## 12. P6-4 — Galaxy Buds Live 普通 AudioRecord 失败证据

Galaxy Buds Live 被 Android 暴露为：

- type：`BLUETOOTH_SCO(7)`；
- sampleRates：`[8000, 16000]`；
- PCM16；
- 手动选择成功；
- `preferredAccepted=true`；
- `actualRoute=BLUETOOTH_SCO / Galaxy Buds Live`；
- read 持续成功；
- `readErrors=0`；
- `consumerErrors=0`。

但普通 `AudioRecord + setPreferredDevice()` 路径的信号证据为：

- frames：293；
- samples：374080；
- peak：0 / `-120.0 dBFS`；
- RMS：0 / `-120.0 dBFS`；
- zero：374080 / **100%**。

结论：普通 setPreferredDevice 路径在该手机/耳机组合上 **FAIL**。该失败证据保留，用于解释 P6-5 compatibility adapter 的必要性。

## 13. P6-5 — Bluetooth HFP/SCO compatibility

### 实现

只对手动选择 `TYPE_BLUETOOTH_SCO` 输入启用 compatibility adapter；内置 / wired / USB / AUTO 普通路径不进入该 adapter。

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
- 已完成实现/自动化覆盖；
- 无旧 Android 真机，状态 **NOT VERIFIED**。

新增 manifest permission：`android.permission.MODIFY_AUDIO_SETTINGS`，只用于 Android communication/SCO route control。

### 生命周期

- Start：communication session 成功后启动 AudioRecord；
- user Pause：AudioRecord stop/release，communication session 可保留；
- Resume：可复用 session，仍创建新的 capture epoch AudioRecord；
- final Stop：cleanup communication device / SCO，恢复进入前 audio mode；
- route loss / capture failure：继续使用 P5 recoverable pause 语义；
- close：强制 cleanup。

### 真机结果 — Galaxy Buds Live

P6-5：**PASS**。

代表性非零 PCM 证据：

- `source=VOICE_COMMUNICATION`；
- `reads=251`；
- `samples=320640`；
- `readErrors=0`；
- `consumerErrors=0`；
- `signalSummary peak=26499 / -1.8 dBFS`；
- `rms=2089.1 / -23.9 dBFS`；
- `zero=3.19%`；
- `clip=0.00%`；
- communication path=`COMMUNICATION_DEVICE`；
- `modeActive=IN_COMMUNICATION`；
- `setCommunicationDeviceAccepted=true`；
- `cleanupSucceeded=true`；
- final mode restored to NORMAL。

进一步真机 gates：

- 实际声学来源确认为 Galaxy Buds 麦克风：**PASS**；
- pause/resume：**PASS**；
- 手动 Bluetooth recording 中断开耳机 → recoverable pause、禁止静默回落手机麦克风：**PASS**；
- 重连后继续同一 WAV：**PASS**；
- stop 后无需重启 App、无需断开耳机即可开始下一次新录音：**PASS**；
- Bluetooth realtime ASR：**PASS**。

## 14. P6 未覆盖硬件

必须保留以下状态，不得视为 PASS：

- USB-C headset / USB mic / USB sound card 输入：**NOT RUN / NOT VERIFIED — missing hardware**；
- Android LE Audio / BLE Audio headset 输入：**NOT RUN / NOT VERIFIED — missing hardware**；
- API 26–30 legacy SCO physical path：**NOT VERIFIED — no old Android physical device**。

Galaxy Buds Live 是 `BLUETOOTH_SCO`，不能替代 BLE Audio / `TYPE_BLE_HEADSET` 真机验证。

## 15. P8 — Playback Route Diagnostics

### 实现

P8 只增加 read-only diagnostics，不改变已冻结 playback behavior：

- actual `AudioTrack.routedDevice` tracking；
- route generation / route change count / route reason；
- output device add/remove 作为 environment event，与 actual route 分开记录；
- AudioTrack state / play state / sample rate / channel / encoding / buffer；
- underrun / write / zero-write / negative-write / last-write-failure；
- timestamp availability / presented frame；
- dedicated `Android 音频输出 / P8` section；
- full diagnostics copy output；
- `[P9 recovery] attempts=0 result=NOT_ATTEMPTED`。

Healthy route migration 不主动重建 AudioTrack sink。

### 自动化 / CI

P8 candidate HEAD：`bb149e663444ef498b5b278c961720d4f1f02e5a`。

Android PR CI：

- run number：`#1699`
- run id：`39043456516`
- Fast PR Gate：**SUCCESS**
- Unit tests and debug compile gate：**SUCCESS**
- QA APK Gate：**SUCCESS**
- Build production-like QA APK：**SUCCESS**
- Verify QA APK：**SUCCESS**
- Upload QA APK：**SUCCESS**

Artifact：`Voica-1.1.0-stage16a-export-qa-apk`，artifact id `12104719077`。

Artifact ZIP digest：`sha256:07c101b2f3a3eac3fdd8205b90d6a77645024992ec60dad7011086c640633e8a`。

Extracted APK SHA256：`570b8c6a91c32b90f1538a5f3ace8d32c29292b0df0c1e49158541df623be76b`。

## 16. P8 真机 QA

P8 Playback Route Diagnostics：**PASS**。

### D1 — Galaxy Buds actual output

**PASS**。

- `actualRoute=BLUETOOTH_A2DP / Galaxy Buds Live`；
- `trackState=INITIALIZED`；
- `underruns=0`；
- `negativeWrites=0`；
- `lastWriteFailure=n/a`；
- `timestampAvailable=true`；
- P9 recovery=`NOT_ATTEMPTED`。

### D2 — Bluetooth output removal / noisy policy

**PASS**。

- `OUTPUT_DEVICE_REMOVED: BLUETOOTH_A2DP`；
- `sinkPhase=PAUSED`；
- `playState=PAUSED`；
- 手机扬声器不自动继续播放；
- 无 write failure / underrun；
- P9 未触发。

### D3 — Bluetooth reconnect + manual replay

**PASS**。

- 不重启 App；
- Galaxy Buds 重连后手动 Play；
- 立即从 Galaxy Buds 正常播放并推进。

### D4 — Wired headset actual output

**PASS**。

- `actualRoute=WIRED_HEADSET`；
- `sinkPhase=ACTIVE`；
- `playState=PLAYING`；
- `underruns=0`；
- `negativeWrites=0`；
- `lastWriteFailure=n/a`；
- `timestampAvailable=true`。

### D5 — Wired output removal / noisy policy

**PASS**。

- `OUTPUT_DEVICE_REMOVED: WIRED_HEADSET`；
- AudioTrack 进入 PAUSED；
- 手机扬声器不自动继续播放；
- 无 AudioTrack failure；
- P9 未触发。

### D6 — Wired reinsert + manual replay

**PASS**。

- 不重启 App；
- 重新插入同一有线耳机后手动 Play；
- 立即正常从 wired output 播放。

### D7 — Speaker → Bluetooth healthy route migration

**PASS**。

代表性诊断：

- `actualRoute=BLUETOOTH_A2DP / Galaxy Buds Live`；
- `sinkGeneration=1`；
- `routeGeneration=6`；
- `routeChanges=5`；
- `sinkPhase=ACTIVE`；
- `playState=PLAYING`；
- `underruns=0`；
- `negativeWrites=0`；
- `lastWriteFailure=n/a`；
- `timestampAvailable=true`；
- P9 recovery=`NOT_ATTEMPTED`。

结论：同一个 AudioTrack sink 可以经历多次健康 Android route migration，无需 Voica 主动重建 sink。

此前“Galaxy Buds 连接时旧录音无法启动/进度不走”的严重症状，在当前候选与本轮真机矩阵中 **NOT REPRODUCED**。不得描述为由 P6-5 代码直接修复，因为 P6-5 并未针对 playback 做行为修复。

## 17. P9 — AudioTrack bounded recovery

状态：**NOT TRIGGERED / NOT IMPLEMENTED**。

原因：

- Bluetooth 重连后播放恢复正常；
- wired 重插后播放恢复正常；
- healthy route migration 可在同一 sink 内完成；
- 无 stale/dead AudioTrack；
- 无必须重启 App；
- 无 negative write；
- 无 write failure；
- 无支持“broken sink 必须 recreate”的真机证据。

因此当前实现只保留 P9 diagnostics placeholder：

`attempts=0 result=NOT_ATTEMPTED`

不得因为 output removal 按 P10 进入 pause 就触发 P9。

## 18. P10 — Noisy output behavior

冻结规则保持：

- `ACTION_AUDIO_BECOMING_NOISY → pause`；
- Bluetooth / wired 活跃输出被移除时，不能静默切到手机扬声器继续播放；
- 用户可在新 route 可用后手动恢复播放。

真机 Bluetooth / wired removal 均：**PASS**。

## 19. P11 — QS668 BLE GATT coexistence

QS668/CB08 BLE GATT 与 Android Bluetooth Audio 是独立 transport domain。

现有硬件真机矩阵：**PASS**。

- QS668 GATT connected + Galaxy Buds SCO phone recording：**PASS**；
- QS668 GATT connected + Galaxy Buds A2DP local playback + QS668 file-list refresh：**PASS**；
- QS668 GATT download transfer + Galaxy Buds SCO phone recording：**PASS**；
- QS668 actual Recording / fresh START edge → local playback auto-pause：**PASS**；
- QS668 fresh RESUME edge → local playback auto-pause：**PASS**。

未发现 QS668 GATT 与当前 Android audio capture/playback path 的资源互斥或连接断开问题。

## 20. 当前 QA 状态汇总

- A1 AudioSource calibration：**PASS**
- P4 AUTO wired route transition：**PASS**
- P5 manual route loss/recovery：**PASS**
- P6 Galaxy Buds ordinary setPreferredDevice-only path：**FAIL — documented trigger evidence**
- P6-5 Galaxy Buds compatibility solution：**PASS**
- P6 Bluetooth realtime ASR：**PASS**
- P6 USB physical input：**NOT RUN / NOT VERIFIED — missing hardware**
- P6 BLE Audio / LE Audio physical input：**NOT RUN / NOT VERIFIED — missing hardware**
- P6 API26–30 legacy SCO physical path：**NOT VERIFIED**
- P8 playback route diagnostics / available output matrix：**PASS**
- P9 bounded AudioTrack recovery：**NOT TRIGGERED / NOT IMPLEMENTED**
- P10 noisy output policy：**PASS**
- P11 QS668 coexistence：**PASS**
- S15 30-minute phone microphone + Small realtime + screen-off：**NOT RUN / WAIVED BY USER**

## 21. 当前待办

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
- [x] P6-4 Galaxy Buds ordinary AudioRecord failure evidence collected
- [x] P6-5 Analysis / Plan 用户确认
- [x] P6-5 implementation + automated CI
- [x] P6-5 Galaxy Buds Live real-device gates PASS
- [x] P6 Bluetooth realtime ASR PASS
- [x] P6 USB hardware QA recorded as NOT RUN / NOT VERIFIED
- [x] P6 BLE Audio hardware QA recorded as NOT RUN / NOT VERIFIED
- [x] API26–30 legacy SCO recorded as NOT VERIFIED
- [x] P8 playback route diagnostics implementation
- [x] P8 automated CI
- [x] P8 available-hardware real-device QA PASS
- [x] P9 evidence review — recovery not triggered / not implemented
- [x] P10 noisy output behavior PASS
- [x] P11 QS668 coexistence QA PASS
- [ ] Final closeout documentation CI
- [ ] Final Freeze/Handoff — requires explicit user confirmation
- [ ] Merge PR #31 to `main` — only after Freeze/Handoff authorization

## 22. 下一步

1. 将本收口进度文档与 PR #31 状态同步；
2. 等待本次 docs-only closeout CI 完成；
3. CI 成功后，向用户提交 Stage 16A.1.6 Final Freeze/Handoff 候选摘要；
4. 只有用户明确确认 Final Freeze/Handoff 后，才允许进入 merge 流程。

CI SUCCESS 不替代真机 QA；缺失硬件项继续保持 NOT RUN / NOT VERIFIED。