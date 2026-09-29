# Voica Stage 4 Real Device Findings

状态：**Stage 4 真机验证进行中 / 非 Freeze**

本文记录 QS668 / CB08 真机事实。未收到用户明确“测试通过”前，不代表 Stage 4 Freeze。

## 当前候选

- Branch: `stage4-development`
- Version: `0.4.1-stage4`
- versionCode: `9`
- Diagnostic enhancement commit: `3f1d740a7f88f02f7619aa2de75e2fb2402974bf`
- Core CI: `36585115551` — success
- Draft PR: #4

## 已确认的真机事实

### 文件列表主链路

0.4.0 Stage 4 真机已连续观察到：

```text
TYPE=2 / CMD=0  request
TYPE=2 / CMD=1  data chunk
TYPE=2 / CMD=1  data chunk
TYPE=2 / CMD=18 done
```

CMD=18 在多次手动刷新中稳定出现，当前继续作为正常成功的唯一完成信号；不引入 idle timeout 作为正常完成。

### 多帧列表

5 个文件时：

- 第一帧 CMD=1：日志 frame bytes=118
- 第二帧 CMD=1：日志 frame bytes=34
- 最终 declared/parsed=5/5

按 ProtocolFrame 去除 TYPE/CMD 后，对应 body：

- 116B = 4B count + 4 × 28B entry
- 32B = 4B count + 1 × 28B entry

6 个文件时：

- 第一帧 CMD=1：frame bytes=118
- 第二帧 CMD=1：frame bytes=62
- declared/parsed=6/6

对应第二个 body：

- 60B = 4B count + 2 × 28B entry

因此当前固件确实会把一个列表拆成多个 CMD=1 数据块。

### filename field

当前 QS668 / CB08 固件实际 filename field 已确认是：

`20 bytes`

真实 raw filename 形态例如：

`note20260908-231420.`

Voica 恢复为：

`note20260908-231420.opus`

真机页面显示完整 `.opus` 文件名正常，未观察到虚拟 WAV 条目。

### 通知来源

本轮观察到：

- CMD=1：AE22
- CMD=18：AE22

实现仍保持按 TYPE/CMD 路由，不将文件协议永久硬编码到 AE22。

### CMD=18 body

真机日志 CMD=18 显示 frame bytes=3。

ProtocolFrame 中 TYPE/CMD 占 2B，因此当前设备的 CMD=18：

`body length = 1 byte`

Stage 4 不要求 CMD=18 body 为空，这与真机行为兼容。

### Sequence

真机中 CMD=1 / CMD=18 使用设备侧递增 sequence，且不要求回显 TYPE=2/CMD=0 的 request sequence。

因此继续遵守 Stage 2 冻结原则：

业务匹配依据 TYPE/CMD，sequence 仅用于诊断。

### 重复刷新

5 文件状态下连续多次手动刷新均得到完整列表并正常 LIST_DONE，未观察到：

- 重复文件
- 漏文件
- partial list 被发布 Fresh
- CRC 错误

### 停止保存后刷新

新录音停止并保存后：

- Recording GET_STATE 返回 raw=2 / Idle
- 随后手动 TYPE=2/CMD=0
- 文件数从 5 增加为 6
- declared/parsed=6/6
- LIST_DONE 正常

因此“停止保存 → Idle → 手动刷新 → 新文件进入列表”已通过一次真机验证。

## 0.4.1 诊断增强

0.4.1 不改变文件列表业务协议，仅将 Diagnostics 拆分并补充：

- Data RX source
- Done RX source
- Last data body
- Filename field
- Done body
- Newest raw filename
- Newest resolved filename
- Newest rawTimeValue
- Newest size bytes
- Newest resolution
- Session duration

这样下一轮可直接确认列表 entry 第一个 BE32 的真实语义。

## 仍待确认

### rawTimeValue

需新录制一段已知时长，例如约 10 秒或 20 秒：

1. 记录 Stage 3 停止保存前显示的录音时长。
2. 保存后刷新文件列表。
3. 查看 `Newest rawTimeValue`。
4. 比较两者。

只有真机确认一致后，才能将该字段正式提升为 `durationSeconds` 并在文件列表 UI 显示时长。

### 尚未完成的真机项

- Empty 设备文件列表真实响应
- 刷新过程中 Disconnect / Reconnect
- App 开始 → 暂停 → 继续 → 停止保存完整 Stage 3 回归
- 设备物理键开始 / 停止完整回归
- 自动连接回归
- rawTimeValue 语义
- size 的最终语义/单位验证

## Stage 4 边界

当前阶段不主动发送：

- TYPE=2 / CMD=2
- TYPE=2 / CMD=12

下载、分段下载、取消、断点续传、删除均属于 Stage 5。
