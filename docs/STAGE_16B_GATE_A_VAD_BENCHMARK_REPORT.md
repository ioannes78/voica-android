# Stage 16B Gate A — VAD Benchmark Report

状态：**GATE A BENCHMARK CLOSED / WAITING FOR EXPLICIT PRODUCTION VAD DECISION**

日期：2026-10-20

目标：为 Stage 16B Local File ASR V2 选择文件转写前端 VAD。当前生产基线为 Silero；候选为 FSMN-VAD 与 TEN-VAD。用户在 A3 后明确停止继续比较 TEN，因此最终收口只比较 **Silero vs FSMN**。

> 本报告只形成 Gate A 技术推荐，不授权切换 production VAD。生产配置、Room schema、QS668/CB08、Android Audio I/O、播放与 Stage 16A.2 ownership 均保持不变，直到用户明确确认。

## 1. 冻结配置

### Silero — production baseline

- threshold = 0.5
- min silence = 250 ms
- min speech = 250 ms
- max speech = 30 s

### FSMN — candidate baseline

- model ID: `fsmn-vad-int8`
- `max_end_silence_time = 800 ms`
- `speech_to_silence = 150 ms`
- `lookahead_end = 100 ms`
- Gate A common max speech = 30 s

FSMN candidate runtime bring-up、integrity、isolated validation、activation 已真机通过；其 baseline 已由 CI contract 锁定。

### TEN — historical challenger only

TEN-VAD FP32 已完成 candidate bring-up 与 A1/A2 比较，但用户在 A3 后明确停止继续比较 TEN。TEN 不进入最终 shortlisted Gate A 决策，也不进入 A4。

## 2. A1 — VAD core behavior

A1 使用相同 canonical PCM，比较 VAD 分段、speech duration、RTF/PSS 与长段/碎片化行为。

六组合成样本 A01–A06 的 label-envelope 总时长为 1646.699 s：

| VAD | 检出 speech 总时长 | 相对参考偏差 | 平均每样本绝对偏差 | 加权 VAD RTF |
|---|---:|---:|---:|---:|
| Silero | 1696.0 s | +2.994% | ~4.04% | ~0.0194 |
| FSMN | 1666.4 s | +1.196% | **~1.17%** | **~0.0096** |
| TEN 30s | 1610.1 s | -2.223% | ~2.29% | ~0.0110 |
| TEN 5s ref | 1507.0 s | -8.484% | ~8.00% | ~0.0106 |

关键观察：

- FSMN 在 envelope 偏差与 VAD 速度上均领先。
- Silero 在 room-noise、mixed-style 与 long-mixed 样本中出现明显长段合并/分段退化。
- TEN 30s 速度有竞争力，但整体更偏向 speech under-detection。

A1 结论：**FSMN provisional leader；不在 A1 单独做最终选择。**

## 3. A2 — Same SenseVoice downstream impact

A2 固定同一条 SenseVoice 下游路径，仅替换 VAD。合成样本使用外部 reference 计算 deterministic CER；真实人声无标准稿时只比较完整性、明显漏句、分段与耗时。

### 3.1 五组合成样本汇总

A01 / A02 / A04 / A05 / A06 总 reference 为 4666 个 CER 有效字符：

| VAD | 加权 CER |
|---|---:|
| **FSMN** | **32.34%** |
| TEN | 51.35% |
| Silero | 72.50% |

代表性结果：

- A01 clean single speaker：FSMN CER 25.64%，Silero 29.12%，TEN 28.21%；FSMN 句首首字正确 17/18，Silero/TEN 均为 8/18。
- A02 pause-boundary stress：FSMN 19.21%，TEN 25.99%，Silero 32.49%。FSMN 将 24 个参考区间合并为 18 个 VAD 段，但没有造成内容损失，反而明显改善 SenseVoice 上下文。
- A04 room noise：FSMN 31.36%，TEN 57.63%，Silero 89.55%。Silero 出现灾难性内容丢失；FSMN 删除错误仅 8 个字符。
- A05 two-speaker-style：FSMN 33.16%，TEN 53.87%，Silero 90.17%。FSMN 删除错误 8；Silero 删除错误 655。
- A06 10-min mixed：FSMN 39.05%，TEN 63.83%，Silero 86.14%。FSMN 质量继续领先；其绝对 ASR 时间上升主要因为保留了更多有效语音。

说明：合成样本绝对 CER 受 TTS 与 SenseVoice 本身影响，只用于同输入、同 ASR 下 VAD 相对比较，不代表真实中文录音的产品绝对 CER。

### 3.2 两组真实人声

真实样本 1：

- Silero：41.344 s speech / 14 段 / 138 chars / 16.535 s total
- FSMN：41.430 s / 17 段 / 153 chars / 11.762 s total
- TEN：36.224 s / 12 段 / 125 chars / 8.441 s total

真实样本 2：

- Silero：269.472 s / 68 段 / 1017 chars / 50.768 s total
- FSMN：288.710 s / 27 段 / 1057 chars / 37.447 s total
- TEN：274.656 s / 42 段 / 1009 chars / 30.261 s total

两组真实人声合计：

- detected speech：Silero 310.816 s / **FSMN 330.140 s** / TEN 310.880 s
- output chars：Silero 1155 / **FSMN 1210** / TEN 1134
- total time：Silero 67.303 s / **FSMN 49.209 s** / TEN 38.702 s

真实人声观察与合成样本一致：FSMN 的短句、句首/句尾与连续讲话完整性最好；Silero 更碎；TEN 更快但边界偏紧、漏短句风险更明显。

A2 结论：**FSMN quality/completeness leader。**

## 4. A3 — Representative diarization impact

用户提供的代表性真实双人录音截图用于 Silero vs FSMN 观察。已知真实 speaker 数为 2。

| 指标 | Silero | FSMN |
|---|---:|---:|
| VAD 段 | 43 | **7** |
| detected speech | 143.4 s | **157.4 s** |
| diarization windows | 4 | **3** |
| detected speakers | 5 | **3** |
| turns | 14 | **13** |
| speaker switches | 10 | **7** |
| `<1s` turns | 3 | **1** |
| overlap turns | 4 | **2** |
| diarization time | 159.708 s | **84.596 s** |
| total time | 164.702 s | **87.704 s** |

FSMN 的第三 speaker 仅约 1.822 s，表现为“两个主要 speaker + 一个短暂误分”；Silero 则出现 5 个 speaker，其中额外 speaker 含约 0.388 s / 0.286 s 的明显碎片。

A3 结论：**FSMN 明显降低 speaker fragmentation，并大幅减少 diarization workload。**

用户在此后明确：**不再继续比较 TEN。**

## 5. A4 — 30-minute long-file stability

用户明确选择以 30 分钟实录完成 A4，并明确不再跑 60/120 分钟。该决定记录为 Gate A 收口条件的一部分，不视为遗漏测试。

真实录音长度：1800.032 s。

| 指标 | Silero | FSMN |
|---|---:|---:|
| detected speech | 1544.602 s | **1746.862 s** |
| VAD segments / ASR calls | 641 | **144** |
| output chars | 4518 | **4877** |
| VAD time | 131.736 s | **13.337 s** |
| SenseVoice time | **196.176 s** | 209.978 s |
| total time | 336.562 s | **230.349 s** |
| total RTF | 0.187 | **0.128** |
| ASR RTF | 0.127 | **0.120** |
| first-quarter ASR RTF | 0.158 | **0.129** |
| last-quarter ASR RTF | 0.110 | **0.097** |
| thermal slowdown | 0.699 | 0.749 |
| start→peak PSS | 89,326→390,434 KiB | 191,461→474,320 KiB |

解释：

- FSMN 总链路约快 31.6%，尽管它多保留约 202.3 s 有效语音。
- FSMN VAD 本身约快 9.9×。
- FSMN 将 VAD/ASR calls 从 641 降至 144，约减少 77.5%。
- 两组 last-quarter RTF 均低于 first-quarter，未发现 30 分钟级热降频信号。
- FSMN PSS 的绝对峰值更高，但起始 PSS 也高；本次 start→peak 增量约 276 MB，低于 Silero 约 294 MB，不能据此推断 FSMN 存在额外内存泄漏风险。

A4 结论：**30-minute real-device stability PASS for FSMN；未发现需要继续 60/120-minute gate 的阻塞信号。用户明确要求在此收口。**

## 6. Gate A final assessment

### Silero

优点：

- 当前 production baseline，迁移成本最低。

主要问题：

- 噪声与 mixed-style 下存在严重 speech/content loss。
- 长连续讲话中分段过碎。
- 下游 SenseVoice 与 diarization workload 明显增大。
- A2 合成 reference、两组真实人声、A3 与 A4 均未体现出质量或性能优势。

### FSMN

优点：

- A1 envelope consistency 与 VAD RTF 最优。
- A2 五组合成样本 CER 明显领先。
- A2 两组真实人声内容完整性领先。
- A3 speaker fragmentation 与 diarization workload 明显改善。
- A4 30 分钟 real-device 总 RTF 与 VAD 成本显著优于 Silero，且无热稳定性异常。

已知限制：

- FSMN interrupted-download resume 仍未单独验证。
- A3 使用代表性真实双人录音而非带严格 diarization ground-truth 的正式 DER 数据集，因此 A3 结论是产品级代表性证据，不是学术 DER benchmark。
- A4 60/120 分钟未执行；这是用户明确要求的收口范围。

## 7. Recommendation

**Gate A 技术推荐：选择 FSMN-VAD 作为 Stage 16B Local File ASR 的 production VAD。**

推荐置信度：**HIGH**。

依据不是单一 benchmark，而是 A1 → A2 synthetic/reference → A2 real speech → A3 diarization → A4 30-min stability 的连续一致证据。

## 8. Decision boundary / STOP

Gate A benchmark 到此正式收口。

在用户明确确认以下决定前：

> **“确认 Gate A：生产 Local File VAD 从 Silero 切换为 FSMN。”**

不得：

- 修改 production Local File VAD 默认绑定；
- promote FSMN candidate 到 production model channel；
- 删除 Silero fallback；
- 进入以新 production VAD 为前提的 Gate B final ASR matrix 实现。

用户确认后，下一步应先完成 FSMN production promotion / binding / fallback / regression contract，再进入 Gate B ASR Model Matrix。
