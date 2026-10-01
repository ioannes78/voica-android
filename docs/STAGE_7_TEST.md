# Voica Stage 7 测试报告

状态：**ACCEPTED / 用户明确决定按测试通过继续开发**

最终用户确认日期：2026-10-01

## 1. 最终生产候选

版本：

- versionCode：19
- versionName：`0.7.0-stage7-alpha2`

最终生产代码候选 commit：

`8a4bbcb3883ddc203702bae2113a18e8b9ca7103`

候选 CI：

- Run ID：`36806760170`
- Run #147
- 结论：**success**
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:core:database:testDebugUnitTest`：通过
- `:core:audio:test`：通过
- `:engine:opus:assembleDebug`：通过
- `:engine:playback:testDebugUnitTest`：通过
- `:engine:playback:assembleDebug`：通过
- `:app:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过
- APK artifact：上传成功

最终真机 APK SHA-256：

`6e1b36792014f70641c0f0299dfc282bc7673d8fceefb5fed366cb598ea4ef1f`

## 2. 最终用户验收决定

用户在完成 Stage 7 基础播放与录音互锁修复测试后，进一步执行 Bluetooth / Audio Focus 专项中的第 1–7 项，并明确回复：

**“1-7真机测试通过，8-12真机未测试，先按测试通过，继续开发。”**

因此 Stage 7 按用户决定进入 Freeze。

必须如实保留：第 8–12 项未执行，不得在后续文档中描述为已经真机验证。

## 3. 已真机验证范围

已确认通过：

- canonical WAV 本地播放
- 播放 / 暂停 / 继续
- sample timeline 驱动的进度显示
- Seek
- 快速 Seek
- 暂停状态 Seek 后保持暂停
- 播放状态 Seek 后继续播放
- 六档倍速
- EOF / Completed / 重新播放
- App 后台自动暂停、回前台不自动恢复
- 设备开始录音时本地播放自动暂停
- 设备物理 START/RESUME 与 App 录音状态均可触发播放互锁
- 录音结束后播放器不自动恢复
- Bluetooth 播放、断开、重新连接与路由变化专项（测试清单 1–4）
- Audio Focus 永久/短暂/Duck 专项（测试清单 5–7）

## 4. 自动化长录音专项

测试代码追加 commit：

`d96b622b6e3872a785b2225f4c0818f1778d699c`

扩展 CI：

- Run ID：`36815026040`
- Run #148
- 结论：**success**

自动化覆盖：

- 30 分钟虚拟 canonical source
- 60 分钟虚拟 canonical source
- 120 分钟虚拟 canonical source
- 10% / 50% / 90% 随机访问 Seek
- `pcmDataOffset + sampleIndex × bytesPerFrame` 地址计算
- 单次 PCM read request 保持 ≤ 32 KiB
- 2 小时虚拟源连续 20 次快速 Seek，最终 target wins

测试不创建真实 2 小时 WAV，不向仓库加入超大测试资产。

## 5. 未执行的真机专项

用户明确未执行此前专项清单第 8–12 项，主要包括：

- 真实约 30 分钟录音专项
- 真实约 1 小时录音专项
- 真实约 2 小时录音专项
- 2 小时混合播放/暂停/Seek/倍速/后台/Bluetooth 长时稳定性
- ADB meminfo 长录音内存对比

这些项目不是 Stage 7 已完成真机证据。

处理决定：

- Stage 7 接受并 Freeze
- 自动化长时长寻址/有界读取证据保留
- 真实 30min / 1h / 2h、RAM/CPU/温度、长时稳定性统一进入 **Stage 13 — 稳定性与长录音专项**

## 6. 精确时间轴自动测试

核心时间事实：

- canonical sample rate：16000 Hz
- absolute PCM sample index 是唯一媒体时间坐标
- `timeUs = sampleIndex * 1_000_000 / 16000`
- WAV data offset 来自真实 RIFF 解析，不假定 44 bytes
- bytes/frame = 2
- 公开 position 使用 presented sample，不使用 submitted cursor

测试覆盖：

- sample ↔ timeUs
- 非 44B WAV data offset
- timestamp / playback-head fallback
- 32-bit playback-head wrap
- seek rebase
- EOF 仅在 presented sample 到达 total samples 后 Completed
- invalid canonical source 在创建 AudioTrack 前失败
- focus resume policy
- device-recording/playback interlock

## 7. Stage 8 / Stage 10 稳定契约

Stage 8 继续复用：

- `PcmSourceResolver`
- `PcmSource`
- 16 kHz / mono / PCM16_LE
- absolute sample index

Stage 10 复用：

- `PlaybackController`
- `PlaybackSnapshot`
- `positionSampleIndex`
- `durationSampleCount`
- `positionUs`
- `durationUs`
- `seekToSample()`
- `discontinuityGeneration`

Stage 8/10 不得把毫秒 Float Slider 或 wall clock 重新定义为媒体时间真值。

## 8. Room 与模块回归

- Room schema version 仍为 1
- Stage 7 未修改 BLE 协议
- Stage 7 未修改 Opus 解码协议链
- Stage 7 新增 `:engine:playback`
- Stage 6 `AudioSourceResolver/PcmSourceResolver` 边界保留

## 9. Freeze 条件结论

用户已明确授权把 Stage 7 按测试通过继续开发。

因此：

- 功能验收：通过
- CI：通过
- Bluetooth / Audio Focus 1–7：真机通过
- 8–12 长录音真机专项：未执行、转 Stage 13
- Stage 7：允许 Freeze/Handoff/merge

## 10. Freeze/Handoff seal

Freeze/Handoff 内容提交：

`669885d9267b91fa0515fe265ea416e1454a585f`

Seal CI：

- Run ID：`36816506117`
- Run #149
- 结论：**success**

该 Run 在用户最终验收决定后，对 Freeze/Handoff 文档提交再次执行完整自动测试与 Debug Build，结果通过。
