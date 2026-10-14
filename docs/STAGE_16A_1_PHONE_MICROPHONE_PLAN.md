# Stage 16A.1 — Phone Microphone Capture 规划

状态：**APPROVED PRODUCT SCOPE / DEVELOPMENT P0 STARTED**

日期：2026-10-15

开发分支：`stage16a1-phone-microphone`

## 1. 定位

Stage 16A.1 在 Stage 16A Final Freeze/Handoff 之后执行，为 Voica 增加手机麦克风能力。

本阶段正式拆分为两个独立用户功能：

1. **手机录音**：只使用手机麦克风完成正式本地录音，不启动 realtime ASR，不依赖任何 realtime 模型。
2. **手机实时转写**：使用同一手机麦克风录音底座，在保存正式本地录音的同时复用 Stage 16A True Streaming ASR。

产品入口必须明确分开，不使用“手机录音 · 实时转写”单一组合入口。

## 2. 手机录音主链

```text
Android AudioRecord
→ PhoneMicrophoneCapture
→ Canonical PCM16 / 16 kHz / mono
→ Primary Recording Sink
→ Canonical WAV
→ Existing Recording Library
```

硬约束：

- 不启动 Stage 16A realtime ASR；
- 不检查 Small/Large realtime 模型；
- 支持 Start / Pause / Resume / Stop；
- 支持波形与录音时长；
- 支持 Home / screen-off；
- 使用 microphone foreground service 与独立录音 WakeLock；
- Stop 后形成可播放、可重新文件转写、可 AI 总结、可搜索、可分享/导出的正式本地录音。

## 3. 手机实时转写主链

```text
Android AudioRecord
→ PhoneMicrophoneCapture
→ Canonical PCM
    ├─→ Primary Recording Sink → Canonical WAV → Recording Library
    └─→ CanonicalRealtimeEventSource
         → Stage 16A Streaming Pipeline
         → PARTIAL / STABLE / FINAL
```

必须复用 Stage 16A：

- `CanonicalRealtimeEventSource` seam；
- `RealtimeTranscriptCoordinator`；
- `StreamingAudioBuffer`；
- `StreamingChunkAssembler`；
- Streaming VAD；
- Small Bilingual Zipformer INT8；
- Chinese Large CTC INT8；
- Hypothesis stabilizer；
- punctuation；
- durable realtime persistence；
- realtime reader / Mini Live Bar。

不得建立第二套 ASR、stabilizer、punctuation 或 realtime reader 管线。

## 4. 两个产品功能共用一个手机录音底座

“手机录音”和“手机实时转写”是两个产品入口，但底层必须共用同一套 `PhoneMicrophoneCapture` 与正式录音 writer。

推荐模式：

```text
PhoneMicrophoneCapture
→ canonical PCM producer

RECORD_ONLY
→ Primary Recording Sink

REALTIME_TRANSCRIPTION
├─→ Primary Recording Sink
└─→ Stage 16A ASR consumer
```

禁止为了两个入口创建两套互相漂移的 AudioRecord lifecycle。

## 5. Recording truth 优先级

Stage 16A.1 的正式事实优先级：

**microphone capture truth → primary recording truth → ASR consumer truth**。

因此：

- ASR slowdown / overflow 不得反压或丢失正式录音；
- ASR runtime 在录音开始后失败时，正式手机录音继续；
- diagnostics 必须分别记录 capture / recording / ASR counters；
- 真机 Gate 必须证明正式 WAV sample count 与 primary recording accepted samples 对齐。

## 6. 模型不可用语义

### 手机录音

完全不依赖 realtime model。

### 手机实时转写

启动前若选定 realtime model 未安装或不可加载：

- 不启动“手机实时转写”；
- 明确提示模型不可用/未安装；
- 不静默切换为普通手机录音。

如果正式录音已经开始后 ASR runtime 才失败：

- 正式录音继续；
- realtime transcription 进入 degraded / failed；
- 用户仍可正常 Pause / Resume / Stop 并保存正式 WAV。

active recording 中不热切 realtime model。

## 7. Pause / Resume 时间语义

两个功能统一：

- Pause 时停止采集有效 PCM；
- Pause 期间不向正式 WAV 写入 silence；
- Resume 后同一逻辑录音继续；
- 正式 WAV 时长排除暂停时间。

手机实时转写额外使用新的 `CONTROL_BOUNDARY` epoch / recognizer continuity，不伪造 wall-clock silence。

## 8. 数据与录音库

继续复用现有 Recording Library，不建立第二套手机录音库。

计划使用：

- `RecordingSourceType.PHONE_MICROPHONE`；
- `AudioAssetRole.CANONICAL_WAV`；
- realtime 模式额外使用现有 `RealtimeAudioSourceTypeValue.PHONE_MICROPHONE` 和 Room v13 realtime domain。

当前结论：**Stage 16A.1 不因产品拆分主动提升 Room schema；默认保持 Room v13。**

只有实际编码发现无法用现有 schema 表达的 durable truth 时，才另行提出 migration 需求并重新确认。

## 9. Android lifecycle / 权限

正式实现必须覆盖：

- `RECORD_AUDIO`；
- microphone foreground service type / permission；
- visible user action 启动 microphone FGS；
- background / screen-off；
- Activity recreation；
- process interruption / recovery；
- microphone ownership contention；
- route/device change；
- permission denial / permanently denied / Settings recovery。

不把 Audio Focus 当作 microphone ownership 保证。

Stage 16A.1 不产品化电话录音、内部音频录制、Bluetooth SCO 高级路由或 USB 专项路由。

## 10. UI 产品语义

主页明确提供：

- **手机录音**
- **手机实时转写**

与录音卡产品关系保持对称：

```text
录音卡：录音 / 实时转写
手机：  手机录音 / 手机实时转写
```

手机实时转写复用 Stage 16A realtime transcript 页面，并明确来源为手机麦克风；手机录音使用简洁录音控制界面。

不得默认同时采集录音卡和手机麦克风。

## 11. QA 最低矩阵

```text
S1  手机录音 Start → Stop → 正式 WAV
S2  手机录音 Pause / Resume
S3  手机录音 Home/background
S4  手机录音 screen-off
S5  手机实时转写 Small
S6  手机实时转写 Stop → WAV + Transcript
S7  手机实时转写 Pause / Resume
S8  ASR artificial slowdown：录音无丢样、ASR 可独立 overflow
S9  Chinese Large CTC
S10 realtime model missing / unavailable
S11 RECORD_AUDIO permission denial/retry/permanent denial
S12 immediate second recording
S13 process interruption / staging recovery
S14 microphone contention / basic route change
S15 30-minute phone recording stability
```

S1–S4 必须完全不依赖 realtime ASR 模型。

## 12. Stage 边界

Stage 16A.1 不包含：

- Stage 16A.2 Timeline Annotation；
- Stage 16B 文件 ASR V2；
- Stage 17 云端文件 ASR；
- Stage 18 云端实时 ASR；
- 双音源同步录音；
- advanced Bluetooth/USB microphone routing；
- call recording / internal playback capture。

详细确认需求与开发拆分分别读取：

- `docs/STAGE_16A_1_REQUIREMENTS_V1.md`
- `docs/STAGE_16A_1_DEVELOPMENT_PLAN_V1.md`
