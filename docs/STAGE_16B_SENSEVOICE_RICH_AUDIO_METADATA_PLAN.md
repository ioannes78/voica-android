# Stage 16B — SenseVoice Rich Audio Metadata 规划

状态：**RETAINED / PLANNED AFTER P5 R5 / NOT STARTED**

归属阶段：**Stage 16B — Local File ASR V2**

修订日期：2026-10-22

本文件保留 Stage 16B 的 SenseVoice Rich Audio Metadata 能力，并根据 P5 R5 收尾后的真实基线更新旧规划。该能力仍属于 Stage 16B，不迁移到 Stage 16C。

本次修订只更新后续开发基线：**不构成实现授权，不修改源码，不修改 Room schema，不修改 production model channel，不执行 Stage 16B Final Freeze/Handoff。**

GitHub 当前仓库始终是唯一事实来源。开始实现前必须重新核验当时的 `main`、PR #35、开发分支、CI、Room schema、production model channel、SenseVoice runtime、ASR contract、Stage 16B Freeze/Handoff 状态和本文件。

Voica 是独立项目。不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的任何实现。

---

## 1. P5 R5 后当前基线

截至 2026-10-22 的规划基线：

- P5 revision-5 已 `CLOSED / REAL-DEVICE PASS`；
- Android PR #35 仍 Draft / open / unmerged；
- Room 当前为 **v14**；
- Local File VAD 当前策略为 **FSMN-VAD INT8 primary → Silero VAD INT8 fallback**；
- SenseVoice INT8 保留为 balanced / efficiency 本地文件 ASR；
- FunASR Nano INT8 保留为 high-quality 本地文件 ASR；
- FunASR revision-2 仍是 candidate，不因本规划自动 promotion；
- 历史 Qwen compatibility 继续保留 source/evidence，但不作为当前 Stage 16B product matrix 的默认前提；
- P5 R5 已证明 shared diarization/stitching 在 SenseVoice / FunASR、AUTO / fixed-2 路径上的当前正确性；
- Stage 16B Final Freeze/Handoff 尚未执行。

本专项的预留顺序更新为：

`P5 R5 CLOSED`
→ `SenseVoice Rich Audio Metadata`
→ `Stage 16B final regression / Freeze / Handoff`
→ `Stage 16C`

该顺序是后续开发规划基线，不等于自动开始实现。

---

## 2. 当前技术结论

Voica 当前 SenseVoice 链的关键事实仍为：

1. `engine:sherpa` 基于 sherpa-onnx runtime；
2. SenseVoice runtime 的 `OfflineRecognizerResult` 可提供：
   - `text`
   - `tokens`
   - `timestamps`
   - `lang`
   - `emotion`
   - `event`
3. sherpa-onnx SenseVoice runtime 已从模型输出中解析 language / emotion / audio event；
4. Voica 当前 adapter 尚未把 emotion/event 完整贯通到统一 ASR contract、transcript persistence 和 UI；
5. 因此本专项原则上**不需要新增 emotion 模型，也不需要增加第二次 SenseVoice ONNX inference**。

结论：

> 核心工作是把同一次 SenseVoice inference 已经得到的 rich metadata，以 capability-driven、lineage-safe 的方式贯通 runtime → ASR contract → segment/timeline → persistence → UI/export/structured input。

---

## 3. 产品能力范围

能力名称：

**SenseVoice Rich Audio Metadata**

Stage 16B 范围包括：

- Language Detection；
- Emotion Recognition；
- Audio Event Detection；
- segment/range-level metadata persistence；
- Speaker × Emotion/Event timeline presentation；
- 可选 Export metadata；
- 为后续 AI structured input 提供可信本地数据。

本能力：

- 不替代 ASR 正文；
- 不替代 diarization；
- 不把 emotion 当作人的长期属性；
- 不形成心理、人格或医学诊断；
- 不把 SenseVoice 宣称为 true-streaming ASR。

Rich metadata 永远是附加能力，emotion/event 为空、未知或解析失败时，正文转写必须仍可完成。

---

## 4. 数据粒度与 lineage

默认粒度：

> 每个实际进入 SenseVoice 的有效 speech range / offline ASR unit，并最终映射回 canonical absolute sample range。

不得把整条录音压缩成单一 emotion。

不得把某段 emotion 写成 Speaker 的永久属性。

建议内部数据至少可追溯：

- `detectedLanguage`
- `emotionRaw`
- `emotionNormalized`
- `audioEventRaw`
- `audioEventNormalized`
- `sourceModelId`
- `sourceModelVersion/revision`
- `sourceSampleStart`
- `sourceSampleEnd`
- 必要的 inference/config lineage

必须同时保留 upstream raw 值和产品 normalized 值；不得只保存中文展示文案。

---

## 5. ASR Contract 扩展

优先扩展统一 ASR contract，不允许只在 Compose/UI 层针对 SenseVoice 写死特例。

规划方向：

```kotlin
data class AsrHypothesis(
    ...,
    val detectedLanguage: String? = null,
    val emotion: AsrEmotion? = null,
    val audioEvents: List<AsrAudioEvent> = emptyList(),
)
```

最终类型名、字段和 capability schema 必须在实现前按当时源码重新设计。

能力声明至少应区分：

- `supportsLanguageDetection`
- `supportsEmotionDetection`
- `supportsAudioEventDetection`

对 FunASR 或其它不提供同等 native metadata 的模型：

- 返回 `null / empty`；
- UI 不显示空壳标签；
- 不通过文本或 LLM 猜测 emotion 冒充模型原生输出。

---

## 6. SenseVoice Adapter 链路

目标链路：

`OfflineRecognizerResult.emotion/event`
→ `NativeSenseVoiceResult`
→ `AsrHypothesis`
→ offline unit / final transcript range
→ persistence
→ timeline/UI/export/structured input

必须保持：

- 不增加第二次 SenseVoice inference；
- 不额外加载 emotion/event 模型；
- 不改变 canonical sample truth；
- 不破坏现有 token timestamp；
- metadata 解析失败不导致 ASR 正文失败；
- metadata 与 model revision/config 可追溯。

---

## 7. 与当前 FSMN Local File VAD 的关系

旧规划中的 `Silero VAD → speech segments → offline ASR` 已被当前 Stage 16B 基线取代。

当前 Local File 路径按：

`FSMN-VAD primary → speech ranges → SenseVoice / FunASR`

并仅在 FSMN 不可用时使用现有 Silero fallback。

因此 Rich Metadata 必须以**当前实际进入 SenseVoice 的 canonical speech range**为 lineage 根，而不是绑定某个特定 VAD 类名。

需要在实现阶段明确：

- 一个 FSMN speech range 被 offline safety chunk 再切分时，metadata 如何聚合回产品 range；
- 一个产品 range 对应多个 SenseVoice inference unit 时，emotion 如何聚合；
- 多个 audio event 如何去重/保留；
- 极短或证据不足的 range 如何表示 `UNKNOWN / UNRESOLVED`；
- 不得用“最后一个 chunk 的 emotion”覆盖整个范围。

若 runtime 没有可靠 emotion confidence，聚合规则必须由真机标注样本决定，不能臆造权重。

---

## 8. 与 Speaker Diarization 的关系

Rich Metadata 和 diarization 必须保持独立 lineage：

- emotion/event：来自 SenseVoice inference range；
- speaker identity：来自 diarization / alignment；
- 两者最终按 canonical absolute sample range 做交集。

例如 UI 可以展示：

`00:18–00:25 · 说话人2 · 愤怒`

但 persistence 不得把 `ANGRY` 写成 SpeakerEntity 的永久属性。

如果一个 emotion range 跨越多个 speaker span：

- 可以按时间范围拆分展示；或
- 标记不确定；
- 不得任意归属单一 speaker。

P5 R5 的 weak-speaker guard、固定 speaker-count 独立性和人工 speaker assignment 规则必须继续受到回归保护。

---

## 9. Room / Persistence

当前真实基线为 **Room v14**，旧规划中的 v12/v13 推测已经失效。

本专项若需要新增持久字段或 entity：

- 必须从实现时的正式 schema 顺序升级；
- 当前基线下若仍为 v14，则下一 schema 只能是受控的 v15 或当时实际的下一个版本；
- 不允许 destructive migration；
- 不允许手工伪造 schema；
- migration 必须由真实 Room/KSP 生成并通过历史 schema gate；
- schema 设计和 migration 在正式开发规划中单独确认，不由本文件直接授权。

持久化方案可以是 transcript segment 字段或独立 rich-metadata entity，最终需考虑：

- 一个 range 多个 audio event；
- confidence / raw / normalized 值；
- 人工 Revision 后 metadata 的继承/失效；
- 搜索、导出和 structured input；
- 历史版本 lineage。

最低要求：

- 历史数据允许 metadata `NULL / empty`；
- 人工编辑正文不得把旧 metadata 伪装成重新推理；
- 重新转写产生新的 metadata lineage；
- 模型切换不重写历史来源。

---

## 10. UI / UX

默认不在阅读正文中大面积堆叠标签。

优先在时间轴/详情视图使用轻量 chip，例如：

- `中性`
- `开心`
- `愤怒`
- `悲伤`
- `笑声`
- `咳嗽`
- 其它 runtime 实际返回并经 normalized mapping 的值。

可预留：

`设置 → 本地语音识别 → SenseVoice → 显示情绪 / 声音事件`

具体默认值在真机 UX 验证后确认。

若提供录音级情绪概览：

- 必须按有效语音时长或明确算法统计；
- 不按 segment 个数简单计数；
- 明确标识“模型推断”；
- 不包装成心理诊断或人格判断。

---

## 11. Export / AI Structured Input

普通 TXT/正文复制继续保持干净。

结构化/时间轴导出可选择包含：

`[00:18–00:25] [Speaker 2] [ANGRY] 文本……`

或：

`[00:25–00:28] [LAUGHTER]`

必须保证：

- raw/normalized 可追溯；
- 是否包含 metadata 的导出行为明确；
- 不破坏现有纯正文体验。

Stage 16B 只负责产生并持久化可信本地 metadata。后续 AI Summary 若消费这些数据：

- 普通正文 Summary 不得因 metadata 缺失失败；
- 不把 emotion 标签当成事实性心理状态；
- 云端 structured input 必须服从现有第三方数据发送边界；
- AI 推断不得覆盖 SenseVoice 原始 metadata。

---

## 12. Benchmark / QA

Rich Metadata QA 至少覆盖：

- emotion raw output coverage；
- event raw output coverage；
- normalized mapping correctness；
- empty/unknown behavior；
- FSMN range / safety chunk 聚合；
- speaker-range alignment；
- persistence / migration；
- export；
- structured input；
- R8/JNI result-field preservation；
- SenseVoice RTF / total time / peak RAM-PSS / CPU / thermal / battery 回归。

标注样本至少包括：

- 中性陈述；
- 明显开心；
- 明显愤怒；
- 明显悲伤；
- 笑声；
- 咳嗽；
- 模型能力允许时的背景音乐/非语音事件；
- 多人交替；
- 同一说话人情绪变化。

通过标准不能只是“返回非空字符串”，应记录明显误判/confusion 和范围对齐问题。

由于 emotion/event 来自同一次 SenseVoice inference，本专项目标是不增加显著 inference 成本；若出现明显回退，应定位 persistence/UI/serialization，而不是牺牲现有 ASR 优化。

---

## 13. Capability / Product Matrix 门禁

当前规划以 **SenseVoice + FunASR** 的 Stage 16B product roles 为基线：

- SenseVoice：balanced / efficiency，并提供 native rich metadata；
- FunASR Nano：high-quality，不能伪造 SenseVoice-only metadata。

产品层必须 capability-driven：

- 有能力才显示；
- 无能力不显示空壳；
- 历史 metadata 保留其 source model lineage；
- 重新转写才产生新的 metadata version。

历史 Qwen compatibility 不因为本专项被删除，但也不作为 Rich Metadata 实现的默认产品目标。

---

## 14. 与 Stage 16C 的边界

SenseVoice Rich Audio Metadata **保留在 Stage 16B**。

Stage 16C 后续规划可以复用其持久化 metadata，但不得把本专项偷偷迁移到 16C 或用 16C 的 realtime two-pass FunASR 路径伪造 SenseVoice emotion/event。

Stage 16C 的 Unified FSMN VAD / Realtime Two-Pass ASR / Sentence Composer / Diarization Performance 规划，不改变本专项的 source-of-truth：

> emotion/event 只来自真实 SenseVoice native result，除非未来另有明确能力规划。

---

## 15. 开发前 Gate

开始本专项实现前必须重新核验：

- `main` HEAD；
- PR #35 当前 HEAD / merge 状态；
- 当前开发 branch；
- 最新 CI / Actions；
- P5 R5 closure；
- Stage 16B requirements/progress；
- 当前 Room schema / migrations；
- production model channel / model-channel PR #21；
- 当前 SenseVoice adapter / runtime result fields；
- 当前 `AsrHypothesis / TranscriptSegment` contracts；
- 当前 Local File FSMN VAD policy；
- 当前 diarization/alignment behavior；
- 当前 export / AI Summary structured-input contract。

若事实变化，先修订需求/开发规划，再编码。

---

## 16. Freeze 条件

只有以下条件全部满足，才可把本专项作为 Stage 16B 已完成能力冻结：

- emotion/event runtime → contract → persistence → UI 全链路可追溯；
- 不增加第二次 SenseVoice inference；
- 不支持能力的模型不伪造结果；
- Room migration / historical data（如发生 schema 变化）通过；
- FSMN range / offline chunk 聚合正确；
- speaker × emotion/event range alignment 通过；
- timeline UI 不影响正文阅读；
- TXT/Markdown export 行为明确；
- structured input 边界明确；
- R8/JNI release-like build 通过；
- 真机 metadata QA 通过；
- 无不可接受的性能回退；
- 用户明确确认测试通过。

完成本专项与 Stage 16B 其余最终回归后，才允许单独执行 **Stage 16B Final Freeze/Handoff**。