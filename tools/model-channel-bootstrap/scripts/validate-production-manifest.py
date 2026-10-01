#!/usr/bin/env python3
import hashlib
import json
import re
import sys
from pathlib import Path

SHA256 = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "manifests/production.json")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("channel") != "production":
        fail("manifest channel must be production")
    models = payload.get("models")
    if not isinstance(models, list) or not models:
        fail("manifest models must be a non-empty array")

    canonical = json.dumps(models, ensure_ascii=False, separators=(",", ":"))
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    declared = str(payload.get("manifestDigest", "")).lower()
    if declared != digest:
        fail(f"manifestDigest mismatch: declared={declared} computed={digest}")

    ids = []
    for model in models:
        model_id = model.get("modelId")
        if not isinstance(model_id, str) or not model_id:
            fail("every model requires modelId")
        ids.append(model_id)
        source_type = model.get("sourceType")
        download = model.get("download")
        if source_type == "MANAGED_DOWNLOAD" and not isinstance(download, dict):
            fail(f"{model_id}: managed model requires download object")
        if isinstance(download, dict):
            url = download.get("url", "")
            if not isinstance(url, str) or not url.startswith("https://"):
                fail(f"{model_id}: download url must use https")
            package_sha = str(download.get("sha256", "")).lower()
            if not SHA256.fullmatch(package_sha):
                fail(f"{model_id}: invalid package SHA-256")
        files = model.get("files")
        if not isinstance(files, list) or not files:
            fail(f"{model_id}: files must be non-empty")
        for item in files:
            file_sha = str(item.get("sha256", "")).lower()
            if not SHA256.fullmatch(file_sha):
                fail(f"{model_id}: invalid file SHA-256")
            if int(item.get("sizeBytes", -1)) < 0:
                fail(f"{model_id}: invalid file size")

    if len(ids) != len(set(ids)):
        fail("duplicate modelId in production manifest")

    print(f"OK: {path} ({len(models)} models, digest {digest})")


if __name__ == "__main__":
    main()
