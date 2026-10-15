# Stage 16A.1.5 Progress

状态：**IN DEVELOPMENT**

日期：2026-10-16

## 当前基线

- repository：`ioannes78/voica-android`
- base main：`cd984fc7910092777190547b0e584dc74a260f32`
- development branch：`stage16a1_5-home-notification-ux`
- open PR at start：0
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- latest pre-stage Android PR CI：#1534 / SUCCESS
- latest pre-stage Android Production Gate：#394 / SUCCESS
- Stage 16A.1 S15：`30-minute phone microphone + Small realtime + screen-off` = `NOT RUN / WAIVED BY USER`

## 已确认边界

- Stage 16A.1.5 位于 16A.1 与 16A.2 之间；
- notification 部分只能改 Android notification、App global state/attention、Global/Attention bars 及 presentation glue；
- 不修改任何 recording / realtime / transcription / diarization / AI Summary / canonical / playback / model install 业务 lifecycle；
- 不修改 FGS ownership、WakeLock、AudioRecord ownership、WorkManager ownership；
- Room 默认保持 v13；
- production model channel 不修改；
- 历史 Freeze/Handoff 不改写。

## 当前执行顺序

`P0 docs/baseline → P1 unified state projection → P2 minimal destination adapter → P3 homepage → P4 global status → P5 notification registry/projection → P6 lifecycle/dedup/dismiss → P7 adapters → P8 tests/CI → P9 one-by-one device QA → P10 Freeze/Handoff`

## P0

- requirements：approved
- development plan：approved
- branch created from verified main
- product roadmap amendment：in progress
- AGENTS current-state refresh：pending

## P1

尚未修改 production UI 或业务 runtime。P1 只允许新增读取既有 truth 的 projection model/resolver 与 unit tests。
