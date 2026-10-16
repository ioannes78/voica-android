# Stage 16A.1.5 QA4 修订规划 V2

状态：**APPROVED / IMPLEMENTATION IN PROGRESS**

日期：2026-10-16

## 1. 目标

本轮只做 Stage 16A.1.5 presentation 收口，不改变 recording / realtime / ASR / BLE / Audio I/O / FGS / Room 等业务生命周期。

修订项：

1. realtime detail 增加醒目的运行时间；
2. realtime detail 移除正式 UI 中的 Sx-A / Sx-B 延迟 QA 控件，但不删除底层调试能力；
3. Home realtime card 在来源/设备名称旁显示 realtime ASR 模型；准备态显示设置模型，运行态显示实际运行模型；
4. 补齐手机录音、手机 realtime 的 App 内全局 live strip；
5. recorder realtime 的全局语义从“正在录音”修正为“实时转写中”；
6. Home 和 exact realtime detail 对当前已明确展示的 activity 做精确 suppression，离开后重新显示全局 strip。

## 2. Realtime Detail

录音卡与手机 realtime 使用统一信息层级：

- 标题：`实时转写`；
- 来源 + 实际运行模型：`QS668 / CB08 · <model>` / `手机 · <model>`；
- 明显居中的 `HH:mm:ss`；
- 现有真实 PCM/audio-activity waveform，不改映射；
- 实时文字 reader；
- 底部只保留正式产品控制：暂停/继续 + 停止；
- 移除 Sx-A / Sx-B 延迟按钮。

计时必须读取既有录音/session truth，不创建第二个计时 owner。

## 3. Home realtime model label

准备态：

- `QS668 / CB08 · <configured realtime model>`
- `手机 · <configured realtime model>`

运行态：

- 使用 runtime 已解析的实际 `modelLabel`；
- 实际运行模型优先于后来变化的设置值；
- 不硬编码 Small Bilingual；
- 文案允许两行/ellipsis，不挤压 Gain、计时和控制按钮。

## 4. App 内全局 live strip

统一产品语义：

- recorder recording：`正在录音 · QS668 / CB08 · HH:mm:ss    查看`
- phone recording：`正在录音 · 手机 · HH:mm:ss    查看`
- recorder realtime：`实时转写中 · QS668 / CB08 · HH:mm:ss    查看`
- phone realtime：`实时转写中 · 手机 · HH:mm:ss    查看`

暂停：

- recording：`录音已暂停 · <source> · HH:mm:ss`
- realtime：`实时转写已暂停 · <source> · HH:mm:ss`

Realtime presentation 优先于其底层 recording truth，避免 recorder realtime 被错误显示成普通录音。

## 5. Exact-page / Home suppression

- Home 已显示对应 Primary/Secondary activity 时，不重复显示同一 activity 的 global strip；
- 正在查看对应 realtime detail 时，不重复显示该 realtime global strip；
- 离开 Home / exact realtime detail 后，active strip 恢复；
- suppression 必须按 source/activity identity 精确处理，另一并发 source 不得被误隐藏；
- ordinary recording 的“查看”返回 Home active card；
- realtime 的“查看”返回对应 live realtime reader；
- stop/finalize 后 active strip 退出，不残留。

## 6. Hard boundary

本轮不得修改：

- BLE protocol / transport；
- QS668 command semantics；
- AudioRecord source / route；
- AudioTrack route / recovery；
- streaming canonical audio contract；
- ASR inference / VAD / punctuation；
- diarization；
- model-install execution；
- WorkManager / FGS / WakeLock ownership；
- Room business schema（保持 v13）；
- canonical generation；
- Android system-notification business lifecycle。

Stage 16A.1.6 Android Audio I/O Compatibility 尚未开始。

## 7. QA4 真机 Gate

严格一次一项：

- QA4-1 recorder realtime detail：时间、设备+实际模型、无 Sx、无重复 global strip；
- QA4-2 phone realtime detail：时间、手机+实际模型、无 Sx；
- QA4-3 Home recorder realtime：准备态设置模型 / 运行态实际模型；
- QA4-4 Home phone realtime：准备态设置模型 / 运行态实际模型；
- QA4-5 phone ordinary recording：Home 不重复，离开后出现 global strip，“查看”返回 Home；
- QA4-6 recorder ordinary recording：同上；
- QA4-7 phone realtime：Home/detail 不重复，离开后显示 realtime strip，“查看”返回正确 session；
- QA4-8 recorder realtime：同上，且不再错误显示“正在录音”；
- QA4-9 pause/resume：暂停文案与 elapsed 正确；
- QA4-10 stop / multi-activity：无残留、无串线、无误 suppression。

全部 PASS 后才进入 Stage 16A.1.5 Final Freeze / Handoff。
