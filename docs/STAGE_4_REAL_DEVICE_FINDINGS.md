# Voica Stage 4 Real Device Findings

状态：**等待 QS668 / CB08 真机验证**

本文件用于记录 Stage 4 真机候选的协议事实。未完成真机验收前，不代表 Freeze 结论。

## 当前候选

- Branch: `stage4-development`
- Version: `0.4.0-stage4`
- versionCode: `8`
- Candidate base commit: `bd37d0f5f533989113c06732221dfab416d54dee`
- Core CI: `36580106183` — success
- Draft PR: #4

## 第一轮重点验证

### 1. 文件列表正常读取

设备处于 Ready + Idle 后点击“刷新文件列表”。

记录 Diagnostics：

- File session
- Transport session
- File request seq
- File frames
- Declared/parsed
- File RX source
- Body/name field
- List done
- Raw filename
- Resolved filename
- Resolution
- Completion

验收：

- 能显示设备现有录音
- 不重复
- 不漏文件
- 收到 LIST_DONE 后一次性更新
- 重复刷新结果稳定

### 2. filename field 实际长度

重点确认：

- 20 bytes
- 24 bytes
- 或其他长度

同时记录 raw filename 与 resolved filename。

若为标准：

`noteYYYYMMDD-HHMMSS.`

应仅恢复为：

`noteYYYYMMDD-HHMMSS.opus`

不得生成虚拟 `.wav` 文件。

### 3. LIST_DONE

确认 TYPE=2 / CMD=18 是否每次稳定出现。

如果没有 CMD=18：

- 当前候选应最终超时失败
- 不得把 partial list 标记 Fresh

不要在真机结论明确前引入 idle fallback。

### 4. 通知来源

记录 TYPE=2 / CMD=1 和 CMD=18 实际来自：

- AE22
- AE23
- 或两者

代码仍按 TYPE/CMD 路由，不绑定通知来源。

### 5. rawTimeValue

建议新录制约：

- 10 秒
- 30 秒
- 60 秒

分别记录：

- Stage 3 录制显示时长
- Stage 4 rawTimeValue
- 文件名
- sizeBytes

确认 rawTimeValue 是否确实等于 duration seconds。

在确认前，Stage 4 UI 时长显示“未知”。

### 6. Empty

清空设备文件后刷新，确认真实响应：

- 是否直接 CMD=18
- 是否先 CMD=1 count=0
- 或其他行为

只有得到正常完成证据才显示“设备中暂无录音”。

### 7. Disconnect / Reconnect

刷新过程中断开设备：

- App 不崩溃
- partial list 不得变 Fresh
- 已有成功列表应变 Stale

重连后：

- 旧结果仍可见但标记过期
- 手动刷新可得到新 Fresh 列表

### 8. Stage 3 回归

必须重新验证：

- App 开始录音
- 暂停
- 继续
- 停止并保存
- 设备物理键开始
- 设备物理键停止
- 自动连接

不得重新出现：

- `RESPONSE_TIMEOUT GET_FILENAME` 被显示成录音操作失败
- Pause 被 GET_STATE=1 错误覆盖为 Recording
- Recording Poller 与文件刷新竞争

## Stage 4 明确不验证

当前阶段不主动发送：

- TYPE=2 / CMD=2
- TYPE=2 / CMD=12

文件下载、分段下载、取消、断点续传、删除均属于 Stage 5。

## 待填写真机结论

- 实际 filename field length：
- CMD=1 是否多帧：
- CMD=18 是否稳定：
- CMD=1 来源：
- CMD=18 来源：
- rawTimeValue 语义：
- size 语义：
- Empty 响应：
- 重复刷新：
- Disconnect / Reconnect：
- Stage 3 regression：
- 用户最终结论：
