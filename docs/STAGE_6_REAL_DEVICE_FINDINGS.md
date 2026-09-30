# Voica Stage 6 真机发现

状态：**ACCEPTED / 用户最终确认真机功能测试通过**

日期：2026-10-01

设备：QS668 / CB08

## 1. Alpha 2 真机发现

Alpha 2 暴露三个主要问题：

1. WAV 下载没有确定型真实进度条。
2. 先下载 OPUS 再下载 WAV 时，DEVICE_WAV 会停在“待验证”；先 WAV 则显示“已验证”。
3. 逻辑本地录音名称受首次下载格式影响，会显示 `.opus` 或 `.wav`。

这些均在 Alpha 3 修复。

## 2. Alpha 3 真机验收

最终版本：

`0.6.0-stage6-alpha3`

最终候选 HEAD：

`13bd6b6cd166be05a85eae1f77db84c7f8dbd26f`

用户最终明确回复：

**“真机功能测试通过”**

因此以下 Stage 6 Alpha 3 功能作为最终可接受行为冻结。

## 3. 设备文件双格式大小

设备文件 UI 分别显示：

- OPUS 大小
- WAV 大小

WAV 大小使用同名 WAV header probe 获取。

这确认当前 QS668/CB08 对 Stage 6 使用的同名 WAV CMD=12 header range 方案可工作。

## 4. OPUS / WAV 下载进度

两种格式都使用真实：

- received bytes
- expected bytes
- percentage

WAV expected bytes 由真实 RIFF header 得出。

## 5. 下载顺序

Alpha 3 将 DEVICE_OPUS / DEVICE_WAV 格式验证从 canonical source selection 拆开。

最终用户确认整体功能测试通过，因此先 OPUS/后 WAV 与先 WAV/后 OPUS 不再作为不同验证语义处理。

## 6. 本地逻辑名称

标准设备名：

- `noteYYYYMMDD-HHMMSS.opus`
- `noteYYYYMMDD-HHMMSS.wav`

本地 Recording 默认显示：

`noteYYYYMMDD-HHMMSS`

逻辑重命名不要求保留文件扩展名。

## 7. 本地物理存储

当前权威文件仍在 App private：

`noBackupFilesDir/recordings`

普通 Android 文件管理器不可直接浏览属于预期行为。

Stage 6 不把权威库移动到公共 Documents / Download。

## 8. canonical WAV

Stage 6 真机链路已能生成并使用：

`16 kHz / mono / PCM16_LE / RIFF-WAVE`

原始设备 OPUS 保留。

## 9. 后台行为

Stage 6 真机范围不包含可靠后台持续下载。

当前 App 进入后台时主动取消当前 BLE 文件传输。

可靠后台/锁屏持续下载已进入 Stage 13。
