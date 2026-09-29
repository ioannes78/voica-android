# Stage 3 第一轮真机协议发现

日期：2026-09-29

## 输入证据

- QS668/CB08 真机：Voica 0.3.0-stage3。
- 用户提供官方 Android APK：`声云语音转写_3.0.9-u.apk`。
- NextProto `ai-recorder-card-open-protocol`。
- Kardo pinned revision `bcec3c5fdbcb34810a6f235e8b5873683f2ab951`。
- VoiceCard 历史 Stage 22.2 协议核验记录，仅作为事实交叉验证，不复制实现代码。

## 真机症状

1. Voica 发送 TYPE=3 奇数 Start/Pause 等命令后，GATT 写成功但设备没有状态变化，随后等待偶数“响应”超时。
2. 机身按键 Start 可以让设备进入录音，Voica 能收到 AE23 奇数事件并查询到 Recording。
3. 未发送官方按键 acknowledgement 时，GET_TIME 持续得到 0/0，物理 Stop 后 App 状态不能可靠收敛。

## 官方 Android 3.0.9-u 静态结果

官方 SDK `com.wind.pnote.bluetooth.Cmd` 的卡录音相关方法生成：

- `startBtnBackRecord()` → DATA `03 02 01`
- `stopBtnBackRecord()` → DATA `03 04 01`
- `pauseBtnBackRecord()` → DATA `03 06 01`
- `continueBtnBackRecord()` → DATA `03 08 01`

同一 APK 的 `startRecord/pauseRecord/continueRecord/stopRecord` 则生成 TYPE=1 实时音频命令 `01 00 / 01 03 01 / 01 03 00 / 01 02`，不是设备卡录音控制。

## Stage 3 修正后的协议解释

| DATA | 方向 | Stage 3 解释 |
| --- | --- | --- |
| `03 01` | Device → App | 物理开始事件 |
| `03 02 01` | App → Device | 开始卡录音 / 对物理开始确认 |
| `03 03` | Device → App | 物理停止保存事件 |
| `03 04 01` | App → Device | 停止保存 / 对物理停止确认 |
| `03 05` | Device → App | 物理暂停事件 |
| `03 06 01` | App → Device | 暂停 / 对物理暂停确认 |
| `03 07` | Device → App | 物理继续事件 |
| `03 08 01` | App → Device | 继续 / 对物理继续确认 |

NextProto/Kardo 将奇数定义为 App request、偶数定义为 response；该模型保留为跨实现参考，但本次真机证据与官方 Android APK 对当前固件的证据优先。

## 收敛规则

- 偶数控制写入成功只表示 GATT 写完成，不等价于设备最终状态。
- App 主动动作后约 120ms 开始 GET_STATE/GET_TIME/GET_FILENAME/GET_GAIN。
- 物理事件先回对应偶数+`01`，再查询。
- 不自动回退发送奇数副作用命令，避免重复开始/停止。

## 自动连接

Ready 后持久化最后成功设备地址。之后：

- App 首次进入前台自动连接记忆设备。
- Bluetooth OFF→ON 后在前台自动尝试。
- 权限恢复后自动尝试。
- 用户主动断开时，本进程停止自动连接。
- App 重新启动后恢复自动连接。
- 远端断线继续沿用 Stage 2 1s→2s→4s、最多 3 次的重连策略。
