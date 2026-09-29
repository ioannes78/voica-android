# Voica Stage 2 测试报告

状态：**ACCEPTED / 用户最终确认测试通过**

最终用户确认日期：2026-09-29

## 1. 真机测试候选

版本：`0.2.1-stage2`

真机代码候选：

`0c09609385ed1b0c07d957bd6c37f3371ceb807c`

候选 CI：

- Workflow：Android PR CI
- Run ID：`36503703348`
- 结论：success
- `:core:protocol:test`：通过
- `:core:ble:testDebugUnitTest`：通过
- `:app:assembleDebug`：通过
- APK Artifact 上传：通过

真机 APK SHA-256：

`cc96ad74763b5e35fbec3498c8224c2265e12babf518644a2b6d7716bce093cc`

## 2. 第一轮主链路

用户结论：**第一轮真机测试通过**

通过项：

- 首次权限申请
- 扫描发现 QS668/CB08
- Scan 去重 / RSSI 更新
- 连接
- AE20 discovered
- AE21 WRITE_NO_RESPONSE
- AE22 Notify
- AE23 Notify
- requestMtu(517)
- Actual MTU = **517**
- MTU >=39
- MTU >=171
- 36B atomic capability
- 168B data channel capability
- 电量
- 充电状态
- 容量
- 固件
- Auth
- 时间同步
- 主动断开

## 3. 第一轮发现并修复的真机差异

首个候选发现：

- Battery Request：TYPE=0/CMD=3
- 真机 Battery Response：TYPE=0/CMD=4
- Response 实际从 **AE23** 返回
- 首个实现仅允许 AE22 完成 pending request，因此出现 `RESPONSE_TIMEOUT cmd=4`

修复：

- AE22/AE23 独立 FrameParser 保持不变
- 两路完整 ProtocolFrame 都可提交给单路 pending matcher
- pending 仍严格匹配 expected TYPE/CMD
- Battery Response 同时更新设备信息 UI
- 新增 AE23 Battery Response 回归测试

另一个真机事实：

response sequence 不保证回显 request sequence。实测 request seq 与 response seq 存在差异，因此 Stage 2 不使用 sequence 强匹配。

修复版 `0.2.1-stage2` 复测通过。

## 4. 第二轮稳定性 / 异常链路

用户结论：**第二轮真机测试通过**

通过项：

- 连续连接/断开 >=10 次
- 设备关机
- 远距离断开
- 自动重连 1s / 2s / 4s，最多 3 次
- 用户主动断开不重连
- Bluetooth Off
- Bluetooth On 后重新扫描/连接
- App 前后台切换 >=5 次
- 页面切换/重组不产生重复 GATT
- 无 Crash
- 无 ANR

## 5. 自动化覆盖

通过：

- Stage 1 `:core:protocol` 回归测试
- Stage 2 Control Protocol
- BLE Permission Policy
- Scanner accumulator 去重/排序
- MTU 39 / 171 / 517
- Reconnect policy
- AE22 / AE23 独立 NotificationRouter
- Serialized GATT Queue FIFO
- Wrong callback 防护
- timeout + late callback 防护
- Queue close / cancel
- pending-before-write fast-response
- AE23 Battery Response regression
- `:core:ble:testDebugUnitTest`
- `:app:assembleDebug`

## 6. 代码漂移审计

真机代码候选 `0c096093…` 之后，到第一轮验收记录 `c9452da5…` 仅有 `docs/STAGE_2_TEST.md` 文档变化，无源码、Gradle、Workflow 变化。

该文档提交 CI：

`36524473446` — success。

第二轮验收记录同样只修改测试文档。

## 7. 最终用户门禁

用户已明确回复：

**“测试通过”**

因此 Stage 2 满足 Freeze/Handoff 条件。


## 8. Freeze 合并前最终验证

Freeze 内容提交：

`e1f6f08e221f9917b28a3ff9c07f92b297867c98`

Android PR CI：

`36526792513` — **success**

该 Run 在 Freeze/Handoff/README/ROADMAP/ARCHITECTURE/AGENTS 收口后再次执行并通过：

- `:core:protocol:test`
- `:core:ble:testDebugUnitTest`
- `:app:assembleDebug`

因此 Stage 2 在用户最终“测试通过”之后仍保持自动验证全绿。
