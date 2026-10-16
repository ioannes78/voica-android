# Stage 16A.1.6 — Android Audio I/O Compatibility Progress

状态：**IN DEVELOPMENT — P0 BASELINE / CONTRACT**

日期：2026-10-16

## 1. 开发授权

用户已明确确认：

1. `Stage 16A.1.6 — Android Audio I/O Compatibility 修订需求 V1`；
2. `Stage 16A.1.6 — Android Audio I/O Compatibility 开发规划 V1`。

因此已授权创建开发分支并开始按规划实施。

## 2. 当前 GitHub 基线

开始开发前重新核验：

- repository：`ioannes78/voica-android`
- production branch：`main`
- base main HEAD：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- PR #30：已 merge
- open PR：none
- 新开发分支此前不存在
- development branch：`stage16a1_6-android-audio-io-compatibility`

分支严格从上述 base SHA 创建。

## 3. 上一阶段冻结基线

Stage 16A.1.5 已完成：

- implementation；
- automated tests / CI；
- real-device QA；
- Final Freeze/Handoff；
- merge to main。

不得借 Stage 16A.1.6 重新设计 Stage 16A.1.5 已冻结 Home IA 或 notification projection。

## 4. P0 技术锁定

### Room

当前 Room：**v13**。

Stage 16A.1.6 默认不得提升 Room；必须保留完整 1..13 migration/schema lineage。

### Production model channel

- channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- manifestVersion：7
- manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

本阶段不得自动 promotion 或修改。

### Canonical audio

继续固定：

- PCM16；
- 16 kHz；
- mono。

### Single-owner invariants

继续保持：

- one `AudioRecord` owner；
- formal WAV truth before optional ASR fan-out；
- one playback controller；
- one `AudioTrack` sink at a time；
- QS668 BLE GATT 与 Android Bluetooth Audio route 分离。

## 5. Notification channel freeze

Stage 16A.1.5 冻结的 9 个 physical channel ID 继续保持：

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

本阶段不得改写为 PASS。

## 7. 当前源码审计摘要

开始编码前已确认：

- Phone capture 当前 `VOICE_RECOGNITION → MIC` controlled fallback；
- 当前 capture 未使用 `AudioRecord.setPreferredDevice()`；
- capture 已记录 `routedDevice`，但 route snapshot 维度有限；
- `clientSilenced=true` 已阻止 PCM 进入正式 consumer；
- 当前未显式创建 AGC / NS / AEC effect；
- playback 当前为 single `AndroidPlaybackController` / single `AudioTrack` sink；
- playback 已有 `ACTION_AUDIO_BECOMING_NOISY` pause；
- playback AudioDeviceCallback 当前主要 rebase position，尚无完整 sink route recovery；
- 当前源码未发现 `setCommunicationDevice` / `startBluetoothSco` / `MODE_IN_COMMUNICATION` / speakerphone 强制路由；
- QS668 idle GATT connection 按现有 interlock policy 本身不应暂停 playback，只有实际 Recording 或 fresh START/RESUME edge 才应暂停。

## 8. 当前阶段进度

- [x] Requirements V1 用户确认
- [x] Development Plan V1 用户确认
- [x] main / PR 状态重新核验
- [x] 创建 Stage 16A.1.6 branch
- [x] Requirements V1 入库
- [x] Development Plan V1 入库
- [x] Progress 文档初始化
- [ ] AGENTS 当前接管状态同步
- [ ] product roadmap 状态更新
- [ ] P0 final baseline check
- [ ] P1 Audio I/O diagnostics foundation
- [ ] A1 Calibration APK / real-device Gate
- [ ] P3+ product compatibility implementation
- [ ] CI / QA
- [ ] Final Freeze/Handoff

## 9. 下一步

完成 P0：

1. 更新 `AGENTS.md` 当前接管状态；
2. 把 `ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md` 中 Stage 16A.1.6 从 `RESERVED / NOT STARTED` 更新为 `IN DEVELOPMENT`，并记录 Requirements/Plan；
3. 重新核验 Room/model channel/notification/S15 不变量；
4. P0 通过后进入 P1。
