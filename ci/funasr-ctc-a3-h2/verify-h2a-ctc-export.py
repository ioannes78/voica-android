#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import tiktoken
import torch
import torch.nn as nn
import torch.nn.functional as F
import torchaudio
import yaml
from modelscope_hub import HubApi
from tiktoken.load import load_tiktoken_bpe

MODEL_ID = "FunAudioLLM/Fun-ASR-Nano-2512"
DEFAULT_REVISION = "master"
EXPECTED_MODEL_SIZE = 2_127_426_538
EXPECTED_MODEL_SHA256 = "81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499"
EXPECTED_TOKENIZER_SHA256 = "747979631e813193436aabcff7c1c235d37de8097b71c563ec8b63b7a515c718"
EXPECTED_CONFIG_SHA256 = "daed38ea6484f5650fb32cbd9069b9aa13880acaf2bcb1f0bf4be2712837917c"
EXPECTED_FUNASR_COMMIT = "24217c8ff0b81f3d967593f0bf84609306929f99"
EXPECTED_ENCODER_DIM = 512
EXPECTED_VOCAB_SIZE = 60_515
EXPECTED_BLANK_ID = 60_514
EXPECTED_DECODER_BLOCKS = 5
PATTERN = r"'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+"
ALIGNMENT_TEXTS = (
    "我叫郗明。你好。",
    "FunASR Nano + Voica：测试ABC123。",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_checkpoint_state(state: Any) -> dict[str, Any]:
    while isinstance(state, dict):
        wrapped = next(
            (
                state[key]
                for key in ("state_dict", "model_state_dict", "model")
                if isinstance(state.get(key), dict)
            ),
            None,
        )
        if wrapped is None or wrapped is state:
            break
        state = wrapped
    if not isinstance(state, dict):
        raise RuntimeError(f"Normalized checkpoint must be a mapping, got {type(state).__name__}")
    normalized: dict[str, Any] = {}
    for raw_key, value in state.items():
        if not isinstance(raw_key, str):
            raise RuntimeError(f"Checkpoint contains non-string key: {raw_key!r}")
        key = raw_key[len("module.") :] if raw_key.startswith("module.") else raw_key
        if key in normalized:
            raise RuntimeError(f"Key collision after module. normalization: {key}")
        normalized[key] = value
    return normalized


def git_head(path: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def download_source(out_dir: Path, revision: str) -> tuple[Path, Path, Path]:
    api = HubApi()
    files = {
        entry.path: entry
        for entry in api.list_repo_files(
            MODEL_ID,
            "model",
            revision=revision,
            recursive=True,
        )
    }
    for required in ("model.pt", "config.yaml", "multilingual.tiktoken"):
        if required not in files:
            raise RuntimeError(f"ModelScope source missing {required}")
    model_meta = files["model.pt"]
    if int(model_meta.size or 0) != EXPECTED_MODEL_SIZE:
        raise RuntimeError(f"model.pt remote size drift: {model_meta.size}")
    remote_sha = (model_meta.sha256 or "").lower() if model_meta.sha256 else None
    if remote_sha and remote_sha != EXPECTED_MODEL_SHA256:
        raise RuntimeError(f"model.pt remote SHA drift: {remote_sha}")

    root = Path(
        api.download_repo(
            MODEL_ID,
            "model",
            revision=revision,
            local_dir=out_dir,
            allow_patterns=["model.pt", "config.yaml", "multilingual.tiktoken"],
            max_workers=1,
        )
    )
    candidates = [root, out_dir]

    def locate(name: str) -> Path:
        for base in candidates:
            candidate = base / name
            if candidate.is_file():
                return candidate
        raise RuntimeError(f"Filtered ModelScope download did not produce {name}: root={root}")

    return locate("model.pt"), locate("config.yaml"), locate("multilingual.tiktoken")


def build_tokenizer(path: Path) -> tiktoken.Encoding:
    ranks = load_tiktoken_bpe(str(path))
    return tiktoken.Encoding(
        name="voica-funasr-h2a-reference",
        pat_str=PATTERN,
        mergeable_ranks=ranks,
        special_tokens={},
    )


def import_official_modules(funasr_root: Path):
    head = git_head(funasr_root)
    if head != EXPECTED_FUNASR_COMMIT:
        raise RuntimeError(f"Pinned FunASR checkout drift: {head} != {EXPECTED_FUNASR_COMMIT}")
    sys.path.insert(0, str(funasr_root))
    from funasr.models.fun_asr_nano.ctc import CTC  # type: ignore
    from funasr.models.llm_asr.adaptor import Transformer  # type: ignore

    return Transformer, CTC


def require_conf(config: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    decoder_conf = config.get("ctc_decoder_conf")
    ctc_conf = config.get("ctc_conf")
    if config.get("ctc_decoder") != "Transformer":
        raise RuntimeError(f"Unexpected ctc_decoder: {config.get('ctc_decoder')!r}")
    if not isinstance(decoder_conf, dict) or not isinstance(ctc_conf, dict):
        raise RuntimeError("CTC config sections must be mappings")
    expected = {
        "downsample_rate": 1,
        "ffn_dim": 2048,
        "llm_dim": EXPECTED_ENCODER_DIM,
        "encoder_dim": EXPECTED_ENCODER_DIM,
        "n_layer": EXPECTED_DECODER_BLOCKS,
    }
    for key, value in expected.items():
        if decoder_conf.get(key) != value:
            raise RuntimeError(f"ctc_decoder_conf.{key} drift: {decoder_conf.get(key)!r} != {value}")
    return dict(decoder_conf), dict(ctc_conf)


def build_official_ctc(
    state: dict[str, Any],
    config: dict[str, Any],
    Transformer: type[nn.Module],
    CTC: type[nn.Module],
) -> tuple[nn.Module, nn.Module]:
    decoder_conf, ctc_conf = require_conf(config)
    decoder_conf.pop("freeze", None)
    decoder = Transformer(**decoder_conf)
    decoder_state = {
        key.removeprefix("ctc_decoder."): value
        for key, value in state.items()
        if key.startswith("ctc_decoder.")
    }
    if len(decoder_state) != 84:
        raise RuntimeError(f"Expected 84 CTC decoder tensors, got {len(decoder_state)}")
    decoder.load_state_dict(decoder_state, strict=True)

    ctc = CTC(
        odim=EXPECTED_VOCAB_SIZE,
        encoder_output_size=EXPECTED_ENCODER_DIM,
        blank_id=EXPECTED_BLANK_ID,
        **ctc_conf,
    )
    ctc_state = {
        key.removeprefix("ctc."): value
        for key, value in state.items()
        if key.startswith("ctc.")
    }
    if sorted(ctc_state) != ["ctc_lo.bias", "ctc_lo.weight"]:
        raise RuntimeError(f"Unexpected CTC projection keys: {sorted(ctc_state)}")
    ctc.load_state_dict(ctc_state, strict=True)

    decoder = decoder.float().eval()
    ctc = ctc.float().eval()
    return decoder, ctc


class CompactCtcScorer(nn.Module):
    """Exportable single-stream/full-valid form of the official CTC path.

    FunASR's generic Transformer forward creates a padding mask with
    make_pad_mask(). The legacy ONNX exporter specializes that helper to the
    trace-time sequence length, so a graph traced at T=17 fails at T=48/64.

    Voica A1 retains one OfflineStream at a time as [1,T,512] with exact valid
    length T and no batch padding. For the accepted checkpoint k=1, therefore,
    the official padding mask is all-true and every EncoderLayer is exactly
    equivalent to calling the same official block with mask=None. H2a proves
    that equivalence against the official masked PyTorch forward before ONNX
    output is accepted.
    """

    def __init__(self, decoder: nn.Module, ctc: nn.Module):
        super().__init__()
        if int(decoder.k) != 1:
            raise RuntimeError(f"Compact export requires CTC decoder k=1, got {decoder.k}")
        self.decoder = decoder
        self.projection = ctc.ctc_lo

    def forward(
        self,
        ctc_encoder_out: torch.Tensor,
        ctc_encoder_out_lens: torch.Tensor,
        candidate_ids: torch.Tensor,
    ) -> torch.Tensor:
        # For k=1 and a full-valid single stream, the official Transformer
        # padding/reshape stage is identity and its mask contains no false
        # positions. Execute the exact trained modules with mask=None so T stays
        # genuinely dynamic in ONNX instead of being frozen by make_pad_mask().
        x = self.decoder.linear1(ctc_encoder_out)
        x = self.decoder.relu(x)
        x = self.decoder.linear2(x)
        if self.decoder.blocks is not None:
            for block in self.decoder.blocks:
                x, _ = block(x, None)

        weight = torch.index_select(self.projection.weight, 0, candidate_ids)
        bias = torch.index_select(self.projection.bias, 0, candidate_ids)
        logits = F.linear(x, weight, bias)

        # Preserve the exact-length input in the ONNX ABI. A1 guarantees
        # lens[0] == tensor T. If a future caller violates that contract, rows
        # outside valid_len are zeroed and native A2 still consumes only the
        # model-owned valid frame count.
        frame_index = torch.arange(logits.size(1), device=logits.device)
        valid = frame_index < ctc_encoder_out_lens[0]
        return torch.where(valid.view(1, -1, 1), logits, torch.zeros_like(logits))


def export_compact(model: nn.Module, path: Path) -> None:
    x = torch.randn(1, 17, EXPECTED_ENCODER_DIM, dtype=torch.float32)
    lens = torch.tensor([17], dtype=torch.int64)
    ids = torch.tensor([EXPECTED_BLANK_ID, 10958, 245, 1234], dtype=torch.int64)
    kwargs: dict[str, Any] = {
        "opset_version": 18,
        "input_names": ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        "output_names": ["candidate_logits"],
        "dynamic_axes": {
            "ctc_encoder_out": {1: "T"},
            "candidate_ids": {0: "C"},
            "candidate_logits": {1: "T_out", 2: "C"},
        },
        "do_constant_folding": True,
        "external_data": False,
        "verbose": False,
    }
    signature = inspect.signature(torch.onnx.export)
    if "dynamo" in signature.parameters:
        kwargs["dynamo"] = False
    if "training" in signature.parameters:
        kwargs["training"] = torch.onnx.TrainingMode.EVAL
    torch.onnx.export(model, (x, lens, ids), str(path), **kwargs)
    graph = onnx.load(str(path))
    onnx.checker.check_model(graph)


def make_session(path: Path) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(
        str(path),
        sess_options=opts,
        providers=["CPUExecutionProvider"],
    )
    if [value.name for value in session.get_inputs()] != [
        "ctc_encoder_out",
        "ctc_encoder_out_lens",
        "candidate_ids",
    ]:
        raise RuntimeError("Compact ONNX input ABI drift")
    if [value.name for value in session.get_outputs()] != ["candidate_logits"]:
        raise RuntimeError("Compact ONNX output ABI drift")
    return session


def compact_viterbi(raw_logits: np.ndarray, targets: list[int]) -> list[int]:
    if raw_logits.ndim != 2 or raw_logits.shape[1] != len(targets) + 1:
        raise RuntimeError("Compact score shape mismatch")
    t_count = raw_logits.shape[0]
    n_targets = len(targets)
    states = 2 * n_targets + 1
    neg_inf = -np.inf
    dp = np.full(states, neg_inf, dtype=np.float64)
    back = np.full((t_count, states), -1, dtype=np.int32)

    def emission(frame: int, state: int) -> float:
        if state % 2 == 0:
            return float(raw_logits[frame, 0])
        return float(raw_logits[frame, state // 2 + 1])

    dp[0] = emission(0, 0)
    if n_targets:
        dp[1] = emission(0, 1)
    for frame in range(1, t_count):
        nxt = np.full(states, neg_inf, dtype=np.float64)
        for state in range(states):
            predecessors = [state]
            if state >= 1:
                predecessors.append(state - 1)
            if state >= 2 and state % 2 == 1:
                current_target = targets[state // 2]
                previous_target = targets[state // 2 - 1]
                if current_target != previous_target:
                    predecessors.append(state - 2)
            best_prev = max(predecessors, key=lambda s: dp[s])
            if np.isfinite(dp[best_prev]):
                nxt[state] = dp[best_prev] + emission(frame, state)
                back[frame, state] = best_prev
        dp = nxt

    finals = [states - 1]
    if states >= 2:
        finals.append(states - 2)
    state = max(finals, key=lambda s: dp[s])
    if not np.isfinite(dp[state]):
        raise RuntimeError("Compact Viterbi has no finite final path")
    state_path = [state]
    for frame in range(t_count - 1, 0, -1):
        state = int(back[frame, state])
        if state < 0:
            raise RuntimeError("Compact Viterbi backtrace is broken")
        state_path.append(state)
    state_path.reverse()

    aligned: list[int] = []
    for state in state_path:
        if state % 2 == 0:
            aligned.append(EXPECTED_BLANK_ID)
        else:
            aligned.append(targets[state // 2])
    return aligned


def official_alignment(full_log_probs: torch.Tensor, targets: list[int]) -> list[int]:
    target_tensor = torch.tensor([targets], dtype=torch.int64)
    alignments, _scores = torchaudio.functional.forced_align(
        full_log_probs.cpu(),
        target_tensor,
        blank=EXPECTED_BLANK_ID,
    )
    return [int(value) for value in alignments[0].tolist()]


def verify_case(
    decoder: nn.Module,
    ctc: nn.Module,
    compact: nn.Module,
    session: ort.InferenceSession,
    tokenizer: tiktoken.Encoding,
    text: str,
    frames: int,
    seed: int,
) -> dict[str, Any]:
    torch.manual_seed(seed)
    encoder = torch.randn(1, frames, EXPECTED_ENCODER_DIM, dtype=torch.float32)
    lens = torch.tensor([frames], dtype=torch.int64)
    targets = tokenizer.encode_ordinary(text)
    if not targets or len(targets) + 1 > frames:
        raise RuntimeError(f"Fixture target/frame geometry invalid: {text!r}, targets={len(targets)}, frames={frames}")
    if EXPECTED_BLANK_ID in targets or max(targets) >= EXPECTED_VOCAB_SIZE:
        raise RuntimeError(f"Fixture emitted invalid target ids: {targets}")
    candidates = torch.tensor([EXPECTED_BLANK_ID, *targets], dtype=torch.int64)

    with torch.inference_mode():
        decoder_out, decoder_lens = decoder(encoder, lens)
        if decoder_lens.tolist() != lens.tolist():
            raise RuntimeError(f"Official decoder length drift: {decoder_lens.tolist()} != {lens.tolist()}")
        full_raw = ctc.ctc_lo(decoder_out)
        full_log_probs = torch.log_softmax(full_raw, dim=-1)
        selected_full = torch.index_select(full_raw, 2, candidates)
        compact_pt = compact(encoder, lens, candidates)

    if not torch.allclose(selected_full, compact_pt, rtol=1e-5, atol=1e-5):
        diff = float(torch.max(torch.abs(selected_full - compact_pt)).item())
        raise RuntimeError(
            "Official masked PyTorch != full-valid compact PyTorch raw logits: "
            f"max_abs={diff}"
        )
    official_fullvalid_max_abs = float(torch.max(torch.abs(selected_full - compact_pt)).item())

    ort_out = session.run(
        ["candidate_logits"],
        {
            "ctc_encoder_out": encoder.numpy(),
            "ctc_encoder_out_lens": lens.numpy(),
            "candidate_ids": candidates.numpy(),
        },
    )[0]
    pt_np = compact_pt.detach().cpu().numpy()
    if not np.allclose(pt_np, ort_out, rtol=1e-4, atol=1e-4):
        diff = float(np.max(np.abs(pt_np - ort_out)))
        raise RuntimeError(f"PyTorch/ONNX compact scorer mismatch: max_abs={diff}")

    official = official_alignment(full_log_probs, targets)
    compact_path = compact_viterbi(ort_out[0], targets)
    if official != compact_path:
        first = next((i for i, pair in enumerate(zip(official, compact_path)) if pair[0] != pair[1]), None)
        raise RuntimeError(
            "Official full-vocab forced alignment != compact raw-logit Viterbi: "
            f"first_diff={first}, official={official}, compact={compact_path}"
        )

    return {
        "text": text,
        "frames": frames,
        "target_count": len(targets),
        "target_ids": targets,
        "candidate_count": len(targets) + 1,
        "official_masked_vs_fullvalid_max_abs": official_fullvalid_max_abs,
        "pytorch_onnx_max_abs": float(np.max(np.abs(pt_np - ort_out))),
        "alignment_frames": len(official),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--funasr-root", type=Path, required=True)
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    Transformer, CTC = import_official_modules(args.funasr_root.resolve())
    model_path, config_path, tokenizer_path = download_source(args.out, args.revision)

    if model_path.stat().st_size != EXPECTED_MODEL_SIZE:
        raise RuntimeError(f"model.pt local size mismatch: {model_path.stat().st_size}")
    model_sha = sha256_file(model_path)
    config_sha = sha256_file(config_path)
    tokenizer_sha = sha256_file(tokenizer_path)
    if model_sha != EXPECTED_MODEL_SHA256:
        raise RuntimeError(f"model.pt local SHA mismatch: {model_sha}")
    if config_sha != EXPECTED_CONFIG_SHA256:
        raise RuntimeError(f"config.yaml SHA mismatch: {config_sha}")
    if tokenizer_sha != EXPECTED_TOKENIZER_SHA256:
        raise RuntimeError(f"multilingual.tiktoken SHA mismatch: {tokenizer_sha}")

    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise RuntimeError("config.yaml root must be mapping")

    checkpoint = torch.load(
        model_path,
        map_location="cpu",
        weights_only=True,
        mmap=True,
    )
    state = normalize_checkpoint_state(checkpoint)
    decoder, ctc = build_official_ctc(state, config, Transformer, CTC)
    compact = CompactCtcScorer(decoder, ctc).eval()

    onnx_path = args.out / "ctc_compact.fp32.onnx"
    export_compact(compact, onnx_path)
    session = make_session(onnx_path)
    tokenizer = build_tokenizer(tokenizer_path)

    cases = [
        verify_case(decoder, ctc, compact, session, tokenizer, ALIGNMENT_TEXTS[0], 48, 1601),
        verify_case(decoder, ctc, compact, session, tokenizer, ALIGNMENT_TEXTS[1], 64, 1602),
    ]

    summary = {
        "model_id": MODEL_ID,
        "requested_revision": args.revision,
        "model_pt_sha256": model_sha,
        "config_sha256": config_sha,
        "tokenizer_sha256": tokenizer_sha,
        "funasr_reference_commit": EXPECTED_FUNASR_COMMIT,
        "torch_version": torch.__version__,
        "torchaudio_version": torchaudio.__version__,
        "onnxruntime_version": ort.__version__,
        "export_runtime_contract": "batch=1, full-valid single stream, ctc_encoder_out_lens[0] == T",
        "onnx_sha256": sha256_file(onnx_path),
        "onnx_size": onnx_path.stat().st_size,
        "onnx_abi": {
            "inputs": ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
            "output": "candidate_logits",
            "encoder_dim": EXPECTED_ENCODER_DIM,
            "vocab_size": EXPECTED_VOCAB_SIZE,
            "blank_id": EXPECTED_BLANK_ID,
        },
        "parity_cases": cases,
        "status": "H2a FP32 CTC scorer export/parity PASS",
    }
    summary_path = args.out / "a3-h2a-ctc-export-parity.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"ONNX_PATH={onnx_path}")


if __name__ == "__main__":
    main()
