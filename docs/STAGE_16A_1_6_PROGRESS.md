# Stage 16A.1.6 — Android Audio I/O Compatibility Progress

状态：**IN DEVELOPMENT — A1 CALIBRATION CANDIDATE**

日期：2026-10-16

## 1. 开发授权

用户已明确确认：

1. `Stage 16A.1.6 — Android Audio I/O Compatibility 修订需求 V1`；
2. `Stage 16A.1.6 — Android Audio I/O Compatibility 开发规划 V1`。

因此已授权创建开发分支并按规划实施。

## 2. GitHub 基线 / 当前开发面

开始开发前重新核验：

- repository：`ioannes78/voica-android`
- production branch：`main`
- base main HEAD：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- PR #30：已 merge
- 创建开发分支前 open PR：none
- development branch：`stage16a1_6-android-audio-io-compatibility`
- Draft PR：`#31`

分支严格从上述 base SHA 创建。

A1 功能实现候选（文档进度更新前）：

- `094215aa916341a9980799548b0e3aaca38bf71b`

PR title 已切换为 `[APK] Stage 16A.1.6 — Android Audio I/O Compatibility`，用于触发 production-like QA APK Gate。

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
- one `AudioRecord` owner；
- formal WAV truth before optional ASR fan-out；
- one playback controller；
- one `AudioTrack` sink at a time；
- QS668 BLE GATT 与 Android Bluetooth Audio route 分离。

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

## 7. P1 — Audio I/O diagnostics foundation

已实现：

- Android input device inventory：`AudioManager.GET_DEVICES_INPUTS + AudioDeviceCallback`；
- runtime-only input descriptor：deviceId / type / productName / address / isSource；
- `AUTO` / `SPECIFIC_DEVICE(deviceId)` input request model；
- requested input 与 actual routed input 分离；
- `setPreferredDevice()` acceptance 与 actual route 分离记录；
- route generation / route-change counter / reason tracker；
- manual specific route mismatch 在正式 consumer 前拒绝新 route PCM；
- `clientSilenced` guard 保持在正式 consumer 前；
- read-only PCM signal diagnostics：frame RMS/peak dBFS、clipping、zero ratio；
- 整段聚合 signal summary：overall RMS、全程 peak、clipping/zero ratio、最低 80 ms frame RMS calibration hint；
- production 默认 `VOICE_RECOGNITION → MIC` 顺序保持不变；
- 未加入 software gain。

P1 初始实现 HEAD 的 Android PR CI #1646 中 `Unit tests and debug compile gate` 已 SUCCESS；该 workflow 后续因新提交被 concurrency supersede，因此 job 总状态 cancelled 不能解释为测试失败。

## 8. P2 — A1 Calibration foundation

已实现 QA-only calibration control：

- `AUTO`：保持正常 `VOICE_RECOGNITION → MIC`；
- `FORCE_VOICE_RECOGNITION`：只尝试 VOICE_RECOGNITION；
- `FORCE_MIC`：只尝试 MIC。

隔离规则：

- 仅 `io.github.ioannes78.voica.qa` 可使用 override；
- production build mutation 无效；
- QA calibration Activity 自身不创建第二个 AudioRecord；
- 它只控制下一次正常“手机录音 / 手机实时转写”使用的 AudioSource；
- 正式 WAV 仍走现有 primary recording truth；
- 校准结果读取同一 capture 的 retained diagnostics；
- Home IA 未修改；
- Manifest 未增加 `MODIFY_AUDIO_SETTINGS`；
- 未启用 communication mode / SCO / communication device。

QA UI 入口：

`设置 → 高级设置 → Stage 16A.1.6 音频校准`

仅 `.qa` 构建显示。

## 9. A1 Gate 目标

A1 必须先用同一手机、同一距离、同一音量、同一文本分别完成 VOICE_RECOGNITION 与 MIC 真机采集，再比较：

- actual input route；
- overall RMS dBFS；
- peak dBFS；
- lowest-frame RMS calibration hint；
- clipping ratio；
- zero ratio；
- 实际播放听感；
- realtime ASR 可用性/质量。

A1 结论前：

- 不修改 production 默认 source policy；
- 不实现 software gain；
- 不继续 P3 产品 input selector / P4+ route productization。

如果 A1 暴露需要修改默认 source 或需要 software gain，必须先输出真机证据、原因和修改方案，等待用户明确确认后再编码。

## 10. 当前自动化覆盖

已新增/扩展：

- signal meter 不修改 canonical PCM；
- silence / peak / RMS / clipping / zero ratio；
- aggregate calibration metrics across frames；
- route tracker generation 仅在 actual route 改变时增长；
- input inventory source lookup；
- production-disabled QA override 始终保持 normal source order；
- QA forced VOICE_RECOGNITION / MIC 无 fallback；
- QA calibration entry 只在 `.qa` applicationId 可见。

## 11. 当前待办

- [x] Requirements V1 用户确认
- [x] Development Plan V1 用户确认
- [x] main / PR 状态重新核验
- [x] 创建 Stage 16A.1.6 branch
- [x] Requirements / Development Plan / Progress 入库
- [x] product roadmap 更新为 Stage 16A.1.6 IN DEVELOPMENT
- [x] P0 Room/model-channel/notification/S15 不变量核验
- [x] P1 Audio I/O diagnostics foundation
- [x] P2 A1 calibration foundation
- [ ] current A1 candidate Fast PR Gate
- [ ] production-like QA APK Gate / artifact
- [ ] A1 真机 VOICE_RECOGNITION vs MIC calibration
- [ ] A1 source/gain decision
- [ ] P3+ product compatibility implementation
- [ ] playback route/recovery implementation
- [ ] QS668 coexistence QA
- [ ] Final Freeze/Handoff

## 12. 文档一致性待办

`AGENTS.md` 顶部“当前接管状态”仍是 Stage 16A.1 / PR #29 的旧时点；其中历史冻结规则仍有效，但顶部 current-stage metadata 需要更新为 Stage 16A.1.6 / PR #31。

当前 GitHub 写入接口对该长期规则文件需要整文件替换。为避免在 A1 候选前对大量历史冻结规则做无意义重写或截断，这项只作为 docs-only consistency follow-up，不改变本 A1 二进制候选、技术边界或开发授权事实。后续更新时必须完整保留历史规则正文。

## 13. 下一步

1. 以当前 HEAD 重新跑 `[APK]` Fast PR Gate + production-like QA APK Gate；
2. CI / APK verification 成功后固定 A1 QA APK artifact；
3. 真机 QA 严格每次只发送一条测试项；
4. 首先执行 A1-1（VOICE_RECOGNITION），收集诊断与实际听感；
5. 在后续独立测试项执行 MIC，同条件比较；
6. 得到 A1 结论前不进入 P3+。
