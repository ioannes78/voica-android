# Voica Stage 10 Test

状态：**用户真机验收通过**

日期：2026-10-02

## 1. 验收结论

Stage 10 — 转写时间轴 + 播放同步已完成 QA 真机验收。

用户最终明确反馈：

**“测试通过”**

因此 Stage 10 功能验收门已满足。

## 2. 最终 QA 基线

- implementation HEAD：`0b1e7d41a284a92af2dc3c39413014c31586235a`
- versionCode：26
- versionName：`0.10.0-stage10-alpha1`
- QA Application ID：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Room schema：3
- QA CI：#287 / `36989306411` — **success**
- QA artifact：`Voica-qa-apk`
- APK SHA-256：`92def5738a7f023f1f1a8e8a052d8c7a2331a46a646c6ad3c56bd60f14a23a7b`

CI #287 已确认：

- Unit test + debug/QA build：success
- stable QA signing identity：success
- committed Room schema v1/v2/v3 gate：success
- QA APK upload：success

## 3. 开发期 CI

- #284 / `36982144920` — Stage 10 core 修复后 success
- #285 / `36983346228` — Timeline/UI integration success
- #286 / `36983996153` — seek/lifecycle/virtual long timeline hardening success
- #287 / `36989306411` — final QA candidate success

## 4. 自动化覆盖

Stage 10 自动化至少覆盖：

- `[start,end)` row/token 边界
- transcript gap / totalSampleCount end
- FIRST_PASS/HQ SECOND_PASS timing source fallback
- punctuation finalText projection EXACT / HEURISTIC / UNAVAILABLE
- zero-duration/same-start token 防错误 cue
- speaker span stable identity
- canonical playback source mismatch reject
- transcription version switch remap
- pause/speed 使用 presented sample，不做 elapsed×speed 推算
- discontinuity 后立即 remap
- user-scroll follow suspend/resume
- transcript click load→seek→play / already-loaded seek→play
- virtual 30/60/120min timeline
- 120min virtual timeline 20Hz 共 144,000 次 position mapping
- random seek / beginning / middle / end mapping

## 5. 真机验收范围

用户已明确确认 Stage 10 QA 候选功能通过。

本轮验收目标包括：

- FAST/HQ 转写与自动 speaker 对齐后的播放同步
- 当前 speaker/span/segment 浅色高亮
- 可靠 timed token 深色高亮
- 标点后的显示文本与时间 cue 对齐
- 点击文字 seek + play
- rapid seek/discontinuity 后高亮重新映射
- pause/resume/倍速同步
- 手动滚动保护与显式“跟随播放”
- FAST/HQ/历史版本切换保持当前播放 sample
- speaker rename / overlap / ambiguous / unresolved 显示
- Stage 7 background/audio-focus/device-recording playback pause 语义不被 Stage 10 破坏

用户未逐项提供定量测量值，因此本文件不扩张为未执行的性能结论。

## 6. FAST token 时间语义

FAST 使用 Small Bilingual Zipformer 的 token + timestamps。

这些时间映射为 absolute canonical sample index：

- token 有 start sample
- end 可由后续 timed token start 或 owner segment end 推导
- token 不保证等同自然语言“词”
- Stage 10 只在 token→finalText projection 为 EXACT 时默认显示深色 token 高亮
- HEURISTIC / UNAVAILABLE 只保留 row/span 高亮

## 7. 未声明为已通过的专项

以下仍不是 Stage 10 已验证真机结论：

- 真实 30min / 1h / 2h 长录音 soak
- ADB peak PSS
- 长时间 CPU / thermal
- 2 小时持续播放+高亮+滚动综合资源曲线
- 极端超长转写的定量 Compose jank benchmark

这些继续进入 Stage 13。
