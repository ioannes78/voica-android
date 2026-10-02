# Voica Stage 10 Handoff

状态：**Stage 10 已完成 / 已真机验收 / 已冻结**

下一阶段：**Stage 11 — AI 智能总结 / 内容理解**

## 1. 接管原则

GitHub 合并后的 `main` 是唯一事实来源。

新 Agent 必须重新读取：

- `AGENTS.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_10_TEST.md`
- `docs/STAGE_10_FREEZE.md`
- 本文件
- 当前 main HEAD / Actions / open PR
- Room v3 schema
- Stage 8/9 transcription + speaker 数据契约
- Stage 10 Timeline / playback sync 核心源码

聊天记录仅是线索。

## 2. Stage 10 实现基线

Freeze 前 implementation HEAD：

`0b1e7d41a284a92af2dc3c39413014c31586235a`

最终 QA：

- versionCode 26
- `0.10.0-stage10-alpha1`
- QA package `io.github.ioannes78.voica.qa`
- Room 3
- sherpa-onnx 1.13.8
- arm64-v8a
- CI #287 / `36989306411` success
- APK SHA-256 `92def5738a7f023f1f1a8e8a052d8c7a2331a46a646c6ad3c56bd60f14a23a7b`

用户明确：

**“测试通过”**

## 3. Stage 10 核心链

播放同步：

`PlaybackSnapshot.positionSampleIndex → TranscriptTimelinePositionMapper → active row/cue/speaker → Compose highlight`

点击跳转：

`Transcript text/token → absolute sample → TranscriptPlaybackCoordinator → seekToSample → play`

唯一时间真值仍是 16 kHz canonical absolute sample index。

## 4. Timeline 数据来源

Room v3：

- TranscriptionEntity
- TranscriptSegmentEntity
- TranscriptTokenEntity
- TranscriptSpeakerAlignmentEntity
- TranscriptSpeakerSpanEntity
- DiarizationSpeakerEntity

Timeline 是只读派生模型，不持久化第二套 timeline 表。

completed alignment 存在时 row=SpeakerSpan；否则 row=TranscriptSegment。

## 5. Token 与高亮

FAST：

- FIRST_PASS Zipformer token timestamps

HQ：

- timed SECOND_PASS 优先
- timed FIRST_PASS fallback

finalText projection：

- EXACT → 允许深色 token 高亮
- HEURISTIC / UNAVAILABLE → 只做 row/span 高亮

注意：ASR token 不是自然语言分词保证。

## 6. 播放与 source lineage

`PlaybackSnapshot` 现在暴露 `sourceAssetId`。

Stage 10 同步要求：

- recordingId 一致
- 当前 canonical lineage 与 transcription source SHA/profile 一致
- playback sourceAssetId 对应当前 canonical
- total sample count 一致

不兼容时禁止文字同步。

## 7. 版本语义

同一 Recording 可有多个 FAST/HIGH_QUALITY/同模式历史版本。

切版本：

- 以 transcriptionId 为唯一版本入口
- cancel/discard 旧异步 load
- 保留 playback sample
- 新 Timeline 在当前 sample 重映射
- 不自动重启播放

## 8. Follow Playback

状态：

- FOLLOWING
- USER_SUSPENDED

手动 transcript scroll → USER_SUSPENDED。

此时：

- 音频继续
- highlight 继续
- viewport 不再自动滚动

点击“跟随播放”恢复 FOLLOWING。

## 9. Room v3

Stage 10 **没有 migration**。

只增加：

- `loadTokensForTranscription(transcriptionId)`

Stage 11 不要为了 AI Summary 改写 Stage 10 Timeline 表，因为根本不存在持久化 Timeline 表。

## 10. Stage 11 可直接消费

Stage 11 默认 AI 总结输入应是：

- selected transcriptionId
- final transcript text
- speaker-aware spans/segments
- sample ranges / time references
- language 等可用 metadata
- recording / transcription lineage

默认链继续按 ROADMAP：

`Recording → local/cloud ASR → structured transcript → Text LLM → AI Summary`

Stage 11 默认**不上传原始音频给 Text LLM**。

## 11. Stage 11 不得破坏

- absolute canonical PCM sample timeline
- Stage 8 transcription version history
- Stage 9 run-local speaker identity
- Stage 10 transcriptionId version binding
- EXACT-only precise token highlight 可信度原则
- Stage 7 playback focus/background/device-recording pause semantics

## 12. 已知测试债务

未完成：

- 真实 30min / 1h / 2h transcript follow soak
- peak PSS
- CPU/thermal
- 2h UI jank / scroll-follow quantitative benchmark

进入 Stage 13，不得在 Stage 11 顺带扩张。

## 13. Stage 11 接管顺序

1. 读取合并后最新 main HEAD。
2. 核对 Stage 10 Freeze/Handoff/Test。
3. 核对 Room v3、Transcription version 与 speaker-aware transcript。
4. baseline validation。
5. 输出《Stage 11 修订需求》。
6. 等用户确认。
7. 输出《Stage 11 修订开发规划》。
8. 再等用户确认。
9. 确认后才创建开发分支并编码。
