# Stage 13A Frozen Roadmap Note

状态：**FROZEN / 2026-10-05 / 用户真机验收通过**

本文件用于覆盖 `docs/ROADMAP.md` 与 `AGENTS.md` 中仍存在的 Stage 13A pre-freeze 候选描述。Stage 13B 接管时，Stage 13A 的最终事实以以下文件为准：

- `docs/STAGE_13A_FREEZE.md`
- `docs/STAGE_13A_HANDOFF.md`
- `docs/STAGE_13A_QA5_FINAL_DECISIONS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`

## 冻结模型矩阵

录音文件离线转写：
- SenseVoice INT8：快速 / 默认
- Qwen3-ASR 0.6B INT8：高质量
- FireRedASR2：不进入产品矩阵

未来实时转写：
- Small Bilingual Zipformer INT8：轻量 / 默认
- Chinese Large CTC INT8：高质量
- Chinese Large Transducer：不进入产品矩阵
- 不提供 AUTO 产品路由

说话人分离：
- Pyannote Segmentation 3.0 INT8 + CAM++
- CAM++ 为冻结 embedding 模型
- ERes2Net 不进入产品选择

辅助模型：Silero VAD、CT-Transformer zh-en punctuation。

## 冻结产品边界

- 录音文件只有一个 `开始离线转写` 产品入口。
- Qwen 最终正文是权威文本；Small Bilingual 只可作为可选时间轴对齐助手。
- 实时模型选择与录音文件离线模型选择相互独立。
- Speech Benchmark / Diarization Benchmark 不属于产品功能。
- Stage 16 复用冻结的 streaming contract，不在 Stage 13B 改写模型契约。

## 下一阶段

下一阶段为 **Stage 13B — 稳定性、后台与真实长录音专项**。

Stage 13B 必须以 Stage 13A 冻结模型矩阵与当前默认参数为基线，重点验证 30 分钟 / 1 小时 / 2 小时真实录音、ASR/diarization 资源与热表现、BLE soak、Foreground Service、后台/锁屏下载、取消/中断/进程恢复、低存储以及 playback/timeline/revision/search/AI Summary 长文本回归。

Stage 13A Freeze 时 production model-channel 仍保持 `ioannes78/voica-model-channel@e4e64d29b8c92b97de4298ec6e292c33273f3ba4`。production manifest 的后续提升属于独立受控操作，不作为本次 Freeze/merge 的隐含步骤。
