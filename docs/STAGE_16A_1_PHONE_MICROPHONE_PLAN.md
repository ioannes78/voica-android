# Stage 16A.1 — Phone Microphone Capture 规划

状态：**APPROVED PRODUCT ROADMAP / NOT IN STAGE 16A CORE IMPLEMENTATION**

日期：2026-10-12

## 1. 定位

Stage 16A.1 在 Stage 16A 本地 True Streaming ASR 完成真机验收、Freeze/Handoff 后执行，目标是让 Voica 在没有 QS668/CB08 录音卡时仍可使用手机麦克风完成录音与实时转写。

本阶段不得在 Stage 16A 核心尚未稳定时提前扩大 QA 面。

## 2. 主链

```text
Android Microphone / AudioRecord
        ↓
PhoneMicrophoneCanonicalSource
        ↓
Canonical PCM16 16 kHz mono
        ↓
复用 Stage 16A StreamingAudioBuffer
        ↓
StreamingChunkAssembler
        ↓
Streaming VAD
        ↓
Local True Streaming ASR
        ↓
PARTIAL / STABLE / FINAL
```

手机麦克风不得建立第二套 ASR / stabilizer / punctuation / UI 管线。

## 3. 音频持久化硬约束

手机麦克风模式中，手机本身是正式录音设备，因此必须同时：

- 保存本地正式录音；
- 提供播放；
- 支持以后重新文件转写；
- 支持 AI 总结；
- 支持导出/分享；
- 支持后续 Timeline Annotation。

禁止只把 microphone samples 送给 ASR 而不保存音频。

## 4. Audio source 产品语义

录音卡模式与手机麦克风模式必须明确区分。

未连接录音卡时主页建议：

- 扫描录音卡
- 手机录音转写

连接录音卡后默认优先录音卡，但允许用户主动选择音频源。

不得默认同时采集录音卡和手机麦克风形成两个隐式时间轴。

## 5. UI 复用

复用 Stage 16A realtime transcript 页面，只在顶部明确来源，例如：

`手机麦克风 · Small Bilingual`

不为手机麦克风重新设计第二套实时转写产品页面。

## 6. Android lifecycle / 权限

正式开发时重新核验目标 Android 版本要求，至少覆盖：

- RECORD_AUDIO permission；
- microphone foreground service 类型与权限；
- background / screen-off；
- Audio Focus；
- 来电/其它应用抢占 microphone；
- route/device change；
- Activity recreation；
- process death/recovery；
- 与 camera 调用的 microphone ownership 冲突。

## 7. 与 Stage 16A 的架构关系

Stage 16A 只预留统一 canonical event source seam；Stage 16A.1 才实现 `AudioRecord`、microphone source、正式录音 writer 与对应 Android lifecycle。

不得为了预留 Stage 16A.1 在 Stage 16A 提前加入未使用的 microphone 生产代码。

## 8. QA

至少验证：

- 无录音卡首次录音；
- Small realtime；
- Large CTC realtime；
- Pause/Resume/Stop；
- background；
- screen-off；
- 来电/Audio Focus；
- microphone permission 拒绝/撤销；
- 相机调用；
- 长时间录音；
- 本地正式录音播放；
- 重新文件转写；
- realtime 与正式音频时间轴一致性。
