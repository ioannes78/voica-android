# Stage 16A.1.5 — Home & Notification UX Consolidation

状态：**APPROVED REQUIREMENTS V1**

日期：2026-10-16

## 1. 阶段定位

Stage 16A.1.5 插入在 Stage 16A.1 与 Stage 16A.2 之间，负责主页信息架构、App 全局状态/提醒与 Android 通知体系的收口。底层业务状态继续以现有 runtime、Room、coordinator、durable journal 为事实来源；本阶段新增代码只能读取、规范化并投影这些事实。

阶段顺序：

`Stage 16A → Stage 16A.1 → Stage 16A.1.5 → Stage 16A.2 → Stage 16B → Stage 17 → Stage 18`

## 2. 已核验开发基线

开始开发时重新核验：

- main HEAD：`cd984fc7910092777190547b0e584dc74a260f32`
- open PR：0
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13，migration lineage 保持不变
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- Stage 16A.1 S15：`NOT RUN / WAIVED BY USER`，不得改写为 PASS

这些值只记录阶段起点；后续每个关键 gate 仍以 GitHub 当前事实重新核验。

## 3. Homepage 需求

主页从“功能目录”收口为“当前正在发生什么 + 高频启动操作”。

- idle 时不并列展示手机录音、录音卡录音、手机实时转写、录音卡实时转写四张同权大卡；
- 一级动作收口为 `录音` 与 `实时转写`，音源 `录音卡 | 手机` 作为属性；
- active 后只允许一个 Primary Activity 大卡；若手机与录音卡同时 active，另一项用紧凑 secondary row/chip 表达；
- active 卡直接显示 elapsed/source/paused/interrupted 等必要状态；
- 手机录音在主页可直接 Pause/Resume/Stop；这些操作仅调用现有 controller/runtime，不新增业务 lifecycle；
- Mini Player 只服务 playback；PLAYING 显示、PAUSED 保留、STOP/unload 隐藏；
- background tasks 与 durable attention 只做紧凑摘要，不与 Primary Activity 抢层级。

## 4. Unified State Projection

新增统一投影层，但不得成为业务事实来源。

建议生命周期投影：

- `ACTIVE_CONTROLLABLE`
- `BACKGROUND_RUNNING`
- `COMPLETED_ATTENTION`
- `FAILED_ATTENTION`
- `INTERRUPTED_ATTENTION`
- `PASSIVE`

建议 activity/task kind：

- recording
- realtime transcription
- playback
- canonical generation
- offline transcription
- diarization
- AI summary
- model install

未来 cloud file ASR / cloud realtime ASR 可复用此投影 seam。

## 5. 通知部分硬边界

通知体系优化只允许修改：

1. Android 系统通知的 presentation；
2. App 内全局状态/提醒；
3. App 内 Global Bar / Attention Bar / notification projection glue。

通知层只能执行：

`现有业务 truth → Notification/App Global Projection`

严禁反向让通知状态控制业务状态。

### 5.1 明确禁止借通知修改

- 录音功能与 lifecycle；
- 手机/录音卡 Pause/Resume/Stop 业务逻辑；
- realtime ASR lifecycle；
- 离线转写与重新转写业务逻辑；
- diarization 与重新 diarization 业务逻辑；
- AI Summary 与重新生成业务逻辑；
- canonical WAV 生成逻辑；
- playback lifecycle；
- model install lifecycle；
- WorkManager ownership；
- FGS acquire/release ownership；
- WakeLock；
- AudioRecord ownership；
- BLE / ASR / diarization / AI pipeline；
- Room schema；
- 现有结果版本机制。

若某通知体验必须修改上述业务层才能实现，停止该项实现并采用不修改业务的降级方案；未经用户再次确认不得越界。

## 6. 通知表现契约

统一三层：

- 当前任务页面：最完整状态与操作；
- App 全局状态：离开 exact task/result 页面后才显示；
- Android 通知：FGS/后台执行持续状态及必要的完成、失败、中断提醒。

通用规则：

- running notification 原位更新，不因 progress 创建新通知；
- exact 页面已完整显示状态时，App global 不重复；
- 成功且用户正看 exact result 时，不额外产生 completion attention/system notification；
- 成功但用户不在 exact result 时，可产生一次 unseen completion；
- 点击通知或从 App 主动进入 exact result 并实际看到结果后，清除对应 completion attention/system notification；
- swipe system notification 只表示系统通知被 dismiss，不等于结果已查看；App 内 unseen attention 可继续存在；
- 同一 terminal generation 被 swipe 后不得因 Flow/restart 立即重新推送；只有新的 generation/result/material state change 才允许再通知；
- failure 与 interrupted 分开表达；
- task cancel 默认只结束 running presentation，不额外发“取消完成”；
- 删除 recording/result 时必须主动清理其 presentation notification/attention。

## 7. 第一次与重新执行的通知文案

若现有业务数据可以可靠区分第一次与重新执行：

- 第一次转写：`转写完成`
- 重新转写：`新转写版本已生成`
- 第一次说话人分离：`说话人分离完成`
- 重新说话人分离：`新的说话人结果已生成`
- 第一次 AI 总结：`AI 总结已完成`
- 重新 AI 总结：`新总结版本已生成`

重新执行失败时，若现有业务事实明确旧结果仍有效，可表达“原结果仍保留”。

若当前业务层无法可靠判断第一次/重新执行，本阶段不得为通知新增业务字段、Room schema 或版本机制；使用通用文案并记录限制。

## 8. Recording / Realtime 通知

- recording/realtime 从 active 到 stop 期间维持现有 FGS notification；
- pause 时 notification 保留并更新为 paused；
- stop/finalize 后按现有 Service lifecycle 主动清除 running notification；
- 用户主动正常 Stop 默认不额外产生“录音完成”通知；
- realtime session 只显示一个前台持续通知，不拆成“录音中 + ASR 中”两条；
- 若 realtime ASR failure 但 formal recording 仍正常，notification 必须表达“录音继续，实时转写中断”，不得误报“录音失败”；
- POST_NOTIFICATIONS 被拒绝不得改变 recording/task 业务 truth。

## 9. Notification Channel / FGS 兼容

现有 channel ID 默认保持，集中登记但不为“统一”而删除或重建用户已有 channel：

- `voica-device-session`
- `voica-phone-microphone`
- `voica-playback`
- `voica-media-processing`
- `voica-ai-summary`
- `model-install`
- `voica-task-result`
- `voica-task-attention`
- `voica-diarization-attention`

不得为通知整理合并不同 Android FGS type 的 execution Service。

## 10. Deep Link 边界

只允许复用/最小封装现有导航能力，让 notification 打开已有 exact page。不得为了通知大规模重构全 App Navigation、创建新业务页面或改变结果版本选择逻辑。

## 11. 数据/模型边界

- Room 默认保持 v13；
- notification dismiss/re-notify presentation state 如需持久化，优先 SharedPreferences/DataStore，不引入 Room v14；
- production model channel 不修改；
- Stage 16A/16A.1 audio/ASR frozen contract 不修改。

## 12. QA 原则

自动化完成、CI PASS 后才进入真机；CI PASS 不等于真机 PASS。真机严格一次一项。Stage 16A.1 S15 继续保持 `NOT RUN / WAIVED BY USER`，除非本阶段经再次确认实际修改了 microphone FGS ownership/WakeLock/capture ownership 等受保护边界。
