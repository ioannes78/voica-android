# Stage 18 Cloud Realtime ASR Contract V1

状态：**PLANNED / APPROVED FOR FUTURE STAGE 18 DEVELOPMENT**

本文件定义 Voica Stage 18 云端实时 ASR 的网络、buffering、heartbeat、reconnect 与 lifecycle 边界。

Stage 18 必须建立在 Stage 15 canonical realtime audio 与 Stage 16A streaming audio shaping contract 之上，不得重新从 BLE/GATT 读取音频。

## 1. 权威输入

Stage 18 的媒体输入为：

`RealtimeCanonicalStreamEvent`

经统一媒体整形层：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler`

Stage 18 不直接感知：

- BluetoothGatt
- BLE UUID
- MTU
- Opus packet
- protocol sequence

canonical sample index 继续是唯一媒体时间真值。

## 2. Cloud realtime 主链

正式链路：

`RealtimeCanonicalAudioSource → StreamingAudioBuffer → StreamingChunkAssembler → ChunkScheduler → NetworkSendQueue → CloudRealtimeAsrProvider → WebSocket → normalized PARTIAL/STABLE/FINAL`

其中：

- `StreamingAudioBuffer`：媒体缓冲；
- `StreamingChunkAssembler`：组装 Provider 所需 PCM chunk；
- `ChunkScheduler`：发送节奏、flush、provider cadence；
- `NetworkSendQueue`：网络 transport 背压隔离；
- `CloudStreamingSession`：socket / heartbeat / reconnect / provider session owner。

这些职责不得混为一个无界队列或一个巨型 provider 类。

## 3. ChunkScheduler

职责：

- 按 Provider capability/config 决定推荐 chunk duration；
- 吸收 BLE/Android producer burst；
- 形成相对稳定的网络发送 cadence；
- 控制 websocket write backlog；
- 处理 final flush；
- provider rate limit / min-max chunk policy。

Stage 15 canonical frame 大小不得因某个 Provider 改变。

Provider 可声明例如 80/100/160/200 ms 等发送粒度，最终值必须通过官方能力、实测延迟和稳定性验证。

## 4. NetworkSendQueue

必须 bounded。

网络慢、socket blocked 或 provider throttling 不得一路反压到：

`ChunkAssembler → CanonicalAudioSource → Opus decoder → BLE callback`

达到 hard limit 时必须执行明确策略：

- provider/session支持连续恢复时按 capability 处理；或
- explicit cloud discontinuity；
- drop stale network/live-media backlog并从 live edge 恢复。

禁止无限内存增长。

## 5. CloudStreamingSession 状态机

建议正式状态：

- `IDLE`
- `CONNECTING`
- `CONNECTED`
- `STREAMING`
- `FINALIZING`
- `DISCONNECTED`
- `RECONNECT_WAIT`
- `CLOSED`
- `ERROR`

异常主路径：

`CONNECTED/STREAMING → SOCKET_ERROR | SOCKET_DONE | HEARTBEAT_TIMEOUT → DISCONNECTED → RECONNECT_WAIT → CONNECTING`

Provider不支持 reconnect/resume 时必须建立新的 cloud logical session，不能假装旧 socket 仍连续。

## 6. 三个 generation / identity domain

Stage 18 必须明确区分：

1. Stage 15 BLE `transportGeneration`
2. Stage 15 canonical `logicalSessionId / epochId`
3. Stage 18 `socketGeneration / cloudSessionId`

示例：

`BLE generation=8` 可以对应 `canonical session=12`，期间 WebSocket 可能从 `socketGeneration=31` 重连到 `32`。

WebSocket 重连不得改变 Stage 15 canonical timeline。

## 7. Socket generation fence

每次创建 WebSocket：

`socketGeneration++`

以下所有 callback 必须携带/捕获 generation 并校验当前性：

- onOpen
- onMessage
- onFailure
- onClosed/onDone
- ping/pong或application heartbeat response
- async write completion（如 runtime 存在）

旧 socket callback 必须拒绝，禁止 stale `onFailure` 关闭已经建立的新 socket。

## 8. HeartbeatController

Heartbeat由 Provider capability 驱动，至少支持：

- WebSocket Ping/Pong
- Provider application-level heartbeat
- NONE

不得对所有 Provider 无脑发送统一 JSON heartbeat。

建议 diagnostics：

- lastTxAt
- lastRxAt
- lastHeartbeatTxAt
- lastHeartbeatAckAt
- heartbeatTimeoutCount
- socketGeneration
- reconnectCount

Heartbeat timeout 进入统一 disconnect/reconnect state machine。

## 9. closeSocketOnly 与 closeSession 分离

### closeSocketOnly()

只释放：

- WebSocket transport
- heartbeat
- socket writer / network send owner

根据 provider capability 可保留：

- cloud logical session intent
- reconnect state
- bounded reconnect PCM buffer
- transcript partial/stable state

### closeSession()

彻底释放：

- WebSocket
- heartbeat
- reconnect job/backoff
- StreamingAudioBuffer
- ChunkScheduler
- NetworkSendQueue
- provider session
- pending finalization
- cloud transcript runtime state

禁止用一个含糊的 `close()` 同时承担两种语义。

## 10. Reconnect PCM 策略

Provider adapter 必须显式声明 reconnect capability，至少映射到以下三种策略之一。

### 10.1 RESUMABLE

短时 socket 中断：

`socket down → canonical PCM继续 → bounded reconnect PCM buffer → socket/session resume → 补发`

只有 Provider 协议明确允许同一 session resume 时使用。

### 10.2 NEW_SESSION_WITH_BUFFER

Provider不支持原 session resume：

`socket down → finalize/abandon old cloud session → bounded PCM buffer → new socket → new cloud session → feed retained live-edge PCM`

必须在 transcript lineage 标记 cloud session boundary。

### 10.3 LIVE_EDGE_ONLY

断网时间过长、buffer overflow或 Provider无法安全补发：

`explicit CloudStreamingDiscontinuity → drop stale PCM → reconnect → resume from live edge`

原则：宁可显式 discontinuity，也不能伪造连续媒体时间。

## 11. Reconnect buffer

Stage 18 必须有 bounded reconnect media buffer。

初始 duration/byte上限由 Stage 18 开发时 benchmark 决定，不在本规划永久冻结具体秒数；建议评估 2–5 秒量级作为起点。

达到上限时不得继续增长，必须进入 explicit discontinuity/live-edge policy。

## 12. Provider capability contract

Realtime Provider capability至少描述：

- authentication / region / endpoint
- model list / selected model
- accepted PCM format/sample rate/channel
- recommended chunk duration
- max/min chunk constraints
- heartbeat mode / interval / timeout
- session resume support
- reconnect semantics
- server finalization semantics
- timestamp capability
- language/hotword capability
- billing/cost metadata（可取得时）

Text LLM、File ASR、Realtime ASR、Audio LLM继续为独立 capability adapter；可以共享 credential/profile，但不得合并成模糊单接口。

## 13. PARTIAL / STABLE / FINAL normalization

不同 Provider 返回格式必须归一到 Voica streaming transcript contract。

必须明确：

- mutable interim → PARTIAL；
- provider-native或Voica stabilizer确认稳定 → STABLE；
- utterance/session final → FINAL。

不得把 provider 临时文本持久化为 FINAL。

## 14. Canonical lifecycle 与 cloud lifecycle

Stage 15：

- SessionStarted
- EpochStarted
- PcmFrame
- ConsumerDiscontinuity
- SessionEnded

Stage 18 必须消费这些事件：

- canonical new session → new cloud ASR logical input session；
- epoch/discontinuity → provider-specific finalize/reset/new cloud epoch/session；
- canonical SessionEnded → flush scheduler + provider finalization；
- cloud socket reconnect不得反向修改 canonical session/epoch。

## 15. Privacy / user consent

用户必须明确知晓 realtime audio 会发送给第三方 Provider。

开始云端实时 ASR 前至少显示/明确：

- Provider
- Model
- 音频将实时上传
- 可能的网络/API成本（可取得时）

禁止：

- 本地 ASR 失败后静默切换云端；
- 未经用户明确选择自动上传实时音频；
- 把 API key 写入 Room 内容 lineage。

## 16. Diagnostics

至少记录：

- canonical input samples/duration
- assembled chunks
- scheduler queue depth/peak
- network queue depth/peak
- sent bytes/chunks
- socket generation
- reconnect count/backoff
- heartbeat tx/ack/timeout
- provider PARTIAL/STABLE/FINAL count
- provider latency
- dropped media/chunks
- discontinuity count/reason
- estimated API cost（可取得时）

不得高频写 Room。

## 17. Failure / fallback policy

必须区分：

- BLE transport close
- canonical discontinuity
- local network unavailable
- DNS/TLS/socket failure
- authentication failure
- provider rate limit
- provider server error
- heartbeat timeout
- protocol parse error

Fallback必须显式、用户可理解。

不得因为 cloud realtime 失败自动改成上传完整录音文件，除非用户明确选择 Stage 17 文件 ASR 流程。

## 18. Stage 18 验收矩阵

至少覆盖：

- normal realtime streaming
- short scheduler jitter/burst
- slow network
- NetworkSendQueue overflow
- heartbeat timeout
- socket failure + reconnect
- stale old socket callback
- Provider resumable path
- Provider new-session path
- reconnect buffer overflow → live edge
- canonical Pause/Resume epoch
- BLE reconnect/new canonical session
- final flush/SessionEnded
- background/screen-off
- 30/60/120 分钟稳定性
- network/API cost diagnostics

## 19. 禁止项

Stage 18 不得：

- 直接连接 BLE callback 与 WebSocket send；
- 让 WebSocket backpressure 阻塞 BLE/decoder；
- 无限缓存 PCM或network messages；
- 使用 wall clock伪造 canonical media continuity；
- 让 socket generation 等同 BLE generation；
- 静默跨 Provider/session discontinuity；
- 未经用户同意上传音频。

## 20. Authority

Stage 18 当前权威规划契约：**本文件 V1**。

Stage 16A 媒体整形基础：`docs/STAGE_16_STREAMING_CONTRACT_V3.md`。

Stage 15 canonical source/lifecycle 由 Stage 15 Final Freeze/Handoff（未来完成）最终确认。
