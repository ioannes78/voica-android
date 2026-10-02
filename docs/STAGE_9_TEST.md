# Voica Stage 9 Test

状态：**用户真机验收通过**

日期：2026-10-02

## 1. 验收结论

用户先完成 Stage 9 手动说话人分离链路真机测试并明确反馈：

**“测试通过”**

随后 Stage 9 Alpha 2 将产品行为修改为：

**直接 FAST/HQ 转写 = 转写完成后自动继续说话人分离 + Speaker 文本对齐**

用户再次明确反馈：

**“测试通过”**

因此 Stage 9 功能验收门已满足。

## 2. 最终 QA 基线

- implementation HEAD：`4d15728282411eaa92882e98d89c0c579b19fca4`
- versionCode：25
- versionName：`0.9.0-stage9-alpha2`
- QA Application ID：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Room schema：3
- CI：#281 / `36970218072` — **success**

CI #281 包含：

- Unit test / debug build
- stable QA signing identity
- committed Room schema v1/v2/v3 check
- QA APK artifact

## 3. 自动化覆盖

Stage 9 自动化覆盖至少包括：

- Room 2 → 3 migration
- Stage 8 recording/transcription/token 数据保留
- 新增 diarization tables 写入与 cascade
- speakerRole manifest 向后兼容
- ERes2Net native embedding validator
- Pyannote + ERes2Net bundle smoke wiring
- native seconds → absolute sample timeline
- negative sherpa confidence 合法范围与 unavailable handling
- 30/60/120min bounded window planner
- sequential overlap-tail window reader
- cross-chunk local label permutation
- embedding cosine stitching
- overlap fallback / duplicate turn merge
- same-chunk local speakers 不折叠
- token-level speaker alignment
- punctuation/ITN finalText reconstruction invariant
- overlap ambiguous / unresolved fallback
- FAST/HQ 对同一 diarization run 的独立 alignment
- canonical lineage mismatch reject
- startup active → INTERRUPTED
- cancellation / model lease release
- direct transcription auto-diarization request tracking
- historical transcript viewing 不自动制造重型 diarization 请求

## 4. 真机已确认范围

用户已明确确认：

- Stage 9 speaker model candidate 可下载/启用并完成真机说话人分离
- 手动“单独说话人分离”主链可用
- 转写结果可应用 Speaker 分离结果
- Alpha 2 直接转写后自动继续说话人分离的产品链路通过
- 用户无需预先点击“单独说话人分离”

本文件不扩张用户反馈为未执行的定量性能结论。

## 5. Model channel 验收

Stage 9 candidate：

- Pyannote Segmentation 3.0 INT8
- 3D-Speaker ERes2Net Base zh-CN 16 kHz

candidate workflow 成功完成：

- upstream download
- package size/SHA
- installed file size/SHA
- candidate manifest validation
- prerelease
- Draft human gate

用户真机通过后 candidate PR #5 已 promotion 到 production。

production：

- model-channel main：`b4baf89a9391bc047668b86037ef5d600992cd76`
- manifestVersion：6
- manifestDigest：`1bbbc9fd324049486674138e76c7d6323b8454eacbbe1830eef71b8367948447`
- validation CI #22：**success**

## 6. 未声明为已通过的专项

以下仍不是 Stage 9 的已验证真机结论：

- 真实 30min / 1h / 2h 长录音 soak
- ADB peak PSS 定量
- 长时间 CPU/thermal 定量
- 系统化 DER benchmark
- 完整 1/2/3/4 人标准化数据集指标
- 强噪声 / 强重叠 / 远场专项 benchmark

上述性能/长录音压力继续进入 Stage 13 或后续专项测试。

