# Stage 16B — SenseVoice Rich Audio Metadata 规划

状态：**PLANNING / APPROVED FOR DOCUMENTATION**

归属阶段：**Stage 16B — 本地文件 ASR V2**

本文件是 `docs/ROADMAP_STAGE_13C_PLUS.md` 中 Stage 16B 的专项细化规划，不改变既有阶段顺序，不修改 Stage 14 当前发布范围，也不授权任何 production model channel 修改。

GitHub 当前仓库始终是唯一事实来源。开始 Stage 16B 实现前，必须重新核验当时的 `main`、open PR、HEAD、CI、Room schema、Freeze/Handoff、sherpa-onnx runtime、SenseVoice model revision、production model manifest 和当前 ASR contract；本文件中的当前事实只能作为规划依据，不能代替未来实施时重新核验。

Voica 是独立项目。不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的任何实现。

---

## 1. 当前核验结论

截至本规划写入时，Voica 当前本地 SenseVoice 链具备以下事实：

1. `engine:sherpa` 使用 `sherpa-onnx v1.13.8`。
2. 当前产品 SenseVoice 为 `sensevoice-2024-int8`，模型来源为 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`。
3. sherpa-onnx Android/Kotlin `OfflineRecognizerResult` 已直接提供：
   - `text`
   - `tokens`
   - `timestamps`
   - `lang`
   - `emotion`
   - `event`
4. sherpa-onnx SenseVoice runtime 会从 SenseVoice 输出 token 中解析 `lang / emotion / event`。
5. Voica 当前 `SherpaSenseVoiceEngine` 只把 `text / tokens / timestamps / lang` 复制到 `NativeSenseVoiceResult`，没有读取 `emotion / event`。
6. 当前 `AsrHypothesis`、`TranscriptSegment`、Room `TranscriptSegmentEntity` 都没有正式的 emotion/event 字段，因此当前 App 会丢弃 sherpa 已经计算出的情绪和声音事件信息。

结论：

> **Stage 16B 不需要为了 SenseVoice 情绪/事件识别新增模型，也不需要增加一次额外 ONNX 推理。核心工作是把当前 runtime 已经返回的 rich metadata 沿现有 ASR contract、pipeline、persistence 和 UI 正确贯通。**

---

## 2. 产品定位

本能力定义为：

**SenseVoice Rich Audio Metadata**

包括：

- Language Detection
- Emotion Recognition
- Audio Event Detection
- Segment-level metadata persistence
- Speaker × Emotion timeline
- Export / AI Summary integration

本能力是本地文件 ASR 的结构化元数据增强，不替代专业 ASR，不替代说话人分离，也不把情绪识别结果解释为人的长期属性或身份特征。

Stage 16A true-streaming ASR 不因本规划改变；不能因为 SenseVoice 支持 emotion/event 就把 SenseVoice 宣称为 true-streaming 模型。

---

## 3. 数据粒度

Rich metadata 的默认粒度固定为：

> **每个实际进入 SenseVoice 的 speech segment / offline ASR segment。**

不得把整条录音只压缩成一个 emotion 值。

不得把某个 speaker 的某一段 emotion 结果提升为该 speaker 的永久情绪属性。

同一 Speaker 在不同时间范围可以有不同 emotion。

建议统一内部结构至少包含：

- `detectedLanguage`
- `emotionRaw`
- `emotionNormalized`
- `audioEventRaw`
- `audioEventNormalized`
- `sourceModelId`
- `sourceModelVersion`
- `sourceModelRevision`
- `sourceSampleStart`
- `sourceSampleEnd`

其中 `Raw` 字段用于保留 upstream/runtime 原始值，`Normalized` 用于产品 UI、搜索、导出和跨版本兼容。

不得仅保存中文展示文案后丢失 upstream 原始值。

---

## 4. ASR Contract 扩展

Stage 16B 实现时，优先在统一 ASR contract 上增加可选 rich metadata，而不是在 UI 层针对 SenseVoice 写死特殊逻辑。

建议方向：

```kotlin
data class AsrHypothesis(
    ...,
    val detectedLanguage: String? = null,
    val emotion: AsrEmotion? = null,
    val audioEvents: List<AsrAudioEvent> = emptyList(),
)
```

具体类型名和 schema 在 Stage 16B 开发前按当时源码重新设计。

能力声明必须显式区分：

- `supportsLanguageDetection`
- `supportsEmotionDetection`
- `supportsAudioEventDetection`

只有 runtime/model 明确支持的能力才可返回值。

Qwen3-ASR、Fun-ASR-Nano 或其它模型如果没有同等能力：

- 返回 `null / empty`
- UI 不显示伪造标签
- 不通过文本内容让 LLM 猜测 emotion 来冒充模型原生结果

---

## 5. SenseVoice Adapter

Stage 16B 的 SenseVoice adapter 至少需要贯通：

`OfflineRecognizerResult.emotion/event`

→ `NativeSenseVoiceResult`

→ `AsrHypothesis`

→ offline segment / final transcript segment

→ persistence

→ UI / export / AI structured input

该链路必须保持：

- 不增加第二次 SenseVoice 推理
- 不额外加载新的 emotion 模型
- 不改变现有音频 canonical timeline
- 不破坏 token timestamp
- 不因 emotion/event 为空导致 ASR 失败
- emotion/event 解析失败时正文转写仍可正常完成

Rich metadata 属于附加能力，不得成为正文可用性的硬依赖。

---

## 6. Room / Persistence

当前 Stage 14 V1 baseline 为 Room v12，但 Stage 16B 实施时必须重新核验当时 schema。

如果 Stage 15 / 16A 期间没有 schema bump，可考虑：

`Room v12 → v13`

如果届时 schema 已高于 v12，则必须从当时正式 schema 顺序升级到下一个版本；不得为了本规划回退、重置或跳过正式 migration。

推荐持久化到 transcript segment 级或等效的独立 rich-metadata entity，最终方案由以下因素决定：

- 一个 segment 是否可能出现多个 audio event
- emotion/event 是否需要独立 confidence
- 人工 Revision 是否继承、失效或重新绑定 metadata
- 搜索/导出/AI Summary 是否需要独立索引

最低要求：

- 历史转写升级后允许 metadata 为 `NULL / empty`
- migration 不得破坏现有 V1 数据
- model revision/config lineage 可追溯
- 人工编辑正文不得把旧 emotion/event 伪装成重新推理结果

---

## 7. 与 VAD / 分段的关系

当前高质量离线转写本来就是：

`Silero VAD → speech segments → offline ASR`

因此 SenseVoice emotion/event 默认对应 ASR 实际处理的 speech segment。

Stage 16B 需要明确：

- VAD segment 被离线安全 chunk 再切分时，metadata 如何回并到产品 segment
- 一个产品 segment 对应多个 offline chunk 时，emotion 如何合并
- audio event 多值如何去重
- 超短 segment 是否需要标记 `UNKNOWN / UNRESOLVED`

不得简单用“最后一个 chunk 的 emotion”覆盖整段。

建议 emotion 聚合优先依据有效语音时长或模型置信信息；如果 runtime 不提供可靠 confidence，则必须通过真实标注测试决定聚合策略。

---

## 8. 与 Speaker Diarization 的关系

Rich metadata 与 speaker diarization 必须保持两套独立 lineage：

- emotion/event 来自 SenseVoice ASR segment
- speaker identity 来自 diarization/alignment

最终 UI 可以按 absolute sample range 做交集展示，例如：

`00:18–00:25 · Speaker 2 · 愤怒`

但数据库中不得把 emotion 直接写成 SpeakerEntity 的永久属性。

对一个 emotion segment 跨越多个 speaker span 的情况，必须按时间范围拆分展示或标记不确定，不能随意归给单一 Speaker。

---

## 9. UI / UX

默认不在阅读模式中大面积堆叠情绪标签。

建议优先在“时间轴”模式以轻量 metadata chip 展示：

- `中性`
- `开心`
- `愤怒`
- `笑声`
- `咳嗽`
- 等 runtime 实际返回并经过 normalized mapping 的标签

建议设置项：

`设置 → 本地语音识别 → SenseVoice → 显示情绪 / 声音事件`

默认值需通过 Stage 16B 真机 UX 验证决定，不在本规划提前冻结。

可增加录音级“情绪概览”，但统计必须按**有效语音时长**计算，不能简单按 segment 数量计算。

例如：

- 中性 78%
- 开心 12%
- 愤怒 7%
- 其他 3%

任何百分比展示都必须明确属于模型推断，不应包装成心理诊断、人格判断或医学结论。

---

## 10. Export

转写 TXT / Markdown 导出应设计可选 rich metadata 模式。

普通纯文本导出应继续保持正文清洁；结构化/时间轴导出可包含：

`[00:18–00:25] [Speaker 2] [ANGRY] 文本……`

如果 audio event 存在：

`[00:25–00:28] [LAUGHTER]`

必须保留：

- 是否包含 emotion/event 的导出选项或固定格式契约
- raw/normalized 值的可追溯性
- 不影响现有纯正文复制和导出体验

---

## 11. AI Summary / Structured Input

Stage 16B 只负责产生并持久化可信的本地 rich metadata。

后续 AI Summary 可以选择把结构化元数据作为输入，例如：

`[00:31][Speaker 2][ANGRY] ……`

潜在能力包括：

- 争议片段
- 情绪变化点
- 讨论最激烈区间
- 笑声/哭声/其它声音事件摘要

但必须遵循：

1. 普通正文 Summary 不能因为 emotion/event 缺失而失败。
2. 不能把模型 emotion 标签作为事实性心理状态描述。
3. 使用云端 LLM 时，如果 structured input 包含 emotion/event，应在现有第三方数据发送边界下处理，不得静默扩大上传内容。
4. AI 不得用文本推断结果覆盖 SenseVoice 原始 metadata。

---

## 12. Benchmark / QA

除 Stage 16B 原有 CER/WER、RTF、RAM、thermal、长录音稳定性外，Rich Audio Metadata 至少增加：

- emotion raw output coverage
- event raw output coverage
- normalized mapping correctness
- empty/unknown value behavior
- segment boundary consistency
- multi-chunk aggregation correctness
- speaker alignment correctness
- persistence / migration correctness
- export correctness
- AI structured-input correctness
- R8/JNI result-field preservation

测试集应包含可人工判断的不同语气和声音事件样本，至少覆盖：

- 中性陈述
- 明显开心
- 明显愤怒
- 明显悲伤
- 笑声
- 咳嗽
- 纯背景音乐/非语音片段（模型能力允许时）
- 多人交替说话
- 同一说话人情绪变化

情绪识别质量不能只用“模型返回了非空字符串”判定通过，应建立人工标注样本并记录 confusion/误判情况。

---

## 13. 性能门禁

由于 emotion/event 已由同一次 SenseVoice inference 返回，Stage 16B 目标是不增加显著推理成本。

必须分别记录改造前后：

- SenseVoice RTF
- total transcription time
- peak RAM/PSS
- CPU
- thermal
- battery

如果只是读取并持久化已有 result 字段，却造成明显性能回退，必须定位 persistence/UI/serialization 开销，不能通过关闭原有 ASR 优化来掩盖问题。

---

## 14. Capability / Product Matrix 门禁

SenseVoice Rich Audio Metadata 不能成为所有本地 ASR 的默认假设。

Stage 16B 最终模型矩阵可能包含 SenseVoice、Fun-ASR-Nano、Qwen3-ASR 或其它通过 benchmark 的模型，因此产品层必须 capability-driven：

- 支持 emotion/event 的模型显示对应能力
- 不支持的模型不显示空壳 UI
- 切换模型后历史 metadata 保留 lineage，不伪装成当前模型结果
- 重新转写产生新的 metadata version/lineage

如果 Stage 16B 最终不保留 SenseVoice 作为正式产品模型，则本专项能力不得单独强迫 SenseVoice 留在产品矩阵；最终模型取舍仍由 Stage 16B 综合 benchmark 决定。

---

## 15. Stage 边界

本规划明确不进入当前 Stage 14 V1 Release Freeze。

Stage 14.4.1 继续只负责 Export UX Closure；其真机验收、Stage 14.5 Release CI、Stage 14.6 V1 RC、Stage 14.7 Final Freeze 不因本规划扩展范围。

Stage 15 继续只负责 BLE realtime Audio → Opus → PCM 与实时媒体时间链。

Stage 16A 继续只负责 true-streaming ASR，并遵循 `docs/STAGE_16_STREAMING_CONTRACT_V2.md`。

本规划正式归属 Stage 16B。

---

## 16. 开发前 Gate

开始实现本专项前必须重新核验：

- `main` HEAD
- 当时 open PR / development branch
- Stage 14 Final Freeze / Handoff
- Stage 15 Freeze / Handoff
- Stage 16A Freeze / Handoff
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- 当前 `SherpaSenseVoiceEngine`
- 当前 `AsrHypothesis / TranscriptSegment` contracts
- 当前 Room schema / migrations
- 当前 sherpa-onnx version
- 当前 SenseVoice model/revision
- 当前 production model manifest

若这些事实与本规划核验时不同，应先修订本规划，再编码。

---

## 17. Freeze 条件

本专项只有在以下条件全部满足后才能作为 Stage 16B 的已完成能力冻结：

- SenseVoice emotion/event 从 runtime 到 persistence 全链路可追溯
- 不增加第二次 SenseVoice inference
- 不支持能力的模型不会伪造结果
- Room migration / historical data 通过
- 时间轴 UI 不影响正文阅读效率
- speaker × emotion range alignment 通过
- TXT / Markdown export 行为明确
- AI structured input 行为明确
- R8/JNI release-like build 通过
- 真机 benchmark 无不可接受性能回退
- 真机情绪/事件样本 QA 通过
- 用户明确确认“测试通过”

完成后再写对应 Stage 16B Freeze/Handoff。