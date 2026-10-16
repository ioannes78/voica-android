# Stage 16A.1.5 Final Handoff — Home & Notification UX Consolidation → Stage 16A.1.6

状态：**FINAL HANDOFF — Stage 16A.1.5 用户整体验收通过；允许文档 Gate 通过后 merge；Stage 16A.1.6 仅预留，尚未开始**

Freeze 文档提交：`be532278027479aa2009762389610f1058dc9244`

## 1. 下一阶段

下一预留产品阶段：

**Stage 16A.1.6 — Android Audio I/O Compatibility**

状态：**RESERVED / NOT STARTED**。

Stage 16A.1.6 完成后才进入 Stage 16A.2 Timeline Annotation。

开始 Stage 16A.1.6 前必须重新执行：

`重新核验 GitHub → 确认需求 → 确认开发规划 → 才允许编码`

不得因为本 Handoff 已存在而自动开始 Stage 16A.1.6。

## 2. 接管前必须重新核验

任何后续 Stage 开始前，必须重新核验 GitHub 当前：

1. `main` HEAD；
2. PR #30 是否已 merged；
3. 当前 open PR / branch；
4. 最新 GitHub Actions / CI / Production Gate；
5. `docs/STAGE_16A_1_5_FREEZE.md`；
6. `docs/STAGE_16A_1_5_HANDOFF.md`；
7. `docs/STAGE_16A_1_5_PROGRESS.md`；
8. Stage 16A / 16A.1 Freeze/Handoff；
9. current source / resources；
10. Room version / migration / schema files；
11. production model channel pin / manifest identity；
12. Android notification channel registry；
13. current AudioRecord / AudioTrack / playback route implementation；
14. current Stage 16A canonical / realtime ownership；
15. current Stage 16A.1 phone microphone ownership。

GitHub 当前仓库始终是唯一事实来源；本 Handoff 的 SHA / CI / artifact 只作为历史交接线索。

## 3. Stage 16A.1.5 最终产品候选

Final implementation / QA source candidate：

`c6183862eff62b407541f6e52f001e60c5da0864`

该 SHA 已完成完整 automated/CI Gate 与最终真机 QA。

Freeze 文档提交后 branch HEAD 会继续前进；后续文档-only commits 不得改变 production app/core/engine 行为。最终 merge 前必须以最新 branch HEAD 重新跑 CI 并核验 diff。

Final identity：

- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

## 4. Final CI / QA artifact

Implementation candidate `c618386...`：

- Android PR CI #1640 / run `38895364665` — **SUCCESS**
- Fast PR Gate — **SUCCESS**
- Unit tests + debug compile — **SUCCESS**
- production-like `releaseQa` — **SUCCESS**
- APK verify / upload — **SUCCESS**
- Production Gate #500：PR policy 下 skipped

QA artifact：

- `Voica-1.1.0-stage16a-export-qa-apk`
- artifact id：`12044161560`
- digest：`sha256:702707a60961386a4e5417c6133ae71bcc90853f9a72f9455bd51d9605160bac`
- APK size：`31,011,452 bytes`
- APK SHA-256：`fb8320874c8d6d15de44160f0d1c1cfae14c7d8ccf857cfcf4c9d57576987ea2`

## 5. Frozen Homepage contract

Home 结构冻结为：

`Header → Device status → source → mode → current task card → recent content`

Device status：

`电量 | 可用空间 | 录音文件 | 固件`

Recent content：

- 最近 3–5 个 recording session；
- union 本地 rich library + current device files；
- remote-only 可直接下载；
- exact remote identity 去重；
- completed transcription truth 驱动 `已转写`；
- recording/event time 排序；
- `查看全部` 回到现有完整录音库。

## 6. Frozen live activity contract

普通录音：

- recorder：`正在录音 · QS668 / CB08 · HH:mm:ss`；
- phone：`正在录音 · 手机 · HH:mm:ss`。

Realtime：

- recorder：`实时转写中 · QS668 / CB08 · HH:mm:ss`；
- phone：`实时转写中 · 手机 · HH:mm:ss`。

Pause：

- recording：`录音已暂停`；
- realtime：`实时转写已暂停`。

Home 已明确展示同一 activity 时不重复 global strip；exact realtime reader 只 suppress 匹配 activity/source；离开后重新显示。

Normal Home 产品 UI 有 primary active activity 时不会继续暴露第二个 idle Start 入口。防御性的多 activity projection 仍保留，用于可重建 truth / 异常恢复或其他已有并发后台任务，但不代表新增了“用户同时启动两路 recording/realtime”的产品能力。

## 7. Frozen realtime detail contract

Recorder / phone realtime 统一：

- `实时转写`；
- source + actual runtime model；
- 中央明显 elapsed；
- 真实 audio-activity waveform；
- live transcript；
- Pause/Resume + Stop；
- 正式产品 UI 不显示 Sx-A / Sx-B latency QA 按钮；
- Home READY 使用 configured model，RUNNING 使用 actual runtime model。

Recorder realtime STOP：完成正常 finalization，但不自动跳历史 recording detail，保持当前 realtime 页面。

## 8. Notification / Attention handoff

9 个 physical channel ID 必须保持：

- `voica-device-session`
- `voica-phone-microphone`
- `voica-playback`
- `voica-media-processing`
- `voica-ai-summary`
- `model-install`
- `voica-task-result`
- `voica-task-attention`
- `voica-diarization-attention`

冻结规则：

- notification registry 是 presentation ownership，不是业务 lifecycle owner；
- system swipe != business acknowledge；
- durable App attention 可继续存在；
- exact result page 真正可见后才 acknowledge；
- logical result/generation dismissal 防止立即重复通知；
- FGS / WorkManager / UIDT / WakeLock ownership 不因 Stage 16A.1.5 改变；
- Mini Player 仅属于 playback。

## 9. Final real-device QA

Stage 16A.1.5 Final Freeze 前：

```text
V4-1 ... V4-16 = ALL PASS
H13 ... H20     = ALL PASS
QA4-1 ... QA4-10 = ALL PASS
```

QA4-10 特别说明：原测试曾要求从正常 Home UI 同时启动 recorder realtime + phone ordinary recording；该路径因 primary active UI 设计不可达，因此测试计划被纠正，不记为 App FAIL。修订后的 stop cleanup → next-task switching 真机流程 **PASS**。

## 10. Stage 16A.1 S15 用户豁免继续有效

Stage 16A.1：

`S15 30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`

Stage 16A.1.5 没有重新执行该 30 分钟 soak；任何后续文档都不得把它改写为 PASS。

## 11. Stage 16A.1.6 接管范围

Stage 16A.1.6 预留处理：

- phone actual recording too low；
- phone realtime input source；
- MIC / VOICE_RECOGNITION source policy；
- USB microphone；
- wired microphone；
- usable Bluetooth microphone；
- Bluetooth output；
- QS668 BLE GATT coexistence with AudioRecord / AudioTrack；
- library playback failing while BLE connected；
- AudioTrack route recovery。

产品意图：

- “手机”表示 Android capture source，而不是永久强制 built-in microphone；
- 继续保持一个 AudioRecord owner；
- QS668/CB08 BLE GATT recorder 与 Bluetooth audio route 必须区分；
- playback 继续保持 single controller；
- 不得以兼容性修复破坏 Stage 16A/16A.1 canonical truth。

这些都 **没有在 Stage 16A.1.5 实现**。

## 12. Hard boundary

后续不得无明确新需求退化：

- BLE protocol/transport；
- recorder command semantics；
- one-owner recording/realtime architecture；
- canonical media/sample truth；
- ASR/VAD/punctuation/diarization engine semantics；
- model-install execution ownership；
- Room v13 migration lineage；
- WorkManager/FGS/WakeLock lifecycle；
- notification channel identity / business lifecycle；
- production model-channel pin；
- `voice-card-android` 完全隔离规则。

## 13. merge 条件

用户已明确回复：

> 确认 Final Freeze / Handoff

因此允许完成 Final docs；随后必须：

1. 重新核验最新 branch HEAD；
2. 确认文档-only diff 没有 production behavior 变化；
3. 等待最新 branch HEAD 的 PR CI SUCCESS；
4. 重新核验 `main` HEAD、PR #30 mergeability；
5. 使用 expected head SHA merge PR #30；
6. merge 后重新核验 `main` / Room / model pin / PR state；
7. 如 main merge 触发 Production Gate，则按仓库现有 policy 等待并记录最终结果。
