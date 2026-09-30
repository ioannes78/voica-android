# Voica Stage 5 真机发现

状态：**ACCEPTED / 用户最终确认测试通过**

日期：2026-09-30

## 1. 设备物理文件事实

同一条录音完成后，电脑直接查看录音卡物理存储：

```
note....opus
note....wav
```

两份同名、不同扩展名文件同时存在。

官方 App 下载该录音不会改变设备物理文件。

官方 App 的“删除缓存文件”不会改变录音卡物理文件；“删除录音笔文件”会同时删除同名 `.opus + .wav`。

## 2. Voica 完整下载

Stage 5 Alpha 1 / Alpha 3：

- 自动刷新正常
- 完整下载正常
- 下载进度正常
- expected / received size 校验通过
- 本地内容不是 RIFF/WAV
- 文件大小与设备 `.opus` 一致
- 本地保存为 `.opus`

Stage 5 Alpha 4 将本地类型正式显示为：

**OPUS 原始流**

用户确认原先 UNKNOWN 的已下载 `.opus` 也能正确恢复显示为“OPUS 原始流”。

## 3. CMD=12 真机验证

Alpha 3 Diagnostics：

- Range：0..255
- Range received：255
- Range actual filename：`note20260930-145841.opus`
- Range matches local：true
- Range end semantics：END_EXCLUSIVE
- Range remote status：0

冻结范围语义：

`[start, end)`

## 4. 单录音设备删除

Alpha 3 使用 raw list-entry 作为 CMD=8 body：

- 一次为 OutcomeUnknown，刷新后文件仍存在
- 一次明确 DELETE_REJECTED

因此 rawEntry 删除方案否定。

Alpha 4 改用：

`00000000 + filename fixed 24B`

用户最终确认：

- 删除正常
- 文件从设备列表消失
- 用电脑检查，设备物理同名 `.opus + .wav` 同时消失
- 其他设备录音未报告受影响

因此该删除参数布局作为当前 QS668/CB08 Stage 5 冻结事实。

## 5. Remote / Local 独立

用户已确认产品要求：

- 本地文件与设备端文件管理分开
- 不提供“同时删除设备和本地”功能

Stage 5 UI 和 Repository 已按该边界实现。

## 6. 用户最终验收

用户最终明确回复：

**“测试通过”**

并明确确认：

1. 本地原先 UNKNOWN 的 `.opus` 已显示“OPUS 原始流”；
2. 删除设备录音正常，文件从设备列表消失；
3. 电脑确认物理同名 `.opus + .wav` 同时消失。
