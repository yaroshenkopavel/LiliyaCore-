# Physical first-chat prerequisite inventory — 2026-11-22

## Confirmed on connected Windows laptop / Android phone
- ADB connected Redmi Note 12s.
- Pulled installed `pro.liliya.app.asfh.test` APK from device to Windows: 2,962,298 bytes.
- Inspected APK ZIP entries: only two `assets/` entries: `PHYSICAL_PRODUCTION_HOSTBOOTSTRAP_ASSETS.md` and `licensing-ca.crt`. Neither semantic ONNX asset is embedded.
- Targeted searches for `multilingual-e5-small*.onnx` in `C:\LiliyaUsbValidation`, `C:\Users\123\Downloads`, and `C:\Users\123\Desktop` returned no matches. This does **not** prove no copies exist elsewhere.
- Local source checkout exists at `C:\LiliyaUsbValidation\repo`, detached at `29aeaf3e748055cf8b8a9bd4e8abaed5af40394d`. Do not assume it matches the current GitHub test branch.

## Repository-provided recovery instructions
`android-app/src/androidTest/assets/PHYSICAL_PRODUCTION_HOSTBOOTSTRAP_ASSETS.md` says binary assets are operator-supplied, deliberately excluded from ordinary Git objects:
- Encoder `multilingual-e5-small-liliya-v0.1.onnx`, 118,258,170 bytes, SHA256 `11f460a6600163508a6eca0f2ccd8df9272fafbbee10579b3dafa74217b084dc`
- Tokenizer `multilingual-e5-small-tokenizer-v0.1.onnx`, 5,069,533 bytes, SHA256 `4d28a2a61017a7b222164065d832e51103fbb3a4451c4e4938a2eeb8e83e44e8`
- Qwen GGUF 1,282,439,264 bytes, SHA256 `d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5`

`android-offline-semantic-provider/ci/reproduce_semantic_model_onnx.py` is a repository-supplied reproduction path for the semantic assets, pinned to upstream multilingual-e5-small revision `fd1525a9fd15316a2d503bf26ab031a61d056e98`.

## Critical safety constraint
`PhysicalProductionHostBootstrapFirstChatInstrumentedTest` cleanup deletes the app-private **raw** Qwen GGUF. The current isolated app has a model copy. Do **not** run this test without an independently verified backup and a restoration plan.

## Remaining prerequisites
Determine where the operator-supplied semantic binaries and legitimate activation test server configuration can be obtained, validate hashes, stage assets, and rebuild the isolated test APK. No real LiliyaCore first chat has passed yet.
