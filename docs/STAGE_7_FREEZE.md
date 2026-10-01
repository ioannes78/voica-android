# Voica Stage 7 Freeze

状态：**FROZEN / 用户验收通过（保留明确测试债务）**

日期：2026-10-01

## 1. Freeze 结论

Stage 7 — 播放器 + 精确时间轴完成并冻结。

用户最终决定：

**“1-7真机测试通过，8-12真机未测试，先按测试通过，继续开发。”**

因此未执行的真实 30min / 1h / 2h 与长时资源压力专项被明确记录为 Stage 13 测试债务，不得在后续交接中误记为 Stage 7 已实测。

## 2. 最终候选

生产代码候选：

`8a4bbcb3883ddc203702bae2113a18e8b9ca7103`

扩展测试 HEAD：

`d96b622b6e3872a785b2225f4c0818f1778d699c`

版本：

- versionCode：19
- versionName：`0.7.0-stage7-alpha2`

候选 CI：

`36806760170` / #147 — **success**

扩展自动化 CI：

`36815026040` / #148 — **success**

APK SHA-256：

`6e1b36792014f70641c0f0299dfc282bc7673d8fceefb5fed366cb598ea4ef1f`

## 3. 冻结模块

当前模块：

```
:app
├─ :core:ble
│  └─ :core:protocol
├─ :core:database
├─ :core:audio
├─ :engine:opus
│  └─ :core:audio
└─ :engine:playback
   └─ :core:audio
```

Room schema 保持 version 1。

## 4. 播放源 Freeze

Stage 7 只播放经过 Stage 6 校验的：

`CANONICAL_WAV`

固定：

- RIFF/WAVE
- 16000 Hz
- mono
- PCM16_LE
- seekable
- integrity verified
- actual RIFF data offset

播放器不得把 raw OPUS 或 DEVICE_WAV 直接作为主播放真值。

## 5. 唯一时间坐标 Freeze

**absolute canonical PCM sample index 是 Voica 唯一媒体时间真值。**

固定：

- sampleRate = 16000
- 1 AudioTrack frame = 1 canonical PCM sample
- bytes/frame = 2
- `timeUs = sampleIndex * 1_000_000 / 16000`
- Slider/Float/wall clock 仅用于 UI 投影，不是媒体真值

该契约必须供 Stage 8/9/10 复用。

## 6. WAV addressing Freeze

随机访问：

```
byteOffset =
pcmDataOffsetBytes
+
sampleIndex * bytesPerFrame
```

不得假定 WAV header = 44 bytes。

seek target 必须 clamp 到：

`[0, totalSampleCount]`

## 7. Streaming / memory Freeze

播放器使用：

- AudioTrack MODE_STREAM
- 32 KiB PCM read buffer
- bounded streaming
- 不 whole-file read
- 不构建整段 ShortArray
- 不按录音时长扩大读取缓冲

30/60/120 分钟虚拟源和 2 小时快速 Seek 已有自动化证据。

真实长录音 RAM/CPU/温度压力仍留 Stage 13。

## 8. Position Freeze

公开位置使用 **presented sample**。

内部严格区分：

- source cursor
- submitted sample
- presented sample

优先：

- AudioTimestamp

fallback：

- playbackHeadPosition
- 32-bit wrap extension

load/seek/route rebase 后重新锚定。

## 9. Seek Freeze

- exact sample target
- latest wins
- seek 时 flush/rebase
- paused seek → 保持暂停
- playing seek → 完成后继续播放
- seek/load/restart 等增加 `discontinuityGeneration`
- Completed 再 Play → 从 sample 0 开始

## 10. Speed Freeze

固定支持：

- 0.5×
- 0.75×
- 1.0×
- 1.25×
- 1.5×
- 2.0×

使用 PlaybackParams：

- speed = selected
- pitch = 1.0
- unsupported speed 为 recoverable error
- speed 不改变媒体 sample timeline

## 11. Audio Focus / lifecycle Freeze

- LOSS → pause，不自动恢复
- LOSS_TRANSIENT → pause；仅符合 resume policy 时允许 gain 后恢复
- LOSS_TRANSIENT_CAN_DUCK → Stage 7 选择 pause，不 duck
- focus denied → 不播放 + recoverable error
- ACTION_AUDIO_BECOMING_NOISY → pause
- App background → pause
- 回前台 → 不因 background 原因自动恢复
- Stage 7 不实现 MediaSession / Foreground Service / lockscreen background playback

Bluetooth / Audio Focus 真机专项第 1–7 项已通过。

## 12. 设备录音互锁 Freeze

当 QS668/CB08 开始或继续录音：

- App 控制录音进入 Recording → 播放器暂停
- 设备物理 START/RESUME hardware edge → 播放器尽快暂停
- Recording 状态持续期间，如果播放器再次进入 Playing → 再次暂停
- 录音暂停/停止后 **不自动恢复播放**

该协调在 App 层完成，播放器 engine 不依赖 BLE 模块。

## 13. 删除协调 Freeze

删除当前正在播放的本地 Recording 前：

`playback unload → close handle → local delete`

不得先删除物理文件再让活跃播放器处理失效 handle。

## 14. Stage 8 契约

Stage 8 必须继续通过：

- `PcmSourceResolver`
- `PcmSource`

读取：

- 16 kHz
- mono
- PCM16_LE
- streaming
- absolute sample index

不得为 ASR 改写播放器时间轴。

## 15. Stage 10 契约

Stage 10 文字同步通过：

- `PlaybackController`
- `PlaybackSnapshot`
- `positionSampleIndex`
- `durationSampleCount`
- `positionUs`
- `durationUs`
- `seekToSample()`
- `discontinuityGeneration`

不得直接依赖 AudioTrack/WAV offset/Room 内部表。

## 16. 明确测试债务

Stage 7 接受但未执行：

- 真实 30 分钟长录音专项
- 真实 1 小时长录音专项
- 真实 2 小时长录音专项
- 2 小时综合稳定性
- ADB meminfo 长录音对比

以上统一进入 Stage 13 稳定性与长录音专项。

## 17. 下一阶段

下一阶段：

**Stage 8 — 本地 ASR + VAD + 标点 + 模型管理**

Stage 8 必须从合并后的最新 `main` 重新做 baseline validation，并先走需求确认/开发规划确认门禁。

## 18. Seal 证据

Freeze/Handoff 内容提交：

`669885d9267b91fa0515fe265ea416e1454a585f`

Seal CI：

`36816506117` / #149 — **success**

本次 seal 证明 Freeze/Handoff 内容提交没有破坏 Stage 1–7 build/test 基线。
