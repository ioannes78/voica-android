#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
from pathlib import Path
from typing import Any

import tiktoken
import yaml
from modelscope_hub import HubApi
from tiktoken.load import load_tiktoken_bpe

MODEL_ID = "FunAudioLLM/Fun-ASR-Nano-2512"
DEFAULT_REVISION = "master"
REPORTED_CTC_MODEL_SHA256 = "81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499"
OFFICIAL_TOKENIZER_SHA256 = "74797963e813193436aabcff7c1c235d37de8097b71c563ec8b63b7a515c718"
OFFICIAL_DEFAULT_CTC_VOCAB_SIZE = 60515
OFFICIAL_CTC_FRAME_PERIOD_MS = 60
PATTERN = r"'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+"
FIXTURES = (
    "郗",
    "我叫郗明。你好。",
    " OpenAI 3.5，中文!\nnext",
    "会议在2026年10月21日 17:30 开始。",
    "FunASR Nano + Voica：测试ABC123。",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def recursive_values(value: Any, key: str) -> list[Any]:
    found: list[Any] = []
    if isinstance(value, dict):
        for current_key, current_value in value.items():
            if current_key == key:
                found.append(current_value)
            found.extend(recursive_values(current_value, key))
    elif isinstance(value, list):
        for item in value:
            found.extend(recursive_values(item, key))
    return found


def single_int(config: Any, key: str) -> int | None:
    values = [
        value
        for value in recursive_values(config, key)
        if isinstance(value, int) and not isinstance(value, bool)
    ]
    distinct = sorted(set(values))
    if not distinct:
        return None
    if len(distinct) != 1:
        raise RuntimeError(f"Conflicting {key} values in config: {distinct}")
    return distinct[0]


def file_map(api: HubApi, revision: str) -> dict[str, Any]:
    files = api.list_repo_files(MODEL_ID, "model", revision=revision, recursive=True)
    return {item.path: item for item in files}


def download_small_file(api: HubApi, remote_path: str, revision: str, out_dir: Path) -> Path:
    downloaded = Path(
        api.download_file(
            MODEL_ID,
            "model",
            remote_path,
            revision=revision,
            local_dir=out_dir,
            force=True,
        )
    )
    if downloaded.is_dir():
        downloaded = downloaded / remote_path
    if not downloaded.is_file():
        candidate = out_dir / remote_path
        if candidate.is_file():
            downloaded = candidate
    if not downloaded.is_file():
        raise RuntimeError(f"ModelScope download did not produce {remote_path}: {downloaded}")
    target = out_dir / Path(remote_path).name
    if downloaded.resolve() != target.resolve():
        shutil.copy2(downloaded, target)
        downloaded = target
    return downloaded


def require_mapping(parent: dict[str, Any], key: str) -> dict[str, Any]:
    value = parent.get(key)
    if not isinstance(value, dict):
        raise RuntimeError(f"config.yaml {key} must be a mapping")
    return value


def require_int(mapping: dict[str, Any], key: str, expected: int, context: str) -> None:
    value = mapping.get(key)
    if value != expected:
        raise RuntimeError(f"Unexpected {context}.{key}: expected {expected}, got {value!r}")


def build_tiktoken(tokenizer_path: Path) -> tuple[tiktoken.Encoding, dict[bytes, int]]:
    mergeable_ranks = load_tiktoken_bpe(str(tokenizer_path))
    encoding = tiktoken.Encoding(
        name="voica-funasr-a3-sensevoice-ordinary-text",
        pat_str=PATTERN,
        mergeable_ranks=mergeable_ranks,
        special_tokens={},
    )
    return encoding, mergeable_ranks


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    api = HubApi()

    files = file_map(api, args.revision)
    required = ("config.yaml", "multilingual.tiktoken", "model.pt")
    missing = [path for path in required if path not in files]
    if missing:
        raise RuntimeError(f"ModelScope snapshot is missing required A3 files: {missing}")

    model_meta = files["model.pt"]
    remote_model_sha = (
        (model_meta.sha256 or "").lower()
        if getattr(model_meta, "sha256", None)
        else None
    )
    if remote_model_sha and remote_model_sha != REPORTED_CTC_MODEL_SHA256:
        raise RuntimeError(
            "ModelScope model.pt metadata SHA-256 drifted from the CTC-complete provenance pin: "
            f"{remote_model_sha}"
        )

    config_path = download_small_file(api, "config.yaml", args.revision, args.out)
    tokenizer_path = download_small_file(api, "multilingual.tiktoken", args.revision, args.out)

    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise RuntimeError("config.yaml root must be a mapping")

    if config.get("ctc_decoder") != "Transformer":
        raise RuntimeError(f"Unexpected ctc_decoder: {config.get('ctc_decoder')!r}")

    audio_adaptor_conf = require_mapping(config, "audio_adaptor_conf")
    require_int(audio_adaptor_conf, "encoder_dim", 512, "audio_adaptor_conf")
    require_int(audio_adaptor_conf, "llm_dim", 1024, "audio_adaptor_conf")
    require_int(audio_adaptor_conf, "n_layer", 2, "audio_adaptor_conf")
    require_int(audio_adaptor_conf, "downsample_rate", 1, "audio_adaptor_conf")

    ctc_decoder_conf = require_mapping(config, "ctc_decoder_conf")
    require_int(ctc_decoder_conf, "encoder_dim", 512, "ctc_decoder_conf")
    require_int(ctc_decoder_conf, "llm_dim", 512, "ctc_decoder_conf")
    require_int(ctc_decoder_conf, "n_layer", 5, "ctc_decoder_conf")
    require_int(ctc_decoder_conf, "downsample_rate", 1, "ctc_decoder_conf")

    frontend_conf = require_mapping(config, "frontend_conf")
    require_int(frontend_conf, "fs", 16000, "frontend_conf")
    require_int(frontend_conf, "frame_shift", 10, "frontend_conf")
    require_int(frontend_conf, "lfr_n", 6, "frontend_conf")
    if frontend_conf["frame_shift"] * frontend_conf["lfr_n"] != OFFICIAL_CTC_FRAME_PERIOD_MS:
        raise RuntimeError("Frontend config no longer maps to the official 60 ms CTC frame period")

    dataset_conf = require_mapping(config, "dataset_conf")
    if dataset_conf.get("ctc_tokenizer") != "SenseVoiceTokenizer":
        raise RuntimeError(
            f"Expected dataset_conf.ctc_tokenizer=SenseVoiceTokenizer, got "
            f"{dataset_conf.get('ctc_tokenizer')!r}"
        )
    require_int(dataset_conf, "audio_encoder_downsample_rate", 6, "dataset_conf")
    ctc_tokenizer_conf = require_mapping(dataset_conf, "ctc_tokenizer_conf")
    if ctc_tokenizer_conf.get("is_multilingual") is not True:
        raise RuntimeError("SenseVoice CTC tokenizer must remain multilingual")
    require_int(ctc_tokenizer_conf, "num_languages", 8749, "dataset_conf.ctc_tokenizer_conf")

    configured_vocab_path = ctc_tokenizer_conf.get("vocab_path")
    if configured_vocab_path is not None and not isinstance(configured_vocab_path, str):
        raise RuntimeError(
            f"Unexpected dataset_conf.ctc_tokenizer_conf.vocab_path type: "
            f"{type(configured_vocab_path).__name__}"
        )
    if isinstance(configured_vocab_path, str) and os.path.isabs(configured_vocab_path):
        raise RuntimeError(
            "A3 ModelScope config unexpectedly pins an absolute CTC tokenizer path: "
            f"{configured_vocab_path}"
        )
    tokenizer_resolution = (
        "official loader fallback: model_dir/multilingual.tiktoken"
        if not configured_vocab_path
        else f"official loader relative path with model_dir fallback: {configured_vocab_path}"
    )

    configured_vocab_size = single_int(config, "ctc_vocab_size")
    if configured_vocab_size is None:
        vocab_size = OFFICIAL_DEFAULT_CTC_VOCAB_SIZE
        vocab_source = "official FunASR source default; A3 heavy export must cross-check CTC projection"
    else:
        vocab_size = configured_vocab_size
        vocab_source = "config.yaml"
    blank_id = single_int(config, "blank_id")
    if blank_id is None:
        blank_id = vocab_size - 1
        blank_source = "official FunASR source default: ctc_vocab_size - 1"
    else:
        blank_source = "config.yaml"
    if blank_id != vocab_size - 1:
        raise RuntimeError(f"Unexpected CTC blank id {blank_id} for vocab size {vocab_size}")

    tokenizer_sha = sha256_file(tokenizer_path)
    if tokenizer_sha != OFFICIAL_TOKENIZER_SHA256:
        raise RuntimeError(
            "ModelScope multilingual.tiktoken digest differs from the official FunASR artifact "
            f"cross-check: {tokenizer_sha}"
        )

    encoding, mergeable_ranks = build_tiktoken(tokenizer_path)
    ranks = sorted(mergeable_ranks.values())
    if not ranks or ranks[0] != 0 or len(ranks) != len(set(ranks)):
        raise RuntimeError("multilingual.tiktoken has invalid/duplicate mergeable ranks")
    if any(bytes([byte]) not in mergeable_ranks for byte in range(256)):
        raise RuntimeError("multilingual.tiktoken is missing one or more base-byte tokens")
    if max(ranks) >= blank_id:
        raise RuntimeError(
            f"Tokenizer mergeable rank {max(ranks)} collides with/extends beyond blank id {blank_id}"
        )

    known_xi = encoding.encode_ordinary("郗")
    if known_xi != [10958, 245]:
        raise RuntimeError(f"Pinned SenseVoice tokenizer fixture drift: 郗 -> {known_xi}")

    vector_path = args.out / "sensevoice-ctc-parity.tsv"
    with vector_path.open("w", encoding="utf-8", newline="\n") as handle:
        for text in FIXTURES:
            token_ids = encoding.encode_ordinary(text)
            roundtrip = encoding.decode(token_ids)
            if roundtrip != text:
                raise RuntimeError(
                    f"Reference tiktoken roundtrip failed for {text!r}: {roundtrip!r}"
                )
            encoded_text = base64.b64encode(text.encode("utf-8")).decode("ascii")
            handle.write(
                f"{encoded_text}\t{','.join(str(token_id) for token_id in token_ids)}\n"
            )

    summary = {
        "model_id": MODEL_ID,
        "requested_revision": args.revision,
        "model_pt_remote_size": int(getattr(model_meta, "size", 0) or 0),
        "model_pt_remote_sha256": remote_model_sha,
        "model_pt_reported_ctc_complete_sha256": REPORTED_CTC_MODEL_SHA256,
        "model_pt_locally_downloaded": False,
        "config_sha256": sha256_file(config_path),
        "tokenizer_sha256": tokenizer_sha,
        "tokenizer_resolution": tokenizer_resolution,
        "ctc_decoder": config.get("ctc_decoder"),
        "ctc_tokenizer": dataset_conf.get("ctc_tokenizer"),
        "ctc_vocab_size": vocab_size,
        "ctc_vocab_size_source": vocab_source,
        "blank_id": blank_id,
        "blank_id_source": blank_source,
        "ctc_frame_period_ms": OFFICIAL_CTC_FRAME_PERIOD_MS,
        "mergeable_rank_count": len(mergeable_ranks),
        "mergeable_rank_max": max(ranks),
        "known_fixture_xi_ids": known_xi,
        "parity_vector_count": len(FIXTURES),
    }
    (args.out / "a3-provenance.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"TIKTOKEN_PATH={tokenizer_path}")
    print(f"PARITY_VECTORS={vector_path}")


if __name__ == "__main__":
    main()
