# Voica Stage 5 协议发现

状态：**FROZEN / 真机验证完成**

日期：2026-09-30

## 1. 文件下载主链

当前 QS668/CB08 真机确认：

```
App → Device  TYPE=2 CMD=2  Download Request
Device → App  TYPE=2 CMD=3  Transfer Start
Device → App  TYPE=2 CMD=4  Data × N
Device → App  TYPE=2 CMD=5  Transfer End
```

下载请求必须作为完整 ProtocolFrame 单次写入 AE21，不做应用层分片。

标准设备文件名：

`noteYYYYMMDD-HHMMSS.opus`

当前标准文件名恰好为 24 ASCII bytes。因此真机观察到标准请求为 36B 完整帧，但这不能推广为“所有未来文件名都只能固定 24B”。

## 2. 实际传输内容

真机下载成功后：

- receivedBytes 与 Stage 4 list sizeBytes 一致
- 首部不是 RIFF/WAVE
- 文件大小与设备物理 `.opus` 一致
- 本地保存为原始 `.opus`
- 当前数据表现为 40B packet 对齐的 raw Opus stream

因此 Stage 5 不做 Opus→WAV 转换。

## 3. CMD=12 范围传输

真机探测：

- request：0..255
- received：255 bytes
- actual filename：`note20260930-145841.opus`
- 与完整下载本地文件前 255 bytes 完全一致
- remote status：0

冻结：

```
range = [start, end)
```

即 end exclusive。

## 4. 取消

取消使用：

- App → Device：TYPE=2 CMD=7
- Device → App：TYPE=2 CMD=11（best effort response）

取消不无限等待 2/11；临时文件必须关闭并删除。

## 5. 单文件删除

Alpha 3 曾验证候选：

```
CMD=8 body = raw file-list entry
```

真机结果为 timeout/outcome unknown 或 reject，文件仍存在，因此该候选被否定。

Alpha 4 改用并由真机最终确认：

```
CMD=8 body = 28 bytes
00 00 00 00
+
filename fixed 24B
```

对标准文件：

`noteYYYYMMDD-HHMMSS.opus`

完整 24B 文件名直接放在后 24B。

成功删除后：

- 设备列表目标录音消失
- 电脑检查确认物理同名 `.opus + .wav` 同时消失

禁止恢复 rawEntry 删除布局。

## 6. 删除结果安全

Stage 5 规则：

- destructive request 写出后，如果 response timeout / disconnect，则进入 OutcomeUnknown
- OutcomeUnknown 不自动重发 CMD=8
- reconnect/refresh 后根据 Fresh list 核对目标是否仍存在
- 不提供 Delete All
- 不提供设备+本地联合删除

## 7. 数据来源优先级

本 Stage 最终协议事实优先级：

1. QS668/CB08 真机重复行为
2. 官方 Android App 3.0.9-u
3. NextProto 固定 commit
4. Kardo 固定 commit

其中 rawEntry 删除候选虽存在于参考实现，但已被当前真机事实否定。
