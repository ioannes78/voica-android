#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import itertools
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic


FIXTURES = ("zh.wav", "en.wav")
EXPECTED_BLOCKS = tuple(range(5))
EXPECTED_WEIGHTED_MATMULS_PER_BLOCK = 6
EXPECTED_ACTIVATION_MATMULS_PER_BLOCK = 2
BLOCK_PATTERN = re.compile(r"(?:^|[/._])blocks[./](\d+)(?:[/._]|$)")


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


def quantized_matmul_count(path: Path) -> int:
    model = onnx.load(str(path))
    return sum(node.op_type in {"MatMulInteger", "QLinearMatMul"} for node in model.graph.node)


def block_matmul_inventory(
    path: Path,
) -> tuple[dict[int, list[str]], dict[int, list[str]], list[str]]:
    model = onnx.load(str(path))
    initializer_names = {initializer.name for initializer in model.graph.initializer}
    weighted: dict[int, list[str]] = {block: [] for block in EXPECTED_BLOCKS}
    activation: dict[int, list[str]] = {block: [] for block in EXPECTED_BLOCKS}
    ungrouped: list[str] = []

    for node in model.graph.node:
        if node.op_type != "MatMul":
            continue
        name = str(node.name or "")
        match = BLOCK_PATTERN.search(name)
        if match is None:
            ungrouped.append(name)
            continue
        block = int(match.group(1))
        if block not in weighted:
            raise RuntimeError(f"Unexpected CTC decoder block in ONNX node {name!r}: {block}")
        if not name:
            raise RuntimeError("Block MatMul node has no stable ONNX node name")
        if len(node.input) >= 2 and node.input[1] in initializer_names:
            weighted[block].append(name)
        else:
            activation[block].append(name)

    for block in EXPECTED_BLOCKS:
        if len(weighted[block]) != EXPECTED_WEIGHTED_MATMULS_PER_BLOCK:
            raise RuntimeError(
                f"CTC block {block} weighted MatMul inventory drift: "
                f"expected={EXPECTED_WEIGHTED_MATMULS_PER_BLOCK}, "
                f"actual={len(weighted[block])}, nodes={weighted[block]}"
            )
        if len(activation[block]) != EXPECTED_ACTIVATION_MATMULS_PER_BLOCK:
            raise RuntimeError(
                f"CTC block {block} activation MatMul inventory drift: "
                f"expected={EXPECTED_ACTIVATION_MATMULS_PER_BLOCK}, "
                f"actual={len(activation[block])}, nodes={activation[block]}"
            )

    flattened = [name for names in weighted.values() for name in names]
    if len(flattened) != len(set(flattened)):
        raise RuntimeError("Duplicate weighted ONNX MatMul node name across block inventory")
    return weighted, activation, ungrouped


def quantize_selected_blocks(
    source: Path,
    target: Path,
    weighted_groups: dict[int, list[str]],
    blocks: tuple[int, ...],
) -> dict[str, Any]:
    node_names = [name for block in blocks for name in weighted_groups[block]]
    if not node_names:
        raise RuntimeError(f"No weighted MatMul nodes selected for blocks={blocks}")
    source_quantized = quantized_matmul_count(source)
    quantize_dynamic(
        model_input=str(source),
        model_output=str(target),
        op_types_to_quantize=["MatMul"],
        nodes_to_quantize=node_names,
        per_channel=True,
        reduce_range=False,
        weight_type=QuantType.QInt8,
        use_external_data_format=False,
    )
    model = onnx.load(str(target))
    onnx.checker.check_model(model)
    actual_total = quantized_matmul_count(target)
    actual_added = actual_total - source_quantized
    if actual_added != len(node_names):
        raise RuntimeError(
            f"Selective decoder weighted-MatMul quantization drift for blocks={blocks}: "
            f"expected_added={len(node_names)}, actual_added={actual_added}, "
            f"source_quantized={source_quantized}, target_quantized={actual_total}, "
            f"nodes={node_names}"
        )
    return {
        "blocks": list(blocks),
        "selected_weighted_matmul_count": len(node_names),
        "selected_weighted_node_names": node_names,
        "source_quantized_matmul_count": source_quantized,
        "target_quantized_matmul_count": actual_total,
        "added_quantized_matmul_count": actual_added,
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
    baseline_script = here / "diagnose-h2d-int8-alignment.py"
    diag = load_module(baseline_script, "voica_h2d_diag")
    h2d = load_module(here / "verify-h2d-int8-ctc-e2e.py", "voica_h2d")
    exporter_utils = load_module(args.exporter_root.resolve() / "utils.py", "voica_h2_exporter_utils")

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    baseline_cmd = [
        sys.executable,
        str(baseline_script),
        "--h2a-root", str(args.h2a_root.resolve()),
        "--h2c-root", str(args.h2c_root.resolve()),
        "--h2c-generation-root", str(args.h2c_generation_root.resolve()),
        "--exporter-root", str(args.exporter_root.resolve()),
        "--funasr-root", str(args.funasr_root.resolve()),
        "--out", str(out),
    ]
    baseline = subprocess.run(baseline_cmd, check=False)

    baseline_summary_path = out / "a3-h2d-int8-alignment-diagnostic.json"
    projection_path = out / "ctc_compact.projection-int8.onnx"
    canonical_path = out / "ctc_compact.int8.onnx"
    for required in (baseline_summary_path, projection_path, canonical_path):
        if not required.is_file():
            raise RuntimeError(f"Baseline H2d did not preserve required diagnostic asset: {required}")
    baseline_summary = json.loads(baseline_summary_path.read_text(encoding="utf-8"))

    if baseline.returncode == 0:
        summary = {
            "baseline_returncode": 0,
            "selection_mode": "full-int8",
            "selected_sha256": sha256_file(canonical_path),
            "selected_size": canonical_path.stat().st_size,
            "selected_size_ratio": canonical_path.stat().st_size / int(baseline_summary["source_fp32_size"]),
            "acceptance_thresholds_unchanged": baseline_summary["acceptance_thresholds_unchanged"],
            "status": "H2d full INT8 already PASS; mixed search not required",
        }
        summary_path = out / "a3-h2d-mixed-int8-selection.json"
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return

    full_baseline_path = out / "ctc_compact.full-int8.onnx"
    if full_baseline_path.exists():
        full_baseline_path.unlink()
    canonical_path.replace(full_baseline_path)

    weighted_groups, activation_groups, ungrouped = block_matmul_inventory(projection_path)
    fp32_path = args.h2a_root.resolve() / "ctc_compact.fp32.onnx"
    dual_encoder_path = args.h2c_root.resolve() / "encoder_adaptor.dual.int8.onnx"
    for required in (fp32_path, dual_encoder_path):
        if not required.is_file():
            raise RuntimeError(f"Missing mixed-search prerequisite: {required}")

    fp32_session = h2d.make_session(
        fp32_path,
        ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
        ["candidate_logits"],
    )
    encoder_session = h2d.make_session(
        dual_encoder_path,
        ["x"],
        ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
    )

    baseline_cases = {str(case["fixture"]): case for case in baseline_summary.get("cases", [])}
    if set(baseline_cases) != set(FIXTURES):
        raise RuntimeError(f"Unexpected baseline H2d fixtures: {sorted(baseline_cases)}")

    fixture_context: dict[str, dict[str, Any]] = {}
    for fixture in FIXTURES:
        wave = args.exporter_root.resolve() / "examples" / fixture
        samples, sample_rate = exporter_utils.load_and_resample_audio(str(wave))
        features = np.asarray(exporter_utils.compute_feat(samples, sample_rate, 7, 6)[None, ...], dtype=np.float32)
        _llm_out, ctc_state, ctc_lens = encoder_session.run(None, {"x": features})
        targets = [int(value) for value in baseline_cases[fixture]["target_ids"]]
        candidates = np.asarray([h2d.EXPECTED_BLANK_ID, *targets], dtype=np.int64)
        feeds = {
            "ctc_encoder_out": np.asarray(ctc_state, dtype=np.float32),
            "ctc_encoder_out_lens": np.asarray(ctc_lens, dtype=np.int64),
            "candidate_ids": candidates,
        }
        fp32_logits = fp32_session.run(["candidate_logits"], feeds)[0]
        if not np.all(np.isfinite(fp32_logits)):
            raise RuntimeError(f"FP32 scorer emitted non-finite logits for {fixture}")
        fixture_context[fixture] = {
            "wave_sha256": sha256_file(wave),
            "sample_rate": int(sample_rate),
            "feature_frames": int(features.shape[1]),
            "targets": targets,
            "feeds": feeds,
            "fp32_logits": fp32_logits,
            "fp32_states": h2d.viterbi_state_path(fp32_logits[0], targets),
        }

    fp32_size = fp32_path.stat().st_size
    candidate_results: list[dict[str, Any]] = []
    accepted_at_cardinality: list[dict[str, Any]] = []

    for cardinality in range(1, len(EXPECTED_BLOCKS) + 1):
        round_results: list[dict[str, Any]] = []
        for blocks in itertools.combinations(EXPECTED_BLOCKS, cardinality):
            candidate_path = out / ("ctc_compact.mixed-b" + "-".join(map(str, blocks)) + ".int8.onnx")
            quant_info = quantize_selected_blocks(projection_path, candidate_path, weighted_groups, blocks)
            size = candidate_path.stat().st_size
            size_ratio = size / fp32_size
            session = h2d.make_session(
                candidate_path,
                ["ctc_encoder_out", "ctc_encoder_out_lens", "candidate_ids"],
                ["candidate_logits"],
            )

            case_results: list[dict[str, Any]] = []
            numeric_all = True
            exact_all = True
            max_relative_rmse = 0.0
            max_abs_error = 0.0
            for fixture in FIXTURES:
                ctx = fixture_context[fixture]
                logits = session.run(["candidate_logits"], ctx["feeds"])[0]
                if not np.all(np.isfinite(logits)):
                    raise RuntimeError(f"Mixed INT8 emitted non-finite logits for {fixture}, blocks={blocks}")
                error = diag.score_error(ctx["fp32_logits"], logits)
                states = h2d.viterbi_state_path(logits[0], ctx["targets"])
                alignment = diag.compare_alignment(h2d, ctx["fp32_states"], states, ctx["targets"])
                numeric_ok = (
                    error["relative_rmse"] <= h2d.MAX_RELATIVE_RMSE
                    and error["max_abs"] <= h2d.MAX_LOGIT_ABS_ERROR
                )
                exact_ok = bool(alignment.get("state_path_exact", False))
                numeric_all = numeric_all and numeric_ok
                exact_all = exact_all and exact_ok
                max_relative_rmse = max(max_relative_rmse, float(error["relative_rmse"]))
                max_abs_error = max(max_abs_error, float(error["max_abs"]))
                case_results.append({
                    "fixture": fixture,
                    "wave_sha256": ctx["wave_sha256"],
                    "sample_rate": ctx["sample_rate"],
                    "feature_frames": ctx["feature_frames"],
                    "logit_error": error,
                    "alignment": alignment,
                    "numeric_thresholds_pass": numeric_ok,
                })

            accepted = size_ratio <= h2d.MAX_SIZE_RATIO and numeric_all and exact_all
            result = {
                **quant_info,
                "sha256": sha256_file(candidate_path),
                "size": size,
                "size_ratio": size_ratio,
                "max_relative_rmse": max_relative_rmse,
                "max_logit_abs_error": max_abs_error,
                "numeric_thresholds_pass": numeric_all,
                "exact_alignment_pass": exact_all,
                "size_threshold_pass": size_ratio <= h2d.MAX_SIZE_RATIO,
                "accepted": accepted,
                "cases": case_results,
                "path": str(candidate_path),
            }
            round_results.append(result)
            candidate_results.append(result)
            if not accepted:
                candidate_path.unlink(missing_ok=True)

        accepted_at_cardinality = [item for item in round_results if item["accepted"]]
        if accepted_at_cardinality:
            break

    report_candidates = [{key: value for key, value in item.items() if key != "path"} for item in candidate_results]
    inventory = {
        "weighted_quantizable_by_block": {str(block): names for block, names in weighted_groups.items()},
        "activation_attention_matmuls_left_fp32_by_block": {
            str(block): names for block, names in activation_groups.items()
        },
        "ungrouped_matmuls_left_fp32": ungrouped,
    }
    if not accepted_at_cardinality:
        summary = {
            "baseline_returncode": baseline.returncode,
            "decoder_matmul_inventory": inventory,
            "acceptance_thresholds_unchanged": baseline_summary["acceptance_thresholds_unchanged"],
            "candidates": report_candidates,
            "status": "H2d mixed INT8 search FAIL: no candidate satisfies unchanged criteria",
        }
        summary_path = out / "a3-h2d-mixed-int8-selection.json"
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        raise RuntimeError("No mixed INT8 CTC scorer satisfies exact alignment + numeric + size criteria")

    selected = min(
        accepted_at_cardinality,
        key=lambda item: (
            float(item["max_relative_rmse"]),
            float(item["max_logit_abs_error"]),
            float(item["size_ratio"]),
            tuple(item["blocks"]),
        ),
    )
    selected_path = Path(str(selected["path"]))
    shutil.copy2(selected_path, canonical_path)
    if sha256_file(canonical_path) != selected["sha256"]:
        raise RuntimeError("Canonical mixed INT8 copy SHA mismatch")

    summary = {
        "baseline_returncode": baseline.returncode,
        "baseline_full_int8_sha256": sha256_file(full_baseline_path),
        "baseline_full_int8_size": full_baseline_path.stat().st_size,
        "baseline_full_int8_exact_alignment_pass": bool(baseline_summary.get("full_int8_exact_alignment_pass")),
        "projection_only_sha256": sha256_file(projection_path),
        "projection_only_size": projection_path.stat().st_size,
        "projection_only_size_ratio": projection_path.stat().st_size / fp32_size,
        "decoder_matmul_inventory": inventory,
        "search_policy": "minimum quantized decoder-block cardinality; then min max-relative-RMSE, max-abs-error, size-ratio, block tuple",
        "acceptance_thresholds_unchanged": baseline_summary["acceptance_thresholds_unchanged"],
        "candidate_count_evaluated": len(candidate_results),
        "candidates": report_candidates,
        "selected": {key: value for key, value in selected.items() if key != "path"},
        "canonical_sha256": sha256_file(canonical_path),
        "canonical_size": canonical_path.stat().st_size,
        "status": "H2d mixed INT8 exact alignment + numeric + size PASS",
    }
    summary_path = out / "a3-h2d-mixed-int8-selection.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"SELECTED_INT8_ONNX_PATH={canonical_path}")


if __name__ == "__main__":
    main()
