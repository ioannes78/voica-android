# Stage 16A 修订需求 V1

状态：**APPROVED — 用户已确认**

日期：2026-10-12

目标：**Stage 16A — 本地 True Streaming ASR**

## 1. 权威基线

GitHub 当前仓库始终是唯一事实来源。Stage 16A 以以下文档为权威边界：

- `docs/STAGE_15_FREEZE.md`
- `docs/STAGE_15_HANDOFF.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V3.md`
- `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`

V2 继续作为 Stage 13A 历史冻结事实；Stage 16A 在 realtime input、buffering、chunk shaping、lifecycle seam 冲突时以 V3 为准。

Stage 15 frozen input：

`RealtimeCanonicalAudioSource → RealtimeCanonicalStreamEvent`

Canonical audio：16 kHz / mono / PCM16；媒体时间真值为 `sessionId + epochId + epoch-local sampleIndex`。

## 2. Stage 16A 主链

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Local Streaming ASR → PARTIAL/STABLE/FINAL → punctuation`

禁止直接从 BLE/GATT/Opus 绕过 Stage 15 canonical seam 接 recognizer。

## 3. 正式 realtime 模型

- Small Bilingual Zipformer INT8：默认、轻量、中英双语。
- Chinese Large CTC INT8：中文高质量选项。

继续禁止：

- SenseVoice / Qwen3 文件模型通过 chunking 冒充 true streaming；
- 恢复 Chinese Large Transducer 为产品模型；
- AUTO model routing。

当前 active realtime session 中不热切模型；用户修改模型后仅对下一 realtime session 生效。

## 4. Canonical consumer ownership

当前 `RealtimeCanonicalAudioSource.receive()` 为 destructive receive。Stage 16A 必须设置一个唯一上层生产 consumer，由 coordinator 向 ASR、持久化、诊断等内部 fan-out。

不得让多个生产模块竞争直接调用 canonical source `receive()`。

## 5. StreamingAudioBuffer / ChunkAssembler

StreamingAudioBuffer 必须：

- bounded；
- 不反压 BLE/decoder；
- 保留 session/epoch/sample identity；
- overflow 显式 discontinuity；
- 从 live edge 恢复；
- 禁止无限缓存、silence padding、wall-clock padding。

StreamingChunkAssembler 必须：

- engine/provider 无关；
- 聚合 canonical frame 为 engine 所需 chunk；
- 保留 canonical sample range；
- chunk duration 经 benchmark 后冻结。

## 6. PARTIAL / STABLE / FINAL

Native recognizer 的 non-final 结果进入产品层 stabilizer：

- STABLE prefix 正常情况下单调增长；
- PARTIAL 只修改正文末尾 mutable tail；
- FINAL 作为当前 utterance 的最终权威结果；
- 不得重复拼接 stable + final；
- discontinuity / epoch boundary 后重建 stabilizer continuity。

普通 UI 不展示 PARTIAL/STABLE/FINAL 工程标签。

## 7. 标点

- PARTIAL：默认不运行外部标点，避免 churn；
- STABLE：是否低频运行 CT-Transformer 由真机性能 benchmark 决定；
- FINAL：正式标点后持久化；
- 标点失败不得使实时转写整体失败；
- 有可靠 native punctuation 的未来模型不得重复标点。

## 8. Lifecycle

### Pause / Resume

PAUSE：flush 当前连续输入，best-effort FINAL，保存已有结果，关闭当前 recognizer continuity。

RESUME：新 CONTROL_BOUNDARY epoch 对应新的 recognizer continuity。

### ConsumerDiscontinuity / Decoder Recovery

禁止静默跨越。保存已确定结果，丢弃不可靠 mutable tail，从 live edge 建立新 continuity。

### STOP / DEVICE_RECORDING_ENDED

统一进入幂等 finalization：drain → flush → finishInput exactly once → FINAL reconcile → punctuation → persist → close。

### Transport Closed / Reconnect

断联只终止手机端当前 realtime segment/session，不得停止录音卡本机录音。

若重新连接后确认仍是同一次设备物理录音，且断联前 realtime intent=ACTIVE 且用户未主动关闭，则自动恢复 realtime：新 transport generation、新 canonical logical session、新 local segment、新 recognizer session。

断联期间缺失媒体不得补 silence 或伪造连续 timeline。

## 9. 持久化

确认 Stage 16A 需要 durable realtime history：

- PARTIAL/STABLE 为 live state，不逐条持久化；
- FINAL utterance 持久化；
- realtime run / segment / lifecycle marker 持久化；
- 断联前后 part01/part02 独立保存；
- realtime transcript 不覆盖文件 ASR；
- 文件 ASR 仍可后续生成独立 transcription version。

Stage 16A 原则上接受 Room v13，具体 schema 在开发实施时基于当前 v12 重新核验后冻结，完整保留 1..12 lineage。

## 10. UI / UX

采用：**阅读器 + 极简录音控制器**。

要求：

- 正文占主要屏幕空间；
- PARTIAL 仅为底部浅色 mutable tail；
- 用户在底部时智能自动跟随；手动上滑后停止自动滚动，并提供“回到实时”；
- 波形仅作轻量声音/工作状态反馈，不做大型 seek timeline；
- 主控制为 Pause/Resume + Stop；
- Pause/Resume、断联、恢复使用轻量时间轴分隔；
- STOP 后显示“正在完成最后内容…”并完成 finalization 后再进入录音详情；
- 离开实时页后使用 Mini Live Bar 返回；
- 工程诊断信息进入诊断页，不污染普通 UI。

## 11. 后台 / 熄屏

Realtime ASR 不属于 Activity / Compose 生命周期。必须由 app/task scoped runtime 持有，并与 Stage 15 foreground execution 协同。

后台、熄屏、页面切换不得停止 realtime transcription。

## 12. 性能与 QA

必须覆盖：

- Small / Large CTC 真机 realtime；
- Pause/Resume；
- TYPE3 SAVE；
- second START；
- disconnect/reconnect；
- Bluetooth OFF/ON；
- 同一物理录音自动 realtime recovery；
- segment persistence；
- background / screen-off；
- model missing；
- active session 模型切换只在下一 session 生效；
- artificial ASR slowdown；
- ConsumerDiscontinuity；
- CPU / RAM / PSS / thermal / latency；
- 30 / 60 / 120 分钟稳定性。

Stage 15 的 60/120 分钟 waiver 不自动继承到 Stage 16A。

## 13. 本阶段不做

- 手机麦克风 AudioRecord；
- 笔记 / 快速标记 / 照片；
- Stage 16B 文件 ASR V2 / FunASR；
- Stage 17 云端文件 ASR；
- Stage 18 云端 realtime / WebSocket；
- Stage 19 AI 总结 / Audio LLM；
- Stage 20 同步等能力。

手机麦克风与 Timeline Annotation 已纳入后续正式产品路线，分别见：

- `docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`
- `docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`
