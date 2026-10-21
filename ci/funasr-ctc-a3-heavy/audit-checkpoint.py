#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
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
EXPECTED_CTC_VOCAB_SIZE = 60_515
EXPECTED_BLANK_ID = 60_514
EXPECTED_CTC_TENSOR_COUNT = 86
EXPECTED_CTC_DECODER_TENSOR_COUNT = 84
EXPECTED_CTC_BLOCKS = list(range(5))
PROJECTION_WEIGHT_KEY = "ctc.ctc_lo.weight"
PROJECTION_BIAS_KEY = "ctc.ctc_lo.bias"


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
    for candidate in (result, out_dir / remote_path, out_dir / Path(remote_path).name):
        if candidate.is_file():
            return candidate
    raise RuntimeError(f"ModelScope download did not produce {remote_path}: {result}")


def download_model_checkpoint(
    api: HubApi,
    revision: str,
    out_dir: Path,
) -> Path:
    # HubApi.download_repo() carries each selected file's authoritative metadata
    # (including size + SHA-256) into the pinned v0.4.6 downloader. With the H1
    # worker env this enables a 4-way HTTP Range download for model.pt while the
    # public API still restricts the snapshot to this one file.
    root = Path(
        api.download_repo(
            MODEL_ID,
            "model",
            revision=revision,
            local_dir=out_dir,
            allow_patterns=["model.pt"],
            max_workers=1,
        )
    )
    for candidate in (root / "model.pt", out_dir / "model.pt"):
        if candidate.is_file():
            return candidate
    raise RuntimeError(f"ModelScope filtered repo download did not produce model.pt: {root}")


def normalize_checkpoint_state(state: Any) -> dict[str, Any]:
    """Mirror FunASR checkpoint_utils.normalize_checkpoint_state()."""
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
        raise RuntimeError(
            f"Normalized checkpoint must be a mapping, got {type(state).__name__}"
        )

    normalized: dict[str, Any] = {}
    for raw_key, value in state.items():
        if not isinstance(raw_key, str):
            raise RuntimeError(f"Checkpoint contains a non-string key: {raw_key!r}")
        key = raw_key[len("module.") :] if raw_key.startswith("module.") else raw_key
        if key in normalized:
            raise RuntimeError(f"Key collision after module. normalization: {key}")
        normalized[key] = value
    return normalized


def tensor_meta(tensor: torch.Tensor) -> dict[str, Any]:
    return {
        "shape": list(tensor.shape),
        "dtype": str(tensor.dtype).removeprefix("torch."),
        "numel": int(tensor.numel()),
    }


def require_tensor(state: dict[str, Any], name: str) -> torch.Tensor:
    value = state.get(name)
    if not isinstance(value, torch.Tensor):
        raise RuntimeError(f"Required checkpoint tensor missing or non-tensor: {name}")
    return value


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
        first = key[len(prefix) :].split(".", 1)[0]
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
        for entry in api.list_repo_files(
            MODEL_ID,
            "model",
            revision=args.revision,
            recursive=True,
        )
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
    model_path = download_model_checkpoint(api, args.revision, args.out)

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

    # Deserialize only after the immutable source file has passed a full local SHA-256.
    # weights_only=True prevents arbitrary pickle object execution.
    checkpoint = torch.load(
        model_path,
        map_location="cpu",
        weights_only=True,
        mmap=True,
    )
    state = normalize_checkpoint_state(checkpoint)

    all_tensor_keys = sorted(
        key for key, value in state.items() if isinstance(value, torch.Tensor)
    )
    ctc_decoder_keys = [
        key for key in all_tensor_keys if key.startswith("ctc_decoder.")
    ]
    ctc_keys = [key for key in all_tensor_keys if key.startswith("ctc.")]
    ctc_related = sorted(ctc_decoder_keys + ctc_keys)

    if len(ctc_related) != EXPECTED_CTC_TENSOR_COUNT:
        raise RuntimeError(
            "CTC tensor inventory mismatch: "
            f"expected {EXPECTED_CTC_TENSOR_COUNT}, got {len(ctc_related)} "
            f"(decoder={len(ctc_decoder_keys)}, projection={len(ctc_keys)})"
        )
    if len(ctc_decoder_keys) != EXPECTED_CTC_DECODER_TENSOR_COUNT:
        raise RuntimeError(
            "CTC decoder tensor inventory mismatch: "
            f"expected {EXPECTED_CTC_DECODER_TENSOR_COUNT}, got {len(ctc_decoder_keys)}"
        )
    expected_projection_keys = [PROJECTION_BIAS_KEY, PROJECTION_WEIGHT_KEY]
    if ctc_keys != expected_projection_keys:
        raise RuntimeError(
            "Unexpected CTC projection tensor set: "
            f"expected {expected_projection_keys}, got {ctc_keys}"
        )

    projection_weight = require_tensor(state, PROJECTION_WEIGHT_KEY)
    projection_bias = require_tensor(state, PROJECTION_BIAS_KEY)
    require_nonzero_weight(PROJECTION_WEIGHT_KEY, projection_weight)
    require_finite(PROJECTION_BIAS_KEY, projection_bias)

    if projection_weight.ndim != 2:
        raise RuntimeError(
            f"Unexpected CTC projection rank: {list(projection_weight.shape)}"
        )
    vocab_size, projection_dim = map(int, projection_weight.shape)
    if [vocab_size, projection_dim] != [
        EXPECTED_CTC_VOCAB_SIZE,
        EXPECTED_ENCODER_DIM,
    ]:
        raise RuntimeError(
            "CTC projection shape drift: "
            f"{list(projection_weight.shape)} != "
            f"[{EXPECTED_CTC_VOCAB_SIZE}, {EXPECTED_ENCODER_DIM}]"
        )
    if list(projection_bias.shape) != [vocab_size]:
        raise RuntimeError(
            f"CTC projection bias shape mismatch: {list(projection_bias.shape)}"
        )

    blank_id = vocab_size - 1
    if blank_id != EXPECTED_BLANK_ID:
        raise RuntimeError(f"Derived blank id drift: {blank_id}")

    decoder_blocks = parse_block_indices(ctc_decoder_keys)
    if decoder_blocks != EXPECTED_CTC_BLOCKS:
        raise RuntimeError(
            f"CTC decoder block inventory drift: {decoder_blocks} != {EXPECTED_CTC_BLOCKS}"
        )

    required_decoder = (
        "ctc_decoder.linear1.weight",
        "ctc_decoder.linear1.bias",
        "ctc_decoder.linear2.weight",
        "ctc_decoder.linear2.bias",
    )
    for name in required_decoder:
        require_tensor(state, name)

    decoder_linear1 = require_tensor(state, "ctc_decoder.linear1.weight")
    decoder_linear2 = require_tensor(state, "ctc_decoder.linear2.weight")
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
            f"linear1={list(decoder_linear1.shape)}, "
            f"linear2={list(decoder_linear2.shape)}"
        )

    # Materialize only the CTC branch for numerical sanity checks. This stays well below
    # loading the complete 2.1 GB checkpoint eagerly and catches empty/NaN/zero weights.
    for name in ctc_decoder_keys:
        require_nonzero_weight(name, require_tensor(state, name))
    for name in ctc_keys:
        require_finite(name, require_tensor(state, name))

    inventory = [
        {"key": key, **tensor_meta(require_tensor(state, key))}
        for key in ctc_related
    ]
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
        "load_policy": "sha256 verified before torch.load; weights_only=true; mmap=true",
        "normalized_checkpoint_entry_count": len(state),
        "total_tensor_count": len(all_tensor_keys),
        "ctc_tensor_count": len(ctc_related),
        "ctc_decoder_tensor_count": len(ctc_decoder_keys),
        "ctc_projection_tensor_count": len(ctc_keys),
        "ctc_related_parameter_numel": int(
            sum(require_tensor(state, key).numel() for key in ctc_related)
        ),
        "ctc_decoder_blocks": decoder_blocks,
        "ctc_decoder_linear1": tensor_meta(decoder_linear1),
        "ctc_decoder_linear2": tensor_meta(decoder_linear2),
        "ctc_projection_weight": tensor_meta(projection_weight),
        "ctc_projection_bias": tensor_meta(projection_bias),
        "derived_ctc_vocab_size": vocab_size,
        "derived_blank_id": blank_id,
        "projection_input_dim": projection_dim,
        "ctc_inventory": inventory,
    }
    summary_path = args.out / "a3-h1-checkpoint-audit.json"
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    printable = dict(summary)
    printable.pop("ctc_inventory")
    printable["ctc_inventory_first_keys"] = [item["key"] for item in inventory[:6]]
    printable["ctc_inventory_last_keys"] = [item["key"] for item in inventory[-6:]]
    print(json.dumps(printable, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")


if __name__ == "__main__":
    main()
