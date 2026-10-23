#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import tiktoken
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import QuantType, quantize_dynamic
from tiktoken.load import load_tiktoken_bpe

EXPECTED_FP32_CTC_SHA256 = "0b386256dd7d0cd29cbe8f9d01c26614e096fb9e7c3dd648d1e10cfdc5ee0328"
EXPECTED_DUAL_ENCODER_SHA256 = "d5bb172a133fc4f996ede12cab43c0b9b4bcbb2922c1f967794505b9549b6d85"
EXPECTED_TOKENIZER_SHA256 = "747979631e813193436aabcff7c1c235d37de8097b71c563ec8b63b7a515c718"
EXPECTED_VOCAB_SIZE = 60_515
EXPECTED_BLANK_ID = 60_514
EXPECTED_ENCODER_DIM = 512
PATTERN = r"'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+"
FIXTURES = ("zh.wav", "en.wav")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_sha(path: Path, expected: str, label: str) -> str:
    actual = sha256_file(path)
    if actual != expected:
        raise RuntimeError(f"{label} SHA mismatch: {actual} != {expected}")
    return actual


def load_h2a_module() -> Any:
    path = Path(__file__).with_name("verify-h2a-ctc-export.py")
    spec = importlib.util.spec_from_file_location("voica_h2a_reference", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load H2a reference module: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if int(module.EXPECTED_BLANK_ID) != EXPECTED_BLANK_ID:
        raise RuntimeError("H2a blank-id contract drift")
    if int(module.EXPECTED_VOCAB_SIZE) != EXPECTED_VOCAB_SIZE:
        raise RuntimeError("H2a vocab contract drift")
    return module


def locate_unique(root: Path, name: str) -> Path:
    direct = root / name
    if direct.is_file():
        return direct
    matches = [path for path in root.rglob(name) if path.is_file()]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one {name} below {root}, got {matches}")
    return matches[0]


def build_tokenizer(path: Path) -> tiktoken.Encoding:
    ranks = load_tiktoken_bpe(str(path))
    return tiktoken.Encoding(
        name="voica-funasr-h2d-reference",
        pat_str=PATTERN,
        mergeable_ranks=ranks,
        special_tokens={},
    )


def find_projection_initializers(model: onnx.ModelProto) -> tuple[str, str, np.ndarray, np.ndarray]:
    weight_matches: list[tuple[str, np.ndarray]] = []
    bias_matches: list[tuple[str, np.ndarray]] = []
    for initializer in model.graph.initializer:
        array = numpy_helper.to_array(initializer)
        if tuple(array.shape) == (EXPECTED_VOCAB_SIZE, EXPECTED_ENCODER_DIM) and array.dtype in (
            np.float32,
            np.float64,
        ):
            weight_matches.append((initializer.name, np.asarray(array, dtype=np.float32)))
        if tuple(array.shape) == (EXPECTED_VOCAB_SIZE,) and array.dtype in (np.float32, np.float64):
            bias_matches.append((initializer.name, np.asarray(array, dtype=np.float32)))
    if len(weight_matches) != 1 or len(bias_matches) != 1:
        raise RuntimeError(
            "Cannot uniquely identify CTC projection initializers: "
            f"weights={[name for name, _ in weight_matches]}, "
            f"biases={[name for name, _ in bias_matches]}"
        )
    return weight_matches[0][0], bias_matches[0][0], weight_matches[0][1], bias_matches[0][1]


def replace_initializer(model: onnx.ModelProto, name: str, array: np.ndarray) -> None:
    for index, initializer in enumerate(model.graph.initializer):
        if initializer.name == name:
            model.graph.initializer[index].CopyFrom(numpy_helper.from_array(array, name=name))
            return
    raise RuntimeError(f"Initializer not found: {name}")


def add_projection_rowwise_int8(fp32_path: Path, output_path: Path) -> dict[str, Any]:
    model = onnx.load(str(fp32_path))
    weight_name, bias_name, weight, _bias = find_projection_initializers(model)

    abs_max = np.max(np.abs(weight), axis=1).astype(np.float32)
    scales = np.where(abs_max > 0.0, abs_max / np.float32(127.0), np.float32(1.0)).astype(np.float32)
    quantized = np.clip(np.rint(weight / scales[:, None]), -127, 127).astype(np.int8)

    projection_gathers = [
        (index, node)
        for index, node in enumerate(model.graph.node)
        if node.op_type == "Gather" and len(node.input) >= 2 and node.input[0] == weight_name
    ]
    if len(projection_gathers) != 1:
        raise RuntimeError(
            "Expected one projection-weight Gather, got "
            f"{[(node.name, list(node.input)) for _, node in projection_gathers]}"
        )
    gather_index, weight_gather = projection_gathers[0]
    if len(weight_gather.output) != 1:
        raise RuntimeError("Projection weight Gather must have exactly one output")
    candidate_input = weight_gather.input[1]
    gathered_int8 = weight_gather.output[0]

    scale_name = weight_name + ".voica_row_scale"
    axes_name = weight_name + ".voica_unsqueeze_axes"
    gathered_scale = gathered_int8 + ".voica_scale"
    gathered_cast = gathered_int8 + ".voica_float"
    gathered_scale_2d = gathered_int8 + ".voica_scale_2d"
    dequantized_weight = gathered_int8 + ".voica_dequantized"

    replace_initializer(model, weight_name, quantized)
    model.graph.initializer.extend(
        [
            numpy_helper.from_array(scales, name=scale_name),
            numpy_helper.from_array(np.asarray([1], dtype=np.int64), name=axes_name),
        ]
    )

    original_nodes = list(model.graph.node)
    for index, node in enumerate(original_nodes):
        if index == gather_index:
            continue
        for input_index, value in enumerate(node.input):
            if value == gathered_int8:
                node.input[input_index] = dequantized_weight

    scale_gather = helper.make_node(
        "Gather",
        [scale_name, candidate_input],
        [gathered_scale],
        axis=0,
        name="VoicaCtcProjectionScaleGather",
    )
    cast = helper.make_node(
        "Cast",
        [gathered_int8],
        [gathered_cast],
        to=TensorProto.FLOAT,
        name="VoicaCtcProjectionWeightCast",
    )
    unsqueeze = helper.make_node(
        "Unsqueeze",
        [gathered_scale, axes_name],
        [gathered_scale_2d],
        name="VoicaCtcProjectionScaleUnsqueeze",
    )
    mul = helper.make_node(
        "Mul",
        [gathered_cast, gathered_scale_2d],
        [dequantized_weight],
        name="VoicaCtcProjectionDequantize",
    )

    del model.graph.node[:]
    model.graph.node.extend(
        [
            *original_nodes[: gather_index + 1],
            scale_gather,
            cast,
            unsqueeze,
            mul,
            *original_nodes[gather_index + 1 :],
        ]
    )
    onnx.checker.check_model(model)
    onnx.save(model, str(output_path))

    reconstructed = quantized.astype(np.float32) * scales[:, None]
    projection_error = np.abs(weight - reconstructed)
    return {
        "weight_initializer": weight_name,
        "bias_initializer": bias_name,
        "scheme": "symmetric per-row int8, scale=max_abs/127, zero_point=0",
        "projection_weight_shape": list(weight.shape),
        "projection_weight_fp32_bytes": int(weight.nbytes),
        "projection_weight_int8_bytes": int(quantized.nbytes),
        "projection_scale_bytes": int(scales.nbytes),
        "projection_weight_max_abs_error": float(np.max(projection_error)),
        "projection_weight_mean_abs_error": float(np.mean(projection_error)),
        "projection_weight_p99_abs_error": float(np.quantile(projection_error, 0.99)),
    }


def dynamic_quantize_decoder(hybrid_path: Path, output_path: Path) -> None:
    quantize_dynamic(
        model_input=str(hybrid_path),
        model_output=str(output_path),
        op_types_to_quantize=["MatMul", "Gemm"],
        per_channel=True,
        reduce_range=False,
        weight_type=QuantType.QInt8,
        use_external_data_format=False,
        extra_options={"MatMulConstBOnly": True},
    )
    model = onnx.load(str(output_path))
    onnx.checker.check_model(model)


def make_ctc_session(path: Path) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    inputs = [value.name for value in session.get_inputs()]
    outputs = [value.name for value in session.get_outputs()]
    if inputs != ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"]:
        raise RuntimeError(f"CTC scorer input ABI drift: {inputs}")
    if outputs != ["candidate_logits"]:
        raise RuntimeError(f"CTC scorer output ABI drift: {outputs}")
    return session


def make_encoder_session(path: Path) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    if [value.name for value in session.get_inputs()] != ["x"]:
        raise RuntimeError(f"Dual encoder input ABI drift: {[value.name for value in session.get_inputs()]}")
    outputs = [value.name for value in session.get_outputs()]
    expected = ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"]
    if outputs != expected:
        raise RuntimeError(f"Dual encoder output ABI drift: {outputs} != {expected}")
    return session


def graph_quantization_inventory(path: Path) -> dict[str, Any]:
    model = onnx.load(str(path))
    int8_initializers: list[dict[str, Any]] = []
    fp32_projection: list[str] = []
    for initializer in model.graph.initializer:
        array = numpy_helper.to_array(initializer)
        if array.dtype == np.int8:
            int8_initializers.append({"name": initializer.name, "shape": list(array.shape)})
        if tuple(array.shape) == (EXPECTED_VOCAB_SIZE, EXPECTED_ENCODER_DIM) and array.dtype == np.float32:
            fp32_projection.append(initializer.name)
    op_counts: dict[str, int] = {}
    for node in model.graph.node:
        op_counts[node.op_type] = op_counts.get(node.op_type, 0) + 1
    projection_int8 = [
        item for item in int8_initializers if item["shape"] == [EXPECTED_VOCAB_SIZE, EXPECTED_ENCODER_DIM]
    ]
    if len(projection_int8) != 1:
        raise RuntimeError(f"Expected one INT8 CTC projection weight, got {projection_int8}")
    if fp32_projection:
        raise RuntimeError(f"INT8 scorer still contains FP32 full projection: {fp32_projection}")
    if len(int8_initializers) < 2:
        raise RuntimeError("INT8 scorer did not quantize any decoder weights")
    if op_counts.get("MatMulInteger", 0) + op_counts.get("DynamicQuantizeLinear", 0) <= 0:
        raise RuntimeError(f"INT8 scorer lacks dynamic quantized decoder ops: {op_counts}")
    return {
        "int8_initializer_count": len(int8_initializers),
        "projection_int8_initializer": projection_int8[0]["name"],
        "op_counts": op_counts,
    }


def load_generation_cases(root: Path) -> dict[str, dict[str, Any]]:
    path = root / "a3-h2c-generation-parity.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    cases = payload.get("cases")
    if not isinstance(cases, list):
        raise RuntimeError("H2c generation summary missing cases")
    result: dict[str, dict[str, Any]] = {}
    for case in cases:
        if not isinstance(case, dict):
            raise RuntimeError("H2c generation case must be an object")
        fixture = case.get("fixture")
        text = case.get("decoded_text")
        if fixture not in FIXTURES or not isinstance(text, str) or not text:
            raise RuntimeError(f"Invalid H2c generation case: {case}")
        if case.get("generated_ids_exact_equal") is not True or case.get("decoded_text_exact_equal") is not True:
            raise RuntimeError(f"H2c generation prerequisite is not exact PASS: {case}")
        result[str(fixture)] = case
    if set(result) != set(FIXTURES):
        raise RuntimeError(f"H2c generation fixtures drift: {sorted(result)}")
    return result


def verify_real_audio_case(
    *,
    fixture: str,
    text: str,
    exporter_root: Path,
    dual_encoder: ort.InferenceSession,
    fp32_scorer: ort.InferenceSession,
    int8_scorer: ort.InferenceSession,
    tokenizer: tiktoken.Encoding,
    compact_viterbi: Any,
) -> dict[str, Any]:
    if str(exporter_root) not in sys.path:
        sys.path.insert(0, str(exporter_root))
    from utils import compute_feat, load_and_resample_audio  # type: ignore

    wave = exporter_root / "examples" / fixture
    if not wave.is_file():
        raise RuntimeError(f"Pinned exporter fixture missing: {wave}")
    samples, sample_rate = load_and_resample_audio(str(wave))
    features = np.asarray(compute_feat(samples, sample_rate, 7, 6)[None, ...], dtype=np.float32)
    if features.ndim != 3 or features.shape[0] != 1 or features.shape[2] != 560 or features.shape[1] <= 0:
        raise RuntimeError(f"Invalid feature geometry for {fixture}: {features.shape}")

    _, ctc_state, ctc_lens = dual_encoder.run(None, {"x": features})
    ctc_state = np.asarray(ctc_state, dtype=np.float32)
    ctc_lens = np.asarray(ctc_lens, dtype=np.int64)
    if ctc_state.ndim != 3 or ctc_state.shape[0] != 1 or ctc_state.shape[2] != EXPECTED_ENCODER_DIM:
        raise RuntimeError(f"Invalid CTC encoder state for {fixture}: {ctc_state.shape}")
    if ctc_lens.shape != (1,) or int(ctc_lens[0]) != int(ctc_state.shape[1]):
        raise RuntimeError(f"Exact CTC length mismatch for {fixture}: lens={ctc_lens.tolist()} state={ctc_state.shape}")

    targets = tokenizer.encode_ordinary(text)
    if not targets or EXPECTED_BLANK_ID in targets or max(targets) >= EXPECTED_VOCAB_SIZE:
        raise RuntimeError(f"Invalid CTC target ids for {fixture}: {targets}")
    if len(targets) + 1 > int(ctc_lens[0]):
        raise RuntimeError(f"CTC target/frame geometry impossible for {fixture}: targets={len(targets)} frames={int(ctc_lens[0])}")
    candidates = np.asarray([EXPECTED_BLANK_ID, *targets], dtype=np.int64)
    feeds = {
        "ctc_encoder_out": ctc_state,
        "ctc_encoder_out_lens": ctc_lens,
        "candidate_ids": candidates,
    }
    fp32 = np.asarray(fp32_scorer.run(["candidate_logits"], feeds)[0], dtype=np.float32)
    int8 = np.asarray(int8_scorer.run(["candidate_logits"], feeds)[0], dtype=np.float32)
    if fp32.shape != int8.shape or fp32.shape != (1, int(ctc_lens[0]), len(candidates)):
        raise RuntimeError(f"CTC scorer output shape drift for {fixture}: fp32={fp32.shape} int8={int8.shape}")
    if not np.all(np.isfinite(fp32)) or not np.all(np.isfinite(int8)):
        raise RuntimeError(f"Non-finite CTC logits for {fixture}")

    diff = np.abs(fp32.astype(np.float64) - int8.astype(np.float64))
    fp32_path = compact_viterbi(fp32[0], targets)
    int8_path = compact_viterbi(int8[0], targets)
    if fp32_path != int8_path:
        first = next((index for index, pair in enumerate(zip(fp32_path, int8_path)) if pair[0] != pair[1]), None)
        raise RuntimeError(
            f"INT8 CTC alignment path changed for {fixture}: first_diff_frame={first}, fp32={fp32_path}, int8={int8_path}"
        )

    return {
        "fixture": fixture,
        "wave_sha256": sha256_file(wave),
        "decoded_text": text,
        "sample_rate": int(sample_rate),
        "sample_count": int(len(samples)),
        "feature_frames": int(features.shape[1]),
        "ctc_frames": int(ctc_lens[0]),
        "target_count": len(targets),
        "target_ids": targets,
        "candidate_count": len(candidates),
        "logit_max_abs_error": float(np.max(diff)),
        "logit_mean_abs_error": float(np.mean(diff)),
        "logit_p99_abs_error": float(np.quantile(diff, 0.99)),
        "fp32_logit_abs_max": float(np.max(np.abs(fp32))),
        "alignment_path_exact_equal": True,
        "alignment_frames": len(fp32_path),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--h2a-root", type=Path, required=True)
    parser.add_argument("--h2c-root", type=Path, required=True)
    parser.add_argument("--generation-root", type=Path, required=True)
    parser.add_argument("--exporter-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    h2a_root = args.h2a_root.resolve()
    h2c_root = args.h2c_root.resolve()
    generation_root = args.generation_root.resolve()
    exporter_root = args.exporter_root.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    h2a = load_h2a_module()
    fp32_path = h2a_root / "ctc_compact.fp32.onnx"
    dual_encoder_path = h2c_root / "encoder_adaptor.dual.int8.onnx"
    tokenizer_path = locate_unique(h2a_root, "multilingual.tiktoken")
    for required in (fp32_path, dual_encoder_path, tokenizer_path):
        if not required.is_file():
            raise RuntimeError(f"Missing H2d prerequisite: {required}")
    fp32_sha = require_sha(fp32_path, EXPECTED_FP32_CTC_SHA256, "H2a FP32 compact CTC")
    dual_sha = require_sha(dual_encoder_path, EXPECTED_DUAL_ENCODER_SHA256, "H2c dual encoder")
    tokenizer_sha = require_sha(tokenizer_path, EXPECTED_TOKENIZER_SHA256, "SenseVoice tokenizer")

    hybrid_path = out / "ctc_compact.projection-int8.onnx"
    projection_stats = add_projection_rowwise_int8(fp32_path, hybrid_path)
    int8_path = out / "ctc_compact.int8.onnx"
    dynamic_quantize_decoder(hybrid_path, int8_path)
    inventory = graph_quantization_inventory(int8_path)

    fp32_size = fp32_path.stat().st_size
    int8_size = int8_path.stat().st_size
    size_ratio = int8_size / fp32_size
    if size_ratio >= 0.55:
        raise RuntimeError(f"INT8 CTC scorer size reduction insufficient: fp32={fp32_size} int8={int8_size} ratio={size_ratio:.4f}")

    fp32_session = make_ctc_session(fp32_path)
    int8_session = make_ctc_session(int8_path)
    dual_encoder = make_encoder_session(dual_encoder_path)
    tokenizer = build_tokenizer(tokenizer_path)
    generation_cases = load_generation_cases(generation_root)

    cases = [
        verify_real_audio_case(
            fixture=fixture,
            text=str(generation_cases[fixture]["decoded_text"]),
            exporter_root=exporter_root,
            dual_encoder=dual_encoder,
            fp32_scorer=fp32_session,
            int8_scorer=int8_session,
            tokenizer=tokenizer,
            compact_viterbi=h2a.compact_viterbi,
        )
        for fixture in FIXTURES
    ]

    summary = {
        "fp32_ctc_sha256": fp32_sha,
        "fp32_ctc_size": fp32_size,
        "dual_encoder_sha256": dual_sha,
        "tokenizer_sha256": tokenizer_sha,
        "int8_ctc_sha256": sha256_file(int8_path),
        "int8_ctc_size": int8_size,
        "size_ratio_int8_over_fp32": size_ratio,
        "size_reduction_percent": (1.0 - size_ratio) * 100.0,
        "quantization": {
            "projection": projection_stats,
            "decoder": {
                "method": "onnxruntime.quantize_dynamic",
                "weight_type": "QInt8",
                "per_channel": True,
                "op_types": ["MatMul", "Gemm"],
                "matmul_const_b_only": True,
            },
            "inventory": inventory,
        },
        "onnx_abi": {
            "inputs": ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
            "output": "candidate_logits",
            "input_encoder_type": fp32_session.get_inputs()[0].type,
            "input_lens_type": fp32_session.get_inputs()[1].type,
            "input_ids_type": fp32_session.get_inputs()[2].type,
            "output_type": int8_session.get_outputs()[0].type,
            "encoder_dim": EXPECTED_ENCODER_DIM,
            "vocab_size": EXPECTED_VOCAB_SIZE,
            "blank_id": EXPECTED_BLANK_ID,
        },
        "real_audio_cases": cases,
        "acceptance": {
            "size_ratio_lt": 0.55,
            "all_logits_finite": True,
            "alignment_path_exact_equal_required": True,
        },
        "status": "H2d INT8 compact CTC scorer real-audio parity PASS",
    }
    summary_path = out / "a3-h2d-int8-ctc-parity.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"INT8_CTC_PATH={int8_path}")


if __name__ == "__main__":
    main()
