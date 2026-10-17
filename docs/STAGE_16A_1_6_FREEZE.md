# Stage 16A.1.6 Final Freeze — Android Audio I/O Compatibility

状态：**FINAL FREEZE — 用户已明确确认 Final Freeze/Handoff；available-hardware 真机 QA 完成；缺少硬件项继续保持 NOT RUN / NOT VERIFIED**

冻结日期：2026-10-17

## 1. 冻结范围

Stage 16A.1.6 冻结 Voica 当前 Android Audio I/O 兼容性行为，包括：

- Android recording input inventory / requested-vs-actual route diagnostics；
- phone recording AudioSource calibration 与普通产品输入策略；
- wired / Bluetooth HFP/SCO 输入兼容；
- manual selected-route loss 的 recoverable pause 语义；
- Android media playback actual route diagnostics；
- `ACTION_AUDIO_BECOMING_NOISY → pause` 输出移除策略；
- QS668 / CB08 BLE GATT 与 Android Audio I/O 共存边界。

本 Freeze 不把缺少硬件的 USB / BLE Audio / legacy Android SCO 路径虚构为 PASS，也不把未被证据触发的 P9 AudioTrack recovery 视为已实现。

## 2. Final Freeze 前 GitHub 真实基线

用户确认 Final Freeze/Handoff 后重新核验：

- repository：`ioannes78/voica-android`
- target branch：`main`
- base `main` HEAD：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- development branch：`stage16a1_6-android-audio-io-compatibility`
- PR：#31 `[APK] Stage 16A.1.6 — Android Audio I/O Compatibility`
- Final implementation / real-device QA source candidate：`bb149e663444ef498b5b278c961720d4f1f02e5a`
- closeout progress source：`761322fe836da1d8863611746b59ffccb2e90ee7`
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13；`VoicaDatabase` 仍为 `version = 13`，Stage 16A.1.6 未增加 migration
- production model channel main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Freeze / Handoff 文档提交后 branch HEAD 会晚于上述 source candidate；这些 docs-only commits 不得改变 app/core/engine production behavior。

## 3. Canonical recording contract 冻结

继续固定：

- exactly one `AudioRecord` owner；
- PCM16 / 16 kHz / mono；
- formal WAV truth before optional realtime ASR fan-out；
- same AudioRecord feeds formal WAV / waveform / optional realtime；
- `clientSilenced` guard 位于正式 consumer 前；
- 不写入 fake silence；
- pause/resume boundary = `CONTROL_BOUNDARY`；
- route/recovery boundary = `DISCONTINUITY`；
- 普通产品 AudioSource 顺序：`MIC → VOICE_RECOGNITION fallback`；
- 物理输入 route 与 AudioSource policy 分离。

A1 calibration 真机结论：**PASS**。本阶段不加入 software gain。

## 4. P4 / P5 输入路由语义冻结

### P4 AUTO route transition

真机：**PASS**。

- built-in → recording 中插入 wired：AUTO reroute；
- wired → unplug：AUTO 返回 built-in；
- route boundary / discontinuity epoch 正常；
- 同一 WAV 连续；
- 无 read / consumer error。

AudioRecord true invalidation + AUTO serial rebuild 有自动化覆盖，但当前真机未触发：**NOT VERIFIED**。

### P5 manual selected-route loss

真机：**PASS**。

- 手动 wired 拔出后进入 recoverable PAUSED；
- 错误 route PCM 不进入 WAV / waveform / ASR；
- 不 silent fallback 到 phone mic；
- 可 reconnect / select another / select AUTO / stop+save；
- 选择 AUTO 后可在同一 WAV 中以 `DISCONTINUITY` 继续。

该语义继续作为 Bluetooth manual route loss 的冻结边界。

## 5. P6 Bluetooth HFP/SCO compatibility 冻结

Galaxy Buds Live 在普通 `AudioRecord + setPreferredDevice()` 路径上曾出现：framework 报告 `BLUETOOTH_SCO` actual route，但 PCM 为 100% zero。该普通路径在已测手机/耳机组合上保留为 **FAIL evidence**，不能视为产品 Bluetooth mic 方案。

产品 compatibility path：

### API 31+

- `MODE_IN_COMMUNICATION`；
- `setCommunicationDevice()`；
- bounded wait actual communication device；
- 成功后才创建/启动唯一 AudioRecord；
- Bluetooth capture source order：`VOICE_COMMUNICATION → MIC → VOICE_RECOGNITION`；
- `setPreferredDevice()` 与 actual AudioRecord route verification 继续保留。

### API 26–30

- isolated legacy `startBluetoothSco()`；
- 等待 SCO connected 后才启动 AudioRecord；
- 自动化/编译覆盖存在；
- 真机状态：**NOT VERIFIED**。

`MODIFY_AUDIO_SETTINGS` 仅用于 Android communication/SCO route control，不改变 QS668 BLE GATT 生命周期。

Galaxy Buds Live P6-5 真机矩阵：**PASS**：

- non-zero PCM；
- 确认实际来自耳机麦克风；
- pause/resume；
- disconnect → recoverable pause，不录入手机麦克风；
- reconnect → 同一 WAV resume；
- stop → 新 session 正常；
- Bluetooth realtime ASR 正常。

缺少硬件项：

- USB input：**NOT RUN / NOT VERIFIED — missing hardware**；
- BLE Audio / LE Audio input：**NOT RUN / NOT VERIFIED — missing hardware**；
- API26–30 legacy SCO：**NOT VERIFIED**。

## 6. Playback / P8–P10 冻结

播放架构继续固定：

`Recording Library → ServiceBackedPlaybackController → ONE AndroidPlaybackController → StreamingPlaybackController → ONE AudioTrack sink → Android system media route`

P8 只增加 read-only diagnostics，不建立第二个 AudioTrack，不新增自定义 output selector，不改变 focus / speed / seek / presented-position tracker。

冻结诊断包括：

- actual `AudioTrack.routedDevice`；
- route generation / route change count / reason；
- output device add/remove environment event 与 actual route 分离；
- AudioTrack state / play state / sample rate / channel / encoding / buffer；
- underrun count；
- successful writes / bytes；
- zero writes；
- negative writes；
- last write failure；
- timestamp availability / last presented frame；
- P9 recovery visibility：`attempts=0 / result=NOT_ATTEMPTED`。

### P8 available-hardware real-device matrix

全部 **PASS**：

- Galaxy Buds actual route = `BLUETOOTH_A2DP`；
- Bluetooth output removal → automatic pause，phone speaker 不继续外放；
- Bluetooth reconnect → manual Play 即刻从 Buds 恢复；
- wired headset actual route = `WIRED_HEADSET`；
- wired output removal → automatic pause，phone speaker 不继续外放；
- wired reinsert → manual Play 即刻恢复；
- speaker → Galaxy Buds healthy route migration：同一 sink 正常迁移。

代表性 D7 证据：

` sinkGeneration=1 / routeGeneration=6 / routeChanges=5 / actualRoute=BLUETOOTH_A2DP / underruns=0 / negativeWrites=0 / lastWriteFailure=n/a `

因此当前没有 stale/dead AudioTrack、无 app restart requirement、无健康迁移需要 sink recreation 的证据。

### P9

状态冻结为：**NOT TRIGGERED / NOT IMPLEMENTED**。

不得仅因为 route removal 导致 pause 就实现 AudioTrack rebuild。只有未来出现明确 broken-sink 证据时，才允许单独评估 bounded recovery。

### P10

冻结输出移除策略：

`ACTION_AUDIO_BECOMING_NOISY → pause`

headset / Bluetooth output removal **不得静默切到 speaker 并继续播放**。

此前 Galaxy Buds 连接时“旧录音无法启动播放”的严重症状在当前 candidate / P8 QA 中为 **NOT REPRODUCED**；不得反向宣称 P6-5 直接修复了 playback。

## 7. P11 QS668 / CB08 coexistence 冻结

QS668 / CB08 BLE GATT 与 Android Bluetooth Audio 为独立 transport domain。

available-hardware coexistence matrix：**PASS**：

- QS668 GATT + Galaxy Buds SCO phone recording；
- QS668 GATT + Galaxy Buds A2DP local playback + file-list refresh；
- QS668 GATT download + Galaxy Buds phone recording；
- QS668 actual START → local playback pause interlock；
- QS668 RESUME → local playback pause interlock。

不得因为 Android Audio I/O 兼容性修复重构或替换既有 QS668 BLE protocol / transport owner。

## 8. Final CI / QA artifact

Implementation / P8 QA candidate `bb149e663444ef498b5b278c961720d4f1f02e5a`：

- Android PR CI #1699 / run `39043456516` — **SUCCESS**；
- Fast PR Gate — SUCCESS；
- Unit tests + debug compile — SUCCESS；
- QA APK Gate — SUCCESS；
- production-like QA APK build / verify / upload — SUCCESS。

P8 QA artifact：

- name：`Voica-1.1.0-stage16a-export-qa-apk`
- artifact id：`12104719077`
- artifact ZIP digest：`sha256:07c101b2f3a3eac3fdd8205b90d6a77645024992ec60dad7011086c640633e8a`
- extracted APK SHA-256：`570b8c6a91c32b90f1538a5f3ace8d32c29292b0df0c1e49158541df623be76b`

Closeout docs source `761322fe836da1d8863611746b59ffccb2e90ee7`：

- Android PR CI #1700 / run `39047297487` — **SUCCESS**；
- Fast PR Gate — SUCCESS；
- Unit tests + debug compile — SUCCESS；
- QA APK Gate / build / verify / upload — SUCCESS；
- Production Gate 在 PR policy 下 skipped，不是失败。

CI PASS 不替代真机 QA，也不把 missing-hardware 项转为 PASS。

## 9. Stage 16A.1 S15 waiver 继续有效

以下测试未在本阶段重新执行：

`S15 30-minute phone microphone + Small realtime + screen-off`

工程事实继续固定为：

`S15 = NOT RUN / WAIVED BY USER`

不得借其他短时 screen-off / recording / realtime PASS 改写。

## 10. Hard Boundary / 不得回归

Stage 16A.1.6 Freeze 不授权后续无新需求修改：

- old project `ioannes78/voice-card-android` 任何复制、迁移、cherry-pick、机械翻译或改写；
- QS668 / CB08 protocol / GATT transport semantics；
- one-owner AudioRecord architecture；
- canonical PCM / WAV truth；
- ASR / VAD / punctuation / diarization engine semantics；
- Room v13 schema / migration lineage；
- production model-channel pin；
- Stage 16A.1.5 Home / notification frozen UX；
- notification channel registry；
- WorkManager / FGS / WakeLock ownership；
- healthy Android output route migration behavior。

## 11. 下一阶段边界

Stage 16A.1.6 完成后，下一预留阶段为：

**Stage 16A.2 — Timeline Annotation**

状态：**RESERVED / NOT STARTED**。

开始 Stage 16A.2 前必须重新核验 GitHub 当前 main / open PR / CI / source / Room / model channel，并重新完成需求确认与开发规划确认；不得依据本 Freeze 自动开始编码。

## 12. 用户最终批准

用户最终明确回复：

> 确认 Final Freeze/Handoff

因此允许创建 Stage 16A.1.6 Final Freeze / Handoff 文档。文档提交后的最新 branch HEAD 必须重新通过 CI；随后重新核验 `main`、PR #31、mergeability 与 docs-only diff，才允许进入最终 merge gate。
