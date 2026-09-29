# Voica Stage 3 测试报告

状态：**ACCEPTED / 用户最终确认测试通过**

最终用户确认日期：2026-09-29

## 1. 最终真机候选

版本：

- versionCode：7
- versionName：`0.3.3-stage3`

最终真机候选提交：

`de03a707dfaea22cc6932a6e0211ac69f9691835`

核心修复提交：

`70b416ba2c20bdd4bd1b9201f337251a756af818`

自动验证：

- Core CI Run：`36567838517` — success
- Candidate APK CI Run：`36568209124` — success
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过
- APK artifact 上传：通过

最终真机 APK SHA-256：

`426addc3a0da5ae68a60bf13bcff9ff7e2a43cca7417d22b68168384c5511ded`

## 2. 真机验收结论

用户最终明确回复：

**“真机测试通过”**

最终通过能力：

- App 开始录音
- App 暂停
- 暂停后显示“继续录音”
- App 继续录音
- App 停止并保存
- 录音时长更新
- 当前大小更新
- 当前文件名显示
- 录音增益读取/设置
- 设备物理按键开始
- 设备物理按键暂停/继续
- 设备物理按键停止
- App 与设备物理操作后状态最终一致
- 辅助查询失败不再误显示为录音操作失败
- 自动连接功能保留并正常工作
- 无本轮已知阻断性 Crash / ANR

## 3. 第一轮真机发现

0.3.0-stage3 初版按 NextProto/Kardo 的奇数 request → 偶数 response 模型实现 App 卡录音控制。

真机发现：

- App 点击开始/暂停后设备不动作
- 奇数 CMD 写成功但后续 response timeout
- 设备物理按键可以开始录音
- 物理录音后 App 能感知部分状态，但停止/时长同步不完整

结合官方 Android `声云语音转写 3.0.9-u` 静态分析和 VoiceCard 历史真机事实交叉验证，确认当前 QS668/CB08 固件实际为：

- Device → App：CMD 1/3/5/7 物理按键事件
- App → Device：CMD 2/4/6/8 + `01` 卡录音控制/确认

因此 0.3.1 改为偶数控制，不再等待错误方向的 action response。

## 4. 第二轮真机发现

0.3.1 基本录音控制恢复正常，但发现：

- Idle/停止后 GET_FILENAME 可能不响应
- Pause 后首次 GET_STATE 可能仍返回 Recording

0.3.2 修正：

- Idle 跳过 GET_FILENAME
- 辅助字段失败降级到 Diagnostics
- App 控制后只读 GET_STATE 做有限收敛，不重复副作用命令

## 5. 第三轮真机发现

0.3.2 进一步证明：

- 设备已经实际 Pause，但连续 6 次 GET_STATE 仍可返回 Recording(1)
- 因此当前固件 GET_STATE=1 不能区分 Recording / Paused
- 物理 Stop 后旧 GET_TIME Poller 可与 full sync 竞争并产生 WRITE_FAILED，但停止动作本身成功

0.3.3 最终修正：

- Pause 使用 App Pause 写成功 / 物理 CMD=5 的强语义证据锁存
- Pause 锁存期间 GET_STATE=1 不覆盖 Paused
- Resume / Start / Save / Idle 解除 Pause 锁存
- 物理事件与 full sync 开始前立即停止 Poller
- Idle 跳过 GET_TIME / GET_FILENAME
- 周期 GET_TIME 和其他辅助查询失败只进入 Diagnostics

## 6. 自动化覆盖

Stage 3 新增并通过：

- TYPE=3 卡录音 control golden frames
- Recording status / gain / time / filename decoder
- truncated TIME_RESPONSE 显式失败
- AE23 response regression
- unsolicited hardware event 不误完成 pending
- RecordingFrameRouter
- RecordingStateReducer
- RecordingPollingPolicy
- Pause semantic evidence policy
- Idle supplementary read policy
- Remembered-device auto-connect policy

同时继续通过 Stage 1/2 regression：

- Protocol parser / CRC / golden frames
- AE22/AE23 独立 parser
- pending-before-write
- GATT serialization
- MTU policy
- reconnect policy
- AE23 Battery Response regression

## 7. Stage 3 验收边界

Stage 3 不包含：

- 文件列表 UI
- 文件下载/断点续传/删除
- Opus/WAV 解码
- Room / 本地录音库
- 播放器
- TYPE=1 实时音频业务
- 手机麦克风录音
- VAD / ASR / 标点 / Speaker
- AI 会议
- 云端转写
- Foreground BLE Service

以上从 Stage 4 及后续阶段继续。
