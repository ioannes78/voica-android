# Stage 16A.2 — Timeline Annotation 规划

状态：**APPROVED PRODUCT ROADMAP / NOT IN STAGE 16A CORE IMPLEMENTATION**

日期：2026-10-12

## 1. 定位

Stage 16A.2 在 Stage 16A.1 之后执行，正式定义为：

**Recording Timeline Annotation / 录音时间轴标注系统**

目标不是照搬官方 App 的简单“笔记1/笔记2”，而是建立稳定的、与 Recording 媒体时间轴绑定的标记/笔记/照片体系。

## 2. 首版能力

至少包括：

- 快速标记；
- 文字笔记；
- 拍照；
- 从相册添加；
- 查看/编辑/删除 Annotation；
- 点击 Annotation 跳转对应音频位置；
- 与对应转写位置联动。

其中“一键快速标记”为高优先级：录音过程中无需输入文字即可立即记录当前媒体位置，录音后再补充说明。

## 3. 数据归属

Annotation 必须属于 Recording / media timeline，不属于某一个具体 TranscriptVersion。

逻辑关系：

```text
Recording
 ├─ Audio
 ├─ Realtime Transcript
 ├─ File Transcription versions
 ├─ AI Summary versions
 └─ Annotations
     ├─ Marker
     ├─ Note
     └─ Photo
```

重新运行 Qwen3 / SenseVoice、修改人工转写、切换转写版本都不得导致 Annotation 丢失或错位。

## 4. 时间锚点

禁止以“第几个字符 / 第几段文本”为主要持久化锚点。

连续 Recording 应绑定可靠 media time / canonical sample identity。

对于 BLE 断联导致的 part01 / part02，必须使用 recording identity + segment identity + segment-local media position，不能跨断联缺失区间伪造一个连续 sample timeline。

## 5. Realtime UI 入口

Stage 16A 核心底部只保留 Pause/Resume + Stop。

Stage 16A.2 再增加轻量 `+` 入口：

```text
[ + ]      [ 暂停/继续 ]      [ 停止 ]
```

`+` 弹出：

- 快速标记
- 文字笔记
- 拍照
- 从相册添加

避免把多个长期功能图标平铺到底栏导致拥挤。

## 6. 正文呈现

Annotation 不应以大卡片持续打断实时正文。

建议使用轻量 timeline marker，例如：

`──── 03:42  🔖 1  📷 1 ────`

点击后展开笔记、照片和编辑/删除操作。

## 7. 录音详情

录音详情后续建议增加：

`转写 | AI总结 | 标注`

“标注”页按媒体时间排序展示 Marker / Note / Photo；点击任意项跳到对应音频位置，并尽量定位到当前有效转写位置。

## 8. 图片边界

Stage 16A.2 首版只负责：

- camera capture；
- gallery attach；
- local attachment lifecycle；
- thumbnail；
- timeline association；
- view/delete。

暂不实现：

- OCR；
- 图片 AI 理解；
- 自动写入 transcript；
- 图片参与 AI 总结。

这些能力留给后续 AI 阶段。

## 9. 相机与 realtime lifecycle

录音过程中打开系统相机不得因 Activity 跳转停止录音卡 realtime ASR。

Stage 16A.1 手机麦克风模式 + camera 组合必须单独真机 QA，因为可能涉及 microphone ownership / foreground service / camera lifecycle 冲突。

## 10. Room 原则

Stage 16A.2 不在 Stage 16A 预建未使用 Annotation 表。

进入 Stage 16A.2 时基于届时实际 Room schema 重新设计 migration。

建议 Annotation domain 至少包含：

- annotationId；
- recordingId；
- segmentId nullable；
- media sample/time anchor；
- type；
- note text；
- created/updated time；
- attachment relationship。

具体表结构、索引、cascade 行为必须在 Stage 16A.2 修订需求与开发规划确认后冻结。

## 11. 后续 AI 扩展

未来允许形成：

`录音 + 转写 + 用户笔记 + 标记 + 图片 → AI 会议纪要`

但 Stage 16A.2 不提前实现该 AI 管线。
