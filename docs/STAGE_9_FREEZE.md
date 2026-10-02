# Voica Stage 9 Freeze

状态：**FROZEN / 用户验收通过**

日期：2026-10-02

## 1. Freeze 结论

Stage 9 — 说话人分离完成并冻结。

用户最终明确确认 Stage 9 自动产品链：

**“测试通过”**

Stage 9 最终默认行为：

**直接 FAST/HQ 转写 → 自动说话人分离 → 自动 Speaker 文本对齐**

用户无需预先点击“单独说话人分离”。

## 2. 最终实现基线

Freeze 前 implementation HEAD：

`4d15728282411eaa92882e98d89c0c579b19fca4`

最终 QA：

- versionCode：25
- versionName：`0.9.0-stage9-alpha2`
- QA Application ID：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Room schema：3

最终实现 CI：

- #281 / `36970218072` — **success**

## 3. Runtime / Model Freeze

Stage 9 沿用 sherpa-onnx 1.13.8，不新增 MNN/NCNN/独立 ORT/PyTorch/TFLite runtime。

speaker pipeline：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → ERes2Net Base → FastClustering`

production model-channel：

- repo：`ioannes78/voica-model-channel`
- main：`b4baf89a9391bc047668b86037ef5d600992cd76`
- manifestVersion：6
- manifest digest：`1bbbc9fd324049486674138e76c7d6323b8454eacbbe1830eef71b8367948447`
- validation CI #22：success

Speaker models：

### Pyannote Segmentation 3.0 INT8

- modelId：`pyannote-segmentation-3-int8`
- role：`DIARIZATION_SEGMENTATION`
- revision：1
- package：6,958,444 bytes
- package SHA-256：`24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488`
- installed `model.int8.onnx`：1,540,506 bytes
- file SHA-256：`d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d`

### ERes2Net Base zh-CN 16 kHz

- modelId：`3dspeaker-eres2net-base-zh-cn-16k`
- role：`EMBEDDING`
- revision：1
- file：39,593,761 bytes
- SHA-256：`1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b`

两者 redistributionPolicy 均为 `UPSTREAM_ONLY`。

## 4. Candidate URL Freeze

Stage 9 candidate URL **不是正式运行依赖**。

正式运行固定读取：

`https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json`

candidate URL 只用于未来未合并模型候选的 Debug/QA 真机验收。

历史 QA 设备如果保存过 Stage 9 candidate override，应在设置页点击“恢复 production”并完全退出/重启。

## 5. Timeline Freeze

所有 diarization / speaker turn / transcript alignment 继续使用：

**absolute canonical PCM sample index**

canonical：

- 16 kHz
- mono
- PCM16_LE

不得拼接 VAD speech-only audio 后建立第二套时间轴。

## 6. Long-audio Freeze

Stage 9 默认：

- chunk：60s
- overlap：10s
- step：50s

`PcmSource` 顺序读；overlap tail 在内存保留。

不得为 30/60/120min 录音一次性构建整段 FloatArray。

## 7. Speaker Identity Freeze

Stage 9 identity 只在一次 DiarizationRun 内有效：

- chunk-local label 必须 stitch 后才可成为 global run-local speaker
- stitching 使用 ERes2Net anchor + overlap/temporal evidence
- embedding 不落 Room
- 不实现跨录音 voiceprint/global identity
- Speaker ordinal 按本 run 首次出现顺序
- displayName rename 只属于当前 run

## 8. Room v3 Freeze

Stage 9 新增：

- `diarization_runs`
- `diarization_speakers`
- `speaker_turns`
- `transcript_speaker_alignments`
- `transcript_speaker_spans`

Stage 8 transcription 数据不被覆盖。

同一 diarization run 可复用到同 canonical lineage 的多个 FAST/HIGH_QUALITY transcription。

## 9. Transcript Alignment Freeze

- timed SECOND_PASS 优先
- timed FIRST_PASS fallback
- speaker switch 可在单 ASR segment 内拆 span
- punctuation/ITN 后 finalText 必须完整覆盖
- overlap 多 speaker 默认 ambiguous
- 无 timing 且跨边界保持 unresolved
- 同一文字不得复制到多个 speaker

## 10. Product Flow Freeze

默认：

```
FAST/HQ transcription
→ persist
→ compatible completed diarization?
   ├─ yes: reuse
   └─ no: run diarization
→ transcript/speaker alignment
→ speaker-aware transcript
```

“单独说话人分离”只用于补做、重跑和专项测试。

查看历史转写不会强制启动新 diarization。

## 11. Lifecycle Freeze

- exact speaker model revision lease
- bundle smoke 在 createRun 前
- cancel → CANCELLED
- recoverable failure → FAILED_RECOVERABLE
- active run/alignment 启动 reconciliation → INTERRUPTED
- rerun 新 UUID，不覆盖旧 run
- UI Completed 不得早于 persistence 与 lease 释放语义

## 12. 真机证据

见 `docs/STAGE_9_TEST.md`。

用户已明确确认手动 Stage 9 主链与最终 Alpha 2 自动转写→说话人链路通过。

## 13. 测试债务

Stage 9 不声称完成：

- 真实 30min / 1h / 2h soak
- peak PSS
- thermal
- 标准化 DER benchmark
- 强重叠/强噪声专项

长录音与资源压力继续 Stage 13。

## 14. 后续边界

下一阶段：**Stage 10 — 转写时间轴 + 播放同步**

Stage 10 可以消费：

- `PlaybackSnapshot.positionSampleIndex`
- `seekToSample`
- `TranscriptSpeakerSpan`
- absolute sample ranges

Stage 10 不得把 Stage 20 voiceprint/global identity 提前实现。

