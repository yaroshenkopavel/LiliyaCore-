#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_REPO="intfloat/multilingual-e5-small"
UPSTREAM_REVISION="fd1525a9fd15316a2d503bf26ab031a61d056e98"
LLAMA_CPP_REVISION="0f3a71be15af836d277c9f918adfafb45732677e"
BENCHMARK_REPO="TwinSunsLLC/multilingual-e5-small-gguf"
BENCHMARK_REVISION="b6cac9615d4ecce28d7f22539b7322d695fc2886"
BENCHMARK_FILENAME="multilingual-e5-small-q8_0.gguf"
BENCHMARK_SHA256="e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"
BENCHMARK_SIZE="132439008"
OUTPUT_FILENAME="liliya-multilingual-e5-small-q8_0.gguf"
EXPECTED_REPRODUCED_SIZE="132438944"
EXPECTED_REPRODUCED_SHA256="167b404b82b1cd3a2d4ebd0af3a21c5c317cc9497841d1bc7e4cf0f312e58b42"

WORK_DIR="${1:-${RUNNER_TEMP:-/tmp}/liliya-semantic-provenance}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
MODEL_DIR="$WORK_DIR/upstream-model"
LLAMA_DIR="$WORK_DIR/llama.cpp"
OUTPUT_PATH="$WORK_DIR/$OUTPUT_FILENAME"
BENCHMARK_PATH="$WORK_DIR/$BENCHMARK_FILENAME"
MANIFEST_PATH="$WORK_DIR/semantic-model-provenance.txt"

rm -rf "$WORK_DIR"
mkdir -p "$MODEL_DIR"

fetch_hf_asset() {
  local repo="$1"
  local revision="$2"
  local filename="$3"
  local output="$4"
  curl --fail --location --retry 3 --retry-all-errors \
    "https://huggingface.co/${repo}/resolve/${revision}/${filename}?download=true" \
    --output "$output"
}

for filename in \
  config.json \
  model.safetensors \
  sentencepiece.bpe.model \
  special_tokens_map.json \
  tokenizer.json \
  tokenizer_config.json
do
  fetch_hf_asset "$UPSTREAM_REPO" "$UPSTREAM_REVISION" "$filename" "$MODEL_DIR/$filename"
done

ORIGINAL_CONFIG_SHA256="$(sha256sum "$MODEL_DIR/config.json" | awk '{print $1}')"
MODEL_SAFETENSORS_SHA256="$(sha256sum "$MODEL_DIR/model.safetensors" | awk '{print $1}')"
TOKENIZER_JSON_SHA256="$(sha256sum "$MODEL_DIR/tokenizer.json" | awk '{print $1}')"
SENTENCEPIECE_SHA256="$(sha256sum "$MODEL_DIR/sentencepiece.bpe.model" | awk '{print $1}')"

MODEL_DIR="$MODEL_DIR" "$PYTHON_BIN" - <<'PY'
import json
import os
from pathlib import Path

path = Path(os.environ["MODEL_DIR"]) / "config.json"
data = json.loads(path.read_text(encoding="utf-8"))

assert data.get("architectures") == ["BertModel"], data.get("architectures")
assert data.get("tokenizer_class") == "XLMRobertaTokenizer", data.get("tokenizer_class")
assert data.get("hidden_size") == 384, data.get("hidden_size")
assert data.get("max_position_embeddings") == 512, data.get("max_position_embeddings")

# Deterministic compatibility correction documented by the verified working
# conversion: the model weights are E5/XLM-R compatible while the upstream
# config advertises BertModel despite shipping an XLM-R SentencePiece tokenizer.
data["architectures"] = ["XLMRobertaModel"]
path.write_text(json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY

PATCHED_CONFIG_SHA256="$(sha256sum "$MODEL_DIR/config.json" | awk '{print $1}')"

git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_CPP_REVISION"
git -C "$LLAMA_DIR" checkout --detach FETCH_HEAD
test "$(git -C "$LLAMA_DIR" rev-parse HEAD)" = "$LLAMA_CPP_REVISION"

"$PYTHON_BIN" -m pip install --disable-pip-version-check -r "$LLAMA_DIR/requirements/requirements-convert_hf_to_gguf.txt"

"$PYTHON_BIN" "$LLAMA_DIR/convert_hf_to_gguf.py" \
  "$MODEL_DIR" \
  --outfile "$OUTPUT_PATH" \
  --outtype q8_0

test -f "$OUTPUT_PATH"

REPRODUCED_SHA256="$(sha256sum "$OUTPUT_PATH" | awk '{print $1}')"
REPRODUCED_SIZE="$(stat --format=%s "$OUTPUT_PATH")"
test "$REPRODUCED_SHA256" = "$EXPECTED_REPRODUCED_SHA256"
test "$REPRODUCED_SIZE" = "$EXPECTED_REPRODUCED_SIZE"

fetch_hf_asset "$BENCHMARK_REPO" "$BENCHMARK_REVISION" "$BENCHMARK_FILENAME" "$BENCHMARK_PATH"
ACTUAL_BENCHMARK_SHA256="$(sha256sum "$BENCHMARK_PATH" | awk '{print $1}')"
ACTUAL_BENCHMARK_SIZE="$(stat --format=%s "$BENCHMARK_PATH")"
test "$ACTUAL_BENCHMARK_SHA256" = "$BENCHMARK_SHA256"
test "$ACTUAL_BENCHMARK_SIZE" = "$BENCHMARK_SIZE"

if [ "$REPRODUCED_SHA256" = "$BENCHMARK_SHA256" ] && [ "$REPRODUCED_SIZE" = "$BENCHMARK_SIZE" ]; then
  BYTE_EQUIVALENT="true"
else
  BYTE_EQUIVALENT="false"
fi

cat > "$MANIFEST_PATH" <<EOF
semantic_model_provenance_version=1
upstream_repository=$UPSTREAM_REPO
upstream_revision=$UPSTREAM_REVISION
original_config_sha256=$ORIGINAL_CONFIG_SHA256
model_safetensors_sha256=$MODEL_SAFETENSORS_SHA256
tokenizer_json_sha256=$TOKENIZER_JSON_SHA256
sentencepiece_sha256=$SENTENCEPIECE_SHA256
config_correction=BertModel->XLMRobertaModel
patched_config_sha256=$PATCHED_CONFIG_SHA256
conversion_tool=ggml-org/llama.cpp/convert_hf_to_gguf.py
conversion_tool_revision=$LLAMA_CPP_REVISION
conversion_outtype=q8_0
reproduced_filename=$OUTPUT_FILENAME
reproduced_size=$REPRODUCED_SIZE
reproduced_sha256=$REPRODUCED_SHA256
expected_reproduced_size=$EXPECTED_REPRODUCED_SIZE
expected_reproduced_sha256=$EXPECTED_REPRODUCED_SHA256
benchmark_repository=$BENCHMARK_REPO
benchmark_revision=$BENCHMARK_REVISION
benchmark_filename=$BENCHMARK_FILENAME
benchmark_size=$BENCHMARK_SIZE
benchmark_sha256=$BENCHMARK_SHA256
byte_equivalent_to_benchmark=$BYTE_EQUIVALENT
EOF

cat "$MANIFEST_PATH"

echo "PROVENANCE_MANIFEST=$MANIFEST_PATH"
echo "REPRODUCED_MODEL=$OUTPUT_PATH"
