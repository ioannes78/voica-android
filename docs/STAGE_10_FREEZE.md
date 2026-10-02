# Voica Stage 10 Freeze

状态：**FROZEN / 用户验收通过**

日期：2026-10-02

## 1. Freeze 结论

Stage 10 — 转写时间轴 + 播放同步完成并冻结。

用户最终明确确认：

**“测试通过”**

Freeze 前 implementation HEAD：

`0b1e7d41a284a92af2dc3c39413014c31586235a`

最终 QA：

- versionCode：26
- versionName：`0.10.0-stage10-alpha1`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：3
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- QA CI：#287 / `36989306411` — success
- APK SHA-256：`92def5738a7f023f1f1a8e8a052d8c7a2331a46a646c6ad3c56bd60f14a23a7b`

## 2. Timeline Freeze

唯一媒体时间真值继续固定为：

**16 kHz canonical PCM absolute sample index**

不得引入 wall-clock、MediaPlayer ms 或 elapsed×speed 作为 transcript/playback 主同步时间。

边界统一为：

`startSampleIndex <= positionSampleIndex < endSampleIndexExclusive`

silence/gap 与总样本末端不产生虚假 active row/token。

## 3. Timeline Model Freeze

Stage 10 Timeline 是 Room v3 数据的只读派生模型，不建立第二套持久化 transcript/timeline 数据库。

row 选择：

- completed speaker alignment 存在 → `TranscriptSpeakerSpan`
- 无 alignment → `TranscriptSegment`

stable identity 必须包含实际 span/segment identity，并与 transcription version 隔离，不得退回 displayIndex。

## 4. Token / finalText Freeze

Stage 9 的 token→finalText projection 已抽取为共享纯逻辑。

projection quality：

- EXACT
- HEURISTIC
- UNAVAILABLE

默认只允许 **EXACT** 产生深色 current-token cue。

HEURISTIC / UNAVAILABLE：

- 不伪造精确 current token
- 仍允许 row/span 浅色高亮
- 仍允许 row-level seek

FAST：

- Small Bilingual Zipformer FIRST_PASS token timestamps

HQ：

- timed SECOND_PASS 优先
- timed FIRST_PASS fallback

ASR token 不保证等同自然语言词；不得为了 UI 强行做无依据 NLP 分词并伪造时间。

## 5. Punctuation Freeze

标点模型改变 finalText，但 punctuation 本身不拥有独立 ASR 时间戳。

Stage 10 通过 finalText projection 把标点/空白附着到相邻 token display range。

不得为 `，。？！` 单独制造 sample interval。

## 6. Playback Mapping Freeze

正向：

`PlaybackSnapshot.positionSampleIndex → TimelinePosition → active row/cue/speaker`

反向：

`Transcript row/token click → absolute sample → PlaybackController.seekToSample()`

点击文字产品行为固定为：

- 未加载目标 recording：load → seek → play
- 已加载：seek → play

rapid transcript click 必须 latest-wins。

Stage 7 的 playback seek/discontinuity 语义继续有效。

## 7. Source Lineage Freeze

同步不能只看 recordingId。

必须验证当前可播放 canonical source 与 transcription canonical lineage 兼容，包括：

- canonical SHA/profile
- source asset identity
- total sample count

旧 canonical asset 不得继续驱动当前 transcription 高亮。

## 8. Version Freeze

FAST/HQ/同模式历史版本按 `transcriptionId` 隔离。

切换 transcription version：

- 保留当前 playback sample
- 不自动 restart audio
- 清除旧 active cue/row
- 用当前 sample 映射新 Timeline
- 旧异步加载结果不得覆盖新版本

## 9. Speaker Freeze

speaker-aware Timeline 继续使用 Stage 9 run-local speaker identity。

rename：

- 只更新显示
- 不 rerun diarization
- 不 seek
- 不改变当前 playback sample

状态必须保留：

- ASSIGNED
- ASSIGNED_WITH_OVERLAP
- OVERLAP_AMBIGUOUS
- UNRESOLVED

不得为 ambiguous/unresolved 强行指定 speaker。

## 10. Follow Playback Freeze

默认 FOLLOWING。

自动滚动：

- 只在 active row 变化时触发
- 当前 row 已在 viewport 时不做无意义滚动
- token 约 50 ms tick 不触发逐 tick viewport 移动

用户手动滚动：

- 立即进入 USER_SUSPENDED
- playback/highlight 继续
- viewport 不被播放抢回
- 只有用户显式点击“跟随播放”才恢复 FOLLOWING

Stage 10 不实现 5s/10s 自动恢复跟随。

## 11. Performance Freeze

- Timeline 按 transcription version 一次构建
- 高频 tick 不做 Room IO
- 不每 tick 全文/整 document rebuild
- 不每 tick O(N) 扫描
- 顺序播放使用前向 cursor
- random/reverse seek 使用二分定位
- 长 Timeline 内存只随文本/token/span 元数据增长，不分配整段 PCM

30/60/120min virtual timeline 自动化属于结构/映射测试，不得写成真实长录音 soak。

## 12. Room Freeze

Room 继续保持 **schema v3**。

Stage 10 只增加按 transcriptionId bulk load tokens 的只读 query。

无 Stage 10 migration。

## 13. Lifecycle Freeze

继续继承 Stage 7：

- background → pause
- foreground 不 autoplay
- Audio Focus loss → pause，不自动恢复
- device recording start → pause local playback
- recording end → 不自动恢复 playback

Stage 10 只消费 playback state，不重新实现第二套播放器。

## 14. 真机证据

见：

- `docs/STAGE_10_TEST.md`

用户已明确确认 Stage 10 QA 候选测试通过。

## 15. 测试债务

仍留 Stage 13：

- 真实 30min / 1h / 2h soak
- peak PSS
- CPU/thermal
- 2h playback/transcript follow 综合稳定性
- 定量 Compose jank

## 16. 后续边界

下一阶段：

**Stage 11 — AI 智能总结 / 内容理解**

Stage 11 可消费：

- transcriptionId/version
- speaker-aware transcript
- absolute sample ranges
- Timeline/segment metadata

Stage 11 不得破坏 Stage 10 sample timeline，也不得把 AI 生成内容反写成 ASR timing 事实。
