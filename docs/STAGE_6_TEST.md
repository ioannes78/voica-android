# Voica Stage 6 测试报告

状态：**ACCEPTED / 用户最终确认真机功能测试通过**

最终用户确认日期：2026-10-01

## 1. 最终真机候选

版本：

- versionCode：17
- versionName：`0.6.0-stage6-alpha3`

最终真机候选 HEAD：

`13bd6b6cd166be05a85eae1f77db84c7f8dbd26f`

Candidate CI：

- Run ID：`36744328433`
- 结论：**success**
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:core:database:testDebugUnitTest`：通过
- `:core:audio:test`：通过
- `:engine:opus:assembleDebug`：通过
- `:app:assembleDebug`：通过
- APK artifact：上传成功

最终真机 APK SHA-256：

`39df6ab657929250a032923e64643d450c37c399ff749fd79ac2c38b70aad38a`

## 2. 用户最终验收

用户最终明确回复：

**“真机功能测试通过”**

Stage 6 Alpha 3 真机功能验收通过后，允许进入 Freeze/Handoff。

## 3. 本地录音库

验证范围：

- Stage 5 轻量 metadata → Room 正式本地录音库
- 一条设备录音对应一条逻辑 Recording
- DEVICE_OPUS / DEVICE_WAV / CANONICAL_WAV 作为独立 AudioAsset
- 本地录音重命名只改变逻辑 displayName
- 标准设备名默认去掉 `.opus/.wav` 扩展名
- 本地删除与设备删除继续保持独立
- App 重启后本地录音库可恢复
- 设备断开后本地录音库仍可独立使用

Room：

- 数据库：`voica-recordings.db`
- schema version：1
- entities：
  - RecordingEntity
  - AudioAssetEntity
  - AudioDerivationEntity
  - LibraryMetaEntity
  - MigrationDiagnosticEntity

## 4. OPUS / WAV 设备资产

最终真机接受：

- 设备同一录音可分别下载 OPUS 与 WAV
- OPUS / WAV 作为同一 Recording 的两个独立设备资产
- 两个资产各自完成 integrity / format validation
- 下载顺序不再影响 WAV 的“已验证”状态
- OPUS → WAV 与 WAV → OPUS 两种顺序均作为 Stage 6 接受行为

Stage 6 不再使用“文件大小能被 40 整除即 RAW_OPUS”的启发式判断。

## 5. WAV 大小与下载进度

Alpha 3 增加并真机验收：

- 设备文件列表分别显示 OPUS / WAV 大小
- Stage 4 `sizeBytes` 继续保持 OPUS 文件大小语义
- WAV 通过同名 `.wav` 的 CMD=12 header probe 获取真实 RIFF 总大小
- probe 范围：`[0,44)`
- RIFF 总大小：little-endian `ChunkSize + 8`
- probe 不可用时，完整 WAV 下载首包仍可从 RIFF 头动态取得真实总大小
- OPUS 与 WAV 下载均显示确定型真实百分比进度：
  - receivedBytes
  - expectedBytes
  - percentage
- WAV 完成时实际 receivedBytes 必须与真实 RIFF total 一致，否则不得提交为成功

不使用 `duration × 32000` 作为完整性判断。

## 6. 原始 OPUS → 标准 WAV

主链：

```
DEVICE_OPUS
→ 原始文件完整性验证
→ raw/container detection
→ packet framing validation
→ official libopus
→ PCM
→ channel/sample-rate normalization
→ 16 kHz mono PCM16
→ RIFF/WAVE
→ SHA-256 / atomic commit
→ Room CANONICAL_WAV READY
```

标准音频契约：

- sampleRate：16000 Hz
- channelCount：1
- sampleFormat：signed PCM16 little-endian
- container：RIFF/WAVE
- byteRate：32000 bytes/s

原始 OPUS 长期保留，不因生成标准 WAV 自动删除。

## 7. 官方 libopus

Stage 6 独立实现 JNI/NDK bridge。

固定：

- libopus：1.6.1
- source SHA-256：
  `6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1`
- ABI：arm64-v8a
- NDK：28.2.13676358
- CMake：3.22.1

最终 APK 已确认包含：

`lib/arm64-v8a/libvoica_opus_jni.so`

## 8. 流式与长录音安全

Stage 6 音频处理保持 bounded memory：

- 下载流式写 `.part`
- SHA-256 流式计算
- Opus packet 小缓冲
- PCM frame 小缓冲
- WAV 流式写入
- 不把整条录音读入 ByteArray / ShortArray
- 转换支持取消
- crash/cancel 时不损坏原始设备音频

## 9. Stage 7 / Stage 8 稳定接口

Stage 6 已提供：

- `AudioSourceResolver`
- `PcmSourceResolver`
- `PcmSource`

后续不得绕过 resolver 直接依赖 Room 表或 BLE 文件路径。

Stage 8 的 PCM 契约：

- 16 kHz
- mono
- PCM16_LE
- streaming
- absolute sample index

## 10. Stage 6 明确不做

- 播放器 UI / seek / 倍速
- ASR / VAD / 标点
- Speaker diarization
- AI / Cloud
- 公共存储导出
- 分享
- Foreground Service
- 后台/锁屏持续下载
- persistent BLE resume
- parallel file download
- Delete All / DeleteBoth

后台持续下载已明确移动到 Stage 13。

## 11. Freeze/Handoff seal

Freeze/Handoff 内容提交：

`fcf38ce5049b84568b7ac7898e6738c96aefc019`

Android PR CI：

`36753154631` — **success**

该 Run 在用户最终“真机功能测试通过”后再次通过完整 Stage 6 自动验证与 Debug Build。

本次 seal evidence commit 仅记录最终验收证据，不改变 Stage 6 生产代码。
