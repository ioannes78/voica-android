#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
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


def string_values(config: Any, key: str) -> list[str]:
    return [value for value in recursive_values(config, key) if isinstance(value, str)]


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


def build_tiktoken(tokenizer_path: Path) -> tuple[tiktoken.Encoding, dict[bytes, int]]:
    mergeable_ranks = load_tiktoken_bpe(str(tokenizer_path))
    encoding = tiktoken.Encoding(
        name="voica-funasr-a3-sensevoice",
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

    ctc_decoder_values = string_values(config, "ctc_decoder")
    if not ctc_decoder_values or not any(value.strip() for value in ctc_decoder_values):
        raise RuntimeError("config.yaml does not enable a CTC decoder")

    ctc_tokenizer_values = string_values(config, "ctc_tokenizer")
    if not any("SenseVoice" in value for value in ctc_tokenizer_values):
        raise RuntimeError(f"Expected SenseVoice CTC tokenizer, got {ctc_tokenizer_values!r}")

    tiktoken_refs: list[str] = []

    def collect_tiktoken_refs(value: Any) -> None:
        if isinstance(value, dict):
            for child in value.values():
                collect_tiktoken_refs(child)
        elif isinstance(value, list):
            for child in value:
                collect_tiktoken_refs(child)
        elif isinstance(value, str) and value.endswith(".tiktoken"):
            tiktoken_refs.append(value)

    collect_tiktoken_refs(config)
    if not any(Path(value).name == "multilingual.tiktoken" for value in tiktoken_refs):
        raise RuntimeError(
            f"config.yaml does not reference multilingual.tiktoken: {tiktoken_refs!r}"
        )

    vocab_size = single_int(config, "ctc_vocab_size")
    if vocab_size is None:
        raise RuntimeError("config.yaml must explicitly declare ctc_vocab_size for A3 provenance")
    blank_id = single_int(config, "blank_id")
    if blank_id is None:
        blank_id = vocab_size - 1
        blank_source = "official FunASR default: ctc_vocab_size - 1 (config has no override)"
    else:
        blank_source = "config.yaml"
    if blank_id != vocab_size - 1:
        raise RuntimeError(f"Unexpected CTC blank id {blank_id} for vocab size {vocab_size}")

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
        "tokenizer_sha256": sha256_file(tokenizer_path),
        "ctc_decoder": ctc_decoder_values,
        "ctc_tokenizer": ctc_tokenizer_values,
        "ctc_vocab_size": vocab_size,
        "blank_id": blank_id,
        "blank_id_source": blank_source,
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
