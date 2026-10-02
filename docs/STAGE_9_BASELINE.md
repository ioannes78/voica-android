# Stage 9 Baseline — 说话人分离

## 1. 开始基线

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Stage 9 branch: `stage9-development`
- Stage 9 starting HEAD: `ab52ad97916749aec478e37d1a361fde404f4685`
- Previous frozen stage: Stage 8
- Stage 8 merge: `894d8cfc8278823c44af320e41cdcefe821dfeec`
- Stage 8 implementation: `189ea18dd11a45227d541b133c4795e38ffa85ea`
- Stage 8 Freeze/Handoff content: `3ddb003b957b7c0e8da17e3df082edf7905d18d9`

Stage 8 merge 到 Stage 9 起点之间共有 4 个提交；GitHub compare 确认只有 `docs/ROADMAP.md` 发生变化，没有 Android 源码、Room schema、Gradle、CI 或 native engine 变化。

## 2. Stage 8 已验证基线

Stage 8 当前核心技术基线：

- Kotlin 2.4.20
- AGP 9.4.0
- Gradle 9.6
- JDK 17
- compileSdk 37.1
- targetSdk 37
- minSdk 26
- sherpa-onnx 1.13.8
- Room schema v2
- ABI arm64-v8a
- versionCode 23
- versionName `0.8.0-stage8-alpha4`

Stage 8 已由用户明确完成真机验收。Stage 8 implementation CI 与 Seal CI 均成功。Stage 9 开始时没有开放 PR，也没有既有 `stage9-development` 分支。

本文件记录的是 GitHub 基线审计；Stage 9 分支创建时没有额外声称执行新的本地 Gradle build。

## 3. Stage 9 可复用契约

### Canonical PCM

Stage 9 必须继续通过 `PcmSourceResolver -> PcmSource` 读取 canonical audio：

- 16 kHz
- mono
- PCM16
- `Long` absolute sample index
- 区间语义 `[startSampleIndex, endSampleIndexExclusive)`

不得把 VAD 语音片段首尾拼接后建立新的伪时间轴。

### Transcript

Stage 8 已提供：

- `SpeechSegment`
- `TranscriptSegment`
- `TranscriptToken`
- FIRST_PASS / SECOND_PASS timed tokens
- FAST / HIGH_QUALITY 独立转写版本

Stage 9 speaker alignment 必须在原 absolute sample timeline 上完成，不能使用 majority-speaker shortcut。

### Model manager

现有 `ModelKind` 已包含 `SPEAKER`。Stage 9 必须复用该 enum，不新增旧版 App 无法解析的 speaker kind。

现有 model manager 已提供：

- manifest digest
- package/file SHA-256
- staged install
- resume download
- candidate validation
- exact revision lease
- rollback / deletion protection

Stage 9 只新增可选 speaker role metadata，并保持 Stage 8 manifest 向后兼容。

## 4. Stage 9 首轮模型策略

Stage 9 模型主线调整为：

- VAD: Stage 8 Silero VAD
- speaker segmentation: pyannote segmentation 3.0 INT8
- speaker embedding: CAM++ INT8
- clustering: sherpa FastClustering
- runtime: sherpa-onnx 1.13.8

CAM++ INT8 是 Stage 9 唯一主线 production candidate，不再先做 CAM++ FP32 / ERes2Net / TitaNet 的 production A/B。

CAM++ FP32 仅保留为量化精度与 embedding 行为的参考基线，不作为 App 用户可选模型，也不作为运行时自动 fallback。

INT8 权重不得直接因为第三方已有文件就进入 production。优先从已核验的 CAM++ FP32 上游权重通过可重复的 ONNX INT8 量化流程生成，并记录：

- 上游 FP32 SHA-256
- 量化工具与版本
- 量化参数
- 输出 INT8 SHA-256
- embedding dimension / finite / norm smoke
- FP32 ↔ INT8 embedding cosine regression
- diarization regression
- Android RTF / Peak PSS / CPU / thermal

目前存在第三方 Pixel 6 / ARM64 / sherpa-onnx 测试显示 dynamic INT8 可能比 FP32 更慢，因此 Stage 9 必须把目标 Android 真机性能验证作为 Freeze 硬门禁。若 INT8 明显更慢、精度明显下降或当前 ORT kernel 对该图不适配，不得静默切回 FP32 后仍宣称 CAM++ INT8 路线完成；应先修正量化方案或执行路径，再重新验收。

Stage 9 第一轮不新增 MNN、NCNN、独立 ONNX Runtime SDK、PyTorch 或 TFLite。

## 5. Room v3 设计边界

Stage 9 采用 additive migration，保留 Stage 8 三张转写表，新增：

- `diarization_runs`
- `diarization_speakers`
- `speaker_turns`
- `transcript_speaker_alignments`
- `transcript_speaker_spans`

Stage 9 不在数据库持久化 speaker embedding / voiceprint / global speaker profile。

## 6. Stage 9 Gate

开发按已确认规划执行：

1. 9A Baseline
2. 9B Diarization contracts
3. 9C Room v3
4. 9D Model infrastructure
5. 9E sherpa diarization engine
6. 9F bounded long-audio pipeline
7. 9G cross-chunk stitching
8. 9H transcript alignment
9. 9I coordinator/lifecycle
10. 9J minimal UI
11. 9K CAM++ INT8 candidate package
12. 9L Android INT8 validation
13. 9M INT8 accuracy/performance acceptance
14. 9N regression
15. user real-device acceptance
16. Freeze/Handoff/merge

CI 成功不能替代真机验收。
