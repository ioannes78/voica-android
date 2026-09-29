# Voica Stage 4 Protocol Findings

状态：**Stage 4 开发中 / 非 Freeze**

## 1. 参考基线

Stage 4 文件协议实现以以下证据顺序为准：

1. QS668 / CB08 当前真机可重复行为
2. 官方 Android App：`声云语音转写_3.0.9-u.apk`
3. NextProto 开放协议
4. Kardo 行为参考

官方 APK SHA-256：

`1fe095ac1fac469082617f57fa399e501b25fe482addead81cd2e7b3028b6662`

本文只记录协议结论，不包含或提交官方 APK、反编译源码或大段 proprietary code。

## 2. 官方 App 文件列表链路

静态分析确认官方 App 的文件列表主链路为：

```text
TYPE=2 / CMD=0  请求文件列表
TYPE=2 / CMD=1  文件列表数据块，可多次出现
TYPE=2 / CMD=18 文件列表结束
```

CMD=1 对应的业务上报为未结束数据块；CMD=18 对应 finish。

因此 Voica Stage 4 使用“session + accumulator + 明确 DONE”的模型，不把第一帧 CMD=1 当成完整列表，也不使用 idle timeout 伪装正常完成。

## 3. CMD=1 文件列表结构

每个列表数据块以大端 32 位数量字段开始。

基础 entry：

```text
4B raw time/value, BE32
4B size, BE32
20B filename base field
```

官方 App 不把 20B 当作唯一长度。当帧长度比 `count × 28B` 更长时，会把多出的字节按 entry 数量平均分配给每个 filename field。

Voica 因此采用：

```text
baseFilenameLength = 20
baseEntryLength = 28

extra = payloadLength - count * 28
filenameFieldLength = 20 + extra / count
entryLength = 8 + filenameFieldLength
```

同时要求长度完全自洽。无法整除、entry 截断、非法 UTF-8 或无法解释的 trailing bytes 一律视为 malformed，不发布 partial Fresh 列表。

## 4. 标准 QS668 文件名

标准文件名：

`noteYYYYMMDD-HHMMSS.opus`

长度为 24 个 ASCII/UTF-8 字节。

其前 20 字节：

`noteYYYYMMDD-HHMMSS.`

因此设备/固件可能出现：

- 20B trailing-dot 名称
- 24B 完整 `.opus` 名称

Voica 只对严格匹配 `noteYYYYMMDD-HHMMSS.` 的名称恢复 `.opus`。普通短名称或未知 trailing-dot 名称不猜扩展名。

## 5. CMD=12 定位

官方 App API 将 TYPE=2 / CMD=12 用作按起止位置读取文件：

```text
start LE32
end LE32
filename UTF-8 bytes
```

其后进入文件传输链路：

```text
2/3 文件开始/实际文件名
2/4 文件数据
2/5 文件结束
```

因此 Stage 4 **不使用 CMD=12 探测完整文件名**。CMD=12 的真机验证和正式实现留给 Stage 5。

## 6. 2/2、2/12 文件名长度差异

当前 Voica Stage 1 builder 仍使用 fixed-24 filename field。

官方 Android App 静态实现使用实际 UTF-8 filename bytes，而非协议层强制补齐 24B。

Stage 4 不执行 2/2 或 2/12，因此本阶段不修改这些 builder。Stage 5 必须重新依据：

- 官方 App
- NextProto
- QS668 / CB08 真机

验证后再冻结下载请求格式。

## 7. rawTimeValue

官方底层模型把列表 entry 第一个 BE32 字段命名为 time，但静态分析不足以证明其永久语义就是 duration seconds。

Stage 4 首轮 APK 保留名称：

`rawTimeValue`

并将 `durationSeconds` 保持 null。

真机使用已知约 10 秒、30 秒、60 秒录音与 Stage 3 录音时长对照后，才能决定是否冻结为 duration seconds。

## 8. 录制时间

录制时间优先从严格文件名：

`noteYYYYMMDD-HHMMSS.opus`

解析为本地 `LocalDateTime`。

不把 `rawTimeValue` 猜成 Unix timestamp，也不引入时区假设。

## 9. Stage 4 真机仍需确认

- 2/1 是否多帧
- 2/18 是否稳定存在
- 2/1 实际通知来源 AE22 / AE23
- 当前固件实际 filename field 长度
- 20B / 24B 真实行为
- rawTimeValue 的真实含义
- size 数值含义与合理性
- 空列表响应
- 重复刷新、断线、重连行为

真机结果若与官方 App 静态分析冲突，以真机为准。
