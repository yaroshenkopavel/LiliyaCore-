# End-to-end first-chat test discovery — 2026-11-22

## Existing acceptance test (not yet PASS)
Source: `android-app/src/androidTest/kotlin/pro/liliya/app/PhysicalProductionHostBootstrapFirstChatInstrumentedTest.kt` on branch `test/pr743-isolated-asfh-build-20261113`.

Test: `PhysicalProductionHostBootstrapFirstChatInstrumentedTest#real_license_exact_qwen_reaches_ready_hostbootstrap_and_completes_first_chat`.

This is an existing end-to-end test that uses the exact local `Qwen3-1.7B-Q4_K_M.gguf` (expected 1,282,439,264 bytes and SHA-256 `d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5`), packages a protected model, provisions semantic ONNX assets from instrumentation APK, validates a real signed license, starts the runtime, and checks the first chat UI.

Required instrumentation arguments (names confirmed in source):
- `activationEndpoint`
- `activationCaBase64`
- `activationLicenseKeyDerBase64`
- `activationProductId`

The test opens `multilingual-e5-small-liliya-v0.1.onnx` and `multilingual-e5-small-tokenizer-v0.1.onnx` from **test APK assets**, not necessarily app private files. Earlier missing private ONNX path is therefore **not** proof that this test APK lacks assets; inspect APK before claiming missing assets.

## Physical attempt (inconclusive)
Attempted `LiliyaProvisioningActivityDeploymentProfileRequiredInstrumentedTest#activation_without_explicit_deployment_profile_fails_closed_before_network_or_runtime` via `pro.liliya.app.asfh.test/androidx.test.runner.AndroidJUnitRunner` on connected Redmi Note 12s. ARC command timed out at 35 seconds after `INSTRUMENTATION_STATUS_CODE: 1`. No final `OK` or failure report was obtained; mark **INCONCLUSIVE**, not PASS.

## Next
1. Verify instrumentation APK actually contains both semantic ONNX assets and inspect available test-license endpoint configuration.
2. Diagnose the timed-out instrumentation with bounded log collection; do not repeat an unbounded test.
3. Run existing end-to-end test only when required test parameters are available and verified. Preserve normal production activation fail-closed.
