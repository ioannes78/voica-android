# Voica Stage 7 Handoff

## 当前结论

Stage 7 已完成开发、自动验证和用户验收，并允许 Freeze/merge。

用户验收决定：

**“1-7真机测试通过，8-12真机未测试，先按测试通过，继续开发。”**

本 Handoff 供完全没有阅读本次聊天的新 Agent 接管 Stage 8。

## 仓库与 PR

Repository：

`ioannes78/voica-android`

Stage 7 开发分支：

`stage7-development`

Stage 7 PR：

`#7 Stage 7: sample-accurate local playback`

Stage 7 起始 main：

`825286d877ff3716ce7d92839ad635734f79eb74`

生产代码候选：

`8a4bbcb3883ddc203702bae2113a18e8b9ca7103`

扩展测试 HEAD：

`d96b622b6e3872a785b2225f4c0818f1778d699c`

Candidate CI：

`36806760170` / #147 — **success**

扩展自动化 CI：

`36815026040` / #148 — **success**

版本：

- versionCode：19
- versionName：`0.7.0-stage7-alpha2`

APK SHA-256：

`6e1b36792014f70641c0f0299dfc282bc7673d8fceefb5fed366cb598ea4ef1f`

最终合并后的 `main` SHA 必须由 Stage 8 接管 Agent 从 GitHub 重新读取，不得从本文件推断。

## 当前模块

```
:app
├─ :core:ble → :core:protocol
├─ :core:database
├─ :core:audio
├─ :engine:opus → :core:audio
└─ :engine:playback → :core:audio
```

Room schema 仍为 version 1。

## Stage 7 核心实现

### :core:audio

新增/扩展：

- PlaybackAudioSourceDescriptor canonical PCM metadata
- sample ↔ timeUs conversion
- sample → PCM byte offset
- PlaybackController
- PlaybackState
- PlaybackSnapshot
- PlaybackError / PlaybackErrorCode

### :engine:playback

新增：

- AudioTrack MODE_STREAM
- bounded canonical PCM streaming
- PlaybackPositionTracker
- AudioTimestamp / playback-head fallback
- 32-bit head wrap
- sample seek
- latest-wins rapid seek
- EOF presented-position gate
- six playback speeds
- Audio Focus
- noisy output / route handling
- app foreground/background policy

### :app

新增：

- application-scoped PlaybackController
- PlaybackViewModel
- Local Recording 播放入口
- 单一 PlayerCard
- sample-based slider projection
- 当前播放录音删除协调
- device recording ↔ local playback interlock

## 最重要时间轴契约

唯一媒体时间：

`absolute canonical PCM sample index`

固定：

- 16000 samples/sec
- mono
- PCM16_LE
- 2 bytes/frame

所有 Stage 8/9/10 时间结果最终必须能映射到这一坐标。

## Stage 8 必须复用

Stage 8 使用：

- `PcmSourceResolver`
- `PcmSource`
- `PcmReadResult.startSampleIndex`

不得：

- 直接读 Room 表作为音频源
- 直接访问 BLE 文件
- 直接使用 raw OPUS 作为 ASR 主输入
- 用毫秒 Float 取代 absolute sample index
- 因 ASR 需求修改 PlaybackController 的媒体真值

## Stage 10 必须复用

Stage 10 使用：

- `PlaybackSnapshot.positionSampleIndex`
- `seekToSample()`
- `discontinuityGeneration`

用于文字点击跳转、句/词高亮和 seek 后立即重算 active transcript。

## Device Recording Interlock

当前冻结行为：

- device Recording → local playback pause
- hardware START/RESUME edge → local playback pause
- Recording 持续期间阻止播放器实际持续 Playing
- device recording 结束后不自动恢复播放

不要把 BLE 依赖下沉到 `:engine:playback`。

## 已知测试债务

已真机通过：

- Stage 7 基础播放功能
- 设备录音互锁
- Bluetooth / Audio Focus 专项 1–7

未真机执行：

- 真实 30min / 1h / 2h 长录音专项
- 2h 综合稳定性
- ADB meminfo 长录音资源对比

已有自动化补偿证据：

- virtual 30/60/120min source
- bounded 32 KiB reads
- 2h / 20 rapid seeks

剩余真实长录音压力统一进入 Stage 13。

## Stage 8 接管顺序

1. 读取 GitHub 最新 `main`、HEAD、open PR、Actions。
2. 阅读根目录 `AGENTS.md`。
3. 阅读：
   - `docs/STAGE_7_TEST.md`
   - `docs/STAGE_7_FREEZE.md`
   - `docs/STAGE_7_HANDOFF.md`
   - `docs/ARCHITECTURE.md`
   - `docs/ROADMAP.md`
4. 检查当前 `PcmSourceResolver/PcmSource` 实现与 Room schema。
5. 执行 baseline validation。
6. 先输出《Voica Stage 8 修订需求》。
7. 等用户确认。
8. 再输出《Voica Stage 8 修订开发规划》。
9. 再次等用户确认后才允许创建 Stage 8 development branch 和编码。

## Freeze/Handoff seal

Freeze/Handoff 内容提交：

`669885d9267b91fa0515fe265ea416e1454a585f`

Seal CI：

`36816506117` / #149 — **success**

Stage 8 接管时仍必须重新读取最终合并后的 `main` HEAD 与最终 Actions。
