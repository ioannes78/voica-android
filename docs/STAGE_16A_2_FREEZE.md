# Stage 16A.2 Final Freeze — Timeline Annotation

状态：**FINAL FREEZE — 用户已明确确认 Final Freeze/Handoff；Stage 16A.2 applicable/tested 真机 QA 已完成**

冻结日期：2026-10-19

## 1. 冻结范围

Stage 16A.2 冻结 Voica 当前 Recording Timeline Annotation 产品能力：

- Recording-owned Point Annotation；
- 快速标记；
- 文字笔记；
- 拍照与相册图片附件；
- sample-based durable timeline anchor；
- 普通手机录音 / 普通 QS668 录音 / 手机 realtime / QS668 realtime 创建入口；
- Recording detail `标注` 管理页；
- waveform 标注投影与长按定位；
- transcript timeline / reading mode 动态标注投影；
- annotation → existing playback seek；
- Recording 删除时 annotation / attachment 生命周期清理。

本 Freeze 不把 Annotation 变成 TranscriptVersion、Revision、Segment、Token、DiarizationRun、Speaker 或 AI Summary 的所有物，也不允许在无法建立精确媒体映射时伪造连续时间轴。

Stage 16A.2 首版不包含 Range Annotation、OCR、图片 AI 理解、全局 Annotation FTS、Annotation export、协作、云端同步、语义标签/优先级/工作流状态。

## 2. Final Freeze 前 GitHub 真实基线

用户确认 Final Freeze/Handoff 后重新核验：

- repository：`ioannes78/voica-android`
- target branch：`main`
- Stage 16A.2 branch point：`7daa031a20e07f262ef28d55f665d39f6bd6500e`
- Final Freeze 时 `main` HEAD：`7348def92ea0ccebe67543fd468e06e6cb16fce8`
  - 相比 branch point 仅包含 PR #34 的 CI artifact 策略优化；
  - 不属于 Stage 16A.2 product behavior。
- development branch：`stage16a2-timeline-annotation`
- PR：#33 `Stage 16A.2 — Timeline Annotation`
- Final real-device-tested App source：`a86f7cc145e53dcaef714e9f3eeda84147dcc776`
- pre-Freeze closeout docs source：`28c1ab017526e2bb8b426a6fafbb852efe1c3fdc`
- versionCode：92
- versionName：`1.1.0-stage16a2`
- Room：v14
- migrations：`MIGRATION_12_13`, `MIGRATION_13_14`
- Room v14 schema identityHash：`c60995f7ba406f15a3e786add6e70197`
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Freeze / Handoff / closeout 文档提交后的 branch HEAD 会晚于已真机测试的 App source；这些 docs-only commits 不得改变 app/core/engine production behavior，也不得被描述为“重新测试过的新 App binary”。

## 3. Annotation ownership contract 冻结

最终 hard owner：**Recording**。

冻结规则：

- Annotation 可以只有 anchor，也可以包含 note；
- 一个 Annotation 可有 `0..N` photo attachments；
- active capture 在最终 Recording row 尚未建立前可使用 durable `PENDING_SESSION` staging；
- staging 必须在可确认最终 Recording 后 promote 到 `ATTACHED_RECORDING`；
- `PENDING_SESSION` 不是第二套长期 ownership；
- 只有 `recordingId` 是最终 ownership FK；
- transcript / revision / segment / token / diarization / speaker / realtime lineage 只允许作为 provenance snapshot，不得形成 ownership cascade。

因此：

- re-ASR 不得删除或迁移 Annotation；
- transcription revision 删除不得删除 Annotation；
- re-diarization / speaker 变化不得删除或迁移 Annotation；
- AI Summary 生命周期不得拥有 Annotation。

## 4. Durable timeline contract 冻结

Primary durable coordinate：**sample index**，不是 wall-clock ms、文本字符位置或 transcript 版本内 offset。

支持 anchor domains：

- `CANONICAL_RECORDING`
- `REALTIME_MEDIA_SEGMENT`

冻结原则：

- canonical recording 优先使用可验证 sample-space identity；
- realtime 使用 run / segment / session / epoch lineage + segment-local sample；
- phone capture/realtime 的映射必须来自正式媒体/sample truth；
- 不得使用 wall-clock elapsed time 代替 canonical media truth；
- QS668 disconnect/reconnect 不能通过简单累计 segment duration 伪造连续 timeline；
- exact mapping 不可证明时保留 Annotation，但拒绝 precise seek。

QS668 disconnect/reconnect 真机 QA 已确认 safe-degradation 行为：Annotation 保留，无法安全映射时明确拒绝精确跳转，而不是构造错误位置。

## 5. Annotation create / projection 冻结

### 普通录音 / realtime capture

统一 `＋标注` composer 支持：

- 快速标记；
- 文字笔记；
- 拍照；
- 相册。

已覆盖的入口包括：

- ordinary phone recording；
- ordinary QS668 / CB08 recording；
- phone realtime transcription；
- QS668 / CB08 realtime transcription。

适用 capture 控件中 `＋` 保持在最右侧，不建立第二个 recorder owner 或独立 timeline source。

### Transcript create / projection

Annotation 仍属于 Recording，通过 audio sample position 动态投影到当前 transcript。

冻结行为：

- exact cue / timed row 可建立 sample anchor；
- segment-only timing 使用可证明的 segment timing；
- revision 有可靠 anchor 时使用 revision anchor；
- 无可靠 timing 时不得根据文本长度猜测时间；
- 可以显式使用当前 playback position 作为用户可理解的 fallback；
- provenance snapshot 不产生 cascade ownership。

Timeline view：

- timed row 提供创建入口；
- 已存在 Annotation 使用 compact horizontal projection；
- Annotation detail surface 可查看/编辑 note、查看/删除 photo、删除 Annotation、seek。

Reading view：

- 只投影已存在 Annotation；
- 不提供新建 Annotation action；
- Annotation UI 与 selectable transcript text 分离；
- 连续阅读 / 连续复制文本行为保持不变。

QA-C2.1 最终冻结 `＋标注` 与 timeline row header 同行，不再膨胀为独立视觉行。

## 6. Recording detail / waveform / playback 冻结

Recording detail 保持：

`录音 | 转写 | 总结 | 标注`

Annotation tab：

- chronological compact list；
- local filters：全部 / 有笔记 / 有图片 / 快速标记；
- note edit；
- attachment view/delete；
- annotation delete；
- time movement / seek。

Waveform：

- 显示 canonical Annotation marker；
- blank waveform long-press 可创建；
- 最终 UX 为 long-press coarse locate + hold/drag fine adjustment + release create；
- V1 不允许已创建 marker 在 waveform 上直接拖拽迁移。

Playback 必须继续复用 Stage 16A.1.6 已有唯一 playback chain；Annotation 不得创建独立 PlaybackController / AudioTrack。

点击 Annotation：

- PLAYING → seek 后继续 PLAYING；
- PAUSED → seek 后保持 PAUSED；
- READY → seek，不自动播放。

## 7. Photo attachment contract 冻结

支持 camera + gallery。

冻结要求：

- 导入后复制为 app-private durable attachment；
- 不永久依赖外部 URI；
- 支持 thumbnail / full view / individual delete；
- Annotation 删除只清理其 attachment rows/files；
- Recording 删除清理 Recording-owned Annotation 与 app-private attachment files；
- camera/gallery lifecycle 不得中断正在进行的 phone/QS668 capture 或 realtime transcription。

Stage 16A.2 不实现 OCR、图片理解、云端图片库或图片参与 AI Summary。

## 8. Room v14 冻结

Stage 16A.2 引入并冻结：

- `TimelineAnnotationEntity`
- `TimelineAnnotationAttachmentEntity`
- `TimelineAnnotationDao`
- `MIGRATION_13_14`
- `VoicaDatabase version = 14`
- generated `14.json`
- migration/schema contract tests

Runtime migration chain 当前包含：

- `MIGRATION_12_13`
- `MIGRATION_13_14`

不得引入 destructive migration 代替正式迁移。

## 9. Stage 16A.1.6 Audio I/O invariants 继续冻结

Stage 16A.2 没有授权修改以下架构：

- exactly one `AudioRecord` owner；
- formal WAV truth before optional realtime fan-out；
- canonical PCM16 / 16 kHz / mono；
- one playback controller / one `AudioTrack` sink；
- existing Audio Focus / route / `ACTION_AUDIO_BECOMING_NOISY` semantics；
- QS668 / CB08 BLE GATT 与 Android Bluetooth Audio 分离；
- healthy output route migration 不因 Annotation 引入额外 sink rebuild。

历史 limitation 不因 Stage 16A.2 自动升级：

- API26–30 legacy SCO：仍不得宣称由本阶段验证；
- BLE Audio / LE Audio：不得因一般 Bluetooth mic regression 测试而宣称完整验证；
- P9 AudioTrack bounded recovery：仍为 `NOT TRIGGERED / NOT IMPLEMENTED`；
- Stage 16A.1 S15 `30-minute phone microphone + Small realtime + screen-off`：仍为 `NOT RUN / WAIVED BY USER`。

Stage 16A.2 对用户现有可用 Bluetooth microphone path 与一条 available external-input path 做过成功 regression check；后者不能被扩写成“所有 wired 和 USB transport 都分别验证”。

## 10. Real-device QA 冻结

Applicable/tested matrix 最终结论：**PASS**。

覆盖内容包括：

- 无 transcript 创建 Annotation；
- quick marker / note / edit / delete；
- reopen persistence；
- PLAYING / PAUSED / READY seek semantics；
- waveform long-press + fine positioning；
- transcript timed create / projection；
- re-ASR ownership stability；
- re-diarization ownership stability；
- gallery / camera attachment；
- single attachment delete / multiple photos；
- local filters；
- Recording delete lifecycle cleanup；
- ordinary phone recording annotation；
- ordinary QS668 recording annotation；
- phone realtime annotation；
- phone pause/resume exact canonical mapping；
- QS668 realtime annotation；
- QS668 disconnect/reconnect safe degradation；
- camera lifecycle during ongoing phone/QS668 capture；
- transcript compact projection / detail management；
- reading-mode view-only projection；
- speaker-count edit entry；
- QA-C2.1 inline `＋标注` layout correction。

Initial QA 暴露的问题没有被伪写为 PASS，而是完成批量修订后重新验证。最终用户于 2026-10-19 对 QA-C2.1 回复：**通过**，随后明确批准 Final Freeze/Handoff。

## 11. Final CI / QA artifact

Final real-device-tested App source：

`a86f7cc145e53dcaef714e9f3eeda84147dcc776`

Fast validation：

- Android PR CI #1791 / run `39181093536` — **SUCCESS**；
- Fast PR Gate — SUCCESS；
- unit tests and debug compile gate — SUCCESS。

Production-like QA APK validation：

- Android PR CI #1792 / run `39181316104` — **SUCCESS**；
- QA APK Gate — SUCCESS；
- artifact：`Voica-1.1.0-stage16a2-export-qa-apk`
- artifact id：`12156731284`
- artifact ZIP digest：`sha256:6ba8ca95baa3e08e5d2fe1a92f9030fe5204f14d34ad50dd7b913ed776d5148f`
- extracted/tested QA APK SHA-256：`6565ecb9e47280f3c401a3eca0e011430ee62598c7d739c015de719691f46e62`

Pre-Freeze closeout docs HEAD：

`28c1ab017526e2bb8b426a6fafbb852efe1c3fdc`

- Android PR CI #1794 / run `39185089442` — **SUCCESS**；
- Fast PR Gate — SUCCESS；
- QA APK Gate — SKIPPED by docs-only/CI design，不是失败。

CI PASS 是 build/test evidence，不替代上述真机 QA。

## 12. Hard Boundary / 不得回归

后续阶段不得在没有独立需求与重新确认的情况下修改或退化：

- `voice-card-android` 完全隔离规则；
- Recording-owned Annotation domain；
- sample-index durable timeline truth；
- exact-or-refuse mapping safety；
- one-owner AudioRecord architecture；
- single playback controller / AudioTrack；
- QS668 / CB08 BLE protocol / GATT transport semantics；
- ASR / VAD / punctuation / diarization engine semantics；
- Room v14 migration lineage；
- production model-channel pin；
- WorkManager / FGS / WakeLock ownership；
- Stage 16A.1.5 Home / notification frozen UX；
- Stage 16A.1.6 Audio I/O frozen semantics。

## 13. 下一阶段边界

按照当前 approved roadmap，Stage 16A.2 之后的下一产品阶段为：

**Stage 16B — Local File ASR V2**

状态：**RESERVED / NOT STARTED**。

开始 Stage 16B 前必须重新核验 GitHub 当前 `main` / open PR / CI / source / resources / Room / model channel / Freeze/Handoff，并重新输出需求确认与开发规划；不得依据本 Freeze 自动开始编码。

## 14. 用户最终批准

用户最终明确回复：

> 确认 Final Freeze/Handoff

因此 Stage 16A.2 product behavior 允许 Final Freeze，并允许继续创建 Final Handoff、执行最新 docs-only HEAD CI、PR finalization、merge 与 post-merge verification。