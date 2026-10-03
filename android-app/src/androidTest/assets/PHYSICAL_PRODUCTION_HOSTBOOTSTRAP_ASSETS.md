# Physical Production HostBootstrap Acceptance Assets

Status: local operator-supplied physical-acceptance artifacts.

These binary artifacts are intentionally not stored as ordinary Git objects. Place them in
`android-app/src/androidTest/assets/` before building
`PhysicalProductionHostBootstrapFirstChatInstrumentedTest`.

## Semantic encoder

- file: `multilingual-e5-small-liliya-v0.1.onnx`
- bytes: `118258170`
- SHA-256: `11f460a6600163508a6eca0f2ccd8df9272fafbbee10579b3dafa74217b084dc`

## Semantic tokenizer

- file: `multilingual-e5-small-tokenizer-v0.1.onnx`
- bytes: `5069533`
- SHA-256: `4d28a2a61017a7b222164065d832e51103fbb3a4451c4e4938a2eeb8e83e44e8`

## Qwen model

The raw Qwen file is staged app-private by the physical operator and is deliberately deleted by
the acceptance test in its final cleanup.

- file: `Qwen3-1.7B-Q4_K_M.gguf`
- bytes: `1282439264`
- SHA-256: `d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5`
- pinned revision: `daeb8e2d528a760970442092f6bf1e55c3b659eb`

The test must fail closed on any size/hash mismatch.
