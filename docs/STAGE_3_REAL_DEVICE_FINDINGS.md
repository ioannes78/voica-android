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


## 第二轮真机修正（0.3.2-stage3）

0.3.1 真机确认卡录音控制已正常。新增观察：

- Idle/停止保存后，固件可能不响应 GET_FILENAME；该超时不能解释为录音操作失败。
- Pause 写入后立即读取 GET_STATE 可能短暂返回旧的 Recording 状态，导致 UI 仍显示“暂停”而不是“继续录音”。

修正：

1. Start/Pause/Resume/Save 控制命令仍只发送一次。
2. 控制后仅重复只读 GET_STATE，最多 6 次、间隔 250ms；目标分别为 Recording/Paused/Recording/Idle。
3. 不重复发送任何有副作用的录音控制命令。
4. 达到 Paused 后 UI 使用既有 Paused 分支自动显示“继续录音 + 停止并保存”。
5. Idle 状态跳过 GET_FILENAME，保留最后已知文件名；避免固件不响应导致约 5 秒超时。
6. GET_TIME/GET_FILENAME/GET_GAIN 等辅助读取失败只记入 BLE Diagnostics，不再写入用户可见的“最近录音操作”。
7. GET_STATE 仍是关键真值；其失败继续使同步失败并禁用不安全控制。


### 0.3.2 自动验证候选

- Core validation commit: `68082af9c1b0fcdf0ab1d2d123a601e6cf437c1e`
- CI Run: `36559318503` — success
- 通过：`:core:protocol:test`、`:core:ble:testDebugUnitTest`、`:app:assembleDebug`
- 下一步仅生成真机候选 APK；Stage 3 仍未 Freeze。


## 第三轮真机修正（0.3.3-stage3）

0.3.2 真机继续确认：

1. 设备执行 Pause 后，实际录音已经暂停，但连续 6 次 GET_STATE 仍可返回 Recording(1)。因此当前固件的 GET_STATE=1 不能区分“正在录音”和“已暂停”。
2. 物理 Stop/Save 后，设备已正确返回 Idle(2)，但旧的 1 秒 GET_TIME Poller 仍可能与 Hardware Event full sync 的 GET_TIME 竞争，造成 WRITE_FAILED；这不是停止录音失败。

0.3.3 规则：

- App 成功写入 Pause（03 06 01）后，将 Paused 作为强状态证据并锁存。
- 收到物理 Pause CMD=5 后，同样锁存 Paused。
- 在 Pause 锁存期间，GET_STATE 返回 Recording(1) 只作为固件歧义值记录，不允许覆盖 Paused。
- App/物理 Resume 解除 Pause 锁存并进入 Recording。
- Start/Save/Idle 同样解除 Pause 锁存。
- 任何 full recording sync 开始前先停止 GET_TIME Poller。
- 收到任何物理录音事件时立即停止 Poller，再做 acknowledgement + reconciliation。
- Idle 不再查询 GET_TIME 或 GET_FILENAME；StateReceived(Idle) 直接把当前录音时长/大小归零并保留最后文件名。
- 周期 GET_TIME 失败与其他辅助查询失败只写 BLE Diagnostics，不再进入用户可见的“最近录音操作”。
- GET_STATE 请求本身失败仍属于关键同步失败。


### 0.3.3 自动验证候选

- Core fix commit: `70b416ba2c20bdd4bd1b9201f337251a756af818`
- Core CI Run: `36567838517` — success
- 通过：`:core:protocol:test`、`:core:ble:testDebugUnitTest`、`:app:assembleDebug`
- 下一步仅生成 0.3.3 真机候选 APK；Stage 3 仍未 Freeze。
