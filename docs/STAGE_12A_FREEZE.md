# Voica Stage 12A Freeze

状态：**FROZEN / 用户真机验收通过**

日期：2026-10-03

## 1. Freeze 结论

Stage 12A — UI / UX 完整化完成并冻结。

用户最终明确确认：

**“测试通过，UI/UX 先这样，后续全部功能完成后再精细优化。”**

Stage 12A 实现 QA HEAD：

`ad192aa74daf6383ac8205c33b9710eca6a336ba`

真机验收记录提交：

`ea8c1b8644a1d16ebd088ccedbc75a80925789ff`

最终 QA：

- versionCode：36
- versionName：`0.12.0-stage12a-uxv3-qa1`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：4（Stage 12A 未改数据库）
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- QA CI：`37096676732` — success
- QA APK SHA-256：`9a0e3c995f1292dbc9f0e32fabccea730f1dff693f70a147ed4cf2b357fd3cb0`

## 2. UI / UX 基线冻结

Stage 12A 冻结为高频效率工具型 UI：

- 一级导航固定为：设备 / 录音库 / 设置
- 二级页面隐藏底部一级导航
- 页面主任务优先，低频动作使用 overflow / dialog / bottom sheet / 二级页
- 避免大面积永久 Card、重复状态和长期占屏的低频控件
- 主要触控目标保持可用性，不通过极小字体换取信息密度

字体基线：

- 系统 Sans Serif
- 页面标题约 24sp
- 正文约 15sp
- 辅助信息约 13sp
- 大录音计时约 36sp

## 3. 视觉主题冻结

默认品牌 UI 使用 Voica Mint。

深色核心基线：

- Background：`#10171B`
- Primary：`#58D6B5`

浅色核心基线：

- Background：`#F6F8F8`
- Primary：`#087D67`

录音/停止/危险操作使用独立 error/red 语义，不用品牌青绿替代。

Launcher 图标继续使用已确认的 Voica 双带状 V 参考图作为品牌视觉源；Android themed icon 使用对应单色派生资源。

后续仅在全部核心功能完成后统一做最终视觉精修，不在 Stage 12B/12C/12D/12E 中反复重做全局视觉体系。

## 4. 设备页冻结

设备首页聚焦：

- 当前连接状态
- 扫描 / 停止扫描 / 断开连接
- 录音控制
- 设备录音入口

设备状态、扫描状态和已连接设备摘要使用统一设备状态卡。

扫描结果：

- QS668 / CB08 / AE20 兼容候选优先
- 普通 BLE 设备进入“其它蓝牙设备”
- 单项最多两行
- 点击整行连接
- 不保留单独“连接”按钮

刷新设备信息、同步时间、设备诊断等低频动作进入 overflow。

## 5. 录音控制冻结

录音卡只保留高频控制：

- Idle：准备录音 / 计时 / 开始录音
- Recording：正在录音 / 计时 / filename + current size / 暂停 / 停止
- Paused：录音已暂停 / 计时 / filename + current size / 继续 / 停止

filename / current size 只在 Recording / Paused 会话中显示。

设备录音时，本地播放继续服从 Stage 7 Audio Focus / recording priority 互锁。

## 6. BLE Diagnostics 冻结

完整 BLE Diagnostics 不删除，只从设备首页移到二级页：

`设备 → ⋮ → 设备诊断`

诊断页使用可折叠 Section，保留：

- 连接与 GATT
- MTU / 36B atomic / 168B data
- AE20 / AE21 / AE22 / AE23
- CCCD / subscriptions
- queue / reconnect
- RX/TX / CRC / invalid length
- 录音 raw/decoded state
- filename / gain / latency
- 设备文件列表 diagnostics
- file transfer diagnostics
- remote delete verification
- range probe
- last error / recent logs

支持“复制完整诊断信息”。

## 7. 设备录音管理冻结

设备录音使用高密度两行列表。

单文件点击进入 Bottom Sheet 执行：

- OPUS 下载
- WAV 下载
- range probe
- 设备端删除

设备录音支持：

- 长按进入多选
- 菜单进入选择
- 全选 / 取消全选
- 批量删除

批量删除必须顺序执行：

`Delete one → refresh → verify identity removed → next`

不得并发发送 destructive BLE delete。

若出现 OutcomeUnknown：

- 立即停止后续批删
- 刷新设备列表
- 不自动重发无法确认的删除

设备端删除继续与手机本地 Recording 生命周期独立。

## 8. Recording Detail / 播放冻结

Recording Detail 固定结构：

- 固定 Top header
- 固定 3 Tab：播放 / 转写 / AI 总结
- 只有正文内容区域滚动
- 录音信息、重命名、删除进入 overflow / 二级信息页

完整播放器：

- 播放 / 暂停
- 主进度 Slider
- -10s / +10s
- 六档离散倍速 Slider：
  - 0.5×
  - 0.75×
  - 1.0×
  - 1.25×
  - 1.5×
  - 2.0×

倍速继续遵守 Stage 7 已冻结 playback engine 能力，不引入任意连续倍速。

## 9. Transcript / Summary Mini Player 冻结

转写与 AI 总结页在 canonical audio 可用且设备未录音时显示 Mini Player：

- play / pause
- current time / total time
- seek slider
- 进入完整播放器

Mini Player 与完整播放器继续消费同一 PlaybackViewModel / PlaybackSnapshot。

Transcript 点击和 Summary evidence seek 继续使用 Stage 10 absolute canonical PCM sample index：

`seekToSample()`

不得建立第二套毫秒时间轴。

播放 snapshot 的高频 tick 应隔离在 playback composable 中，避免驱动整页长转写重组。

## 10. 全局状态与反馈冻结

全局 Recording 状态条：

- 设备首页不重复显示
- 离开设备首页后显示 Recording / Paused 状态
- 点击返回设备首页

成功类瞬时反馈优先使用 Snackbar，不长期占页面。

需要用户处理的错误仍应保留足够上下文，不得用短 Snackbar 隐去重要错误。

## 11. 设置 / Provider 冻结

设置首页使用目录式结构：

- AI 智能
- 本地模型
- 主题与显示
- 高级设置
- 关于 Voica

Provider 使用列表 → 详情编辑模式。

Provider 表单有未保存修改时返回必须确认：

- 继续编辑
- 放弃修改

API Key 仍遵守 Stage 11 Keystore + AES-GCM 安全边界。

## 12. 数据与架构边界

Stage 12A：

- Room 保持 v4
- 不引入 Stage 12B 的收藏/文件夹/标签/本地库 schema
- 不改变 Recording / AudioAsset / AudioDerivation 生命周期
- 不改变 Transcription version 语义
- 不改变 Stage 9 speaker identity
- 不改变 Stage 10 sample timeline
- 不改变 Stage 11 Summary/evidence lineage
- 不实现 Stage 13 Foreground Service / 后台下载

## 13. 真机证据

见：

- `docs/STAGE_12A_TEST.md`

用户已明确确认 UX/UI V3 真机测试通过。

## 14. 后续 UI 优化边界

用户明确要求：

**当前 UI/UX 先冻结，等全部功能完成后再做精细优化。**

因此 Stage 12B 及后续功能开发：

- 应复用当前视觉与交互基线
- 可为新增功能增加必要界面
- 不应无功能必要反复进行全局 redesign
- 最终功能齐备后再统一做视觉、动效、细节与可访问性精修

## 15. 下一阶段

下一子阶段：

**Stage 12B — 本地录音库增强**

Stage 12B 将在 Room v4 基线上设计显式 migration，并实现本地录音库搜索/筛选/文件夹/标签/收藏/多选/批量操作/导入导出/存储管理等能力。
