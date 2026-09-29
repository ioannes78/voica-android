# Voica Stage 4 Freeze

状态：**FROZEN / ACCEPTED**

用户最终验收日期：2026-09-29

## 1. 冻结范围

Stage 4 冻结以下能力：

- TYPE=2/CMD=0 文件列表请求
- TYPE=2/CMD=1 多帧文件列表严格解析与聚合
- TYPE=2/CMD=18 明确完成
- 动态 filename field 长度兼容
- 严格 UTF-8/NUL/malformed 校验
- 20B 标准截断名安全恢复 `.opus`
- RemoteDeviceFile
- 文件录制时间、时长、大小与排序
- FileListFrameRouter
- FileListSessionCoordinator
- Fresh/Empty/Stale/Failed 状态
- 手动刷新
- Disconnect / generation / stale session 防护
- 文件列表 Diagnostics
- Stage 3 Recording/Poller 隔离
- Stage 4 Unit Tests

Stage 4 不冻结、不宣称实现：

- 文件下载
- TYPE=2/CMD=2 下载业务
- TYPE=2/CMD=12 区间下载业务
- 删除
- Room
- Opus/WAV
- 播放
- ASR/VAD
- Speaker
- AI/Cloud

## 2. 技术基线

- versionCode：10
- versionName：`0.4.2-stage4`
- Kotlin：2.4.20
- AGP：9.4.0
- Gradle：9.6
- JDK：17
- compileSdk：37.1
- targetSdk：37
- minSdk：26
- Application ID：`io.github.ioannes78.voica`
- 模块：`:app`、`:core:protocol`、`:core:ble`

依赖方向：

`app → core:ble → core:protocol`

## 3. 最终真机候选

候选 HEAD：

`f75a7a3e9169e4cdb92a1ccec5df5dde2067b9e8`

核心 duration 提交：

`4ca20453269ce054bd3fc0dbb80f69e721831656`

自动验证：

- Core CI：`36587944561` — success
- Candidate APK CI：`36588581441` — success
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过

APK SHA-256：

`b7d48c12a7346cc2f391645ddffe13f0edbb3f1c7cdf70183c76e3430cd000bc`

Freeze/Handoff 内容提交与最终 Freeze CI 由后续 seal 提交记录；seal 不改变 Stage 4 生产代码。

## 4. 冻结 TYPE=2 文件列表事实

| 方向 | 行为 | TYPE/CMD |
| --- | --- | --- |
| App → Device | 请求列表 | 2/0 |
| Device → App | 列表数据块 | 2/1 |
| Device → App | 列表结束 | 2/18 |

正常成功：

- 允许多个 2/1
- 只有收到 2/18 后才能原子发布 Fresh/Empty
- timeout 只能失败，不能伪装成功
- partial list 不得发布 Fresh

当前真机：

- CMD=1、CMD=18 均观察到 AE22
- 业务代码仍不得硬绑定 AE22
- CMD=18 body length = 1B
- response sequence 不要求回显 CMD=0 request sequence

## 5. 列表 entry

基础布局：

```text
COUNT_BE32
durationSeconds_BE32
sizeBytes_BE32
filename
```

官方 Android App 证明 filename field 可扩展；Voica 动态推导长度并严格验证。

当前 QS668/CB08 真机：

- filename field = 20B
- 标准 raw：`noteYYYYMMDD-HHMMSS.`
- 标准 resolved：`noteYYYYMMDD-HHMMSS.opus`

未知 trailing-dot 名称不得盲目补 Opus；不得自动生成 WAV variant。

## 6. 时长冻结事实

两组真机：

- 实际 11 秒 → rawTimeValue=11
- 实际 24 秒 → rawTimeValue=24

冻结：

`durationSeconds = rawTimeValue`

单位：秒。

协议模型仍保留 rawTimeValue 作为原始字段。

## 7. 文件列表 session

- 同时最多一个 active file-list session
- 2/1 只进入本轮 accumulator
- 2/18 一次性 finalize
- malformed payload 终止本轮
- disconnect 终止本轮
- transport session generation 变化不得完成旧列表
- 重连后旧成功结果可 Stale，但必须重新刷新才变 Fresh

## 8. 与 Recording 的隔离

Stage 4 第一版只有：

- Ready
- Recording Idle
- Recording freshness FRESH
- command state IDLE

才允许手动刷新文件列表。

Recording、Paused 或 command transition 时禁止刷新。

不得通过停止/重启 Stage 3 Poller 来“抢”文件列表 BLE 请求。

## 9. 2/12 重新定位

官方 Android App 静态分析确认：

**TYPE=2/CMD=12 是 ranged file transfer。**

Stage 4 不再采用 Kardo 式 2/12 文件名探测。

Stage 5 才实现：

- 2/2
- 2/3
- 2/4
- 2/5
- 2/7
- 2/12

并重新验证 filename 参数长度。

## 10. 用户验收

用户最终明确回复：

**“测试通过”**

因此 Stage 4 满足 Freeze/Handoff 条件。

## 11. 下一阶段

Stage 5：**文件下载 + 删除 + 原始音频落盘**

Stage 5 必须从 Stage 4 合并后的 `main` 重新接管，先检查实际 HEAD 和源码，再执行需求/规划双确认门禁。
