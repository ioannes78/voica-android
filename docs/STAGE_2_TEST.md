# Voica Stage 2 测试清单

状态：开发中

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
- [ ] AE20 discovered
- [ ] AE21 WRITE_NO_RESPONSE
- [ ] AE22 Notify
- [ ] AE23 Notify
- [ ] requestMtu(517)
- [ ] 记录 actual negotiated MTU
- [ ] MTU >=39
- [ ] 记录是否 >=171
- [ ] 电量
- [ ] 充电时 body[0] == 110 / UI“充电中”
- [ ] 容量
- [ ] 固件
- [ ] Auth
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
