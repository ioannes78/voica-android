# Voica Stage 2 测试清单

状态：开发中

## 2026-09-29 第一轮真机观察

- CB08 已成功进入 Ready；AE20/AE21/AE22/AE23 均发现，AE22/AE23 均成功订阅。
- Actual MTU = 517；36B 原子写与 168B 数据通道能力均满足。
- 容量、固件版本、Auth 已正常读取。
- 电量查询发现设备实际把 CONTROL `0/4` Battery Response 发到 **AE23**；首个候选仅把 AE22 提交给 pending request，导致 `RESPONSE_TIMEOUT cmd=4`。
- 真机响应 sequence 不回显 request sequence；例如 request seq=14 时 battery response seq=20。因此 Stage 2 保持 TYPE/CMD 为强匹配，sequence 仅用于诊断。
- 修复要求：AE22/AE23 两路独立解析不变，但两路完整 ProtocolFrame 都可提交给单路 pending matcher；matcher 仍只接受期望 TYPE/CMD。Battery Response 同时作为设备状态通知更新 UI。

## 自动验证

- [ ] Stage 1 `:core:protocol` 回归测试
- [ ] Stage 2 Control Protocol 测试
- [ ] BLE Permission Policy 测试
- [ ] MTU 39 / 171 / 517 边界测试
- [ ] Reconnect 1s / 2s / 4s 策略测试
- [ ] AE22 / AE23 独立 NotificationRouter 测试
- [ ] Serialized GATT Queue FIFO
- [ ] Wrong callback 不完成当前 operation
- [ ] Timeout 后 late callback 不污染下一 operation
- [ ] Queue close 取消 active operation
- [ ] Device request pending-before-write fast-response 测试
- [ ] `:core:ble:testDebugUnitTest`
- [ ] `:app:assembleDebug`
- [ ] GitHub Actions Android PR CI

## 第一轮真机主链路

- [ ] 首次权限申请
- [ ] 扫描发现 QS668/CB08
- [ ] Scan 去重 / RSSI 更新
- [ ] 连接
- [x] AE20 discovered
- [x] AE21 WRITE_NO_RESPONSE
- [x] AE22 Notify
- [x] AE23 Notify
- [x] requestMtu(517)
- [x] 记录 actual negotiated MTU：517
- [x] MTU >=39
- [x] >=171
- [ ] 电量
- [ ] 充电时 body[0] == 110 / UI“充电中”
- [x] 容量
- [x] 固件
- [x] Auth
- [ ] 时间同步命令
- [ ] 主动断开

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
