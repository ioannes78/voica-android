# Stage 15.7 — D0 / D1 TYPE3 与 TYPE1 独立性真机 Gate

状态：**D0 真机事实已确认 / D1 待真机验收**

## 1. 范围

本 Gate 只验证 QS668 / CB08 当前真机上的两套控制面关系：

- TYPE=3：设备本地录音生命周期；
- TYPE=1：BLE realtime audio 生命周期。

不得把本 Gate 扩展为 Stage 16A streaming ASR 产品逻辑。

生产 BLE transport、RealtimeAudioRuntime、Opus decoder、canonical source 的实现不因 D0 结果而绕过或替换。

## 2. D0 — Passive TYPE1 Fact Gate

### 2.1 固定操作

只执行：

`TYPE3 APP_RECORD_START → 录音 8–10 秒 → TYPE3 APP_RECORD_SAVE`

明确禁止调用 realtime START / STOP / PAUSE / RESUME。

### 2.2 2026-10-12 真机结果

已确认 Gate：

```text
runtime realtimeCommands start=0/0 pause=0/0 resume=0/0 stop=0/0
runtime fence gatedReject=0 staleOffer=0 quarantineReject=0 overflow=0
runtime source started=0 ended=0 offeredFrames=0 offeredSamples=0
D0 delta gatedReject=0 realtimeStart=0 realtimePause=0 realtimeResume=0 realtimeStop=0
D0 sourceDelta started=0 ended=0 fileCountDelta=1
D0 invariant noRealtimeControl=true passiveType1Observed=false
```

### 2.3 D0 正式事实

D0 Fact Gate 有效并完成。

冻结事实：

- TYPE3 普通设备录音能够独立成功创建录音文件；
- 未调用 realtime START 时，真机没有发送 TYPE1 realtime audio；
- 因此 `TYPE3 APP_RECORD_START` **不会隐式启动** TYPE1 realtime audio；
- TYPE1 realtime audio 必须由 App 显式执行 realtime START。

正确解释不是“D0 Probe 失败”，而是：

```text
D0 factGate=PASS
passiveType1DuringType3=false
conclusion=TYPE1_REQUIRES_EXPLICIT_REALTIME_START
```

## 3. D1 — TYPE3 + 显式 TYPE1 Dual-Control Gate

### 3.1 目的

验证 TYPE3 本地录音已经运行时，显式启动/停止 TYPE1 realtime audio 是否能够共存，且 realtime STOP 不会终止 TYPE3 本地录音。

### 3.2 QA Surface

Launcher：

`Voica Stage15 D1 Probe`

QA Activity：

`Stage157D1DualControlProbeActivity`

该页面只调用现有生产入口：

- `DeviceRepository.startRecording()`；
- `RealtimeAudioRuntime.start()`；
- `RealtimeAudioRuntime.stop()`；
- `DeviceRepository.saveRecording()`。

页面不会实现第二套 BLE owner、第二套 realtime runtime 或第二套音频 decoder。

为避免本 Gate 自己制造 canonical slow-consumer backpressure，页面会持续消费 canonical stream event，但只做丢弃式 QA drain，不参与媒体处理。

### 3.3 固定真机步骤

1. 安装当前 Stage 15 QA APK。
2. 打开正常 Voica，连接 QS668 / CB08，等待设备 Ready。
3. 返回桌面，打开 `Voica Stage15 D1 Probe`。
4. 确认页面显示 `连接 Ready：true`。
5. 点击 `重置D1观察`。
6. 点击 `刷新设备文件`。
7. 点击 `D1-1 TYPE3开始`。
8. 等页面提示 `D1-1 完成`。
9. 点击 `D1-2 realtime START`。
10. 等页面提示 realtime START 成功。
11. 正常讲话 **8–10 秒**；期间不要按录音卡物理按键，不要切换到其他 Stage 15 Probe。
12. 点击 `D1-3 realtime STOP`。
13. 等页面提示 `D1-3 完成`。
14. 此时 **不要立即保存**，先确认录音卡仍处于 TYPE3 录音状态；页面 Gate 的 `afterRealtimeStop` 不应为 Idle。
15. 点击 `D1-4 TYPE3保存`。
16. 等页面提示 `D1-4 完成：可复制 D1 Gate`。
17. 点击 `复制D1 Gate`，提交完整文本进行判定。

## 4. D1 PASS 条件

核心事实必须同时满足：

- `realtimeStartSuccess=true`；
- realtime 窗口内 `acceptedBlocks > 0`；
- realtime 窗口内 `canonicalFrames > 0`；
- realtime 窗口内 `canonicalSamples > 0`；
- `realtimeStopSuccess=true`；
- TYPE3 状态在 `TYPE3 start → realtime START → realtime STOP` 期间保持同一非 Idle 录音状态；
- realtime STOP 后 canonical source 已闭合，`lastEnd=STOP`；
- `drainTimedOut=false`；
- realtime 窗口内 ingress overflow / malformed / dropped / decode failure / source overflow 均为 0；
- 最终 TYPE3 SAVE 后设备文件数量 `fileCountDelta=1`。

最终 Gate：

```text
D1 factGate=PASS
```

## 5. D1 失败分类

### A. realtime START 失败

若 `realtimeStartSuccess=false`，优先检查 TYPE1 command transaction / device state，不修改 TYPE3 录音路径。

### B. START 成功但无媒体

若 START 成功，而 `acceptedBlocks/canonicalFrames/canonicalSamples` 没有增长，则进入 TYPE1 transport / command-fence / device concurrency 调查。

### C. realtime STOP 同时终止 TYPE3

若 `afterRealtimeStop=Idle` 或与 TYPE3 start 后状态不一致，则证明两控制面存在设备级耦合，需要把该真机事实冻结后再调整 Stage 15 lifecycle contract。

### D. realtime 媒体正常但有异常计数

若 overflow / malformed / dropped / decode failure 增长，则 D1 不通过，先定位 realtime pipeline 异常，不用文件落盘结果掩盖媒体异常。

### E. realtime 正常、TYPE3 仍录音，但保存不增文件

若前述事实正常而 `fileCountDelta != 1`，归入 TYPE3 SAVE / file refresh 基线问题单独处理。

## 6. 后续 D2

只有 D1 PASS 后再执行反向顺序 Gate：

`realtime START → TYPE3 START → TYPE3 SAVE → realtime STOP`

D2 用于确认 TYPE3 SAVE 不会反向终止正在运行的 TYPE1 realtime stream，从而完成双向独立性冻结。
