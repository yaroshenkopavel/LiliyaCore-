# Physical integration checkpoint — 2026-11-22

## Device and connection
- USB ADB confirmed `device` for Redmi Note 12s (`23030RAC7Y`), Android SDK 34, ABI arm64-v8a.
- Installed `pro.liliya.app` and isolated `pro.liliya.app.asfh`, both version 0.2.0.
- Isolated app contains `files/Qwen3-1.7B-Q4_K_M.gguf` (1,282,439,264 bytes); production app `files/` has no GGUF.

## Real model inference
Executed on device via instrumentation `pro.liliya.android.semanticprovider.qwenfd.nothink1105.test/androidx.test.runner.AndroidJUnitRunner`:
`pro.liliya.android.llamacppengine.Qwen3NoThinkQualityInstrumentedTest#four_no_think_quality_inferences`.
Result: `OK (1 test)`, 4 completed calls, `QWEN3_NOTHINK_LOAD_MS=4054`, `QWEN3_NOTHINK_CLOSE_RESULT=0`, instrumentation time 63.615 seconds. This proves real model execution in the separate Qwen3 quality-test package, not in the LiliyaCore runtime.

## App end-to-end blocker
Launched isolated `pro.liliya.app.asfh` with Android launcher. Foreground Activity: `pro.liliya.app.LiliyaProvisioningActivity`; UI shows “Liliya — подготовка доступа”, “Введите код активации”, and “Активировать”. Thus first chat is not yet accessible. Source `LiliyaProvisioningActivity.kt` confirms `ProductionAndroidActivatedLicenseState.MISSING` displays activation form and runtime host opens only after successful preparation or configured runtime startup.

No activation code was guessed, no license controls bypassed, and no production package modified. Isolated app GGUF remains present.

Missing isolated fixture paths: `files/stories15M-q4_0.gguf`, `files/multilingual-e5-small-liliya-v0.1.onnx`.

## Next work
Investigate and implement a legitimate isolated test configuration or activation-profile fixture for end-to-end first-chat verification; preserve fail-closed production activation behavior. Then verify actual chat response in isolated LiliyaCore with real Qwen3 and record instrumentation evidence.
