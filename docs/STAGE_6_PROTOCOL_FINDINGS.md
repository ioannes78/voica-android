# Voica Stage 6 协议与音频发现

状态：**FROZEN / Stage 6 真机验收完成**

日期：2026-10-01

## 1. Stage 5 协议继续有效

Stage 6 不改变：

```
TYPE=2 CMD=2  Download Request
TYPE=2 CMD=3  Transfer Start
TYPE=2 CMD=4  Data × N
TYPE=2 CMD=5  Transfer End
```

CMD=12 继续冻结为：

`[start,end)`

CMD=8 删除继续冻结为：

`00000000 + full filename fixed 24B`

## 2. 设备双格式录音

当前设备逻辑录音对应同时间戳的：

- `noteYYYYMMDD-HHMMSS.opus`
- `noteYYYYMMDD-HHMMSS.wav`

Stage 6 支持分别下载两种格式。

Stage 4 文件列表 entry 的 `sizeBytes` 继续代表 OPUS 大小，不能直接拿来作为 WAV expected size。

## 3. WAV Header Probe

Stage 6 Alpha 3 增加：

```
CMD=12
filename = same-name .wav
range = [0,44)
```

真机功能验收通过。

返回数据按 RIFF/WAVE 解析：

- bytes[0..3] = `RIFF`
- bytes[4..7] = little-endian ChunkSize
- bytes[8..11] = `WAVE`

WAV 真实文件总大小：

`ChunkSize + 8`

该值用于：

- 设备列表 WAV 大小
- WAV 下载真实 expectedBytes
- 下载完成 size validation

## 4. WAV 下载 fallback

若列表阶段 header probe 没有取得 WAV 总大小：

- 完整 WAV 下载仍正常启动
- 累积首部字节
- 一旦获得 RIFF/WAVE 头，动态更新 expectedBytes
- UI 从 indeterminate 切换到真实百分比

不以录音时长估算值作为 integrity truth。

## 5. OPUS raw validation

Stage 5 曾观察当前 raw 数据具有 40B 对齐表现，但 Stage 6 不冻结“40B 即 Opus”的启发式。

Stage 6 规则：

1. container detection
2. 建立 packet boundary
3. 对 packet 使用 official libopus inspect
4. 检查 frame count / channels / samples / packet duration
5. 整段一致后才允许 decode

无法可靠建立 framing 时必须返回 UNSUPPORTED_FRAMING，不得猜测并继续。

## 6. Ogg

Stage 6 不要求把设备 raw Opus 先封装成 Ogg。

只要可靠获得完整 Opus packet boundary，可直接送入 libopus decoder。

Ogg detection 保留，但不作为 Stage 6 强制中间格式。

## 7. 设备 WAV

设备 WAV 下载后独立解析：

- RIFF
- WAVE
- PCM format
- sampleRate
- channels
- bitsPerSample
- blockAlign
- data chunk

DEVICE_WAV 的 VALID / UNSUPPORTED / INVALID 与 canonical 最终选择 OPUS 还是 WAV 无关。

## 8. canonical

Voica 自己的 canonical 音频契约固定：

- RIFF/WAVE
- PCM format 1
- 16000 Hz
- mono
- PCM16_LE

设备源参数不能通过修改 WAV header 冒充 canonical；需要真实 downmix / resample。

## 9. 数据来源优先级

Stage 6 继续遵循：

1. QS668/CB08 真机可重复行为
2. 官方 Android App 3.0.9-u 静态实现
3. NextProto 固定 commit
4. Kardo 固定 commit

其中 `voice-card-android` 仅可提供过去真机观察线索，不是 Voica 源码来源。
