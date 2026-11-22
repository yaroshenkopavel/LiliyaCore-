# Signed ASF-H ONNX APK: Windows recovery and physical installation (2026-11-22)

## Source and CI
- Repository: `yaroshenkopavel/LiliyaCore-`.
- Source commit: `01da7c411682255159c151c15241217ae878d60c`.
- Successful workflow run: `45050665317` (`pr743-isolated-asfh-build.yml`).
- Verified CI markers: `SEMANTIC_ONNX_PROVENANCE=PASS`, `ISOLATED_ASFH_BUILD=PASS`, `ASF_H_STABLE_SIGNING=PASS`.
- GitHub Actions artifact ID: `14764564887`; archive bytes: `128887038`.

## Windows independent verification
- Archive path: `C:\LiliyaUsbValidation\signed-onnx-asfh-20261122\pr743-asfh-artifact.zip`.
- Archive SHA-256: `77cd2392f52de1f085c04f4e3cbb2a1da6585aa1bd3503b1f026b4162f400b89`.
- Isolated app APK SHA-256: `8a118e036576f888bb8f0ba9241b82066da516782b4af5cfad59ecf735c67e12`; bytes `113689661`.
- Isolated test APK SHA-256: `04f48581f193d8d90f738474d3a57b42e7047d3d0fe765933d4881902e90333c`; bytes `93016139`.
- Both APKs independently verified with `apksigner` to certificate SHA-256 `b04992d3784447a6a01099f6ad2c7f7196b6cffd86c9f378cc4b406d48e7d953`.
- Test APK contains `assets/multilingual-e5-small-liliya-v0.1.onnx` (118258170 bytes) and `assets/multilingual-e5-small-tokenizer-v0.1.onnx` (5069533 bytes).

## Device installation
- Device: Redmi Note 12s, Android 14, `arm64-v8a`, ADB serial `IVKZ4XS8FAKJKZFY`.
- `adb install -r --no-streaming` test APK: `Success`.
- `adb install -r --no-streaming` isolated app APK: `Success`.
- Installed test runner: `pro.liliya.app.asfh.test/androidx.test.runner.AndroidJUnitRunner` targets `pro.liliya.app.asfh`.
- Post-install private Qwen model: `files/Qwen3-1.7B-Q4_K_M.gguf`, 1282439264 bytes (preserved).
- Production package `pro.liliya.app` remains installed and was not modified by these operations.

## Remaining acceptance gate
- Full `PhysicalProductionHostBootstrapFirstChatInstrumentedTest` was **not run**.
- Its required app-private one-time activation code file `files/physical-activation-code.once` is **absent**. The test also requires genuine HTTPS activation endpoint, trusted CA, license public key and product ID arguments. Do not fake or bypass activation.
- Next: establish legitimate activation fixture and run exact physical first-chat test, collecting instrumentation logs and resource evidence. Avoid destructive data resets.