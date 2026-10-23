#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import tarfile
import urllib.request
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper

PACKAGE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2"
EXPECTED_PACKAGE_SIZE = 841_730_611
EXPECTED_PACKAGE_SHA256 = "eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b"
EXPECTED_ENCODER_SIZE = 238_277_200
EXPECTED_ENCODER_SHA256 = "d0246c823f2c34133ae0efee395d8a189c8f92643e3432f866939ee34d34492c"
EXPECTED_INPUT_DIM = 560
EXPECTED_CTC_DIM = 512
EXPECTED_LLM_DIM = 1024
TEST_CASES = ((30, 2601), (47, 2602), (61, 2603))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "voica-stage16b-h2c/1"})
    with urllib.request.urlopen(request, timeout=120) as response, target.open("wb") as out:
        while True:
            chunk = response.read(8 * 1024 * 1024)
            if not chunk:
                break
            out.write(chunk)


def extract_encoder(package: Path, out_dir: Path) -> Path:
    suffix = "/encoder_adaptor.int8.onnx"
    with tarfile.open(package, "r:bz2") as archive:
        matches = [m for m in archive.getmembers() if m.isfile() and m.name.endswith(suffix)]
        if len(matches) != 1:
            raise RuntimeError(f"Expected exactly one production encoder in package, got {[m.name for m in matches]}")
        member = matches[0]
        source = archive.extractfile(member)
        if source is None:
            raise RuntimeError(f"Cannot extract {member.name}")
        target = out_dir / "encoder_adaptor.int8.onnx"
        with target.open("wb") as handle:
            while True:
                chunk = source.read(8 * 1024 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
    return target


def producer_map(model: onnx.ModelProto) -> dict[str, onnx.NodeProto]:
    result: dict[str, onnx.NodeProto] = {}
    for node in model.graph.node:
        for output in node.output:
            if output:
                if output in result:
                    raise RuntimeError(f"Duplicate producer for tensor {output!r}")
                result[output] = node
    return result


def node_by_name(model: onnx.ModelProto, name: str) -> list[onnx.NodeProto]:
    return [node for node in model.graph.node if node.name == name]


def all_tensor_names(model: onnx.ModelProto) -> set[str]:
    names: set[str] = set()
    for node in model.graph.node:
        names.update(x for x in node.input if x)
        names.update(x for x in node.output if x)
    names.update(x.name for x in model.graph.input)
    names.update(x.name for x in model.graph.output)
    names.update(x.name for x in model.graph.value_info)
    names.update(x.name for x in model.graph.initializer)
    return names


def find_boundary_tensor(legacy: onnx.ModelProto, dual: onnx.ModelProto, production: onnx.ModelProto) -> tuple[str, dict[str, str]]:
    dual_producers = producer_map(dual)
    ctc_producer = dual_producers.get("ctc_encoder_out")
    if ctc_producer is None or not ctc_producer.name:
        raise RuntimeError("Dual FP32 graph does not have a named producer for ctc_encoder_out")
    try:
        output_index = list(ctc_producer.output).index("ctc_encoder_out")
    except ValueError as exc:
        raise RuntimeError("ctc_encoder_out producer/output mismatch") from exc

    legacy_matches = node_by_name(legacy, ctc_producer.name)
    if len(legacy_matches) != 1:
        raise RuntimeError(
            f"Cannot map dual CTC producer {ctc_producer.name!r} into pinned legacy FP32 graph: {len(legacy_matches)} matches"
        )
    legacy_node = legacy_matches[0]
    if output_index >= len(legacy_node.output):
        raise RuntimeError("Mapped legacy producer has fewer outputs than dual producer")
    legacy_boundary = legacy_node.output[output_index]
    if not legacy_boundary:
        raise RuntimeError("Pinned legacy boundary tensor is empty")

    production_matches = node_by_name(production, ctc_producer.name)
    if len(production_matches) == 1 and output_index < len(production_matches[0].output):
        production_boundary = production_matches[0].output[output_index]
        if production_boundary:
            return production_boundary, {
                "mapping": "producer-node-name",
                "producer_node": ctc_producer.name,
                "legacy_boundary": legacy_boundary,
                "production_boundary": production_boundary,
            }

    names = all_tensor_names(production)
    if legacy_boundary in names:
        return legacy_boundary, {
            "mapping": "preserved-tensor-name",
            "producer_node": ctc_producer.name,
            "legacy_boundary": legacy_boundary,
            "production_boundary": legacy_boundary,
        }

    hints = sorted(
        node.name for node in production.graph.node
        if node.name and (
            "audio_encoder" in node.name
            or "adaptor" in node.name
            or ctc_producer.name.rsplit("/", 1)[-1] in node.name
        )
    )[-80:]
    raise RuntimeError(
        "Production INT8 graph does not preserve the pinned encoder/adaptor boundary. "
        f"dual_producer={ctc_producer.name!r} legacy_boundary={legacy_boundary!r} hints={hints}"
    )


def tensor_info(model: onnx.ModelProto, name: str) -> tuple[int, list[Any]] | None:
    candidates = list(model.graph.value_info) + list(model.graph.input) + list(model.graph.output)
    for value in candidates:
        if value.name != name or not value.type.HasField("tensor_type"):
            continue
        tensor = value.type.tensor_type
        shape: list[Any] = []
        for dim in tensor.shape.dim:
            if dim.HasField("dim_value"):
                shape.append(int(dim.dim_value))
            elif dim.HasField("dim_param") and dim.dim_param:
                shape.append(dim.dim_param)
            else:
                shape.append(None)
        return int(tensor.elem_type), shape
    return None


def build_dual_graph(production: onnx.ModelProto, boundary: str) -> onnx.ModelProto:
    model = copy.deepcopy(production)
    if [x.name for x in model.graph.output] != ["encoder_out"]:
        raise RuntimeError(f"Production encoder output ABI drift: {[x.name for x in model.graph.output]}")

    inferred = onnx.shape_inference.infer_shapes(model, strict_mode=False, data_prop=False)
    info = tensor_info(inferred, boundary)
    if info is None:
        raise RuntimeError(f"Shape inference did not expose boundary tensor {boundary!r}")
    elem_type, shape = info
    if elem_type not in (TensorProto.FLOAT, TensorProto.FLOAT16):
        raise RuntimeError(f"Boundary tensor must be float-like, got ONNX elem_type={elem_type}")
    if len(shape) != 3 or shape[2] != EXPECTED_CTC_DIM:
        raise RuntimeError(f"Boundary tensor shape contract mismatch: {shape}")

    existing = all_tensor_names(model)
    reserved = {
        "ctc_encoder_out",
        "ctc_encoder_out_lens",
        "voica_ctc_shape",
        "voica_ctc_t",
        "voica_ctc_axis_1",
        "voica_ctc_unsqueeze_axes",
    }
    collisions = sorted(existing & reserved)
    if collisions:
        raise RuntimeError(f"Production graph already contains Voica H2c reserved names: {collisions}")

    model.graph.initializer.extend([
        numpy_helper.from_array(np.asarray(1, dtype=np.int64), name="voica_ctc_axis_1"),
        numpy_helper.from_array(np.asarray([0], dtype=np.int64), name="voica_ctc_unsqueeze_axes"),
    ])
    model.graph.node.extend([
        helper.make_node("Identity", [boundary], ["ctc_encoder_out"], name="voica_ctc_expose_pre_adaptor"),
        helper.make_node("Shape", [boundary], ["voica_ctc_shape"], name="voica_ctc_shape_of_pre_adaptor"),
        helper.make_node(
            "Gather",
            ["voica_ctc_shape", "voica_ctc_axis_1"],
            ["voica_ctc_t"],
            name="voica_ctc_gather_time",
            axis=0,
        ),
        helper.make_node(
            "Unsqueeze",
            ["voica_ctc_t", "voica_ctc_unsqueeze_axes"],
            ["ctc_encoder_out_lens"],
            name="voica_ctc_make_length_vector",
        ),
    ])
    model.graph.output.extend([
        helper.make_tensor_value_info("ctc_encoder_out", elem_type, [1, "T_ctc", EXPECTED_CTC_DIM]),
        helper.make_tensor_value_info("ctc_encoder_out_lens", TensorProto.INT64, [1]),
    ])
    onnx.checker.check_model(model)
    return model


def make_session(path: Path, outputs: list[str]) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    if [x.name for x in session.get_inputs()] != ["x"]:
        raise RuntimeError(f"Production encoder input ABI drift: {[x.name for x in session.get_inputs()]}")
    actual_outputs = [x.name for x in session.get_outputs()]
    if actual_outputs != outputs:
        raise RuntimeError(f"Encoder output ABI mismatch: {actual_outputs} != {outputs}")
    return session


def verify_case(original: ort.InferenceSession, dual: ort.InferenceSession, frames: int, seed: int) -> dict[str, Any]:
    rng = np.random.default_rng(seed)
    x = rng.standard_normal((1, frames, EXPECTED_INPUT_DIM), dtype=np.float32)
    original_out = original.run(["encoder_out"], {"x": x})[0]
    dual_out, ctc_state, ctc_lens = dual.run(None, {"x": x})

    if original_out.shape != dual_out.shape or original_out.shape[-1] != EXPECTED_LLM_DIM:
        raise RuntimeError(f"LLM output shape drift for T={frames}: {original_out.shape} vs {dual_out.shape}")
    exact_equal = bool(np.array_equal(original_out, dual_out))
    max_abs = float(np.max(np.abs(original_out - dual_out))) if original_out.size else 0.0
    if not exact_equal:
        raise RuntimeError(f"Production /1 vs graph-surgery /2 output[0] is not bit-exact for T={frames}; max_abs={max_abs}")
    if ctc_state.shape != (1, frames, EXPECTED_CTC_DIM):
        raise RuntimeError(f"Exposed pre-adaptor shape mismatch for T={frames}: {ctc_state.shape}")
    if ctc_lens.dtype != np.int64 or ctc_lens.shape != (1,) or int(ctc_lens[0]) != frames:
        raise RuntimeError(f"Exposed pre-adaptor length mismatch for T={frames}: {ctc_lens!r}")
    if not np.isfinite(ctc_state).all():
        raise RuntimeError(f"Exposed pre-adaptor contains non-finite values for T={frames}")
    return {
        "frames": frames,
        "encoder_out_shape": list(dual_out.shape),
        "ctc_encoder_out_shape": list(ctc_state.shape),
        "ctc_encoder_out_lens": [int(v) for v in ctc_lens.tolist()],
        "output0_exact_equal": exact_equal,
        "output0_max_abs": max_abs,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--h2b-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    legacy_fp32 = args.h2b_root / "encoder_adaptor.legacy.fp32.onnx"
    dual_fp32 = args.h2b_root / "encoder_adaptor.dual.fp32.onnx"
    if not legacy_fp32.is_file() or not dual_fp32.is_file():
        raise RuntimeError("H2c requires the already-proven H2b FP32 legacy+dual graphs from the same job")

    package = out / "sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2"
    download(PACKAGE_URL, package)
    if package.stat().st_size != EXPECTED_PACKAGE_SIZE:
        raise RuntimeError(f"Production /1 package size mismatch: {package.stat().st_size} != {EXPECTED_PACKAGE_SIZE}")
    package_sha = sha256_file(package)
    if package_sha != EXPECTED_PACKAGE_SHA256:
        raise RuntimeError(f"Production /1 package SHA mismatch: {package_sha}")

    encoder = extract_encoder(package, out)
    if encoder.stat().st_size != EXPECTED_ENCODER_SIZE:
        raise RuntimeError(f"Production encoder size mismatch: {encoder.stat().st_size} != {EXPECTED_ENCODER_SIZE}")
    encoder_sha = sha256_file(encoder)
    if encoder_sha != EXPECTED_ENCODER_SHA256:
        raise RuntimeError(f"Production encoder SHA mismatch: {encoder_sha}")

    legacy_model = onnx.load(str(legacy_fp32), load_external_data=False)
    dual_model = onnx.load(str(dual_fp32), load_external_data=False)
    production_model = onnx.load(str(encoder), load_external_data=False)
    onnx.checker.check_model(production_model)

    boundary, mapping = find_boundary_tensor(legacy_model, dual_model, production_model)
    candidate = build_dual_graph(production_model, boundary)
    candidate_path = out / "encoder_adaptor.dual.int8.onnx"
    onnx.save(candidate, str(candidate_path))

    original_session = make_session(encoder, ["encoder_out"])
    dual_session = make_session(
        candidate_path,
        ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
    )
    output_types = [x.type for x in dual_session.get_outputs()]
    if output_types != ["tensor(float)", "tensor(float)", "tensor(int64)"]:
        raise RuntimeError(f"Production dual INT8 output type drift: {output_types}")

    cases = [verify_case(original_session, dual_session, frames, seed) for frames, seed in TEST_CASES]
    candidate_sha = sha256_file(candidate_path)

    summary = {
        "production_package_url": PACKAGE_URL,
        "production_package_sha256": package_sha,
        "production_encoder_sha256": encoder_sha,
        "production_encoder_size": encoder.stat().st_size,
        "dual_int8_sha256": candidate_sha,
        "dual_int8_size": candidate_path.stat().st_size,
        "boundary_mapping": mapping,
        "dual_onnx_abi": {
            "input": "x",
            "outputs": ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
            "input_dim": EXPECTED_INPUT_DIM,
            "ctc_dim": EXPECTED_CTC_DIM,
            "llm_dim": EXPECTED_LLM_DIM,
            "batch_contract": 1,
        },
        "cases": cases,
        "status": "H2c-1 production INT8 graph-surgery output[0] exact parity PASS",
    }
    summary_path = out / "a3-h2c-int8-dual-surgery.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")
    print(f"DUAL_INT8_PATH={candidate_path}")


if __name__ == "__main__":
    main()
