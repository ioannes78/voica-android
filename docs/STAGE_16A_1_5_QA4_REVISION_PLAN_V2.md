# Stage 16A.1.5 QA4 修订规划 V2

状态：**COMPLETED / ALL QA4 DEVICE GATES PASS / SUPERSEDED BY FINAL FREEZE**

日期：2026-10-16

最终权威结果：

- `docs/STAGE_16A_1_5_FREEZE.md`
- `docs/STAGE_16A_1_5_HANDOFF.md`

## 1. 目标

本轮只做 Stage 16A.1.5 presentation 收口，不改变 recording / realtime / ASR / BLE / Audio I/O / FGS / Room 等业务生命周期。

已完成：

1. realtime detail 增加醒目的运行时间；
2. realtime detail 移除正式 UI 中的 Sx-A / Sx-B 延迟 QA 控件，但不删除底层调试能力；
3. Home realtime card 在来源/设备名称旁显示 realtime ASR 模型；准备态显示设置模型，运行态显示实际运行模型；
4. 补齐手机录音、手机 realtime 的 App 内全局 live strip；
5. recorder realtime 的全局语义从“正在录音”修正为“实时转写中”；
6. Home 和 exact realtime detail 对当前已明确展示的 activity 做精确 suppression，离开后重新显示全局 strip。

## 2. Realtime Detail — COMPLETE

录音卡与手机 realtime 已统一信息层级：

- 标题：`实时转写`；
- 来源 + 实际运行模型：`QS668 / CB08 · <model>` / `手机 · <model>`；
- 明显居中的 `HH:mm:ss`；
- 现有真实 PCM/audio-activity waveform，不改映射；
- 实时文字 reader；
- 底部正式产品控制：暂停/继续 + 停止；
- 正式 UI 不再显示 Sx-A / Sx-B 延迟按钮。

计时读取既有录音/session truth，没有创建第二个计时 owner。

## 3. Home realtime model label — COMPLETE

准备态：

- `QS668 / CB08 · <configured realtime model>`
- `手机 · <configured realtime model>`

运行态使用 runtime 已解析的实际 `modelLabel`；实际运行模型优先于后来变化的设置值；不硬编码 Small Bilingual。

## 4. App 内全局 live strip — COMPLETE

统一产品语义：

- recorder recording：`正在录音 · QS668 / CB08 · HH:mm:ss    查看`
- phone recording：`正在录音 · 手机 · HH:mm:ss    查看`
- recorder realtime：`实时转写中 · QS668 / CB08 · HH:mm:ss    查看`
- phone realtime：`实时转写中 · 手机 · HH:mm:ss    查看`

暂停：

- recording：`录音已暂停 · <source> · HH:mm:ss`
- realtime：`实时转写已暂停 · <source> · HH:mm:ss`

Realtime presentation 优先于 backing recording truth。

## 5. Exact-page / Home suppression — COMPLETE

- Home 已显示对应 activity 时，不重复显示同一 activity 的 global strip；
- 正在查看对应 realtime detail 时，不重复显示该 realtime global strip；
- 离开后 active strip 恢复；
- suppression 按 source/activity identity 处理；
- ordinary recording 的“查看”返回 Home active card；
- realtime 的“查看”返回对应 live realtime reader；
- stop/finalize 后 active strip 退出，不残留。

防御性的 multi-activity projection 保留，但正常 Home 产品 UI 在已有 primary active activity 时不会继续提供第二个 idle Start 入口。

## 6. Hard boundary — PRESERVED

本轮未修改：

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
- Android system-notification business lifecycle；
- production model channel。

Stage 16A.1.6 Android Audio I/O Compatibility：`RESERVED / NOT STARTED`。

## 7. QA4 真机 Gate — FINAL

```text
QA4-1  recorder realtime detail                           PASS
QA4-2  phone realtime detail                              PASS
QA4-3  Home recorder realtime configured/actual model     PASS
QA4-4  Home phone realtime configured/actual model        PASS
QA4-5  phone ordinary recording global strip              PASS
QA4-6  recorder ordinary recording global strip           PASS
QA4-7  phone realtime strip / View / suppression          PASS
QA4-8  recorder realtime strip / View / suppression       PASS
QA4-9  pause/resume wording + elapsed                     PASS
QA4-10 stop cleanup + next-task switching                 PASS
```

QA4-10 原测试计划曾要求在 recorder realtime 已 active 时，再从正常 Home UI 启动 phone ordinary recording。源码核验确认 active primary card 会替代 idle Start controls，该路径不可达；这不是 App 缺陷。QA4-10 已改为产品实际可达的 `stop cleanup → next-task switching` 流程并真机 PASS。

因此 QA4 V2 已完成，不再处于 implementation / QA pending 状态。
