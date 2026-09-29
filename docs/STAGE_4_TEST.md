# Voica Stage 4 测试报告

状态：**ACCEPTED / 用户最终确认测试通过**

最终用户确认日期：2026-09-29

## 1. 最终真机候选

版本：

- versionCode：10
- versionName：`0.4.2-stage4`

最终候选 HEAD：

`f75a7a3e9169e4cdb92a1ccec5df5dde2067b9e8`

核心时长语义提交：

`4ca20453269ce054bd3fc0dbb80f69e721831656`

自动验证：

- Core CI Run：`36587944561` — success
- Candidate APK CI Run：`36588581441` — success
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过
- APK artifact 上传：通过

最终真机 APK SHA-256：

`b7d48c12a7346cc2f391645ddffe13f0edbb3f1c7cdf70183c76e3430cd000bc`

## 2. 用户最终验收

用户最终明确回复：

**“测试通过”**

因此 Stage 4 满足 Freeze/Handoff 门禁。

## 3. 真机已确认文件列表链路

正常链路：

```text
TYPE=2/CMD=0 request
↓
TYPE=2/CMD=1 data chunk
↓
TYPE=2/CMD=1 data chunk
↓
...
↓
TYPE=2/CMD=18 done
```

真机多次刷新均观察到 CMD=18，Stage 4 不采用 idle timeout 作为正常完成条件。

已观察：

- 5 文件：2 个 CMD=1，declared/parsed=5/5
- 6 文件：2 个 CMD=1，declared/parsed=6/6
- 8 文件：2 个 CMD=1，declared/parsed=8/8
- 9 文件：3 个 CMD=1，declared/parsed=9/9
- Data RX source：AE22
- Done RX source：AE22
- Done body：1B
- 无已观察 CRC 错误

## 4. 文件名

当前 QS668/CB08 真机 filename field：

**20 bytes**

raw：

`noteYYYYMMDD-HHMMSS.`

Voica 仅对严格标准格式恢复：

`noteYYYYMMDD-HHMMSS.opus`

完整文件名显示、录制时间解析和最新优先排序通过真机验收。

Stage 4 不生成虚拟 WAV variant，也不使用 CMD=12 探测文件名。

## 5. 时长语义专项

0.4.1-stage4 两组专项：

- 实际录音 11 秒 → `Newest rawTimeValue=11`
- 实际录音 24 秒 → `Newest rawTimeValue=24`

因此确认列表 entry 第一个 BE32：

**durationSeconds，单位秒**

0.4.2-stage4 已正式映射到文件列表 UI 的 mm:ss / hh:mm:ss 显示。

## 6. 新录音保存后刷新

真机已验证：

`Stop/Save → Idle → 手动刷新 → 新录音进入文件列表`

文件数从 5 增加到 6 时：

- declared/parsed=6/6
- LIST_DONE 正常

## 7. 自动化覆盖

Stage 4 新增/强化：

- 20B / 24B / 动态 filename field 解码
- BE32 time / size
- body<4 / truncated entry / declared>available
- extra bytes 不可整除
- invalid UTF-8
- NUL padding
- standard trailing-dot → .opus
- unknown trailing-dot 不猜扩展名
- Recording filename timestamp parsing
- FileListFrameRouter
- FileListSessionCoordinator
- multi-frame aggregation
- LIST_DONE completion
- malformed session failure
- transport session mismatch
- RemoteDeviceFile mapping / identity / sorting
- durationSeconds 映射
- DeviceCommandClient send-only sequence

同时继续通过 Stage 1–3 regression。

## 8. Stage 4 边界

Stage 4 不实现：

- 文件下载
- TYPE=2/CMD=2 下载业务
- TYPE=2/CMD=12 区间下载业务
- 下载进度/取消/断点续传
- 设备删除
- Room / 本地录音库
- Opus/Ogg/WAV 解码
- 播放器
- ASR/VAD/标点/Speaker
- AI / Cloud

以上从 Stage 5 及后续阶段继续。
