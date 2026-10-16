# Stage 16A.1.5 Progress

状态：**FINAL FREEZE / HANDOFF — DEVICE QA COMPLETE; MERGE GATE IN PROGRESS**

日期：2026-10-16

## 当前最终基线

- repository：`ioannes78/voica-android`
- target：`main`
- base main（Final Freeze 授权时）：`cd984fc7910092777190547b0e584dc74a260f32`
- development branch：`stage16a1_5-home-notification-ux`
- PR：#30
- Final implementation / QA source：`c6183862eff62b407541f6e52f001e60c5da0864`
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13；未新增 migration
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`，未修改
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`
- Stage 16A.1 S15：`30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

Final Freeze：`docs/STAGE_16A_1_5_FREEZE.md`

Final Handoff：`docs/STAGE_16A_1_5_HANDOFF.md`

## Scope complete

Stage 16A.1.5 已完成：

- Homepage IA consolidation；
- device status + recent content consolidation；
- recorder / phone ordinary recording Home control；
- recorder / phone realtime Home + detail presentation；
- actual/configured realtime model presentation；
- Gain reconciliation UX；
- visible timestamp format normalization；
- App live activity strip semantics / suppression；
- GlobalTaskStatusBar / durable attention presentation consolidation；
- Android notification registry / swipe / deep-link presentation；
- playback / canonical / model-install read-only presentation adapters；
- launcher / notification brand resource QA。

Stage 16A.1.5 没有取得 BLE / Audio I/O / ASR / Room / model-channel 等业务生命周期所有权。

## Final CI

Final implementation candidate `c6183862eff62b407541f6e52f001e60c5da0864`：

- Android PR CI #1640 / run `38895364665`：**SUCCESS**
- Fast PR Gate：**SUCCESS**
- Unit tests + debug compile：**SUCCESS**
- production-like QA APK Gate：**SUCCESS**
- APK verify / upload：**SUCCESS**
- Android Production Gate #500：PR policy 下 skipped

Artifact：

- `Voica-1.1.0-stage16a-export-qa-apk`
- artifact id：`12044161560`
- digest：`sha256:702707a60961386a4e5417c6133ae71bcc90853f9a72f9455bd51d9605160bac`
- APK SHA-256：`fb8320874c8d6d15de44160f0d1c1cfae14c7d8ccf857cfcf4c9d57576987ea2`
- APK size：`31,011,452 bytes`

## Final real-device QA

```text
V4-1 ... V4-16   ALL PASS
H13 ... H20       ALL PASS
QA4-1 ... QA4-10 ALL PASS
```

QA4-10 纠正记录：原测试设计要求在一个 primary active activity 已运行时，再通过正常 Home UI 启动第二个 recording/realtime；当前产品 UI 在 active 时会用 Primary Activity card 替代 idle start controls，该路径不可达，不属于 App 缺陷。修订后的 `stop cleanup → next-task switching` 可达流程真机 PASS。

Stage 16A.1 S15 没有在 Stage 16A.1.5 重跑，继续保持 `NOT RUN / WAIVED BY USER`，不得改写为 PASS。

## Notification registry frozen

现有 9 个 physical channel ID 保持：

- `voica-device-session`
- `voica-phone-microphone`
- `voica-playback`
- `voica-media-processing`
- `voica-ai-summary`
- `model-install`
- `voica-task-result`
- `voica-task-attention`
- `voica-diarization-attention`

没有新增、删除、改名或 importance reset。

## Hard boundary

最终范围审计保持：

- BLE protocol / transport 未改；
- recording/realtime ownership 未改；
- AudioRecord source/route 未改；
- AudioTrack route/recovery 未改；
- canonical audio/sample truth 未改；
- ASR/VAD/punctuation/diarization engine 未改；
- model-install execution 未改；
- WorkManager/FGS/WakeLock ownership 未改；
- Room 保持 v13；
- production model channel 未 promotion；
- `voice-card-android` 完全隔离规则保持。

## 下一阶段

**Stage 16A.1.6 — Android Audio I/O Compatibility**：`RESERVED / NOT STARTED`。

预留范围包括 phone recording level、phone realtime input source、MIC / VOICE_RECOGNITION policy、USB/wired/Bluetooth microphone、Bluetooth output、QS668 BLE coexistence、BLE connected playback、AudioTrack route recovery。

Stage 16A.1.6 不得因本 Stage Handoff 自动开始；必须重新核验 GitHub，并重新完成需求确认与开发规划确认。完成 16A.1.6 后再进入 Stage 16A.2。

## 当前 merge Gate

用户已明确确认 Final Freeze / Handoff。当前只允许 Final docs / roadmap / PR metadata 收口；最新 docs HEAD 必须重新通过 PR CI。CI SUCCESS 后重新核验 PR #30 / main / mergeability，才允许 merge。
