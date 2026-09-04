# Android Offline Semantic Provider — Third-Party Notices

This file records the third-party inputs used by the Offline Semantic Provider v0.1 evidence path.
It does not grant a license for LiliyaCore itself and does not declare the controlled benchmark or
the ephemeral CI conversion to be the accepted production GGUF artifact.

## llama.cpp / ggml

- Source: `ggml-org/llama.cpp`
- Exact revision: `0f3a71be15af836d277c9f918adfafb45732677e`
- License at that revision: MIT
- Copyright notice: `Copyright (c) 2023-2026 The ggml authors`

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## multilingual-e5-small upstream model

- Source: `intfloat/multilingual-e5-small`
- Exact revision: `fd1525a9fd15316a2d503bf26ab031a61d056e98`
- License metadata at that revision: `mit`
- Role: immutable upstream weights, tokenizer and configuration input to the reproducible conversion

The pinned model repository exposes the license through model-card/repository metadata but does not
contain a separate `LICENSE` file at this revision. Any production distribution must preserve the
applicable upstream attribution and license information and must receive a separate release/legal
review rather than treating this notice as proof of distribution clearance.

## Controlled benchmark GGUF

- Source: `TwinSunsLLC/multilingual-e5-small-gguf`
- Exact revision: `b6cac9615d4ecce28d7f22539b7322d695fc2886`
- Exact file: `multilingual-e5-small-q8_0.gguf`
- Exact size: `132439008` bytes
- SHA-256: `e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93`
- License metadata at that revision: `mit`
- Role: controlled compatibility, quality and resource benchmark only

This third-party conversion is not the accepted LiliyaCore production artifact. Its pinned model
repository also exposes license metadata without a separate `LICENSE` file at this revision.

## Ephemeral reproducible CI fixture

The provenance workflow converts the pinned upstream model using the pinned llama.cpp converter and
Q8_0 output mode. The resulting bytes are temporary CI evidence. They are not published at the
upstream model repository and are not accepted as the production artifact.

Before production readiness, the exact reviewed GGUF must be published at a real immutable location
with its repository/revision, filename, size, SHA-256, conversion provenance and required license and
attribution materials recorded together.
