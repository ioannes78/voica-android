#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from collections import Counter
from pathlib import Path
from typing import Any

import numpy as np
import torch
import yaml


FIXTURES = ("zh.wav", "en.wav")


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load module from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def locate_unique(root: Path, name: str) -> Path:
    matches = [path for path in root.rglob(name) if path.is_file()]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one {name} below {root}, got {matches}")
    return matches[0]


def score_error(reference: np.ndarray, candidate: np.ndarray) -> dict[str, float]:
    if reference.shape != candidate.shape:
        raise RuntimeError(f"Logit shape drift: {reference.shape} != {candidate.shape}")
    delta = candidate.astype(np.float64) - reference.astype(np.float64)
    max_abs = float(np.max(np.abs(delta)))
    rmse = float(math.sqrt(float(np.mean(np.square(delta)))))
    ref_rms = float(math.sqrt(float(np.mean(np.square(reference.astype(np.float64))))))
    return {
        "max_abs": max_abs,
        "rmse": rmse,
        "reference_rms": ref_rms,
        "relative_rmse": rmse / max(ref_rms, 1e-9),
    }


def safe_spans(h2d, path: list[int], targets: list[int]) -> tuple[list[dict[str, int]] | None, str | None]:
    try:
        return h2d.target_spans(path, targets), None
    except Exception as exc:  # diagnostic must survive a malformed candidate path
        return None, f"{type(exc).__name__}: {exc}"


def compare_alignment(h2d, reference: list[int], candidate: list[int], targets: list[int]) -> dict[str, Any]:
    if len(reference) != len(candidate):
        return {
            "same_length": False,
            "reference_frames": len(reference),
            "candidate_frames": len(candidate),
            "state_path_exact": False,
        }

    ref_labels = h2d.state_path_to_labels(reference, targets)
    cand_labels = h2d.state_path_to_labels(candidate, targets)
    state_diffs = [i for i, (a, b) in enumerate(zip(reference, candidate)) if a != b]
    label_diffs = [i for i, (a, b) in enumerate(zip(ref_labels, cand_labels)) if a != b]
    pair_counts = Counter((reference[i], candidate[i]) for i in state_diffs)

    ref_spans, ref_span_error = safe_spans(h2d, reference, targets)
    cand_spans, cand_span_error = safe_spans(h2d, candidate, targets)
    span_deltas: list[dict[str, Any]] = []
    max_start_delta = 0
    max_end_delta = 0
    changed_targets = 0
    if ref_spans is not None and cand_spans is not None:
        if len(ref_spans) != len(cand_spans):
            raise RuntimeError("Target span count changed despite identical target sequence")
        for ref_span, cand_span in zip(ref_spans, cand_spans):
            start_delta = int(cand_span["start_frame"] - ref_span["start_frame"])
            end_delta = int(cand_span["end_frame_exclusive"] - ref_span["end_frame_exclusive"])
            if start_delta != 0 or end_delta != 0:
                changed_targets += 1
            max_start_delta = max(max_start_delta, abs(start_delta))
            max_end_delta = max(max_end_delta, abs(end_delta))
            span_deltas.append({
                "target_index": int(ref_span["target_index"]),
                "token_id": int(ref_span["token_id"]),
                "reference_start_frame": int(ref_span["start_frame"]),
                "reference_end_frame_exclusive": int(ref_span["end_frame_exclusive"]),
                "candidate_start_frame": int(cand_span["start_frame"]),
                "candidate_end_frame_exclusive": int(cand_span["end_frame_exclusive"]),
                "start_delta_frames": start_delta,
                "end_delta_frames": end_delta,
                "start_delta_ms": start_delta * h2d.EXPECTED_FRAME_PERIOD_MS,
                "end_delta_ms": end_delta * h2d.EXPECTED_FRAME_PERIOD_MS,
            })

    boundary_only = all(
        abs(reference[i] - candidate[i]) == 1
        and ((reference[i] % 2) != (candidate[i] % 2))
        for i in state_diffs
    )
    first_diffs = [
        {
            "frame": i,
            "reference_state": int(reference[i]),
            "candidate_state": int(candidate[i]),
            "reference_label": int(ref_labels[i]),
            "candidate_label": int(cand_labels[i]),
        }
        for i in state_diffs[:32]
    ]
    return {
        "same_length": True,
        "frames": len(reference),
        "state_path_exact": not state_diffs,
        "label_path_exact": not label_diffs,
        "state_diff_frame_count": len(state_diffs),
        "state_diff_fraction": len(state_diffs) / max(len(reference), 1),
        "label_diff_frame_count": len(label_diffs),
        "label_diff_fraction": len(label_diffs) / max(len(reference), 1),
        "first_state_differences": first_diffs,
        "state_pair_counts": [
            {"reference_state": int(pair[0]), "candidate_state": int(pair[1]), "frames": count}
            for pair, count in sorted(pair_counts.items())
        ],
        "all_state_differences_are_adjacent_target_blank_boundaries": boundary_only,
        "reference_span_error": ref_span_error,
        "candidate_span_error": cand_span_error,
        "changed_target_positions": changed_targets,
        "max_abs_start_delta_frames": max_start_delta,
        "max_abs_end_delta_frames": max_end_delta,
        "max_abs_start_delta_ms": max_start_delta * h2d.EXPECTED_FRAME_PERIOD_MS,
        "max_abs_end_delta_ms": max_end_delta * h2d.EXPECTED_FRAME_PERIOD_MS,
        "target_span_deltas": span_deltas,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--h2a-root", type=Path, required=True)
    parser.add_argument("--h2c-root", type=Path, required=True)
    parser.add_argument("--h2c-generation-root", type=Path, required=True)
    parser.add_argument("--exporter-root", type=Path, required=True)
    parser.add_argument("--funasr-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    here = Path(__file__).resolve().parent
    h2d = load_module(here / "verify-h2d-int8-ctc-e2e.py", "voica_h2d")
    h2a = load_module(here / "verify-h2a-ctc-export.py", "voica_h2a")

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
            raise RuntimeError(f"Missing H2d diagnostic prerequisite: {required}")

    projection_path = out / "ctc_compact.projection-int8.onnx"
    full_int8_path = out / "ctc_compact.int8.onnx"
    projection_info = h2d.quantize_projection_weight_only(fp32_path, projection_path)
    h2d.quantize_decoder(projection_path, full_int8_path)
    graph_audit = h2d.audit_quantized_graph(full_int8_path, projection_info, fp32_path.stat().st_size)

    fp32_session = h2d.make_session(
        fp32_path,
        ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        ["candidate_logits"],
    )
    projection_session = h2d.make_session(
        projection_path,
        ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        ["candidate_logits"],
    )
    int8_session = h2d.make_session(
        full_int8_path,
        ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        ["candidate_logits"],
    )
    encoder_session = h2d.make_session(
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

    exporter_utils = load_module(exporter_root / "utils.py", "voica_h2_exporter_utils")
    generation_summary = json.loads(generation_summary_path.read_text(encoding="utf-8"))
    generation_cases = {case["fixture"]: case for case in generation_summary.get("cases", [])}
    if set(generation_cases) != set(FIXTURES):
        raise RuntimeError(f"Unexpected H2c generation fixtures: {sorted(generation_cases)}")

    cases: list[dict[str, Any]] = []
    exact_full_int8_pass = True
    numeric_full_int8_pass = True
    for fixture in FIXTURES:
        wave = exporter_root / "examples" / fixture
        samples, sample_rate = exporter_utils.load_and_resample_audio(str(wave))
        features = np.asarray(exporter_utils.compute_feat(samples, sample_rate, 7, 6)[None, ...], dtype=np.float32)
        _llm_out, ctc_state, ctc_lens = encoder_session.run(None, {"x": features})
        if ctc_state.shape != (1, features.shape[1], h2d.EXPECTED_ENCODER_DIM):
            raise RuntimeError(f"CTC state geometry drift for {fixture}: {ctc_state.shape}")
        if ctc_lens.dtype != np.int64 or ctc_lens.tolist() != [features.shape[1]]:
            raise RuntimeError(f"CTC length drift for {fixture}: {ctc_lens!r}")

        text = str(generation_cases[fixture]["decoded_text"])
        targets = list(tokenizer.encode_ordinary(text))
        if not targets or h2d.EXPECTED_BLANK_ID in targets or max(targets) >= h2d.EXPECTED_VOCAB_SIZE:
            raise RuntimeError(f"Invalid CTC targets for {fixture}: {targets}")
        candidates = np.asarray([h2d.EXPECTED_BLANK_ID, *targets], dtype=np.int64)

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
        projection_logits = projection_session.run(["candidate_logits"], feeds)[0]
        full_int8_logits = int8_session.run(["candidate_logits"], feeds)[0]
        if not np.all(np.isfinite(projection_logits)) or not np.all(np.isfinite(full_int8_logits)):
            raise RuntimeError(f"Quantized scorer emitted non-finite logits for {fixture}")

        official_fp32_error = score_error(selected_official, fp32_logits)
        if not np.allclose(selected_official, fp32_logits, rtol=1e-4, atol=1e-4):
            raise RuntimeError(
                f"Official full-vocab vs FP32 compact drift for {fixture}: "
                f"max_abs={official_fp32_error['max_abs']}"
            )

        official_labels = h2a.official_alignment(full_log_probs, targets)
        fp32_states = h2d.viterbi_state_path(fp32_logits[0], targets)
        projection_states = h2d.viterbi_state_path(projection_logits[0], targets)
        full_int8_states = h2d.viterbi_state_path(full_int8_logits[0], targets)
        fp32_labels = h2d.state_path_to_labels(fp32_states, targets)
        if official_labels != fp32_labels:
            raise RuntimeError(f"Official vs FP32 compact alignment drift for {fixture}")

        projection_error = score_error(fp32_logits, projection_logits)
        full_int8_error = score_error(fp32_logits, full_int8_logits)
        projection_alignment = compare_alignment(h2d, fp32_states, projection_states, targets)
        full_int8_alignment = compare_alignment(h2d, fp32_states, full_int8_states, targets)

        numeric_ok = (
            full_int8_error["relative_rmse"] <= h2d.MAX_RELATIVE_RMSE
            and full_int8_error["max_abs"] <= h2d.MAX_LOGIT_ABS_ERROR
        )
        numeric_full_int8_pass = numeric_full_int8_pass and numeric_ok
        exact_full_int8_pass = exact_full_int8_pass and bool(full_int8_alignment.get("state_path_exact", False))

        cases.append({
            "fixture": fixture,
            "wave_sha256": sha256_file(wave),
            "sample_rate": int(sample_rate),
            "feature_frames": int(features.shape[1]),
            "final_text": text,
            "target_ids": targets,
            "target_count": len(targets),
            "official_vs_fp32_logits": official_fp32_error,
            "projection_only_vs_fp32_logits": projection_error,
            "full_int8_vs_fp32_logits": full_int8_error,
            "projection_only_alignment": projection_alignment,
            "full_int8_alignment": full_int8_alignment,
            "numeric_thresholds_pass": numeric_ok,
        })

    summary = {
        "source_fp32_sha256": sha256_file(fp32_path),
        "source_fp32_size": fp32_path.stat().st_size,
        "projection_only_sha256": sha256_file(projection_path),
        "projection_only_size": projection_path.stat().st_size,
        "projection_only_size_ratio": projection_path.stat().st_size / fp32_path.stat().st_size,
        "full_int8_sha256": sha256_file(full_int8_path),
        "full_int8_size": full_int8_path.stat().st_size,
        "full_int8_size_ratio": full_int8_path.stat().st_size / fp32_path.stat().st_size,
        "frame_period_ms": h2d.EXPECTED_FRAME_PERIOD_MS,
        "vocab_size": h2d.EXPECTED_VOCAB_SIZE,
        "blank_id": h2d.EXPECTED_BLANK_ID,
        "projection_quantization": projection_info,
        "full_int8_graph_audit": graph_audit,
        "acceptance_thresholds_unchanged": {
            "max_size_ratio": h2d.MAX_SIZE_RATIO,
            "max_relative_rmse": h2d.MAX_RELATIVE_RMSE,
            "max_logit_abs_error": h2d.MAX_LOGIT_ABS_ERROR,
            "alignment": "exact state-path equality on both real-audio fixtures",
        },
        "cases": cases,
        "full_int8_numeric_thresholds_pass": numeric_full_int8_pass,
        "full_int8_exact_alignment_pass": exact_full_int8_pass,
        "status": (
            "H2d diagnostic PASS under original exact criteria"
            if numeric_full_int8_pass and exact_full_int8_pass
            else "H2d diagnostic FAIL under original exact criteria"
        ),
    }
    summary_path = out / "a3-h2d-int8-alignment-diagnostic.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"PROJECTION_ONLY_ONNX_PATH={projection_path}")
    print(f"FULL_INT8_ONNX_PATH={full_int8_path}")

    if not numeric_full_int8_pass:
        raise RuntimeError("H2d full INT8 scorer exceeds existing logit error thresholds; see diagnostic JSON")
    if not exact_full_int8_pass:
        raise RuntimeError("H2d full INT8 scorer violates existing exact alignment criterion; see diagnostic JSON")


if __name__ == "__main__":
    main()
