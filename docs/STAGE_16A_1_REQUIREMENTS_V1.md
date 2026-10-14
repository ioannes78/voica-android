# Stage 16A.1 Requirements V1 — 手机录音 / 手机实时转写

状态：**APPROVED**

确认日期：2026-10-15

基线 `main`：`075bc9689b011678685e1046e3590a26d787067f`

开发分支：`stage16a1-phone-microphone`

## 1. 目标

Stage 16A.1 为 Voica 增加手机麦克风能力，正式交付两个独立功能：

1. 手机录音；
2. 手机实时转写。

二者产品入口独立，底层共用同一个手机麦克风 capture / lifecycle / primary recording 实现。

## 2. 手机录音需求

### R1 启动

- 用户点击“手机录音”后申请/校验 `RECORD_AUDIO`；
- 权限满足后启动 microphone foreground service；
- 建立 AudioRecord capture；
- 建立正式 canonical WAV writer；
- 不检查、不加载 realtime ASR model。

### R2 录制格式

正式录音处理格式：

- PCM16；
- 16 kHz；
- mono；
- canonical sample timeline。

最终形成标准可播放 WAV。

### R3 控制

必须支持：

- Start；
- Pause；
- Resume；
- Stop。

Pause 期间不写入 silence，最终 WAV 时长排除暂停时间。

### R4 生命周期

录音开始后必须支持：

- Home/background；
- screen-off；
- Activity recreation；
- notification reopen；
- second recording after normal stop。

Activity/Compose 不拥有 microphone capture lifetime。

### R5 正式录音库

Stop 成功后录音进入现有 Recording Library，并可：

- 播放；
- 重新文件转写；
- 说话人处理；
- AI 总结；
- 搜索；
- 分享/导出。

不得建立第二套手机录音库。

## 3. 手机实时转写需求

### RT1 独立入口

“手机实时转写”是与“手机录音”并列的独立入口。

点击后使用同一个 `PhoneMicrophoneCapture` 底座，但额外启动 Stage 16A realtime ASR consumer。

### RT2 正式录音必须同时存在

手机实时转写不能只保留文字。

每次 realtime run 必须同时维护正式手机 WAV，供后续播放、重新文件转写与内容处理。

### RT3 Stage 16A pipeline 复用

手机 microphone canonical events 必须通过 Stage 16A 现有 seam 接入：

```text
CanonicalRealtimeEventSource
→ RealtimeTranscriptCoordinator
→ StreamingAudioBuffer
→ StreamingChunkAssembler
→ Streaming VAD
→ realtime recognizer
→ stabilizer
→ punctuation
→ durable realtime persistence
```

不得重新实现第二套 realtime ASR / stabilizer / punctuation / reader。

### RT4 模型

正式支持：

- Small Bilingual Zipformer INT8；
- Chinese Large CTC INT8。

active session 模型 identity 固定，不热切模型。

### RT5 模型不可用

启动前模型未安装/不可加载：

- 不启动“手机实时转写”；
- 显示明确中文错误/安装引导；
- 不静默退化为“手机录音”。

正式录音已经开始后 ASR runtime 失败：

- 正式录音继续；
- realtime transcription 标记 degraded / failed；
- Stop 仍必须完成正式 WAV。

## 4. 录音真值与并发约束

### A1 Recording truth 优先

必须满足：

```text
microphone capture truth
→ primary recording truth
→ optional realtime ASR truth
```

ASR 是从属 consumer，不得反压正式录音。

### A2 单一 AudioRecord owner

不得因为两个产品入口创建两套 AudioRecord lifecycle。

capture / route / pause / resume / stop / diagnostics 共用底层实现。

### A3 单一 canonical source pump

realtime 模式继续遵守 Stage 16A single-consumer/fan-out 原则，禁止多个 consumer 竞争 destructive receive。

## 5. Android 平台需求

### P1 权限

必须正确处理：

- `RECORD_AUDIO` runtime permission；
- microphone foreground service permission/type；
- denied；
- retry；
- permanently denied；
- Settings recovery。

### P2 Foreground Service

microphone FGS 必须由可见用户动作启动，并在后台/息屏期间维护 capture lifetime。

### P3 WakeLock

手机正式录音拥有独立于 ASR runtime 的 execution lease / partial WakeLock。

ASR 失败或关闭不能释放仍在进行的录音所需执行资源。

### P4 麦克风争用

必须处理/诊断：

- read error；
- silent client；
- microphone ownership contention；
- camera/VoIP 等其它应用占用；
- basic route change。

Audio Focus 不作为 microphone exclusivity 保证。

## 6. 音频持久化与异常恢复

### D1 Primary WAV

采用增量 canonical WAV 写入，不增加 Stop 时第二套 raw→WAV 打包链。

### D2 Staging

录制中使用 staging/partial 状态，正常 Stop 后原子完成正式 WAV。

### D3 Process interruption

异常进程终止后不得把未完整录音伪装成正常 COMPLETED。

下一次启动必须能够识别可恢复 staging：

- 以实际 PCM payload / block alignment 修复 WAV；
- 注册为 interrupted/recovered recording；
- 保留真实可播放媒体；
- 不声称录音在进程死亡后继续。

## 7. 数据模型

当前 Stage 16A 基线为 Room v13。

Stage 16A.1 默认保持 v13：

- Recording source 增加/使用 `PHONE_MICROPHONE` 产品值；
- audio asset 使用 `CANONICAL_WAV`；
- realtime source 使用已存在的 `RealtimeAudioSourceTypeValue.PHONE_MICROPHONE`；
- realtime durable domain 继续复用 v13 run/media/utterance/timeline marker。

纯手机录音不得伪造 realtime run。

若实现过程中发现现有 schema 无法表达必要 durable truth，必须暂停 schema 变更并单独确认 migration。

## 8. UI / UX

主页/设备产品页必须明确提供两个入口：

- 手机录音；
- 手机实时转写。

要求：

- 手机录音界面简洁显示来源、时长、波形、Pause/Resume/Stop；
- 手机实时转写复用 Stage 16A reader 与 Mini Live Bar；
- 明确显示“手机麦克风”来源；
- 不默认同时使用录音卡和手机麦克风；
- 用户可见错误默认简体中文。

## 9. Diagnostics

至少提供：

- requested sample rate；
- actual sample rate；
- channel count / encoding；
- audio source；
- min/effective buffer bytes；
- AudioRecord state；
- routed device；
- read calls；
- samples read；
- short reads；
- read errors；
- client silenced；
- primary recording accepted samples；
- finalized WAV samples；
- realtime ASR offered/consumed samples；
- ASR overflow/drop counters；
- recording drop counters；
- microphone FGS state；
- WakeLock state；
- Pause/Resume count；
- terminal reason。

关键 Gate：允许 ASR drop/overflow，但正式录音 drop 必须为 0。

## 10. 明确不在范围

Stage 16A.1 不包含：

- call recording；
- Android internal playback capture；
- 双音源同步录制；
- Bluetooth SCO 专项产品化；
- USB microphone 高级路由专项；
- Stage 16A.2 Timeline Annotation；
- Stage 16B 文件 ASR V2；
- Stage 17/18 云端 ASR。
