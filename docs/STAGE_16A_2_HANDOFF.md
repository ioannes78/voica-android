# Stage 16A.2 Final Handoff — Timeline Annotation → Stage 16B

状态：**FINAL HANDOFF — Stage 16A.2 用户已明确确认 Final Freeze/Handoff；等待最新 docs-only HEAD CI 与最终 merge gate**

Freeze 文档提交：`415810514889fdac3e0750d6ee3cf42a9bc1eacc`

## 1. 下一阶段

下一预留产品阶段：

**Stage 16B — Local File ASR V2**

状态：**RESERVED / NOT STARTED**。

开始 Stage 16B 前必须重新执行：

`重新核验 GitHub → 输出修订需求 → 用户确认 → 输出开发规划 → 用户确认 → 才允许编码`

不得因为本 Handoff 已存在而自动开始 Stage 16B。

## 2. 接管前必须重新核验

任何后续 Stage 开始前，必须重新核验 GitHub 当前：

1. `main` HEAD；
2. PR #33 是否已 merged；
3. 当前 open PR / branch；
4. 最新 GitHub Actions / CI / Production Gate；
5. `docs/STAGE_16A_2_FREEZE.md`；
6. `docs/STAGE_16A_2_HANDOFF.md`；
7. `docs/STAGE_16A_2_PROGRESS.md`；
8. Stage 16A / 16A.1 / 16A.1.5 / 16A.1.6 Freeze/Handoff；
9. current Recording / AudioAsset / canonical sample truth；
10. current Timeline Annotation entities / DAO / repositories / attachment store；
11. current transcript timeline projection / reading-mode projection；
12. current phone and QS668 capture/realtime annotation integration；
13. current playback controller / AudioTrack architecture；
14. current source / resources；
15. Room version / migrations / schema；
16. production model channel pin / manifest identity；
17. Stage 16A.1 S15 waiver status；
18. Stage 16A.1.6 remaining hardware caveats。

GitHub 当前仓库始终是唯一事实来源；本 Handoff 中的 SHA / CI / artifact 只作为历史交接线索。

## 3. Stage 16A.2 最终产品候选

Final real-device-tested App source：

`a86f7cc145e53dcaef714e9f3eeda84147dcc776`

Pre-Freeze closeout docs source：

`28c1ab017526e2bb8b426a6fafbb852efe1c3fdc`

Freeze 文档提交：

`415810514889fdac3e0750d6ee3cf42a9bc1eacc`

Freeze / Handoff / progress / roadmap docs-only commits 不得改变 app/core/engine production behavior；最终 merge 前必须以最新 branch HEAD 重新跑 CI 并核验从 `a86f7cc...` 起的 delta 只涉及授权文档。

Final identity：

- versionCode：92
- versionName：`1.1.0-stage16a2`
- Room：v14
- migrations：`MIGRATION_12_13`, `MIGRATION_13_14`
- Room v14 schema identityHash：`c60995f7ba406f15a3e786add6e70197`
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

## 4. Frozen Annotation ownership contract

Stage 16A.2 交接后继续固定：

- hard owner = Recording；
- quick marker = anchor-only Annotation；
- optional note；
- `0..N` photo attachments；
- active capture 可以 durable `PENDING_SESSION` staging；
- staging 最终 promote 到 `ATTACHED_RECORDING`；
- only `recordingId` is ownership FK；
- transcript / revision / segment / token / diarization / speaker / realtime values are provenance snapshots only。

后续不得因为 re-ASR、revision delete、re-diarization、speaker rename 或 AI Summary lifecycle 删除或迁移 Recording-owned Annotation。

## 5. Frozen timeline contract

Primary durable coordinate：**sample index**。

支持：

- `CANONICAL_RECORDING`
- `REALTIME_MEDIA_SEGMENT`

后续实现必须继续遵守：

- canonical/sample truth 优先；
- phone capture/realtime 不以 wall-clock 代替媒体 sample truth；
- QS668 disconnect gap 不允许伪造连续 sample timeline；
- mapping 必须 exact-or-refuse；
- 无法安全映射时保留 Annotation，但 precise seek 必须拒绝。

如果 Stage 16B 或更后阶段修改 canonical-file pipeline，不得破坏现有 Annotation sample identity。任何必要 remap 必须有独立数据合同和 migration 设计，不能通过文本位置或时长比例猜测。

## 6. Frozen create surfaces

统一 `＋标注` composer 已接入：

- ordinary phone recording；
- ordinary QS668 recording；
- phone realtime transcription；
- QS668 realtime transcription；
- transcript timeline timed rows；
- waveform long-press create。

Composer 支持：

- 快速标记；
- 文字笔记；
- 拍照；
- 相册。

适用 realtime/capture 控件中 `＋` 保持在最右侧。

## 7. Frozen Recording detail / transcript UX

Recording detail tabs：

`录音 | 转写 | 总结 | 标注`

Annotation management：

- compact chronological list；
- filters：全部 / 有笔记 / 有图片 / 快速标记；
- note edit；
- photo view/delete；
- annotation delete；
- seek；
- waveform marker projection。

Transcript timeline：

- Annotation 按 sample position 动态投影，不属于 TranscriptVersion；
- timed row 提供创建入口；
- compact horizontal annotation projection；
- shared annotation detail/edit/delete/photo/seek surface；
- QA-C2.1 最终 `＋标注` 与 timeline row header 同行。

Transcript reading mode：

- existing Annotation 可显示/隐藏、查看；
- 不提供新建 Annotation action；
- selectable continuous transcript text 不被 Annotation UI 切断；
- 连续阅读 / 连续复制行为必须保持。

Speaker UX：

- document summary 的 speaker count 自身作为 speaker-edit entry；
- 不需要额外独立右侧 speaker-edit button。

## 8. Frozen waveform / playback semantics

Waveform：

- canonical markers；
- long-press coarse locate；
- hold/drag fine adjustment；
- release create；
- V1 不允许已创建 marker 直接 waveform drag reposition。

Playback 必须继续走唯一既有链路；Annotation 不建立第二个 PlaybackController / AudioTrack。

点击 Annotation：

- PLAYING → seek + keep playing；
- PAUSED → seek + remain paused；
- READY → seek + no autoplay。

## 9. Frozen photo attachment lifecycle

Photo attachment：

- camera + gallery；
- app-private durable copy；
- 不永久依赖 external URI；
- thumbnail / full view / per-photo delete；
- Annotation delete 清理其 attachments；
- Recording delete 清理 Recording-owned annotations + physical app-private attachment files；
- camera/gallery round-trip 不得终止 ongoing capture/realtime。

Stage 16A.2 没有实现 OCR、image AI、cloud photo library 或 photo-aware AI Summary。

## 10. Room v14 handoff

Stage 16A.2 新增：

- `TimelineAnnotationEntity`
- `TimelineAnnotationAttachmentEntity`
- `TimelineAnnotationDao`
- `MIGRATION_13_14`
- `VoicaDatabase version = 14`
- schema `14.json`

后续 Stage 必须从实际 `main` 的 Room v14 基线继续；不得回退到 v13，也不得使用 destructive fallback 绕过 migration。

## 11. Stage 16A.1.6 invariants / historical caveats

继续保持：

- one `AudioRecord` owner；
- PCM16 / 16 kHz / mono canonical recording truth；
- formal WAV before optional realtime ASR fan-out；
- one playback controller / one `AudioTrack` sink；
- Android Audio Focus / route / noisy semantics；
- QS668 BLE GATT 与 Android Bluetooth Audio 分离。

历史状态不得被 Stage 16A.2 改写：

- API26–30 legacy SCO：NOT VERIFIED unless separately evidenced；
- BLE Audio / LE Audio：Stage 16A.2 generic Bluetooth mic regression 不等于完整验证；
- P9 AudioTrack bounded recovery：`NOT TRIGGERED / NOT IMPLEMENTED`；
- Stage 16A.1 S15：`30-minute phone microphone + Small realtime + screen-off = NOT RUN / WAIVED BY USER`。

Stage 16A.2 available external-input regression 只证明实际测试到的 path；不得扩写为所有 wired + USB transport independently PASS。

## 12. Final QA evidence

Applicable/tested Stage 16A.2 matrix 最终：**PASS**。

最终覆盖包括：

- no-transcript annotation；
- marker / note / edit / delete / persistence；
- PLAYING / PAUSED / READY seek；
- waveform fine positioning；
- transcript timed create / projection；
- re-ASR / re-diarization ownership stability；
- camera / gallery / multi-photo / per-photo delete；
- filters；
- Recording delete cleanup；
- ordinary phone / QS668 recording annotation；
- phone / QS668 realtime annotation；
- phone pause/resume exact mapping；
- QS668 disconnect safe degradation；
- capture + camera lifecycle；
- transcript compact projection / reading-mode view-only projection；
- speaker-count edit entry；
- QA-C2.1 timeline-row inline `＋标注`。

用户最终真机结果：QA-C2.1 **通过**，随后明确回复 `确认 Final Freeze/Handoff`。

## 13. Final CI / APK evidence

Final real-device-tested App source `a86f7cc145e53dcaef714e9f3eeda84147dcc776`：

- Android PR CI #1791 / run `39181093536` — SUCCESS；
- Fast PR Gate — SUCCESS；
- unit tests + debug compile — SUCCESS。

Production-like QA candidate：

- Android PR CI #1792 / run `39181316104` — SUCCESS；
- QA APK Gate — SUCCESS；
- artifact：`Voica-1.1.0-stage16a2-export-qa-apk`
- artifact id：`12156731284`
- ZIP digest：`sha256:6ba8ca95baa3e08e5d2fe1a92f9030fe5204f14d34ad50dd7b913ed776d5148f`
- tested APK SHA-256：`6565ecb9e47280f3c401a3eca0e011430ee62598c7d739c015de719691f46e62`

Pre-Freeze closeout docs source `28c1ab017526e2bb8b426a6fafbb852efe1c3fdc`：

- Android PR CI #1794 / run `39185089442` — SUCCESS；
- Fast PR Gate — SUCCESS；
- QA APK Gate — SKIPPED by CI design，不是失败。

Freeze / Handoff / final closeout docs 提交后的最新 branch HEAD 必须再次通过 Fast PR Gate 后才允许 merge。

## 14. Stage 16B 接管边界

Stage 16B = **Local File ASR V2**。

Stage 16B 不得因为处理本地文件而破坏 Stage 16A.2 已冻结的 Recording/sample identity。特别是：

- re-ASR 不能迁移 Annotation ownership；
- transcript version change 不能修改 Annotation sample anchor；
- canonical asset replacement/derivation 若会影响 sample identity，必须先明确设计 exact mapping / lineage；
- 不能用 transcript 文本长度、word index、segment ordinal 或总时长比例近似迁移 Annotation；
- Stage 16B 若新增 Room schema，必须基于实际 `main` v14 重新设计 migration；
- 不得借 Stage 16B 修改 QS668 GATT、Android Audio I/O、Annotation photo lifecycle 或 production model channel，除非另有明确授权。

## 15. Hard boundary

后续不得无新需求退化：

- `voice-card-android` 完全隔离；
- Recording-owned Annotation；
- exact sample timeline semantics；
- one-owner AudioRecord；
- single playback controller / AudioTrack；
- canonical WAV truth；
- existing ASR / VAD / punctuation / diarization semantics outside authorized Stage scope；
- QS668 / CB08 BLE protocol / transport；
- Room v14 lineage；
- production model-channel pin；
- WorkManager / FGS / WakeLock lifecycle；
- Stage 16A.1.5 Home / notification freeze；
- Stage 16A.1.6 Audio I/O freeze。

## 16. merge 条件

用户已明确回复：

> 确认 Final Freeze/Handoff

因此允许完成 Final Freeze/Handoff 文档与最终 merge flow。merge 前必须：

1. 完成 progress / roadmap closeout；
2. 重新核验最新 development branch HEAD；
3. 确认从 tested App source `a86f7cc...` 到最终 branch HEAD 的 delta 仅为授权 docs-only 变化；
4. 等待最终 branch HEAD 的 PR CI SUCCESS；
5. 重新核验 `main` HEAD；
6. 重新核验 PR #33 state / head / mergeability；
7. 将 Draft PR 标记 Ready for Review；
8. 使用 expected head SHA merge PR #33；
9. merge 后重新核验 `main` / PR state / Room v14 / model channel pin；
10. 如 merge 触发额外 workflow，则按仓库当前 policy 跟踪并如实记录。