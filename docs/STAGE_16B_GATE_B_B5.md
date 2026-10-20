# Stage 16B Gate B — B5 Isolated Cold/Warm Performance + Memory

Status: **PLANNED / NOT STARTED**

Date: 2026-10-20

B5 begins only after B4 REAL-DEVICE PASS. The benchmark must isolate candidates so model-memory carryover from a previous ASR engine cannot contaminate start/peak/end PSS or cold-open timing.

Candidates remain:

- SenseVoice INT8;
- Fun-ASR-Nano INT8;
- Qwen3-ASR 0.6B INT8.

Formal B5 evidence still requires exact FSMN and the common verified 25-second maximum ASR input boundary. Detailed harness design and real-device procedure will be frozen before execution.
