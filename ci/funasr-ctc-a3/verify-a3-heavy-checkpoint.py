#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import torch
from modelscope_hub import HubApi

MODEL_ID = "FunAudioLLM/Fun-ASR-Nano-2512"
DEFAULT_REVISION = "master"
EXPECTED_MODEL_SHA256 = "81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499"
EXPECTED_MODEL_SIZE = 2127426538
EXPECTED_CTC_TENSOR_COUNT = 86
EXPECTED_CTC_VOCAB_SIZE = 60515
EXPECTED_CTC_ENCODER_DIM = 512
EXPECTED_BLANK_ID = 60514
PROJECTION_WEIGHT_KEY = "ctc.ctc_lo.weight"
PROJECTION_BIAS_KEY = "ctc.ctc_lo.bias"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_downloaded_file(downloaded: Path, out_dir: Path) -> Path:
    if downloaded.is_file():
        return downloaded
    candidate = out_dir / "model.pt"
    if candidate.is_file():
        return candidate
    nested = downloaded / "model.pt"
    if nested.is_file():
        return nested
    raise RuntimeError(f"ModelScope download did not produce model.pt: {downloaded}")


def normalize_checkpoint_state(state: Any) -> dict[str, Any]:
    # Keep this identical in meaning to FunASR's checkpoint_utils.normalize_checkpoint_state().
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
            raise RuntimeError(f"Checkpoint contains a non-string key: {raw_key!r}")
        key = raw_key[len("module.") :] if raw_key.startswith("module.") else raw_key
        if key in normalized:
            raise RuntimeError(f"Checkpoint key collision after module. normalization: {key}")
        normalized[key] = value
    return normalized


def tensor_record(key: str, tensor: torch.Tensor) -> dict[str, Any]:
    return {
        "key": key,
        "shape": list(tensor.shape),
        "dtype": str(tensor.dtype).removeprefix("torch."),
        "numel": int(tensor.numel()),
    }


def require_tensor(state: dict[str, Any], key: str) -> torch.Tensor:
    value = state.get(key)
    if not isinstance(value, torch.Tensor):
        raise RuntimeError(f"Required checkpoint tensor missing or non-tensor: {key}")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    api = HubApi()

    downloaded = Path(
        api.download_file(
            MODEL_ID,
            "model",
            "model.pt",
            revision=args.revision,
            local_dir=args.out,
            force=True,
            expected_sha256=EXPECTED_MODEL_SHA256,
        )
    )
    model_path = resolve_downloaded_file(downloaded, args.out)

    local_size = model_path.stat().st_size
    if local_size != EXPECTED_MODEL_SIZE:
        raise RuntimeError(
            f"model.pt size drift: expected {EXPECTED_MODEL_SIZE}, got {local_size}"
        )

    local_sha = sha256_file(model_path)
    if local_sha != EXPECTED_MODEL_SHA256:
        raise RuntimeError(
            "model.pt local SHA-256 mismatch after ModelScope download: "
            f"expected {EXPECTED_MODEL_SHA256}, got {local_sha}"
        )

    # Only deserialize after the complete source file has passed the immutable SHA pin.
    # weights_only=True intentionally rejects arbitrary checkpoint pickle objects.
    raw_state = torch.load(
        model_path,
        map_location="cpu",
        weights_only=True,
        mmap=True,
    )
    state = normalize_checkpoint_state(raw_state)

    ctc_keys = sorted(
        key for key in state if key.startswith("ctc_decoder.") or key.startswith("ctc.")
    )
    if len(ctc_keys) != EXPECTED_CTC_TENSOR_COUNT:
        raise RuntimeError(
            "CTC checkpoint tensor count mismatch: "
            f"expected {EXPECTED_CTC_TENSOR_COUNT}, got {len(ctc_keys)}"
        )

    non_tensor_ctc = [key for key in ctc_keys if not isinstance(state[key], torch.Tensor)]
    if non_tensor_ctc:
        raise RuntimeError(f"CTC state contains non-tensor entries: {non_tensor_ctc[:5]}")

    decoder_keys = [key for key in ctc_keys if key.startswith("ctc_decoder.")]
    projection_keys = [key for key in ctc_keys if key.startswith("ctc.")]
    expected_projection_keys = [PROJECTION_BIAS_KEY, PROJECTION_WEIGHT_KEY]
    if projection_keys != expected_projection_keys:
        raise RuntimeError(
            "Unexpected CTC projection tensor set: "
            f"expected {expected_projection_keys}, got {projection_keys}"
        )

    projection_weight = require_tensor(state, PROJECTION_WEIGHT_KEY)
    projection_bias = require_tensor(state, PROJECTION_BIAS_KEY)
    if list(projection_weight.shape) != [
        EXPECTED_CTC_VOCAB_SIZE,
        EXPECTED_CTC_ENCODER_DIM,
    ]:
        raise RuntimeError(
            f"Unexpected {PROJECTION_WEIGHT_KEY} shape: "
            f"{list(projection_weight.shape)}"
        )
    if list(projection_bias.shape) != [EXPECTED_CTC_VOCAB_SIZE]:
        raise RuntimeError(
            f"Unexpected {PROJECTION_BIAS_KEY} shape: {list(projection_bias.shape)}"
        )

    actual_vocab_size = int(projection_weight.shape[0])
    actual_encoder_dim = int(projection_weight.shape[1])
    actual_blank_id = actual_vocab_size - 1
    if actual_blank_id != EXPECTED_BLANK_ID:
        raise RuntimeError(
            f"Derived blank id mismatch: expected {EXPECTED_BLANK_ID}, got {actual_blank_id}"
        )

    all_tensor_count = sum(isinstance(value, torch.Tensor) for value in state.values())
    inventory = [tensor_record(key, state[key]) for key in ctc_keys]
    summary = {
        "model_id": MODEL_ID,
        "requested_revision": args.revision,
        "model_pt_size": local_size,
        "model_pt_sha256": local_sha,
        "checkpoint_normalized_entry_count": len(state),
        "checkpoint_tensor_count": all_tensor_count,
        "ctc_tensor_count": len(ctc_keys),
        "ctc_decoder_tensor_count": len(decoder_keys),
        "ctc_projection_tensor_count": len(projection_keys),
        "ctc_vocab_size_from_projection": actual_vocab_size,
        "ctc_encoder_dim_from_projection": actual_encoder_dim,
        "blank_id_derived_from_vocab": actual_blank_id,
        "projection_weight_dtype": str(projection_weight.dtype).removeprefix("torch."),
        "projection_bias_dtype": str(projection_bias.dtype).removeprefix("torch."),
        "ctc_inventory": inventory,
        "torch_version": torch.__version__,
        "load_policy": "sha256-verified before torch.load; weights_only=true; mmap=true",
    }
    summary_path = args.out / "a3-heavy-checkpoint.json"
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(
        json.dumps(
            {key: value for key, value in summary.items() if key != "ctc_inventory"},
            ensure_ascii=False,
            indent=2,
        )
    )
    print(f"CTC_INVENTORY_PATH={summary_path}")


if __name__ == "__main__":
    main()
