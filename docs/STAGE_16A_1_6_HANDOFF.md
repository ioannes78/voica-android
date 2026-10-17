# Stage 16A.1.6 Final Handoff — Android Audio I/O Compatibility → Stage 16A.2

状态：**FINAL HANDOFF — Stage 16A.1.6 用户已明确确认 Final Freeze/Handoff；等待最新文档 HEAD CI 与最终 merge gate**

Freeze 文档提交：`9800c1996d4f69ff92d176d6e91b945df374eee6`

## 1. 下一阶段

下一预留产品阶段：

**Stage 16A.2 — Timeline Annotation**

状态：**RESERVED / NOT STARTED**。

开始 Stage 16A.2 前必须重新执行：

`重新核验 GitHub → 确认需求 → 确认开发规划 → 才允许编码`

不得因为本 Handoff 已存在而自动开始 Stage 16A.2。

## 2. 接管前必须重新核验

任何后续 Stage 开始前，必须重新核验 GitHub 当前：

1. `main` HEAD；
2. PR #31 是否已 merged；
3. 当前 open PR / branch；
4. 最新 GitHub Actions / CI / Production Gate；
5. `docs/STAGE_16A_1_6_FREEZE.md`；
6. `docs/STAGE_16A_1_6_HANDOFF.md`；
7. `docs/STAGE_16A_1_6_PROGRESS.md`；
8. Stage 16A / 16A.1 / 16A.1.5 Freeze/Handoff；
9. current AudioRecord / input route implementation；
10. current AudioTrack / playback route implementation；
11. QS668 / CB08 BLE GATT coexistence semantics；
12. current source / resources；
13. Room version / migrations / schema；
14. production model channel pin / manifest identity；
15. notification channel registry；
16. Stage 16A.1 S15 waiver status。

GitHub 当前仓库始终是唯一事实来源；本 Handoff 中的 SHA / CI / artifact 只作为历史交接线索。

## 3. Stage 16A.1.6 最终产品候选

Final implementation / physical QA source candidate：

`bb149e663444ef498b5b278c961720d4f1f02e5a`

Final available-hardware QA closeout source：

`761322fe836da1d8863611746b59ffccb2e90ee7`

Freeze 文档提交：

`9800c1996d4f69ff92d176d6e91b945df374eee6`

Freeze / Handoff docs-only commits 不得改变 app/core/engine production behavior；最终 merge 前必须以最新 branch HEAD 重新跑 CI 并核验 diff。

Final identity：

- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13
- production model channel main：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

## 4. Frozen recording input contract

Stage 16A.1.6 交接后继续保持：

- one `AudioRecord` owner；
- PCM16 / 16 kHz / mono；
- formal WAV before optional realtime ASR fan-out；
- same AudioRecord feeds WAV / waveform / realtime；
- `clientSilenced` guard before formal consumers；
- no fake silence；
- `CONTROL_BOUNDARY` for pause/resume；
- `DISCONTINUITY` for route/recovery boundary；
- normal product AudioSource：`MIC → VOICE_RECOGNITION fallback`；
- physical route selection 与 AudioSource policy 分离。

A1 calibration：PASS；本阶段未加入 software gain。

## 5. Frozen route-loss semantics

AUTO：

- wired insertion/removal 真机 PASS；
- Android 自动 reroute；
- same WAV continuity + discontinuity metadata 正常。

MANUAL：

- selected route loss → recoverable PAUSED；
- wrong-route PCM 不得进入 WAV / waveform / ASR；
- 不 silent fallback 到 phone mic；
- reconnect / select another / select AUTO / stop-save 均保留；
- select AUTO 后可在 same WAV 中继续。

AudioRecord true invalidation + AUTO rebuild 真机仍为 NOT VERIFIED；只有自动化覆盖。

## 6. Frozen Bluetooth microphone compatibility

Galaxy Buds Live 普通 `AudioRecord + setPreferredDevice()` 路径的 100% zero PCM 证据保留为 tested-combination failure，不得删除或改写成成功方案。

正式 compatibility path：

- API31+：`MODE_IN_COMMUNICATION + setCommunicationDevice()`；
- API26–30：isolated legacy `startBluetoothSco()`；
- Bluetooth capture AudioSource order：`VOICE_COMMUNICATION → MIC → VOICE_RECOGNITION`；
- setup success 后才启动唯一 AudioRecord；
- final stop / route loss / failure cleanup communication route；
- `MODIFY_AUDIO_SETTINGS` 仅用于 communication route control。

Galaxy Buds Live available-hardware 真机结果：PASS：

- non-zero PCM；
- actual earbud mic capture；
- pause/resume；
- disconnect protection；
- reconnect same-WAV resume；
- stop/new session；
- realtime ASR。

必须继续保持以下未验证状态：

- USB input：NOT RUN / NOT VERIFIED — missing hardware；
- BLE Audio / LE Audio input：NOT RUN / NOT VERIFIED — missing hardware；
- API26–30 legacy SCO：NOT VERIFIED。

## 7. Frozen playback contract

播放 owner 继续是单一链：

`Recording Library → ServiceBackedPlaybackController → ONE AndroidPlaybackController → StreamingPlaybackController → ONE AudioTrack sink`

P8 diagnostics 是 read-only observation：

- actual `AudioTrack.routedDevice`；
- route generation / change count / reason；
- environment add/remove events；
- track/play state；
- format/buffer；
- underrun/write counters；
- timestamp / presented frame；
- P9 recovery status。

available-hardware 真机：

- Bluetooth A2DP actual route：PASS；
- Bluetooth remove → noisy pause/no speaker continuation：PASS；
- Bluetooth reconnect/manual replay：PASS；
- wired actual route：PASS；
- wired remove → noisy pause/no speaker continuation：PASS；
- wired reinsert/manual replay：PASS；
- speaker → Bluetooth healthy route migration in same sink：PASS。

代表性证据：`sinkGeneration=1` 且多次 route generation 增长后仍正常播放，无 underrun / negative write / write failure。

P9：**NOT TRIGGERED / NOT IMPLEMENTED**。

后续不得因为普通 healthy route migration 或 noisy pause 自动引入 AudioTrack rebuild。只有出现明确 broken sink 证据时才可单独立项。

P10：

`ACTION_AUDIO_BECOMING_NOISY → pause`

输出设备移除不得自动切 phone speaker 并继续播放。

## 8. QS668 / CB08 coexistence handoff

QS668 / CB08 BLE GATT 与 Android Bluetooth Audio route 是不同 transport domain。

available-hardware coexistence：PASS：

- QS668 GATT + Bluetooth SCO phone recording；
- QS668 GATT + Bluetooth A2DP playback + file-list refresh；
- QS668 download + Bluetooth recording；
- QS668 actual START → local playback pause；
- QS668 RESUME → local playback pause。

后续不得通过 Audio I/O 改造复制/替换/重构官方 protocol owner，除非有新的独立需求和证据。

## 9. Final CI / QA evidence

Implementation / P8 physical QA candidate `bb149e663444ef498b5b278c961720d4f1f02e5a`：

- Android PR CI #1699 / run `39043456516` — SUCCESS；
- Unit/debug compile — SUCCESS；
- QA APK build/verify/upload — SUCCESS。

QA artifact：

- `Voica-1.1.0-stage16a-export-qa-apk`
- artifact id：`12104719077`
- ZIP digest：`sha256:07c101b2f3a3eac3fdd8205b90d6a77645024992ec60dad7011086c640633e8a`
- APK SHA-256：`570b8c6a91c32b90f1538a5f3ace8d32c29292b0df0c1e49158541df623be76b`

Closeout source `761322fe836da1d8863611746b59ffccb2e90ee7`：

- Android PR CI #1700 / run `39047297487` — SUCCESS；
- Fast PR Gate — SUCCESS；
- Unit/debug compile — SUCCESS；
- QA APK Gate/build/verify/upload — SUCCESS；
- Production Gate under PR policy = SKIPPED，不是失败。

Freeze / Handoff docs 提交后的最新 branch HEAD 必须重新通过 CI，才允许 merge。

## 10. Stage 16A.1 S15 waiver

继续固定：

`S15 30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

本 Handoff 不得把该项改为 PASS。

## 11. Hard boundary

后续不得无明确新需求退化：

- `voice-card-android` 完全隔离；
- QS668 / CB08 BLE protocol / transport；
- one-owner AudioRecord；
- canonical PCM/WAV truth；
- Stage 16A / 16A.1 realtime ownership；
- ASR/VAD/punctuation/diarization engine semantics；
- playback single-controller/single-sink architecture；
- healthy Android route migration；
- Stage 16A.1.5 Home / notification frozen UX；
- Room v13 lineage；
- production model channel pin；
- WorkManager / FGS / WakeLock lifecycle；
- notification channel identity。

## 12. Stage 16A.2 接管边界

Stage 16A.2 仅在重新确认需求和开发规划后开始。

Stage 16A.1.6 不替 Stage 16A.2 预授权 timeline marker 编辑、annotation data model、Room schema 变化或任何 UI 实现。

如果 Stage 16A.2 需要 Room migration，必须以开始该阶段时的 `main` 为事实基线重新设计；不得从本 Handoff 推断 migration 已获授权。

## 13. merge 条件

用户已明确回复：

> 确认 Final Freeze/Handoff

因此允许完成 Final Freeze/Handoff 文档与最终 merge flow。merge 前必须：

1. 重新核验最新 development branch HEAD；
2. 确认 Freeze/Handoff 提交为 docs-only，不改变 production behavior；
3. 等待最新 branch HEAD 的 PR CI SUCCESS；
4. 重新核验 `main` HEAD 未漂移；
5. 重新核验 PR #31 state / head / base / mergeability；
6. 将 Draft PR 标记 Ready for Review；
7. 使用 expected head SHA merge PR #31；
8. merge 后重新核验 `main` / PR state / Room v13 / model channel pin；
9. 如 main merge 触发 Production Gate，则按仓库现有 policy 跟踪并记录结果。
