# Voica Stage 9 Handoff

状态：**Stage 9 已完成 / 已真机验收 / 已冻结**

下一阶段：**Stage 10 — 转写时间轴 + 播放同步**

## 1. 接管原则

GitHub 当前 `main` 是唯一事实来源。

新 Agent 必须重新读取：

- `AGENTS.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- `docs/STAGE_9_TEST.md`
- `docs/STAGE_9_FREEZE.md`
- 本文件
- 当前 main HEAD / Actions / open PR
- Room v3 schema 和 Stage 9 核心源码

聊天记录仅是线索。

## 2. Stage 9 实现基线

Freeze 前 implementation HEAD：

`4d15728282411eaa92882e98d89c0c579b19fca4`

最终 QA：

- versionCode 25
- `0.9.0-stage9-alpha2`
- QA package `io.github.ioannes78.voica.qa`
- sherpa-onnx 1.13.8
- Room 3
- arm64-v8a

CI：

- #281 / `36970218072` — success

用户明确：

**“测试通过”**

## 3. Stage 9 正式链

`PcmSource → Silero VAD → bounded windows → Pyannote INT8 → ERes2Net → FastClustering → cross-chunk stitching → SpeakerTurn`

转写对齐：

`TranscriptToken → absolute interval intersection → TranscriptSpeakerSpan`

## 4. 默认产品行为

直接点击 FAST/HIGH_QUALITY：

1. 完成本地转写并持久化。
2. 查找同 canonical lineage 的 completed diarization run。
3. 有则复用。
4. 无则自动执行 diarization。
5. 自动建立 transcript/speaker alignment。
6. UI 显示 Speaker-aware transcript。

用户不需要预先点击“单独说话人分离”。

## 5. Model channel

正式 URL：

`https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json`

Stage 9 Freeze：

- model-channel main `b4baf89a9391bc047668b86037ef5d600992cd76`
- manifestVersion 6
- digest `1bbbc9fd324049486674138e76c7d6323b8454eacbbe1830eef71b8367948447`
- validation CI #22 success

Speaker：

- Pyannote Segmentation 3.0 INT8 revision 1
- ERes2Net Base zh-CN 16 kHz revision 1

Stage 9 candidate URL 已退出正式依赖。

Debug/QA 设备如果仍保存 candidate override，应点“恢复 production”并完全退出重开。

## 6. Room v3

新增五张表：

- diarization_runs
- diarization_speakers
- speaker_turns
- transcript_speaker_alignments
- transcript_speaker_spans

Stage 8 transcriptions / segments / tokens 保持。

不要把 speaker 重新简化成 TranscriptSegment 单字段。

## 7. 时间轴

唯一媒体时间仍是：

**16 kHz canonical absolute PCM sample index**

Stage 10 必须继续使用这一时间系：

- transcript/speaker span range
- playback position sample
- seek sample

不要引入毫秒浮点作为持久化主键时间。

## 8. Stage 10 可直接复用

- `PlaybackSnapshot.positionSampleIndex`
- `PlaybackController.seekToSample()`
- playback discontinuity generation
- `TranscriptSpeakerSpanEntity`
- `SpeakerTurnEntity`
- transcription version history
- run-local speaker displayName
- absolute sample ranges

Stage 10 目标是播放同步/高亮，不重新发明 ASR 或 diarization。

## 9. 禁止提前扩张

Stage 10 不实现：

- Stage 11 AI 总结
- Stage 12 搜索/分享/文件管理增强
- Stage 13 后台/长录音基础设施
- Stage 16 realtime ASR
- Stage 20 voiceprint / global speaker identity

## 10. 已知测试债务

未完成的真实长录音/资源专项：

- 30min / 1h / 2h soak
- peak PSS
- CPU/thermal
- 标准化 DER
- 强噪声/强重叠 benchmark

这些不是 Stage 9 已完成事实。

## 11. Stage 10 接管顺序

1. 重新读取合并后最新 main HEAD。
2. 核对 Stage 9 Freeze/Handoff/Test 与 Room v3。
3. 核对 PlaybackSnapshot、seekToSample 与 speaker span range。
4. baseline validation。
5. 输出《Stage 10 修订需求》。
6. 等用户确认。
7. 输出《Stage 10 修订开发规划》。
8. 再等用户确认。
9. 确认后才创建开发分支并编码。

