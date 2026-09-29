# Voica Stage 2 测试清单

状态：第一轮真机主链路已通过，第二轮稳定性 / 异常链路待验收

## 2026-09-29 第一轮真机验收

测试 APK：`0.2.1-stage2`

候选 commit：`0c09609385ed1b0c07d957bd6c37f3371ceb807c`

GitHub Actions：`36503703348` — success

用户结论：**第一轮真机测试通过**



- CB08 已成功进入 Ready；AE20/AE21/AE22/AE23 均发现，AE22/AE23 均成功订阅。
- Actual MTU = 517；36B 原子写与 168B 数据通道能力均满足。
- 容量、固件版本、Auth 已正常读取。
- 电量查询发现设备实际把 CONTROL `0/4` Battery Response 发到 **AE23**；首个候选仅把 AE22 提交给 pending request，导致 `RESPONSE_TIMEOUT cmd=4`。
- 真机响应 sequence 不回显 request sequence；例如 request seq=14 时 battery response seq=20。因此 Stage 2 保持 TYPE/CMD 为强匹配，sequence 仅用于诊断。
- 修复后 AE22/AE23 两路独立解析保持不变，两路完整 ProtocolFrame 都可提交给单路 pending matcher；matcher 仍只接受期望 TYPE/CMD。Battery Response 同时作为设备状态通知更新 UI。`0.2.1-stage2` 真机复测通过。

## 自动验证

- [x] Stage 1 `:core:protocol` 回归测试
- [x] Stage 2 Control Protocol 测试
- [x] BLE Permission Policy 测试
- [x] MTU 39 / 171 / 517 边界测试
- [x] Reconnect 1s / 2s / 4s 策略测试
- [x] AE22 / AE23 独立 NotificationRouter 测试
- [x] Serialized GATT Queue FIFO
- [x] Wrong callback 不完成当前 operation
- [x] Timeout 后 late callback 不污染下一 operation
- [x] Queue close 取消 active operation
- [x] Device request pending-before-write fast-response 测试
- [x] `:core:ble:testDebugUnitTest`
- [x] `:app:assembleDebug`
- [x] GitHub Actions Android PR CI

## 第一轮真机主链路

- [x] 首次权限申请
- [x] 扫描发现 QS668/CB08
- [x] Scan 去重 / RSSI 更新
- [x] 连接
- [x] AE20 discovered
- [x] AE21 WRITE_NO_RESPONSE
- [x] AE22 Notify
- [x] AE23 Notify
- [x] requestMtu(517)
- [x] 记录 actual negotiated MTU：517
- [x] MTU >=39
- [x] >=171
- [x] 电量
- [x] 充电时 body[0] == 110 / UI“充电中”
- [x] 容量
- [x] 固件
- [x] Auth
- [x] 时间同步命令
- [x] 主动断开

## 第二轮稳定性 / 异常链路

- [ ] 连续连接/断开 >=10 次
- [ ] 设备关机
- [ ] 远距离断开
- [ ] 自动重连 1s / 2s / 4s，最多三次
- [ ] 用户主动断开不重连
- [ ] Bluetooth Off
- [ ] Bluetooth On 后重新扫描/连接
- [ ] App 前后台 >=5 次
- [ ] 页面重组/切换不产生重复 GATT
- [ ] 无 Crash
- [ ] 无 ANR

## Freeze 门禁

只有用户明确回复 **“测试通过”** 后，才能填写最终 CI Run、APK、真机型号/Android 版本/设备固件、实际 MTU 和协议观察事实，并创建 Stage 2 Freeze/Handoff。
