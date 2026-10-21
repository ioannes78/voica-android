#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tarfile
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

EXPECTED_PACKAGE_SHA256 = "eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b"
EXPECTED_ORIGINAL_ENCODER_SHA256 = "d0246c823f2c34133ae0efee395d8a189c8f92643e3432f866939ee34d34492c"
EXPECTED_DUAL_ENCODER_SHA256 = "d5bb172a133fc4f996ede12cab43c0b9b4bcbb2922c1f967794505b9549b6d85"
EXPECTED_EMBEDDING_SHA256 = "a05d2816e284fcca29a5dccb2c14b9edeb638fd983a84cd4a447248889b6a408"
EXPECTED_LLM_SHA256 = "7f0c5a508b41474b1b1ec1cdbdefafd2cf8b3642c6915a0a425265b7b7d2c960"
EXPECTED_MERGES_SHA256 = "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"
EXPECTED_TOKENIZER_SHA256 = "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"
EXPECTED_VOCAB_SHA256 = "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"
EXPECTED_SPECIAL_IDS = {
    "<|endoftext|>": 151643,
    "<|im_start|>": 151644,
    "<|im_end|>": 151645,
}
PROMPT = "语音转写："
SYSTEM_PROMPT = "You are a helpful assistant."
MAX_NEW_TOKENS = 80
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


def extract_required(package: Path, out_dir: Path) -> dict[str, Path]:
    wanted = {
        "embedding.int8.onnx": EXPECTED_EMBEDDING_SHA256,
        "llm.int8.onnx": EXPECTED_LLM_SHA256,
        "Qwen3-0.6B/merges.txt": EXPECTED_MERGES_SHA256,
        "Qwen3-0.6B/tokenizer.json": EXPECTED_TOKENIZER_SHA256,
        "Qwen3-0.6B/vocab.json": EXPECTED_VOCAB_SHA256,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    result: dict[str, Path] = {}
    with tarfile.open(package, "r:bz2") as archive:
        members = [member for member in archive.getmembers() if member.isfile()]
        for relative, expected_sha in wanted.items():
            suffix = "/" + relative
            matches = [member for member in members if member.name.endswith(suffix)]
            if len(matches) != 1:
                raise RuntimeError(f"Expected exactly one {relative} in production tar, got {[m.name for m in matches]}")
            source = archive.extractfile(matches[0])
            if source is None:
                raise RuntimeError(f"Cannot extract {matches[0].name}")
            target = out_dir / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("wb") as handle:
                while True:
                    chunk = source.read(8 * 1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            require_sha(target, expected_sha, relative)
            result[relative] = target
    return result


class ProductionTokenizer:
    """Use only the tokenizer.json shipped inside the exact revision-1 package."""

    def __init__(self, tokenizer_json: Path):
        self.backend = Tokenizer.from_file(str(tokenizer_json))
        for token, expected in EXPECTED_SPECIAL_IDS.items():
            actual = self.backend.token_to_id(token)
            if actual != expected:
                raise RuntimeError(f"Production tokenizer special id drift for {token}: {actual} != {expected}")

    def encode(self, text: str) -> list[int]:
        return list(self.backend.encode(text, add_special_tokens=False).ids)

    def decode(self, ids: list[int]) -> str:
        return self.backend.decode(ids, skip_special_tokens=True)


def build_source_ids(tokenizer: ProductionTokenizer, audio_token_len: int) -> tuple[np.ndarray, int, int]:
    # Equivalent to the pinned revision-1 exporter's build_source_ids() for the
    # exact Voica transcription prompt. Keep this local so the parity proof does
    # not require tokenizer_config.json that is absent from the production tar.
    prefix = (
        f"<|im_start|>system\n{SYSTEM_PROMPT}<|im_end|>\n"
        f"<|im_start|>user\n{PROMPT}<|startofspeech|>"
    )
    suffix = "<|endofspeech|><|im_end|>\n<|im_start|>assistant\n"
    prefix_ids = tokenizer.encode(prefix)
    suffix_ids = tokenizer.encode(suffix)
    source_ids = np.asarray([*prefix_ids, *([0] * audio_token_len), *suffix_ids], dtype=np.int64)
    return source_ids, len(prefix_ids), audio_token_len


def make_encoder_session(path: Path, expected_outputs: list[str]) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    if [value.name for value in session.get_inputs()] != ["x"]:
        raise RuntimeError(f"Encoder input ABI drift: {[value.name for value in session.get_inputs()]}")
    actual_outputs = [value.name for value in session.get_outputs()]
    if actual_outputs != expected_outputs:
        raise RuntimeError(f"Encoder output ABI drift: {actual_outputs} != {expected_outputs}")
    return session


def generate(
    *,
    encoder_out: np.ndarray,
    tokenizer: ProductionTokenizer,
    embedding: Any,
    llm: Any,
    sample_token: Any,
    pick_last_logits_np: Any,
) -> tuple[list[int], str, dict[str, int]]:
    audio_token_len = int(encoder_out.shape[1])
    source_ids_1d, fbank_beg_idx, fake_token_len = build_source_ids(tokenizer, audio_token_len)

    text_embeds = embedding(source_ids_1d[None, :]).astype(np.float32)
    text_embeds = np.where(np.isfinite(text_embeds), text_embeds, 0.0)
    input_dtype = llm.input_dtype
    inputs_embeds = text_embeds.astype(input_dtype, copy=True)
    audio_embeds = np.asarray(encoder_out, dtype=input_dtype)
    inputs_embeds[0, fbank_beg_idx:fbank_beg_idx + fake_token_len, :] = audio_embeds[0, :fake_token_len, :]
    inputs_embeds = np.ascontiguousarray(inputs_embeds, dtype=input_dtype)

    prompt_len = int(inputs_embeds.shape[1])
    if llm.max_total_len > 0 and prompt_len >= llm.max_total_len:
        raise RuntimeError(f"Fixture exceeds production LLM context: prompt_len={prompt_len}, max={llm.max_total_len}")

    caches_k, caches_v = llm.alloc_caches(batch=1)
    cache_position = np.arange(0, prompt_len, dtype=np.int64)
    attention_mask = np.ones((1, prompt_len), dtype=np.int64)
    outs = llm.run_cpu(inputs_embeds, attention_mask, cache_position, caches_k, caches_v)
    logits = outs[0]
    for index in range(llm.num_layers):
        caches_k[index][:, 0:prompt_len, :, :] = outs[1 + 2 * index].astype(llm.cache_dtype, copy=False)
        caches_v[index][:, 0:prompt_len, :, :] = outs[1 + 2 * index + 1].astype(llm.cache_dtype, copy=False)
    next_logits = pick_last_logits_np(logits, prompt_len=prompt_len)
    past_len = prompt_len

    generated: list[int] = []
    eos_token_id = EXPECTED_SPECIAL_IDS["<|im_end|>"]
    im_end_token_id = EXPECTED_SPECIAL_IDS["<|im_end|>"]
    for step in range(MAX_NEW_TOKENS):
        if llm.max_total_len > 0 and past_len >= llm.max_total_len:
            break
        token = int(
            sample_token(
                next_logits,
                temperature=0.0,
                top_p=1.0,
                eos_token_id=eos_token_id,
                im_end_token_id=im_end_token_id,
                step=step,
            )
        )
        generated.append(token)
        if step > 0 and token in (eos_token_id, im_end_token_id):
            break

        token_embed = embedding(np.asarray([[token]], dtype=np.int64)).astype(input_dtype, copy=False)
        token_embed = np.ascontiguousarray(token_embed, dtype=input_dtype)
        cache_position = np.asarray([past_len], dtype=np.int64)
        attention_mask = np.ones((1, past_len + 1), dtype=np.int64)
        outs = llm.run_cpu(token_embed, attention_mask, cache_position, caches_k, caches_v)
        for index in range(llm.num_layers):
            caches_k[index][:, past_len:past_len + 1, :, :] = outs[1 + 2 * index].astype(llm.cache_dtype, copy=False)
            caches_v[index][:, past_len:past_len + 1, :, :] = outs[1 + 2 * index + 1].astype(llm.cache_dtype, copy=False)
        past_len += 1
        next_logits = outs[0][0, 0, :]
        if np.any(~np.isfinite(next_logits)):
            next_logits = np.where(np.isfinite(next_logits), next_logits, -1e9).astype(np.float32, copy=False)

    if not generated:
        raise RuntimeError("Production LLM generated no tokens for parity fixture")
    return generated, tokenizer.decode(generated), {
        "audio_token_len": audio_token_len,
        "fake_token_len": fake_token_len,
        "prompt_len": prompt_len,
        "generated_count": len(generated),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--h2c-root", type=Path, required=True)
    parser.add_argument("--exporter-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    h2c_root = args.h2c_root.resolve()
    exporter_root = args.exporter_root.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    package = h2c_root / "sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2"
    original_encoder = h2c_root / "encoder_adaptor.int8.onnx"
    dual_encoder = h2c_root / "encoder_adaptor.dual.int8.onnx"
    for required in (package, original_encoder, dual_encoder):
        if not required.is_file():
            raise RuntimeError(f"Missing H2c-1 prerequisite: {required}")
    require_sha(package, EXPECTED_PACKAGE_SHA256, "production package")
    require_sha(original_encoder, EXPECTED_ORIGINAL_ENCODER_SHA256, "production encoder")
    require_sha(dual_encoder, EXPECTED_DUAL_ENCODER_SHA256, "dual encoder")

    assets = extract_required(package, out / "production-assets")
    tokenizer = ProductionTokenizer(assets["Qwen3-0.6B/tokenizer.json"])

    sys.path.insert(0, str(exporter_root))
    from inference import EmbeddingOnnx, UnifiedKvDeltaLLMOnnx  # type: ignore
    from utils import compute_feat, load_and_resample_audio, pick_last_logits_np, sample_token  # type: ignore

    embedding = EmbeddingOnnx(str(assets["embedding.int8.onnx"]), device="cpu")
    llm = UnifiedKvDeltaLLMOnnx(str(assets["llm.int8.onnx"]), device="cpu")
    if llm.is_cuda:
        raise RuntimeError("H2c generation parity must execute the pinned CPU INT8 path")

    original_session = make_encoder_session(original_encoder, ["encoder_out"])
    dual_session = make_encoder_session(
        dual_encoder,
        ["encoder_out", "ctc_encoder_out", "ctc_encoder_out_lens"],
    )
    input_meta = original_session.get_inputs()[0]

    cases: list[dict[str, Any]] = []
    for fixture in FIXTURES:
        wave = exporter_root / "examples" / fixture
        if not wave.is_file():
            raise RuntimeError(f"Pinned exporter fixture missing: {wave}")
        samples, sample_rate = load_and_resample_audio(str(wave))
        # The production encoder metadata is fixed at LFR window=7, shift=6.
        features = compute_feat(samples, sample_rate, 7, 6)[None, ...]
        features = np.asarray(features, dtype=np.float32)
        if features.shape[0] != 1 or features.shape[2] != 560 or features.shape[1] <= 0:
            raise RuntimeError(f"Invalid feature geometry for {fixture}: {features.shape}")

        original_out = original_session.run(["encoder_out"], {input_meta.name: features})[0]
        dual_out, ctc_state, ctc_lens = dual_session.run(None, {input_meta.name: features})
        if not np.array_equal(original_out, dual_out):
            max_abs = float(np.max(np.abs(original_out - dual_out)))
            raise RuntimeError(f"Actual-audio encoder output0 parity failed for {fixture}: max_abs={max_abs}")
        if ctc_state.shape != (1, features.shape[1], 512):
            raise RuntimeError(f"Actual-audio CTC state shape mismatch for {fixture}: {ctc_state.shape}")
        if ctc_lens.dtype != np.int64 or ctc_lens.tolist() != [features.shape[1]]:
            raise RuntimeError(f"Actual-audio CTC length mismatch for {fixture}: {ctc_lens!r}")

        original_ids, original_text, original_meta = generate(
            encoder_out=original_out,
            tokenizer=tokenizer,
            embedding=embedding,
            llm=llm,
            sample_token=sample_token,
            pick_last_logits_np=pick_last_logits_np,
        )
        dual_ids, dual_text, dual_meta = generate(
            encoder_out=dual_out,
            tokenizer=tokenizer,
            embedding=embedding,
            llm=llm,
            sample_token=sample_token,
            pick_last_logits_np=pick_last_logits_np,
        )
        if original_meta != dual_meta:
            raise RuntimeError(f"Prompt/audio geometry drift for {fixture}: {original_meta} != {dual_meta}")
        if original_ids != dual_ids:
            first = next(
                (index for index, pair in enumerate(zip(original_ids, dual_ids)) if pair[0] != pair[1]),
                min(len(original_ids), len(dual_ids)),
            )
            raise RuntimeError(
                f"Generated token parity failed for {fixture}: first_diff={first}, "
                f"original={original_ids}, dual={dual_ids}"
            )
        if original_text != dual_text:
            raise RuntimeError(f"Decoded text parity failed for {fixture}: {original_text!r} != {dual_text!r}")

        cases.append({
            "fixture": fixture,
            "wave_sha256": sha256_file(wave),
            "sample_rate": int(sample_rate),
            "sample_count": int(samples.shape[0]),
            "feature_frames": int(features.shape[1]),
            "encoder_output0_exact_equal": True,
            "encoder_output0_max_abs": 0.0,
            "ctc_encoder_shape": list(ctc_state.shape),
            "ctc_encoder_out_lens": [int(value) for value in ctc_lens.tolist()],
            "prompt_geometry": original_meta,
            "generated_token_ids": original_ids,
            "decoded_text": original_text,
            "generated_ids_exact_equal": True,
            "decoded_text_exact_equal": True,
        })

    summary = {
        "production_package_sha256": EXPECTED_PACKAGE_SHA256,
        "production_encoder_sha256": EXPECTED_ORIGINAL_ENCODER_SHA256,
        "dual_encoder_sha256": EXPECTED_DUAL_ENCODER_SHA256,
        "embedding_sha256": EXPECTED_EMBEDDING_SHA256,
        "llm_sha256": EXPECTED_LLM_SHA256,
        "tokenizer_json_sha256": EXPECTED_TOKENIZER_SHA256,
        "vocab_sha256": EXPECTED_VOCAB_SHA256,
        "merges_sha256": EXPECTED_MERGES_SHA256,
        "generation_contract": {
            "system_prompt": SYSTEM_PROMPT,
            "prompt": PROMPT,
            "temperature": 0.0,
            "top_p": 1.0,
            "max_new_tokens": MAX_NEW_TOKENS,
            "device": "CPUExecutionProvider",
            "only_changed_asset": "encoder_adaptor.int8.onnx -> graph-surgery dual-output encoder",
        },
        "cases": cases,
        "status": "H2c-2 /1 -> dual-output /2 full LLM generated-token/text exact parity PASS",
    }
    summary_path = out / "a3-h2c-generation-parity.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"SUMMARY_PATH={summary_path}")


if __name__ == "__main__":
    main()
