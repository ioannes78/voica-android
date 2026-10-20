# Stage 16B Gate B — B3 Hotword / Context-Bias Real-Device Evidence

Status: **PASS**

Date: 2026-10-20

This is the final B3 follow-up after the controlled punctuation / ITN / language / short-utterance / no-speech profiles.

## Frozen contract

- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- FSMN runs once on canonical PCM;
- canonical `sampleIndex` speech boundaries are frozen once;
- the same frozen PCM is reused for all OFF/ON variants;
- common maximum ASR input duration: `25 s`;
- no external punctuation;
- no Qwen timeline alignment;
- no Room benchmark persistence;
- SenseVoice is excluded because the current integration exposes no equivalent hotword control; unsupported capability is not emulated.

Reference:

`今天测试 Qwen3、OpenAI 和 GitHub 的语音识别效果。`

Configured hotwords:

`Qwen3,OpenAI,GitHub`

Real-device recording:

- recording: `a6fb8986-ca35-4f63-87af-fbe560277429`;
- canonical SHA-256: `811226ba1847eae1d663b98b6ff685f07a1f9317887ea523977b4d9316ca441b`;
- VAD wall: `326 ms`;
- speech segments: `1`;
- frozen ASR inputs: `1`.

## FunASR Nano INT8

### HOTWORDS_OFF

- configured hotwords: none;
- engine open: `17500 ms`;
- ASR wall: `3567 ms`;
- calls / blank: `1 / 0`;
- content CER: `0.1724`;
- hotword coverage: `2/3`;
- exact hotword hits: `OpenAI`, `GitHub`;
- raw text: `今天测试 qwen_three openai 和 github 的语音识别效果。`

### HOTWORDS_ON

- configured hotwords: `Qwen3,OpenAI,GitHub`;
- engine open: `11794 ms`;
- ASR wall: `3680 ms`;
- calls / blank: `1 / 0`;
- content CER: `0.0345`;
- hotword coverage: `3/3`;
- exact hotword hits: `Qwen3`, `OpenAI`, `GitHub`;
- raw text: `今天测试Qwen3D OpenAI和GitHub的语音识别效果。`

Interpretation: the context-bias control is behaviorally active. Relative to OFF, literal CER falls from `0.1724` to `0.0345` and exact hotword coverage rises from `2/3` to `3/3`. The residual `Qwen3D` extra `D` remains visible in raw output and prevents treating this as a perfect literal transcription.

## Qwen3-ASR 0.6B INT8

### HOTWORDS_OFF

- configured hotwords: none;
- engine open: `6318 ms`;
- ASR wall: `4526 ms`;
- calls / blank: `1 / 0`;
- content CER: `0.3103`;
- hotword coverage: `0/3`;
- exact hotword hits: none;
- raw text: `今天测试Q本四、六、OPPO A I和G H O B的语音识别效果。`

### HOTWORDS_ON

- configured hotwords: `Qwen3,OpenAI,GitHub`;
- engine open: `6256 ms`;
- ASR wall: `3667 ms`;
- calls / blank: `1 / 0`;
- content CER: `0.0690`;
- hotword coverage: `2/3`;
- exact hotword hits: `OpenAI`, `GitHub`;
- raw text: `今天测试Qwen三力、OpenAI和GitHub的语音识别效果。`

Interpretation: Qwen3 context bias is also behaviorally active. CER falls from `0.3103` to `0.0690`, and exact hotword coverage rises from `0/3` to `2/3`. `OpenAI` and `GitHub` are recovered, but `Qwen3` itself is still not an exact hit in the ON variant.

## Decision

**PASS** for the B3 hotword/context-bias capability check.

Both supported runtimes show a material, same-input OFF -> ON improvement and neither produces blank output or runtime failure. On this one controlled sample, FunASR reaches the stronger ON result (`CER 0.0345`, `3/3` exact hotword coverage) than Qwen3 (`CER 0.0690`, `2/3`). This is capability evidence, not a general ASR-quality or performance ranking.

Performance timings are signals only; B5 owns isolated cold/warm performance and memory ranking.

## B3 closure

With this follow-up complete, B3 has real-device evidence for:

- native punctuation;
- ITN behavior;
- monolingual and mixed-language behavior;
- short-utterance robustness;
- silence/noise filtering at the `FSMN -> ASR` product-pipeline boundary;
- supported hotword/context-bias behavior.

B3 is therefore **REAL-DEVICE PASS**. Next gate: **B4 Timestamp / Timeline Compatibility**.
