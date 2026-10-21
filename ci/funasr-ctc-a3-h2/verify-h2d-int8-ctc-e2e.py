#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch
import yaml
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import QuantType, quantize_dynamic

EXPECTED_ENCODER_DIM = 512
EXPECTED_VOCAB_SIZE = 60_515
EXPECTED_BLANK_ID = 60_514
EXPECTED_FRAME_PERIOD_MS = 60
MAX_RELATIVE_RMSE = 0.08
MAX_LOGIT_ABS_ERROR = 2.0
MAX_SIZE_RATIO = 0.35
FIXTURES = ("zh.wav", "en.wav")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load module from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def locate_unique(root: Path, name: str) -> Path:
    matches = [path for path in root.rglob(name) if path.is_file()]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one {name} below {root}, got {matches}")
    return matches[0]


def make_session(path: Path, inputs: list[str], outputs: list[str]) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    actual_inputs = [value.name for value in session.get_inputs()]
    actual_outputs = [value.name for value in session.get_outputs()]
    if actual_inputs != inputs:
        raise RuntimeError(f"Input ABI drift for {path.name}: {actual_inputs} != {inputs}")
    if actual_outputs != outputs:
        raise RuntimeError(f"Output ABI drift for {path.name}: {actual_outputs} != {outputs}")
    return session


def gather_axis_zero(node: onnx.NodeProto) -> bool:
    attrs = {attr.name: helper.get_attribute_value(attr) for attr in node.attribute}
    return int(attrs.get("axis", 0)) == 0


def quantize_projection_weight_only(source: Path, target: Path) -> dict[str, Any]:
    model = onnx.load(str(source))
    graph = model.graph
    initializers = list(graph.initializer)

    weight_matches = [
        init for init in initializers
        if list(init.dims) == [EXPECTED_VOCAB_SIZE, EXPECTED_ENCODER_DIM]
        and init.data_type == TensorProto.FLOAT
    ]
    bias_matches = [
        init for init in initializers
        if list(init.dims) == [EXPECTED_VOCAB_SIZE]
        and init.data_type == TensorProto.FLOAT
    ]
    if len(weight_matches) != 1 or len(bias_matches) != 1:
        raise RuntimeError(
            "Projection initializer geometry drift: "
            f"weights={[init.name for init in weight_matches]}, "
            f"biases={[init.name for init in bias_matches]}"
        )
    weight_init = weight_matches[0]
    bias_init = bias_matches[0]

    weight_gathers = [
        node for node in graph.node
        if node.op_type == "Gather"
        and len(node.input) >= 2
        and node.input[0] == weight_init.name
        and node.input[1] == "candidate_ids"
        and gather_axis_zero(node)
    ]
    bias_gathers = [
        node for node in graph.node
        if node.op_type == "Gather"
        and len(node.input) >= 2
        and node.input[0] == bias_init.name
        and node.input[1] == "candidate_ids"
        and gather_axis_zero(node)
    ]
    if len(weight_gathers) != 1 or len(bias_gathers) != 1:
        raise RuntimeError(
            "Dynamic candidate projection structure drift: "
            f"weight_gathers={len(weight_gathers)}, bias_gathers={len(bias_gathers)}"
        )

    weight = numpy_helper.to_array(weight_init).astype(np.float32, copy=False)
    row_max = np.max(np.abs(weight), axis=1)
    scales = np.where(row_max > 0.0, row_max / 127.0, 1.0).astype(np.float32)
    quantized = np.clip(np.rint(weight / scales[:, None]), -127, 127).astype(np.int8)
    reconstructed = quantized.astype(np.float32) * scales[:, None]
    projection_max_abs = float(np.max(np.abs(weight - reconstructed)))
    projection_rmse = float(np.sqrt(np.mean(np.square(weight - reconstructed), dtype=np.float64)))

    q_name = weight_init.name + "__voica_int8"
    scale_name = weight_init.name + "__voica_scale"
    axes_name = weight_init.name + "__voica_scale_axes"
    q_init = numpy_helper.from_array(quantized, name=q_name)
    scale_init = numpy_helper.from_array(scales, name=scale_name)
    axes_init = numpy_helper.from_array(np.asarray([1], dtype=np.int64), name=axes_name)

    weight_gather = weight_gathers[0]
    original_output = weight_gather.output[0]
    q_gather_output = original_output + "__voica_int8"
    cast_output = original_output + "__voica_float"
    scale_gather_output = original_output + "__voica_scale"
    scale_unsqueezed = original_output + "__voica_scale_unsqueezed"
    weight_gather.input[0] = q_name
    weight_gather.output[0] = q_gather_output

    inserted = [
        helper.make_node(
            "Cast",
            [q_gather_output],
            [cast_output],
            name="VoicaProjectionInt8Cast",
            to=TensorProto.FLOAT,
        ),
        helper.make_node(
            "Gather",
            [scale_name, "candidate_ids"],
            [scale_gather_output],
            name="VoicaProjectionScaleGather",
            axis=0,
        ),
        helper.make_node(
            "Unsqueeze",
            [scale_gather_output, axes_name],
            [scale_unsqueezed],
            name="VoicaProjectionScaleUnsqueeze",
        ),
        helper.make_node(
            "Mul",
            [cast_output, scale_unsqueezed],
            [original_output],
            name="VoicaProjectionDequantizeSelectedRows",
        ),
    ]

    new_nodes: list[onnx.NodeProto] = []
    for node in graph.node:
        new_nodes.append(node)
        if node is weight_gather:
            new_nodes.extend(inserted)
    del graph.node[:]
    graph.node.extend(new_nodes)

    kept_initializers = [init for init in initializers if init.name != weight_init.name]
    del graph.initializer[:]
    graph.initializer.extend([*kept_initializers, q_init, scale_init, axes_init])

    onnx.checker.check_model(model)
    onnx.save_model(model, str(target), save_as_external_data=False)
    if not target.is_file() or target.stat().st_size <= 0:
        raise RuntimeError("Projection INT8 rewrite did not produce an ONNX file")

    return {
        "float_weight_name": weight_init.name,
        "bias_name": bias_init.name,
        "quantized_weight_name": q_name,
        "scale_name": scale_name,
        "projection_weight_max_abs_error": projection_max_abs,
        "projection_weight_rmse": projection_rmse,
    }


def quantize_decoder(source: Path, target: Path) -> None:
    quantize_dynamic(
        model_input=str(source),
        model_output=str(target),
        op_types_to_quantize=["MatMul"],
        per_channel=True,
        reduce_range=False,
        weight_type=QuantType.QInt8,
        use_external_data_format=False,
    )
    graph = onnx.load(str(target))
    onnx.checker.check_model(graph)


def audit_quantized_graph(path: Path, projection_info: dict[str, Any], fp32_size: int) -> dict[str, Any]:
    graph = onnx.load(str(path))
    initializer_by_name = {init.name: init for init in graph.graph.initializer}
    q_name = str(projection_info["quantized_weight_name"])
    if q_name not in initializer_by_name:
        raise RuntimeError(f"Quantized projection initializer missing after decoder quantization: {q_name}")
    q_init = initializer_by_name[q_name]
    if q_init.data_type != TensorProto.INT8 or list(q_init.dims) != [EXPECTED_VOCAB_SIZE, EXPECTED_ENCODER_DIM]:
        raise RuntimeError(f"Quantized projection initializer ABI drift: type={q_init.data_type}, dims={list(q_init.dims)}")
    if str(projection_info["float_weight_name"]) in initializer_by_name:
        raise RuntimeError("FP32 full-vocabulary projection weight still exists in INT8 scorer")

    op_counts: dict[str, int] = {}
    for node in graph.graph.node:
        op_counts[node.op_type] = op_counts.get(node.op_type, 0) + 1
    quantized_matmuls = op_counts.get("MatMulInteger", 0) + op_counts.get("QLinearMatMul", 0)
    if quantized_matmuls <= 0:
        raise RuntimeError(f"Decoder quantization produced no integer MatMul nodes: {op_counts}")

    size = path.stat().st_size
    ratio = size / fp32_size
    if ratio > MAX_SIZE_RATIO:
        raise RuntimeError(f"INT8 scorer size ratio too high: {ratio:.4f} > {MAX_SIZE_RATIO}")
    return {
        "int8_size": size,
        "fp32_size": fp32_size,
        "size_ratio": ratio,
        "quantized_matmul_count": quantized_matmuls,
        "op_counts": op_counts,
    }


def viterbi_state_path(raw_logits: np.ndarray, targets: list[int]) -> list[int]:
    if raw_logits.ndim != 2 or raw_logits.shape[1] != len(targets) + 1:
        raise RuntimeError(f"Compact score shape mismatch: {raw_logits.shape}, targets={len(targets)}")
    t_count = int(raw_logits.shape[0])
    states = 2 * len(targets) + 1
    if t_count <= 0 or states <= 1:
        raise RuntimeError("CTC Viterbi requires non-empty frames and targets")
    dp = np.full(states, -np.inf, dtype=np.float64)
    back = np.full((t_count, states), -1, dtype=np.int32)

    def emission(frame: int, state: int) -> float:
        return float(raw_logits[frame, 0 if state % 2 == 0 else state // 2 + 1])

    dp[0] = emission(0, 0)
    dp[1] = emission(0, 1)
    for frame in range(1, t_count):
        nxt = np.full(states, -np.inf, dtype=np.float64)
        for state in range(states):
            predecessors = [state]
            if state >= 1:
                predecessors.append(state - 1)
            if state >= 2 and state % 2 == 1:
                if targets[state // 2] != targets[state // 2 - 1]:
                    predecessors.append(state - 2)
            best = max(predecessors, key=lambda index: dp[index])
            if np.isfinite(dp[best]):
                nxt[state] = dp[best] + emission(frame, state)
                back[frame, state] = best
        dp = nxt

    final_candidates = [states - 1, states - 2]
    state = max(final_candidates, key=lambda index: dp[index])
    if not np.isfinite(dp[state]):
        raise RuntimeError("CTC Viterbi has no finite final state")
    path = [state]
    for frame in range(t_count - 1, 0, -1):
        state = int(back[frame, state])
        if state < 0:
            raise RuntimeError("CTC Viterbi backtrace is broken")
        path.append(state)
    path.reverse()
    return path


def state_path_to_labels(path: list[int], targets: list[int]) -> list[int]:
    return [EXPECTED_BLANK_ID if state % 2 == 0 else targets[state // 2] for state in path]


def target_spans(path: list[int], targets: list[int]) -> list[dict[str, int]]:
    spans: list[dict[str, int]] = []
    for index, token_id in enumerate(targets):
        state = 2 * index + 1
        frames = [frame for frame, value in enumerate(path) if value == state]
        if not frames:
            raise RuntimeError(f"Target position {index} has no aligned frame")
        spans.append({
            "target_index": index,
            "token_id": int(token_id),
            "start_frame": min(frames),
            "end_frame_exclusive": max(frames) + 1,
            "start_ms": min(frames) * EXPECTED_FRAME_PERIOD_MS,
            "end_ms": (max(frames) + 1) * EXPECTED_FRAME_PERIOD_MS,
        })
    return spans


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--h2a-root", type=Path, required=True)
    parser.add_argument("--h2c-root", type=Path, required=True)
    parser.add_argument("--h2c-generation-root", type=Path, required=True)
    parser.add_argument("--exporter-root", type=Path, required=True)
    parser.add_argument("--funasr-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    h2a_root = args.h2a_root.resolve()
    h2c_root = args.h2c_root.resolve()
    generation_root = args.h2c_generation_root.resolve()
    exporter_root = args.exporter_root.resolve()
    funasr_root = args.funasr_root.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    fp32_path = h2a_root / "ctc_compact.fp32.onnx"
    dual_encoder_path = h2c_root / "encoder_adaptor.dual.int8.onnx"
    generation_summary_path = generation_root / "a3-h2c-generation-parity.json"
    for required in (fp32_path, dual_encoder_path, generation_summary_path):
        if not required.is_file():
            raise RuntimeError(f"Missing H2d prerequisite: {required}")

    h2a = load_module(Path(__file__).with_name("verify-h2a-ctc-export.py"), "voica_h2a_reference")
    exporter_utils = load_module(exporter_root / "utils.py", "voica_h2_exporter_utils")

    projection_path = out / "ctc_compact.projection-int8.onnx"
    int8_path = out / "ctc_compact.int8.onnx"
    projection_info = quantize_projection_weight_only(fp32_path, projection_path)
    quantize_decoder(projection_path, int8_path)
    graph_audit = audit_quantized_graph(int8_path, projection_info, fp32_path.stat().st_size)

    fp32_session = make_session(
        fp32_path,
        ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        ["candidate_logits"],
    )
    int8_session = make_session(
        int8_path,
        ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        ["candidate_logits"],
    )
    encoder_session = make_session(
        dual_encoder_path,
        ["x"],
        ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
    )

    tokenizer_path = locate_unique(h2a_root, "multilingual.tiktoken")
    config_path = locate_unique(h2a_root, "config.yaml")
    model_path = locate_unique(h2a_root, "model.pt")
    tokenizer = h2a.build_tokenizer(tokenizer_path)
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise RuntimeError("config.yaml root must be a mapping")
    Transformer, CTC = h2a.import_official_modules(funasr_root)
    checkpoint = torch.load(model_path, map_location="cpu", weights_only=True, mmap=True)
    state = h2a.normalize_checkpoint_state(checkpoint)
    decoder, ctc = h2a.build_official_ctc(state, config, Transformer, CTC)

    generation_summary = json.loads(generation_summary_path.read_text(encoding="utf-8"))
    generation_cases = {case["fixture"]: case for case in generation_summary.get("cases", [])}
    if set(generation_cases) != set(FIXTURES):
        raise RuntimeError(f"Unexpected H2c generation fixtures: {sorted(generation_cases)}")

    cases: list[dict[str, Any]] = []
    for fixture in FIXTURES:
        wave = exporter_root / "examples" / fixture
        if not wave.is_file():
            raise RuntimeError(f"Pinned exporter fixture missing: {wave}")
        samples, sample_rate = exporter_utils.load_and_resample_audio(str(wave))
        features = exporter_utils.compute_feat(samples, sample_rate, 7, 6)[None, ...]
        features = np.asarray(features, dtype=np.float32)
        _llm_out, ctc_state, ctc_lens = encoder_session.run(None, {"x": features})
        if ctc_state.shape != (1, features.shape[1], EXPECTED_ENCODER_DIM):
            raise RuntimeError(f"CTC state geometry drift for {fixture}: {ctc_state.shape}")
        if ctc_lens.dtype != np.int64 or ctc_lens.tolist() != [features.shape[1]]:
            raise RuntimeError(f"CTC length drift for {fixture}: {ctc_lens!r}")

        text = str(generation_cases[fixture]["decoded_text"])
        targets = list(tokenizer.encode_ordinary(text))
        if not targets or EXPECTED_BLANK_ID in targets or max(targets) >= EXPECTED_VOCAB_SIZE:
            raise RuntimeError(f"Invalid CTC targets for {fixture}: text={text!r}, ids={targets}")
        candidates = np.asarray([EXPECTED_BLANK_ID, *targets], dtype=np.int64)

        state_tensor = torch.from_numpy(np.asarray(ctc_state, dtype=np.float32))
        lens_tensor = torch.from_numpy(np.asarray(ctc_lens, dtype=np.int64))
        candidate_tensor = torch.from_numpy(candidates)
        with torch.inference_mode():
            decoder_out, decoder_lens = decoder(state_tensor, lens_tensor)
            if decoder_lens.tolist() != lens_tensor.tolist():
                raise RuntimeError(f"Official decoder length drift for {fixture}: {decoder_lens.tolist()}")
            full_raw = ctc.ctc_lo(decoder_out)
            selected_official = torch.index_select(full_raw, 2, candidate_tensor).cpu().numpy()
            full_log_probs = torch.log_softmax(full_raw, dim=-1)

        feeds = {
            "ctc_encoder_out": np.asarray(ctc_state, dtype=np.float32),
            "ctc_encoder_out_lens": np.asarray(ctc_lens, dtype=np.int64),
            "candidate_ids": candidates,
        }
        fp32_logits = fp32_session.run(["candidate_logits"], feeds)[0]
        int8_logits = int8_session.run(["candidate_logits"], feeds)[0]
        if not np.all(np.isfinite(int8_logits)):
            raise RuntimeError(f"INT8 scorer emitted non-finite logits for {fixture}")
        if not np.allclose(selected_official, fp32_logits, rtol=1e-4, atol=1e-4):
            max_abs = float(np.max(np.abs(selected_official - fp32_logits)))
            raise RuntimeError(f"Official full-vocab vs FP32 compact drift for {fixture}: max_abs={max_abs}")

        official_labels = h2a.official_alignment(full_log_probs, targets)
        fp32_path_states = viterbi_state_path(fp32_logits[0], targets)
        int8_path_states = viterbi_state_path(int8_logits[0], targets)
        fp32_labels = state_path_to_labels(fp32_path_states, targets)
        int8_labels = state_path_to_labels(int8_path_states, targets)
        if official_labels != fp32_labels:
            raise RuntimeError(f"Official vs FP32 compact alignment path drift for {fixture}")
        if fp32_path_states != int8_path_states:
            first = next(
                index for index, pair in enumerate(zip(fp32_path_states, int8_path_states))
                if pair[0] != pair[1]
            )
            raise RuntimeError(
                f"FP32 vs INT8 alignment path drift for {fixture}: first_frame={first}, "
                f"fp32_state={fp32_path_states[first]}, int8_state={int8_path_states[first]}"
            )
        if fp32_labels != int8_labels:
            raise RuntimeError(f"FP32 vs INT8 alignment labels drift for {fixture}")

        delta = int8_logits.astype(np.float64) - fp32_logits.astype(np.float64)
        max_abs = float(np.max(np.abs(delta)))
        rmse = float(math.sqrt(float(np.mean(np.square(delta)))))
        fp_rms = float(math.sqrt(float(np.mean(np.square(fp32_logits.astype(np.float64))))))
        relative_rmse = rmse / max(fp_rms, 1e-9)
        if relative_rmse > MAX_RELATIVE_RMSE:
            raise RuntimeError(
                f"INT8 relative RMSE too high for {fixture}: {relative_rmse:.6f} > {MAX_RELATIVE_RMSE}"
            )
        if max_abs > MAX_LOGIT_ABS_ERROR:
            raise RuntimeError(f"INT8 max logit error too high for {fixture}: {max_abs:.6f} > {MAX_LOGIT_ABS_ERROR}")

        spans = target_spans(int8_path_states, targets)
        cases.append({
            "fixture": fixture,
            "wave_sha256": sha256_file(wave),
            "sample_rate": int(sample_rate),
            "feature_frames": int(features.shape[1]),
            "final_text": text,
            "target_ids": targets,
            "target_count": len(targets),
            "official_vs_fp32_max_abs": float(np.max(np.abs(selected_official - fp32_logits))),
            "int8_vs_fp32_max_abs": max_abs,
            "int8_vs_fp32_rmse": rmse,
            "int8_vs_fp32_relative_rmse": relative_rmse,
            "official_fp32_alignment_exact": True,
            "fp32_int8_alignment_exact": True,
            "target_spans": spans,
        })

    summary = {
        "source_fp32_sha256": sha256_file(fp32_path),
        "source_fp32_size": fp32_path.stat().st_size,
        "projection_int8_intermediate_sha256": sha256_file(projection_path),
        "int8_sha256": sha256_file(int8_path),
        "int8_size": int8_path.stat().st_size,
        "frame_period_ms": EXPECTED_FRAME_PERIOD_MS,
        "vocab_size": EXPECTED_VOCAB_SIZE,
        "blank_id": EXPECTED_BLANK_ID,
        "projection_quantization": projection_info,
        "graph_audit": graph_audit,
        "acceptance_thresholds": {
            "max_size_ratio": MAX_SIZE_RATIO,
            "max_relative_rmse": MAX_RELATIVE_RMSE,
            "max_logit_abs_error": MAX_LOGIT_ABS_ERROR,
            "alignment": "exact state-path equality on both real-audio fixtures",
        },
        "cases": cases,
        "status": "H2d production INT8 compact CTC end-to-end parity PASS",
    }
    summary_path = out / "a3-h2d-int8-ctc-e2e.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"INT8_ONNX_PATH={int8_path}")


if __name__ == "__main__":
    main()
