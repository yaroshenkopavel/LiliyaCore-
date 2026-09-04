#!/usr/bin/env python3
import hashlib
import json
import math
import shutil
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
import onnxruntime as ort
import torch
import torch.nn.functional as F
from onnxruntime.quantization import QuantType, quantize_dynamic
from onnxruntime_extensions import gen_processing_models, get_library_path
from optimum.exporters.onnx import main_export
from transformers import AutoModel, AutoTokenizer

MODEL_ID = "intfloat/multilingual-e5-small"
MODEL_REVISION = "fd1525a9fd15316a2d503bf26ab031a61d056e98"
OUTPUT_ENCODER = "multilingual-e5-small-liliya-v0.1.onnx"
OUTPUT_TOKENIZER = "multilingual-e5-small-tokenizer-v0.1.onnx"
DIMENSION = 384
MAX_TOKENS = 512
MIN_INT8_REFERENCE_COSINE = 0.995

SAMPLES = [
    "query: where did I leave my apartment keys?",
    "query: где лежат ключи от квартиры?",
    "query: де лежать ключі від квартири?",
    "passage: Після вечері ключі від квартири залишилися на кухонному столі.",
    "passage: After dinner, the apartment keys were left on the kitchen table.",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def append_mean_pooling_and_l2(source: Path, target: Path) -> None:
    model = onnx.load(source.as_posix())
    graph = model.graph
    output_names = [value.name for value in graph.output]
    if "last_hidden_state" in output_names:
        hidden = "last_hidden_state"
    elif output_names:
        hidden = output_names[0]
    else:
        raise RuntimeError("encoder ONNX graph has no outputs")

    input_names = {value.name for value in graph.input}
    if "attention_mask" not in input_names:
        raise RuntimeError(f"encoder ONNX graph lacks attention_mask input: {sorted(input_names)}")

    graph.initializer.extend([
        numpy_helper.from_array(np.asarray([2], dtype=np.int64), name="liliya_unsqueeze_axes"),
        numpy_helper.from_array(np.asarray([1], dtype=np.int64), name="liliya_reduce_axes"),
        numpy_helper.from_array(np.asarray([1e-9], dtype=np.float32), name="liliya_pool_min"),
    ])
    graph.node.extend([
        helper.make_node("Cast", ["attention_mask"], ["liliya_mask_float"], to=TensorProto.FLOAT),
        helper.make_node(
            "Unsqueeze",
            ["liliya_mask_float", "liliya_unsqueeze_axes"],
            ["liliya_mask_expanded"],
        ),
        helper.make_node(
            "Mul",
            [hidden, "liliya_mask_expanded"],
            ["liliya_masked_embeddings"],
        ),
        helper.make_node(
            "ReduceSum",
            ["liliya_masked_embeddings", "liliya_reduce_axes"],
            ["liliya_sum_embeddings"],
            keepdims=0,
        ),
        helper.make_node(
            "ReduceSum",
            ["liliya_mask_float", "liliya_reduce_axes"],
            ["liliya_sum_mask"],
            keepdims=1,
        ),
        helper.make_node(
            "Clip",
            ["liliya_sum_mask", "liliya_pool_min"],
            ["liliya_safe_sum_mask"],
        ),
        helper.make_node(
            "Div",
            ["liliya_sum_embeddings", "liliya_safe_sum_mask"],
            ["liliya_mean_embedding"],
        ),
        helper.make_node(
            "LpNormalization",
            ["liliya_mean_embedding"],
            ["embedding"],
            axis=1,
            p=2,
        ),
    ])
    del graph.output[:]
    graph.output.extend([
        helper.make_tensor_value_info(
            "embedding",
            TensorProto.FLOAT,
            ["batch_size", DIMENSION],
        )
    ])
    onnx.checker.check_model(model)
    onnx.save(model, target.as_posix())


def generate_tokenizer_model(tokenizer, target: Path) -> None:
    tokenizer_model = gen_processing_models(tokenizer, pre_kwargs={})[0]
    if tokenizer_model is None:
        raise RuntimeError("ONNX Runtime Extensions did not produce tokenizer preprocessing graph")
    onnx.checker.check_model(tokenizer_model)
    onnx.save(tokenizer_model, target.as_posix())


def tokenizer_session(path: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.register_custom_ops_library(get_library_path())
    return ort.InferenceSession(
        path.as_posix(),
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )


def normalize_1d(value) -> np.ndarray:
    array = np.asarray(value)
    if array.ndim == 2 and array.shape[0] == 1:
        array = array[0]
    return array.reshape(-1)


def identify_tokenizer_outputs(session, outputs):
    names = [item.name for item in session.get_outputs()]
    mapped = dict(zip(names, outputs))
    ids = None
    mask = None
    for name, value in mapped.items():
        lower = name.lower()
        if "input_ids" in lower or lower in {"ids", "token_ids"}:
            ids = normalize_1d(value).astype(np.int64)
        elif "attention_mask" in lower or lower == "mask":
            mask = normalize_1d(value).astype(np.int64)
    if ids is None:
        integer_outputs = [
            normalize_1d(value).astype(np.int64)
            for value in outputs
            if np.asarray(value).dtype.kind in {"i", "u"}
        ]
        if integer_outputs:
            ids = integer_outputs[0]
    if ids is None:
        raise RuntimeError(f"unable to identify tokenizer input_ids outputs: {names}")
    # The Extensions tokenizer graph is used one text at a time without padding. Some generated
    # tokenizer graphs expose auxiliary integer outputs (for example offsets) but no explicit
    # attention_mask. Never guess that an arbitrary integer output is the mask. With no padding,
    # every produced token is attended, so the exact mask is a vector of ones matching input_ids.
    if mask is None:
        mask = np.ones_like(ids, dtype=np.int64)
    return ids, mask, names


def validate_tokenizer(tokenizer, tokenizer_path: Path) -> dict:
    session = tokenizer_session(tokenizer_path)
    input_names = [item.name for item in session.get_inputs()]
    if len(input_names) != 1:
        raise RuntimeError(f"tokenizer graph must expose one string input, got {input_names}")
    input_name = input_names[0]

    sample_evidence = {}
    for text in SAMPLES:
        reference = tokenizer(
            text,
            add_special_tokens=True,
            truncation=False,
            return_attention_mask=True,
        )
        actual_outputs = session.run(None, {input_name: np.asarray([text], dtype=object)})
        actual_ids, actual_mask, output_names = identify_tokenizer_outputs(session, actual_outputs)
        expected_ids = np.asarray(reference["input_ids"], dtype=np.int64)
        expected_mask = np.asarray(reference["attention_mask"], dtype=np.int64)
        if not np.array_equal(actual_ids, expected_ids):
            raise RuntimeError(
                f"tokenizer input_ids mismatch for sample; outputs={output_names}; "
                f"expected={expected_ids.tolist()} actual={actual_ids.tolist()}"
            )
        if not np.array_equal(actual_mask, expected_mask):
            raise RuntimeError(
                f"tokenizer attention_mask mismatch; expected={expected_mask.tolist()} "
                f"actual={actual_mask.tolist()}"
            )
        sample_evidence[text[:24]] = int(actual_ids.size)

    over_bound = "query: " + " ".join(["hello"] * 600)
    expected_over = tokenizer(
        over_bound,
        add_special_tokens=True,
        truncation=False,
        return_attention_mask=True,
    )
    actual_outputs = session.run(None, {input_name: np.asarray([over_bound], dtype=object)})
    actual_ids, _, _ = identify_tokenizer_outputs(session, actual_outputs)
    if not np.array_equal(actual_ids, np.asarray(expected_over["input_ids"], dtype=np.int64)):
        raise RuntimeError("over-bound tokenizer case does not preserve exact non-truncated token IDs")
    if actual_ids.size <= MAX_TOKENS:
        raise RuntimeError("over-bound tokenizer fixture unexpectedly fits within 512 tokens")

    return {
        "input_name": input_name,
        "output_names": [item.name for item in session.get_outputs()],
        "sample_token_counts": sample_evidence,
        "over_bound_token_count": int(actual_ids.size),
    }


def encoder_inputs(session, hf_inputs):
    available = {item.name for item in session.get_inputs()}
    feeds = {}
    for name in ("input_ids", "attention_mask", "token_type_ids"):
        if name in available and name in hf_inputs:
            feeds[name] = hf_inputs[name].detach().cpu().numpy().astype(np.int64)

    if "input_ids" not in feeds or "attention_mask" not in feeds:
        raise RuntimeError(f"encoder graph inputs are incompatible: {sorted(available)}")

    # The exported BERT graph requires token_type_ids even though this multilingual E5 tokenizer
    # does not emit them for single-sequence requests. BERT segment id 0 is the exact canonical
    # value for every token in one unpaired sequence, so synthesize a shape-matched zero tensor.
    if "token_type_ids" in available and "token_type_ids" not in feeds:
        feeds["token_type_ids"] = np.zeros_like(feeds["input_ids"], dtype=np.int64)

    return feeds


def reference_embedding(model, inputs) -> np.ndarray:
    with torch.no_grad():
        hidden = model(**inputs).last_hidden_state
        mask = inputs["attention_mask"].unsqueeze(-1).to(hidden.dtype)
        pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
        normalized = F.normalize(pooled, p=2, dim=1)
    return normalized.detach().cpu().numpy().astype(np.float32)


def validate_encoder(tokenizer, torch_model, encoder_path: Path) -> dict:
    session = ort.InferenceSession(
        encoder_path.as_posix(),
        providers=["CPUExecutionProvider"],
    )
    if [item.name for item in session.get_outputs()] != ["embedding"]:
        raise RuntimeError(
            f"final encoder must expose only embedding output, got "
            f"{[item.name for item in session.get_outputs()]}"
        )

    evidence = {}
    for text in SAMPLES:
        inputs = tokenizer(
            text,
            add_special_tokens=True,
            truncation=False,
            return_tensors="pt",
        )
        if int(inputs["attention_mask"].sum().item()) > MAX_TOKENS:
            raise RuntimeError("normal validation sample exceeds 512 tokens")
        expected = reference_embedding(torch_model, inputs)[0]
        actual = np.asarray(
            session.run(["embedding"], encoder_inputs(session, inputs))[0],
            dtype=np.float32,
        )[0]
        if actual.shape != (DIMENSION,):
            raise RuntimeError(f"unexpected embedding shape {actual.shape}")
        if not np.all(np.isfinite(actual)):
            raise RuntimeError("ONNX embedding contains non-finite values")
        norm = float(np.linalg.norm(actual))
        if abs(norm - 1.0) > 1e-3:
            raise RuntimeError(f"ONNX embedding is not L2 normalized: norm={norm}")
        cosine = float(np.dot(expected, actual) / (np.linalg.norm(expected) * norm))
        max_abs = float(np.max(np.abs(expected - actual)))
        if cosine < MIN_INT8_REFERENCE_COSINE:
            raise RuntimeError(
                f"INT8 ONNX reference cosine below gate: {cosine} < "
                f"{MIN_INT8_REFERENCE_COSINE}"
            )
        evidence[text[:24]] = {
            "cosine_to_reference": cosine,
            "max_abs_error": max_abs,
        }
    return evidence


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: reproduce_semantic_model_onnx.py <output-dir>")

    root = Path(sys.argv[1]).resolve()
    root.mkdir(parents=True, exist_ok=True)
    export_dir = root / "encoder-export"
    if export_dir.exists():
        shutil.rmtree(export_dir)

    main_export(
        model_name_or_path=MODEL_ID,
        output=export_dir,
        task="feature-extraction",
        opset=17,
        revision=MODEL_REVISION,
        library_name="transformers",
        do_validation=True,
        dynamo=False,
    )
    base_encoder = export_dir / "model.onnx"
    if not base_encoder.is_file():
        candidates = sorted(export_dir.glob("*.onnx"))
        if len(candidates) != 1:
            raise RuntimeError(f"expected one exported encoder ONNX, got {candidates}")
        base_encoder = candidates[0]

    pooled_fp32 = root / "encoder-pooled-fp32.onnx"
    append_mean_pooling_and_l2(base_encoder, pooled_fp32)

    encoder_int8 = root / OUTPUT_ENCODER
    quantize_dynamic(
        model_input=pooled_fp32.as_posix(),
        model_output=encoder_int8.as_posix(),
        weight_type=QuantType.QInt8,
        per_channel=True,
    )
    onnx.checker.check_model(onnx.load(encoder_int8.as_posix()))

    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        use_fast=False,
        model_max_length=MAX_TOKENS,
    )
    fast_tokenizer = AutoTokenizer.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
        use_fast=True,
        model_max_length=MAX_TOKENS,
    )
    for text in SAMPLES:
        slow = tokenizer(text, add_special_tokens=True, truncation=False)["input_ids"]
        fast = fast_tokenizer(text, add_special_tokens=True, truncation=False)["input_ids"]
        if slow != fast:
            raise RuntimeError("fast/slow Hugging Face tokenizer mismatch on acceptance sample")

    tokenizer_path = root / OUTPUT_TOKENIZER
    generate_tokenizer_model(tokenizer, tokenizer_path)
    tokenizer_evidence = validate_tokenizer(fast_tokenizer, tokenizer_path)

    torch_model = AutoModel.from_pretrained(
        MODEL_ID,
        revision=MODEL_REVISION,
    ).eval()
    encoder_evidence = validate_encoder(fast_tokenizer, torch_model, encoder_int8)

    manifest = {
        "pipeline": "liliya-onnx-export-v0.1",
        "upstream_model": MODEL_ID,
        "upstream_revision": MODEL_REVISION,
        "encoder": {
            "file": encoder_int8.name,
            "size": encoder_int8.stat().st_size,
            "sha256": sha256(encoder_int8),
            "dimension": DIMENSION,
            "pooling": "MEAN",
            "normalization": "L2",
            "quantization": "dynamic-QInt8-per-channel",
            "reference": encoder_evidence,
        },
        "tokenizer": {
            "file": tokenizer_path.name,
            "size": tokenizer_path.stat().st_size,
            "sha256": sha256(tokenizer_path),
            "max_tokens": MAX_TOKENS,
            "reference": tokenizer_evidence,
        },
    }
    (root / "semantic-onnx-provenance.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
