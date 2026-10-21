#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import torch
import yaml
from modelscope_hub import HubApi

MODEL_ID = "FunAudioLLM/Fun-ASR-Nano-2512"
DEFAULT_REVISION = "master"
EXPECTED_MODEL_SIZE = 2_127_426_538
EXPECTED_MODEL_SHA256 = "81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499"
EXPECTED_ENCODER_DIM = 512
SOURCE_DEFAULT_CTC_VOCAB_SIZE = 60_515
SOURCE_DEFAULT_BLANK_ID = 60_514
EXPECTED_CTC_BLOCKS = list(range(5))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_file(
    api: HubApi,
    remote_path: str,
    revision: str,
    out_dir: Path,
    expected_sha256: str | None = None,
) -> Path:
    result = Path(
        api.download_file(
            MODEL_ID,
            "model",
            remote_path,
            revision=revision,
            local_dir=out_dir,
            force=True,
            expected_sha256=expected_sha256,
        )
    )
    candidates = [result, out_dir / remote_path, out_dir / Path(remote_path).name]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise RuntimeError(f"ModelScope download did not produce {remote_path}: {result}")


def normalize_key(key: str) -> str:
    while key.startswith("module."):
        key = key[len("module.") :]
    return key


def tensor_mapping_candidates(value: Any, path: str = "root") -> list[tuple[str, dict[str, torch.Tensor]]]:
    candidates: list[tuple[str, dict[str, torch.Tensor]]] = []
    if not isinstance(value, Mapping):
        return candidates

    tensors: dict[str, torch.Tensor] = {}
    for key, child in value.items():
        if isinstance(key, str) and isinstance(child, torch.Tensor):
            tensors[normalize_key(key)] = child
    if tensors:
        candidates.append((path, tensors))

    for key, child in value.items():
        if isinstance(key, str) and isinstance(child, Mapping):
            candidates.extend(tensor_mapping_candidates(child, f"{path}.{key}"))
    return candidates


def select_model_state(checkpoint: Any) -> tuple[str, dict[str, torch.Tensor]]:
    candidates = tensor_mapping_candidates(checkpoint)
    if not candidates:
        raise RuntimeError("No tensor mapping found in model.pt")

    def score(item: tuple[str, dict[str, torch.Tensor]]) -> tuple[int, int, int]:
        _, tensors = item
        ctc_decoder = sum(key.startswith("ctc_decoder.") for key in tensors)
        ctc = sum(key.startswith("ctc.") for key in tensors)
        return (ctc_decoder + ctc, len(tensors), sum(tensor.numel() for tensor in tensors.values()))

    selected = max(candidates, key=score)
    if score(selected)[0] == 0:
        details = [
            {
                "path": path,
                "tensor_count": len(tensors),
                "ctc_key_count": sum(
                    key.startswith("ctc_decoder.") or key.startswith("ctc.") for key in tensors
                ),
            }
            for path, tensors in candidates
        ]
        raise RuntimeError(f"No CTC tensor mapping found in model.pt candidates: {details}")
    return selected


def tensor_meta(tensor: torch.Tensor) -> dict[str, Any]:
    return {
        "shape": list(tensor.shape),
        "dtype": str(tensor.dtype).replace("torch.", ""),
        "numel": int(tensor.numel()),
    }


def require_finite(name: str, tensor: torch.Tensor) -> None:
    if tensor.numel() == 0:
        raise RuntimeError(f"Checkpoint tensor is empty: {name}")
    if tensor.is_floating_point() or tensor.is_complex():
        if not bool(torch.isfinite(tensor).all().item()):
            raise RuntimeError(f"Checkpoint tensor contains non-finite values: {name}")


def require_nonzero_weight(name: str, tensor: torch.Tensor) -> None:
    require_finite(name, tensor)
    if name.endswith(".weight") and not bool(torch.count_nonzero(tensor).item()):
        raise RuntimeError(f"Checkpoint weight tensor is entirely zero: {name}")


def parse_block_indices(keys: list[str]) -> list[int]:
    indices: set[int] = set()
    prefix = "ctc_decoder.blocks."
    for key in keys:
        if not key.startswith(prefix):
            continue
        remainder = key[len(prefix) :]
        first = remainder.split(".", 1)[0]
        if first.isdigit():
            indices.add(int(first))
    return sorted(indices)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    api = HubApi()
    files = {
        entry.path: entry
        for entry in api.list_repo_files(MODEL_ID, "model", revision=args.revision, recursive=True)
    }
    for required in ("model.pt", "config.yaml", "multilingual.tiktoken"):
        if required not in files:
            raise RuntimeError(f"ModelScope source is missing required file: {required}")

    remote_model = files["model.pt"]
    remote_size = int(remote_model.size or 0)
    remote_sha = (remote_model.sha256 or "").lower() if remote_model.sha256 else None
    if remote_size != EXPECTED_MODEL_SIZE:
        raise RuntimeError(f"model.pt remote size drift: {remote_size} != {EXPECTED_MODEL_SIZE}")
    if remote_sha and remote_sha != EXPECTED_MODEL_SHA256:
        raise RuntimeError(f"model.pt remote SHA-256 drift: {remote_sha}")

    config_path = download_file(api, "config.yaml", args.revision, args.out)
    tokenizer_path = download_file(api, "multilingual.tiktoken", args.revision, args.out)
    model_path = download_file(
        api,
        "model.pt",
        args.revision,
        args.out,
        expected_sha256=EXPECTED_MODEL_SHA256,
    )

    local_size = model_path.stat().st_size
    if local_size != EXPECTED_MODEL_SIZE:
        raise RuntimeError(f"model.pt local size mismatch: {local_size} != {EXPECTED_MODEL_SIZE}")
    local_sha = sha256_file(model_path)
    if local_sha != EXPECTED_MODEL_SHA256:
        raise RuntimeError(f"model.pt local SHA-256 mismatch: {local_sha}")

    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise RuntimeError("config.yaml root must be a mapping")
    if config.get("ctc_decoder") != "Transformer":
        raise RuntimeError(f"Unexpected ctc_decoder: {config.get('ctc_decoder')!r}")

    try:
        checkpoint = torch.load(
            model_path,
            map_location="cpu",
            weights_only=True,
            mmap=True,
        )
        mmap_used = True
    except RuntimeError as mmap_error:
        print(f"mmap load unavailable, retrying ordinary weights_only load: {mmap_error}")
        checkpoint = torch.load(
            model_path,
            map_location="cpu",
            weights_only=True,
        )
        mmap_used = False

    state_path, state = select_model_state(checkpoint)
    keys = sorted(state)
    ctc_decoder_keys = [key for key in keys if key.startswith("ctc_decoder.")]
    ctc_keys = [key for key in keys if key.startswith("ctc.")]
    if not ctc_decoder_keys:
        raise RuntimeError("CTC-complete checkpoint has no ctc_decoder.* tensors")
    if not ctc_keys:
        raise RuntimeError("CTC-complete checkpoint has no ctc.* tensors")

    projection_weight_key = "ctc.ctc_lo.weight"
    projection_bias_key = "ctc.ctc_lo.bias"
    if projection_weight_key not in state or projection_bias_key not in state:
        raise RuntimeError(
            "CTC projection tensors are missing; expected ctc.ctc_lo.weight and ctc.ctc_lo.bias"
        )

    projection_weight = state[projection_weight_key]
    projection_bias = state[projection_bias_key]
    require_nonzero_weight(projection_weight_key, projection_weight)
    require_finite(projection_bias_key, projection_bias)
    if projection_weight.ndim != 2:
        raise RuntimeError(f"Unexpected CTC projection rank: {tuple(projection_weight.shape)}")
    vocab_size, projection_dim = map(int, projection_weight.shape)
    if list(projection_bias.shape) != [vocab_size]:
        raise RuntimeError(
            f"CTC projection bias shape mismatch: {list(projection_bias.shape)} vs [{vocab_size}]"
        )
    if projection_dim != EXPECTED_ENCODER_DIM:
        raise RuntimeError(f"CTC projection input dim drift: {projection_dim}")
    if vocab_size != SOURCE_DEFAULT_CTC_VOCAB_SIZE:
        raise RuntimeError(
            f"Checkpoint projection vocab size {vocab_size} != source default {SOURCE_DEFAULT_CTC_VOCAB_SIZE}"
        )
    blank_id = vocab_size - 1
    if blank_id != SOURCE_DEFAULT_BLANK_ID:
        raise RuntimeError(f"Derived blank id drift: {blank_id}")

    decoder_blocks = parse_block_indices(ctc_decoder_keys)
    if decoder_blocks != EXPECTED_CTC_BLOCKS:
        raise RuntimeError(
            f"CTC decoder block inventory drift: {decoder_blocks} != {EXPECTED_CTC_BLOCKS}"
        )

    required_decoder = {
        "ctc_decoder.linear1.weight",
        "ctc_decoder.linear1.bias",
        "ctc_decoder.linear2.weight",
        "ctc_decoder.linear2.bias",
    }
    missing_decoder = sorted(required_decoder.difference(state))
    if missing_decoder:
        raise RuntimeError(f"Required CTC decoder tensors missing: {missing_decoder}")

    for name in ctc_decoder_keys:
        require_nonzero_weight(name, state[name])
    for name in ctc_keys:
        require_finite(name, state[name])

    decoder_linear1 = state["ctc_decoder.linear1.weight"]
    decoder_linear2 = state["ctc_decoder.linear2.weight"]
    if decoder_linear1.ndim != 2 or int(decoder_linear1.shape[1]) != EXPECTED_ENCODER_DIM:
        raise RuntimeError(
            f"Unexpected ctc_decoder.linear1.weight shape: {list(decoder_linear1.shape)}"
        )
    if decoder_linear2.ndim != 2 or int(decoder_linear2.shape[0]) != EXPECTED_ENCODER_DIM:
        raise RuntimeError(
            f"Unexpected ctc_decoder.linear2.weight shape: {list(decoder_linear2.shape)}"
        )
    if int(decoder_linear1.shape[0]) != int(decoder_linear2.shape[1]):
        raise RuntimeError(
            "CTC decoder FFN geometry mismatch: "
            f"linear1={list(decoder_linear1.shape)}, linear2={list(decoder_linear2.shape)}"
        )

    ctc_related = ctc_decoder_keys + ctc_keys
    summary = {
        "model_id": MODEL_ID,
        "requested_revision": args.revision,
        "model_pt_remote_size": remote_size,
        "model_pt_remote_sha256": remote_sha,
        "model_pt_local_size": local_size,
        "model_pt_local_sha256": local_sha,
        "config_sha256": sha256_file(config_path),
        "tokenizer_sha256": sha256_file(tokenizer_path),
        "torch_version": torch.__version__,
        "torch_mmap_used": mmap_used,
        "selected_state_path": state_path,
        "total_tensor_count": len(state),
        "ctc_decoder_tensor_count": len(ctc_decoder_keys),
        "ctc_tensor_count": len(ctc_keys),
        "ctc_related_parameter_numel": int(sum(state[key].numel() for key in ctc_related)),
        "ctc_decoder_blocks": decoder_blocks,
        "ctc_decoder_linear1": tensor_meta(decoder_linear1),
        "ctc_decoder_linear2": tensor_meta(decoder_linear2),
        "ctc_projection_weight": tensor_meta(projection_weight),
        "ctc_projection_bias": tensor_meta(projection_bias),
        "derived_ctc_vocab_size": vocab_size,
        "derived_blank_id": blank_id,
        "projection_input_dim": projection_dim,
        "ctc_decoder_key_sample": ctc_decoder_keys[:12],
        "ctc_key_inventory": ctc_keys,
    }
    summary_path = args.out / "a3-h1-checkpoint-audit.json"
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")


if __name__ == "__main__":
    main()
