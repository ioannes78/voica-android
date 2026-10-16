# Stage 16A.1.6 — Android Audio I/O Compatibility Progress

状态：**IN DEVELOPMENT — A1 PASS / MIC-FIRST DECISION IMPLEMENTED**

日期：2026-10-17

## 1. 开发授权

用户已明确确认：

1. `Stage 16A.1.6 — Android Audio I/O Compatibility 修订需求 V1`；
2. `Stage 16A.1.6 — Android Audio I/O Compatibility 开发规划 V1`。

A1 真机完成后，用户继续授权按 A1 结论推进：普通 AudioRecord 产品策略改为 `MIC → VOICE_RECOGNITION fallback`，本阶段不加入 software gain；之后继续 P3 输入设备选择。

## 2. GitHub 基线 / 当前开发面

开始开发前及 A1 决策落地前均重新核验：

- repository：`ioannes78/voica-android`
- production branch：`main`
- base main HEAD：`ee708b30e5f36ad0fd4d94b7861b967e2ed1c1a8`
- PR #30：已 merge
- development branch：`stage16a1_6-android-audio-io-compatibility`
- Draft PR：`#31`
- A1 决策落地前 branch HEAD：`5b5239478d250eb06d2f09a3d3583bed99e0646c`
- A1 决策落地前最新 Android PR CI：`#1658`，SUCCESS

分支严格从上述 base SHA 创建。

A1 QA binary/source candidate：

- `18c992b1a259494ffa736b0d8d4c3c58e852cf3e`

其中 A1 功能代码在 `094215aa916341a9980799548b0e3aaca38bf71b` 已完成；`18c992b1...` 仅继续记录 A1 candidate 进度，未改变产品代码。

PR title：`[APK] Stage 16A.1.6 — Android Audio I/O Compatibility`，用于触发 production-like QA APK Gate。

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
- 未加入 software gain。

P1 初始实现 HEAD 的 Android PR CI #1646 中 `Unit tests and debug compile gate` 已 SUCCESS；该 workflow 后续因新提交被 concurrency supersede，因此 job 总状态 cancelled 不能解释为测试失败。

## 8. P2 — A1 Calibration foundation

已实现 QA-only calibration control：

- `AUTO`：使用产品默认 AudioSource 策略；
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

## 9. A1 Real-device Gate — PASS

测试设备实际 route 两侧一致：`RMX3041 / type=15 / id=16 / address=bottom`，两侧 `routeChanges=0`，无 read/consumer error，无 clipping。

### A1-1 — FORCE_VOICE_RECOGNITION

- source：`VOICE_RECOGNITION`
- samples：497280 @ 16 kHz
- overall RMS：`-27.9 dBFS`
- peak：`-6.0 dBFS`
- noiseFloorCandidate：`-57.0 dBFS`
- clipping ratio：`0.0000`
- zero ratio：`0.0019`

### A1-2 — FORCE_MIC

- source：`MIC`
- samples：521280 @ 16 kHz
- overall RMS：`-26.6 dBFS`
- peak：`-2.2 dBFS`
- noiseFloorCandidate：`-72.3 dBFS`
- clipping ratio：`0.0000`
- zero ratio：`0.0037`

### A1 对比 / 用户听感

- MIC overall RMS 约高 `1.3 dB`，不足以解释为显著音量改善；
- MIC peak 高约 `3.8 dB`；
- MIC noise-floor candidate 约低 `15.3 dB`；
- 用户主观音量：**基本一样**；
- 用户主观底噪：**MIC 更干净**；
- 用户主观清晰度：**MIC 更清晰**；
- 双方均无 clipping。

### A1 决策

A1：**PASS**。

经用户确认，普通产品 AudioRecord 默认 AudioSource 策略改为：

`MIC → VOICE_RECOGNITION fallback`

同时明确：

- 本阶段 **不加入 software gain**；MIC 实测 peak 已到 `-2.2 dBFS`，固定增益存在削波风险，且用户听感音量基本相同；
- MIC-first 只是 AudioSource product policy，**不是物理输入设备绑定**；
- 有线 / USB / supported Bluetooth 输入仍由 `AudioDeviceInfo`、`PhoneMicrophoneInputRequest`、`setPreferredDevice()` 与 actual-route verification 独立决定；
- 不因 MIC-first 启用 communication mode、SCO 或 communication device；
- 传统 HFP/SCO 若未来真机证明普通 AudioRecord 路径不可用，必须作为独立 adapter 证据化评估，并再次取得用户明确批准后才能加入；
- 不做机型硬编码。

## 10. A1 production policy implementation

新增 `PhoneMicrophoneAudioSourcePolicy`：

- 产品首选 `MIC`；
- `VOICE_RECOGNITION` 保留为 fallback；
- 只排序 AudioSource candidates，不参与 `AudioDeviceInfo` 物理设备选择；
- 复用现有 `Stage16A16QaAudioSourceOverride.sourceOrder(...)` seam，因此不新增第二个 AudioRecord owner；
- QA forced source 模式继续可用于诊断；
- QA `AUTO` 文案同步为 `MIC → VOICE_RECOGNITION`。

新增/更新自动化覆盖：

- calibrated product policy 必须输出 MIC-first / VOICE_RECOGNITION fallback；
- production-disabled QA override 仍使用 calibrated product order；
- QA AUTO 仍使用 calibrated product order；
- QA force VOICE_RECOGNITION / MIC 仍为 singleton、无 fallback；
- 单一可用 source 不得被策略丢弃；
- signal meter 不修改 canonical PCM；
- route tracker / route guard / clientSilenced 边界不变。

## 11. A1 Candidate CI / Artifact

A1 calibration binary/source candidate：`18c992b1a259494ffa736b0d8d4c3c58e852cf3e`。

Android PR CI：

- run number：`#1657`
- run id：`38920825453`
- Fast PR Gate：**SUCCESS**
- `Unit tests and debug compile gate`：**SUCCESS**
- QA APK Gate：**SUCCESS**
- `Build production-like QA APK only`：**SUCCESS**
- `Verify QA APK`：**SUCCESS**
- `Upload QA APK`：**SUCCESS**
- Android Production Gate #517：PR policy 下 skipped，不是失败。

A1 artifact：

- artifact id：`12055151881`
- artifact name：`Voica-1.1.0-stage16a-export-qa-apk`
- artifact ZIP digest：`sha256:ef980cf1b04a795ea54e605345aa7e3f7eb0e39d56d745a324182d348c2dd6b3`
- extracted APK：`app-releaseQa.apk`
- locally rechecked APK SHA-256：`fe26ac054b8cde024e657f632fc2d07f60d1f314199f152cf65699ec7ae23fc8`

A1 artifact 用于 calibration 证据采集；A1 决策后的 MIC-first 产品改动需要新的 CI，不得用 #1657/#1658 代替其验证。

## 12. 当前待办

- [x] Requirements V1 用户确认
- [x] Development Plan V1 用户确认
- [x] main / PR 状态重新核验
- [x] 创建 Stage 16A.1.6 branch
- [x] Requirements / Development Plan / Progress 入库
- [x] product roadmap 更新为 Stage 16A.1.6 IN DEVELOPMENT
- [x] P0 Room/model-channel/notification/S15 不变量核验
- [x] P1 Audio I/O diagnostics foundation
- [x] P2 A1 calibration foundation
- [x] A1 calibration QA APK / automated gate
- [x] A1 真机 VOICE_RECOGNITION vs MIC calibration
- [x] A1 source/gain decision：MIC-first；software gain rejected for this stage
- [x] A1 production source-policy implementation
- [ ] A1 production source-policy CI
- [ ] P3 product input selector
- [ ] P4+ route compatibility implementation
- [ ] playback route/recovery implementation
- [ ] QS668 coexistence QA
- [ ] Final Freeze/Handoff

## 13. 文档一致性待办

`AGENTS.md` 顶部“当前接管状态”仍是 Stage 16A.1 / PR #29 的旧时点；其中历史冻结规则仍有效，但顶部 current-stage metadata 需要更新为 Stage 16A.1.6 / PR #31。

当前 GitHub 写入接口对该长期规则文件需要整文件替换。为避免对大量历史冻结规则做无意义重写或截断，这项继续作为 docs-only consistency follow-up，不改变技术边界或开发授权事实。后续更新时必须完整保留历史规则正文。

## 14. 下一步

1. 先让 A1 production source-policy 改动通过新的 Android PR CI；
2. CI SUCCESS 只代表自动化 Gate，不替代任何后续真机验证；
3. 通过后进入 P3：把 P1 已存在的 input inventory / AUTO / SPECIFIC_DEVICE 基础接到产品输入设备选择，同时保持 requested route 与 actual route 分离；
4. P3 不引入 SCO / communication mode / software gain，不把 MIC-first 解释为 built-in-mic-only。
