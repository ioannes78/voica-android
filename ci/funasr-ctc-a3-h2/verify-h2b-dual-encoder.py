#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn

EXPECTED_EXPORTER_COMMIT = "6823a8ed9f4a0393750d54d750051cf5a51a7fa9"
EXPECTED_MODEL_SIZE = 2_127_426_538
EXPECTED_MODEL_SHA256 = "81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499"
EXPECTED_INPUT_DIM = 560
EXPECTED_CTC_DIM = 512
EXPECTED_LLM_DIM = 1024
TEST_CASES = ((30, 1701), (47, 1702), (61, 1703))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_head(path: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(path), "rev-parse", "HEAD"],
        text=True,
    ).strip()


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


def locate_model(source_root: Path) -> Path:
    candidates = [p for p in source_root.rglob("model.pt") if p.is_file()]
    if not candidates:
        raise RuntimeError(f"No model.pt under H2 source root: {source_root}")
    exact = [p for p in candidates if p.stat().st_size == EXPECTED_MODEL_SIZE]
    if len(exact) != 1:
        raise RuntimeError(
            f"Expected exactly one {EXPECTED_MODEL_SIZE}-byte model.pt under {source_root}, "
            f"got {[str(p) for p in candidates]}"
        )
    return exact[0]


def import_exporter(exporter_root: Path):
    head = git_head(exporter_root)
    if head != EXPECTED_EXPORTER_COMMIT:
        raise RuntimeError(f"Pinned exporter drift: {head} != {EXPECTED_EXPORTER_COMMIT}")
    scripts = exporter_root / "scripts"
    if not (scripts / "nano_llm.py").is_file():
        raise RuntimeError(f"Pinned exporter missing scripts/nano_llm.py: {exporter_root}")
    sys.path.insert(0, str(scripts))
    import nano_llm  # type: ignore

    return nano_llm


def load_legacy_model(model_path: Path, nano_llm: Any) -> tuple[nn.Module, int, int]:
    checkpoint = torch.load(
        model_path,
        map_location="cpu",
        weights_only=True,
        mmap=True,
    )
    state = normalize_checkpoint_state(checkpoint)

    adaptor_blocks = {
        int(key.split(".")[2])
        for key in state
        if key.startswith("audio_adaptor.blocks.")
        and len(key.split(".")) >= 3
        and key.split(".")[2].isdigit()
    }
    if adaptor_blocks and adaptor_blocks != set(range(max(adaptor_blocks) + 1)):
        raise RuntimeError(f"Non-contiguous audio_adaptor blocks: {sorted(adaptor_blocks)}")
    n_layer = len(adaptor_blocks)

    model = nano_llm.NanoLLM(
        adaptor_config={
            "downsample_rate": 1,
            "encoder_dim": EXPECTED_CTC_DIM,
            "llm_dim": EXPECTED_LLM_DIM,
            "ffn_dim": 2048,
            "n_layer": n_layer,
        }
    )

    filtered: dict[str, Any] = {}
    source_count = 0
    for key, value in state.items():
        if key.startswith("audio_encoder."):
            filtered[key] = value
            source_count += 1
        elif key.startswith("audio_adaptor."):
            filtered[key.replace("audio_adaptor.", "adaptor.", 1)] = value
            source_count += 1
    if not filtered:
        raise RuntimeError("Accepted checkpoint contains no audio_encoder/audio_adaptor tensors")

    missing, unexpected = model.load_state_dict(filtered, strict=False)
    if missing or unexpected:
        raise RuntimeError(
            "Pinned /1 exporter cannot strictly consume accepted checkpoint encoder/adaptor weights: "
            f"missing={missing[:10]} unexpected={unexpected[:10]}"
        )
    return model.float().eval(), n_layer, source_count


class DualOutputNanoLLM(nn.Module):
    """Revision-2 encoder contract built from the exact pinned /1 modules."""

    def __init__(self, legacy: nn.Module):
        super().__init__()
        self.audio_encoder = legacy.audio_encoder
        self.adaptor = legacy.adaptor

    def forward(self, x: torch.Tensor):
        # Preserve the pinned /1 forward computation for output[0]. The only
        # semantic addition is exposing the already-computed pre-adaptor state
        # and its exact single-stream valid length.
        x = x.clone()
        ctc_encoder_out = self.audio_encoder(x)
        encoder_out = self.adaptor(ctc_encoder_out)
        # Batch is intentionally fixed to 1 by the sherpa OfflineStream ABI.
        # The legacy exporter translates size(1) of a dynamic-axis tensor into
        # Shape/Gather, so this remains data-dependent for T=30/47/61.
        ctc_encoder_out_lens = torch.ones(
            (1,), dtype=torch.int64, device=ctc_encoder_out.device
        ) * ctc_encoder_out.size(1)
        return encoder_out, ctc_encoder_out, ctc_encoder_out_lens


def export_legacy(model: nn.Module, x: torch.Tensor, path: Path) -> None:
    kwargs: dict[str, Any] = {
        "opset_version": 18,
        "input_names": ["x"],
        "output_names": ["encoder_out"],
        "dynamic_axes": {"x": {1: "T"}, "encoder_out": {1: "T_out"}},
        "verbose": False,
        "do_constant_folding": True,
        "external_data": False,
    }
    signature = inspect.signature(torch.onnx.export)
    if "training" in signature.parameters:
        kwargs["training"] = torch.onnx.TrainingMode.EVAL
    if "dynamo" in signature.parameters:
        kwargs["dynamo"] = False
    torch.onnx.export(model, x, str(path), **kwargs)
    graph = onnx.load(str(path))
    onnx.checker.check_model(graph)


def export_dual(model: nn.Module, x: torch.Tensor, path: Path) -> None:
    kwargs: dict[str, Any] = {
        "opset_version": 18,
        "input_names": ["x"],
        "output_names": ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
        "dynamic_axes": {
            "x": {1: "T"},
            "encoder_out": {1: "T_out"},
            "ctc_encoder_out": {1: "T_ctc"},
        },
        "verbose": False,
        "do_constant_folding": True,
        "external_data": False,
    }
    signature = inspect.signature(torch.onnx.export)
    if "training" in signature.parameters:
        kwargs["training"] = torch.onnx.TrainingMode.EVAL
    if "dynamo" in signature.parameters:
        kwargs["dynamo"] = False
    torch.onnx.export(model, x, str(path), **kwargs)
    graph = onnx.load(str(path))
    onnx.checker.check_model(graph)


def make_session(path: Path, expected_outputs: list[str]) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(
        str(path), sess_options=opts, providers=["CPUExecutionProvider"]
    )
    if [x.name for x in session.get_inputs()] != ["x"]:
        raise RuntimeError(f"Unexpected encoder input ABI: {[x.name for x in session.get_inputs()]}")
    actual_outputs = [x.name for x in session.get_outputs()]
    if actual_outputs != expected_outputs:
        raise RuntimeError(f"Unexpected encoder output ABI: {actual_outputs} != {expected_outputs}")
    return session


def allclose_metric(
    name: str,
    expected: np.ndarray,
    actual: np.ndarray,
    *,
    rtol: float,
    atol: float,
) -> float:
    if expected.shape != actual.shape:
        raise RuntimeError(f"{name} shape mismatch: {expected.shape} != {actual.shape}")
    if not np.isfinite(expected).all() or not np.isfinite(actual).all():
        raise RuntimeError(f"{name} contains non-finite values")
    diff = np.abs(expected - actual)
    max_abs = float(diff.max()) if diff.size else 0.0
    if not np.allclose(expected, actual, rtol=rtol, atol=atol):
        raise RuntimeError(f"{name} failed allclose rtol={rtol} atol={atol}, max_abs={max_abs}")
    return max_abs


def verify_case(
    legacy: nn.Module,
    dual: nn.Module,
    legacy_session: ort.InferenceSession,
    dual_session: ort.InferenceSession,
    frames: int,
    seed: int,
) -> dict[str, Any]:
    torch.manual_seed(seed)
    x = torch.randn(1, frames, EXPECTED_INPUT_DIM, dtype=torch.float32)
    with torch.inference_mode():
        pt_legacy = legacy(x)
        pt_dual0, pt_dual1, pt_lens = dual(x)
        pt_encoder = legacy.audio_encoder(x.clone())

    legacy_np = pt_legacy.detach().cpu().numpy()
    dual0_np = pt_dual0.detach().cpu().numpy()
    dual1_np = pt_dual1.detach().cpu().numpy()
    encoder_np = pt_encoder.detach().cpu().numpy()
    lens_np = pt_lens.detach().cpu().numpy()

    pt_output0_max = allclose_metric(
        "PyTorch legacy vs dual output[0]", legacy_np, dual0_np, rtol=1e-6, atol=1e-6
    )
    pt_output1_max = allclose_metric(
        "PyTorch audio_encoder vs dual output[1]", encoder_np, dual1_np, rtol=1e-6, atol=1e-6
    )
    if lens_np.dtype != np.int64 or lens_np.shape != (1,) or int(lens_np[0]) != frames:
        raise RuntimeError(f"PyTorch dual length mismatch for T={frames}: {lens_np!r}")
    if dual1_np.shape != (1, frames, EXPECTED_CTC_DIM):
        raise RuntimeError(f"PyTorch dual CTC state shape mismatch: {dual1_np.shape}")
    if dual0_np.ndim != 3 or dual0_np.shape[0] != 1 or dual0_np.shape[2] != EXPECTED_LLM_DIM:
        raise RuntimeError(f"PyTorch dual LLM embedding shape mismatch: {dual0_np.shape}")

    x_np = x.numpy()
    ort_legacy = legacy_session.run(["encoder_out"], {"x": x_np})[0]
    ort_dual0, ort_dual1, ort_lens = dual_session.run(None, {"x": x_np})

    pt_legacy_ort_max = allclose_metric(
        "Pinned /1 PyTorch vs legacy ONNX", legacy_np, ort_legacy, rtol=1e-3, atol=1e-3
    )
    pt_dual0_ort_max = allclose_metric(
        "Pinned /1 PyTorch vs dual ONNX output[0]", legacy_np, ort_dual0, rtol=1e-3, atol=1e-3
    )
    legacy_dual_ort_max = allclose_metric(
        "Legacy ONNX vs dual ONNX output[0]", ort_legacy, ort_dual0, rtol=1e-4, atol=1e-4
    )
    pt_encoder_ort_max = allclose_metric(
        "PyTorch audio_encoder vs dual ONNX output[1]", encoder_np, ort_dual1, rtol=1e-3, atol=1e-3
    )

    if ort_dual1.shape != (1, frames, EXPECTED_CTC_DIM):
        raise RuntimeError(f"Dual ONNX CTC state shape mismatch for T={frames}: {ort_dual1.shape}")
    if ort_lens.dtype != np.int64 or ort_lens.shape != (1,) or int(ort_lens[0]) != frames:
        raise RuntimeError(f"Dual ONNX exact length mismatch for T={frames}: {ort_lens!r}")

    return {
        "frames": frames,
        "legacy_output_shape": list(ort_legacy.shape),
        "ctc_encoder_shape": list(ort_dual1.shape),
        "ctc_encoder_out_lens": [int(v) for v in ort_lens.tolist()],
        "pytorch_legacy_vs_dual0_max_abs": pt_output0_max,
        "pytorch_encoder_vs_dual1_max_abs": pt_output1_max,
        "pytorch_legacy_vs_legacy_onnx_max_abs": pt_legacy_ort_max,
        "pytorch_legacy_vs_dual_onnx0_max_abs": pt_dual0_ort_max,
        "legacy_onnx_vs_dual_onnx0_max_abs": legacy_dual_ort_max,
        "pytorch_encoder_vs_dual_onnx1_max_abs": pt_encoder_ort_max,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--exporter-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    model_path = locate_model(args.source_root.resolve())
    if sha256_file(model_path) != EXPECTED_MODEL_SHA256:
        raise RuntimeError("H2b model.pt SHA mismatch; refusing to reuse untrusted H2a source")

    nano_llm = import_exporter(args.exporter_root.resolve())
    legacy, n_layer, source_tensor_count = load_legacy_model(model_path, nano_llm)
    dual = DualOutputNanoLLM(legacy).eval()

    dummy = torch.randn(1, 30, EXPECTED_INPUT_DIM, dtype=torch.float32)
    legacy_path = args.out / "encoder_adaptor.legacy.fp32.onnx"
    dual_path = args.out / "encoder_adaptor.dual.fp32.onnx"
    export_legacy(legacy, dummy, legacy_path)
    export_dual(dual, dummy, dual_path)

    legacy_session = make_session(legacy_path, ["encoder_out"])
    dual_session = make_session(
        dual_path, ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"]
    )
    dual_output_types = [x.type for x in dual_session.get_outputs()]
    if dual_output_types != ["tensor(float)", "tensor(float)", "tensor(int64)"]:
        raise RuntimeError(f"Dual encoder output type drift: {dual_output_types}")

    cases = [
        verify_case(legacy, dual, legacy_session, dual_session, frames, seed)
        for frames, seed in TEST_CASES
    ]

    summary = {
        "source_model_sha256": EXPECTED_MODEL_SHA256,
        "pinned_exporter_commit": EXPECTED_EXPORTER_COMMIT,
        "adaptor_layers": n_layer,
        "loaded_encoder_adaptor_tensor_count": source_tensor_count,
        "legacy_onnx_sha256": sha256_file(legacy_path),
        "legacy_onnx_size": legacy_path.stat().st_size,
        "dual_onnx_sha256": sha256_file(dual_path),
        "dual_onnx_size": dual_path.stat().st_size,
        "dual_onnx_abi": {
            "input": "x",
            "outputs": ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
            "output_types": dual_output_types,
            "input_dim": EXPECTED_INPUT_DIM,
            "ctc_dim": EXPECTED_CTC_DIM,
            "llm_dim": EXPECTED_LLM_DIM,
            "batch_contract": 1,
        },
        "cases": cases,
        "status": "H2b dual-output encoder FP32 parity PASS",
    }
    summary_path = args.out / "a3-h2b-dual-encoder-parity.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"LEGACY_ONNX_PATH={legacy_path}")
    print(f"DUAL_ONNX_PATH={dual_path}")


if __name__ == "__main__":
    main()
