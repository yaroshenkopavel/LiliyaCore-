# Android Offline Semantic Provider — Third-Party Notices

This file records the third-party runtime and model inputs used by Offline Semantic Provider v0.1.
It does not grant a license for LiliyaCore itself and does not replace a release/legal review.

## ONNX Runtime Android

- Maven coordinate: `com.microsoft.onnxruntime:onnxruntime-android:1.29.0`
- Upstream project: `microsoft/onnxruntime`
- License: MIT
- Role: Android CPU inference runtime for the semantic encoder ONNX graph

The provider disables ONNX Runtime telemetry and uses the runtime only through the Android
Kotlin/Java API. The generation `android-llama-cpp-engine` remains a separate frozen module and is
not part of the semantic runtime implementation.

## ONNX Runtime Extensions Android

- Maven coordinate: `com.microsoft.onnxruntime:onnxruntime-extensions-android:0.12.4`
- Upstream project: `microsoft/onnxruntime-extensions`
- License: MIT
- Role: tokenizer custom-operator runtime used only by the tokenizer ONNX session

The tokenizer session registers the Extensions custom-op library. The encoder session does not.

## multilingual-e5-small upstream model

- Source: `intfloat/multilingual-e5-small`
- Exact revision: `fd1525a9fd15316a2d503bf26ab031a61d056e98`
- License metadata at that revision: `mit`
- Role: immutable upstream weights, tokenizer and configuration input to the reproducible ONNX export

The pinned model repository exposes the license through model-card/repository metadata but does not
contain a separate `LICENSE` file at this revision. Any production distribution must preserve the
applicable upstream attribution and license information and must receive a separate release/legal
review rather than treating this notice as proof of distribution clearance.

## Reproducible ONNX evidence bundle

The provenance workflow exports the pinned upstream model with the repository-controlled, pinned
ONNX toolchain and verifies two independent exports byte-for-byte before publishing evidence.

The export environment records its exact Python package versions in
`semantic-onnx-provenance.json`. The tokenizer graph is generated with Python
`onnxruntime-extensions==0.12.0`; Android execution uses the separately published Maven artifact
`onnxruntime-extensions-android:0.12.4`. This cross-package version difference is intentional and
must remain explicit in provenance. The generated tokenizer graph is accepted only after it passes
the Android real-model instrumentation against the pinned Android runtime.

Exact accepted v0.1 evidence identity:

- Encoder: `multilingual-e5-small-liliya-v0.1.onnx`
- Encoder size: `118258170` bytes
- Encoder SHA-256: `11f460a6600163508a6eca0f2ccd8df9272fafbbee10579b3dafa74217b084dc`
- Tokenizer: `multilingual-e5-small-tokenizer-v0.1.onnx`
- Tokenizer size: `5069533` bytes
- Tokenizer SHA-256: `4d28a2a61017a7b222164065d832e51103fbb3a4451c4e4938a2eeb8e83e44e8`

These files are reproducible acceptance/provenance evidence for the pinned semantic profile. Their
presence in CI evidence does not by itself constitute a release-distribution approval.
