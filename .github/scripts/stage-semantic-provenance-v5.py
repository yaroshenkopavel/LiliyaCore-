from pathlib import Path
import hashlib
import json
import os
import shutil

root = Path(os.environ["RUNNER_TEMP"]) / "semantic-onnx-download"
manifests = list(root.rglob("semantic-onnx-provenance.json"))
encoders = list(root.rglob("multilingual-e5-small-liliya-v0.1.onnx"))
tokenizers = list(root.rglob("multilingual-e5-small-tokenizer-v0.1.onnx"))
if len(manifests) != 1 or len(encoders) != 1 or len(tokenizers) != 1:
    raise SystemExit(f"unexpected semantic provenance layout: {len(manifests)}/{len(encoders)}/{len(tokenizers)}")
manifest = json.loads(manifests[0].read_text(encoding="utf-8"))

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

for path, metadata in ((encoders[0], manifest["encoder"]), (tokenizers[0], manifest["tokenizer"])):
    if path.stat().st_size != metadata["size"] or digest(path) != metadata["sha256"]:
        raise SystemExit(f"semantic provenance mismatch: {path.name}")

assets = Path("android-app/src/androidTest/assets")
assets.mkdir(parents=True, exist_ok=True)
shutil.copy2(encoders[0], assets / encoders[0].name)
shutil.copy2(tokenizers[0], assets / tokenizers[0].name)
print("SEMANTIC_TEST_ASSETS_STAGED=PASS")
