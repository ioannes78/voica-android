# Stage 16A P2 — Room v13 Realtime Durable Domain

状态：**IMPLEMENTED / CI PASS**

## 1. 基线与范围

P2 在 Stage 16A 已确认规划下把 Room 从 v12 单向升级为 v13，仅增加 realtime durable domain；不修改 Stage 15 BLE / Opus / canonical 行为，不修改 production model channel，也不把 PARTIAL / STABLE 高频结果持久化成正式文本。

## 2. Room v13

新增四个 durable domain：

- `realtime_transcription_runs`
- `realtime_media_segments`
- `realtime_utterances`
- `realtime_timeline_markers`

原则：

- `RealtimeTranscriptionRun` 记录 audio source、ASR/VAD/punctuation lineage、runtime、config snapshot、manifest digest 与生命周期；
- `RealtimeMediaSegment` 表达同一次 realtime run 因 disconnect/reconnect 形成的 `part01 / part02` 等独立手机媒体 segment；
- `RealtimeUtterance` 只持久化 FINAL；
- `RealtimeTimelineMarker` 持久化 Pause/Resume/Discontinuity/Transport Closed/Reconnected 等边界；
- recording 尚未 reconciliation 时 run 的 `recordingId` 允许为空，设备正式 recording 建立后再回填；
- recording 删除时 realtime run / segment / final utterance / marker 通过 FK cascade 一并收口。

## 3. Migration

新增 `MIGRATION_12_13`。Post-V1 runtime 只注册当前支持的 `12 → 13` migration；历史 1..12 schema 继续保留为 schema lineage / migration evidence，不重新恢复 pre-V1 runtime migrations。

Room KSP 已生成并提交 `13.json`；schema lineage 为完整 `1..13`。

## 4. 自动化验证

新增 `Stage16AMigrationTest` 覆盖：

- v12 DB 创建并保留已有 recording；
- 12→13 migration；
- realtime run / segment / FINAL utterance / marker 插入；
- realtime FINAL 内容读取；
- recording 删除后的完整 cascade。

`Stage14V1BaselineContractTest` 已同步到 post-V1 v13 事实，继续确认旧 `MIGRATION_1_2 ... MIGRATION_11_12` 不在当前 runtime builder 注册。

## 5. CI 证据

P2 最终验证 head：`5bac7b802ff0d349b540f98c946b2042589db154`。

Android PR CI：**#1346 / run 38253667314 — SUCCESS**。

- Fast PR Gate：SUCCESS；
- QA APK Gate：SUCCESS；
- Room schema contract：1..13 / current v13 PASS；
- unit tests / debug compile gate：PASS；
- production-like QA APK build / verify / upload：PASS。

前一轮 #1345 暴露的是 Stage 15 `RealtimeRecordingLifecycleReconcilerTest` 等待 `requested` 后立即断言 terminal outcome 的竞态；仅加强测试同步为等待 completed/no-op terminal state，未修改 Stage 15 production behavior，也未降低断言。

## 6. P2 边界

P2 不实现 recognizer、Streaming VAD、PARTIAL/STABLE/FINAL stabilizer、标点或产品 UI。下一步进入 P3 Small Bilingual true-streaming bring-up。
