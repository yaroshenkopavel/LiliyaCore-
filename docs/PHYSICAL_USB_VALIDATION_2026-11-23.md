# Physical USB validation вЂ” 2026-11-23

Device: Redmi Note 12S, Android SDK 34, arm64-v8a; ADB serial IVKZ4XS8FAKJKZFY.

- Main package `pro.liliya.app` preserved; its installed certificate SHA-256 is `85bb2c4ed17744a2dfb724738b38865ea425c8f5df2ab58bfc67052d87e2f8d0`. New debug APK certificate is `29a0b922bf522641d1ff031b9e7566a7ee91f35cfe566e718baaa91ddc631626`. `adb install -r` rejected the replacement with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. No uninstall or data clearing performed.
- Isolated variant `pro.liliya.app.physicalnov22` built with `:android-app:assemblePhysicalNov22` (BUILD SUCCESSFUL, 3m04s). APK SHA-256 `3CD73F3ADFD778A1D0D2FDB20D2A1058B0D7B240ED6B7A8505E9D54C200A4BA7`, 113378377 bytes. Installed successfully, cold-start Activity reported 799ms; PID observed.
- Isolated instrumentation launcher initially used package-relative `.LiliyaProvisioningActivity` despite the actual Activity class `pro.liliya.app.LiliyaProvisioningActivity`. ADB confirmed `Error type 3` for the relative name and successful launch for the fully qualified class. The test helper now uses `LiliyaProvisioningActivity::class.java.name`.
- Rebuilt isolated instrumentation APK using `:android-app:assemblePhysicalNov22AndroidTest -PliliyaPhysicalNov22Isolation=true` (BUILD SUCCESSFUL, 2m38s). AndroidX test dependencies required online retrieval; offline attempt lacked `androidx.test.ext:junit:1.3.0` and `androidx.test:runner:1.7.0`.
- After helper fix, `LiliyaActivityLifecycleStateInstrumentedTest`: OK (2 tests), 3.672s. Earlier run before fix: 2 failures due to launcher.
- Additional tests: `LiliyaActivityAsyncStartupInstrumentedTest` PASS, `LiliyaActivitySavedStateChatTerminalInstrumentedTest` PASS, `LiliyaActivitySavedStateResumeInstrumentedTest` FAIL at line 69. Isolated rerun reproduced the same assertion failure: expected completed reply not to be visible before `onResume`, but it was visible. Needs root-cause analysis; do not mark as passed.
- Battery observed 41%; temperatures 39.7C before and 37.1C after initial checks. No Qwen3 inference was performed in this validation sequence.

Evidence logs on Windows: `C:\LiliyaServer\logs\physical-lifecycle-fixed-20261123.log`, `physical-regression-20261123.log`, `physical-savedstate-resume-repro-20261123.log`, `rebuild-physical-test-20261123-online.log`.

## Saved-state contract reconciliation

Inspection found two contradictory test expectations: `LiliyaActivitySavedStateChatTerminalInstrumentedTest` requires terminal text to render once after saved state while the completion remains unconsumed until recreation, whereas `LiliyaActivitySavedStateResumeInstrumentedTest` expected that same terminal text to remain invisible until resume. `LiliyaActivity.deliverApplicationChatCompletion` deliberately renders the terminal once when `stateSaved` is true, retains the completed task, and consumes it upon resume/recreation. Aligned the latter test's visibility assertion with that established contract, retaining the Completed-before-resume and Idle-after-resume assertions. No production code change.

- Isolated rerun after test assertion correction: OK (1 test), 1.762s.
- Combined USB instrumentation regression: OK (5 tests), 8.143s, including lifecycle recreation, async startup, saved-state resume, and terminal chat restoration.
- Evidence: `C:\LiliyaServer\logs\physical-regression-contract-20261123.log`.

## Expanded isolated instrumentation regression

USB ADB connection remained healthy; battery 42%, battery temperature 36.2 C before the run. Additional five tests executed on Redmi Note 12S with `pro.liliya.app.physicalnov22.test/androidx.test.runner.AndroidJUnitRunner`:

- `LiliyaActivityConsumedTerminalChatInstrumentedTest`
- `LiliyaActivityFirstRunAcquisitionRecreationInstrumentedTest`
- `LiliyaActivityLocalModelImportRecreationInstrumentedTest`
- `LiliyaActivitySavedStateLocalModelImportTerminalInstrumentedTest`
- `LiliyaProductSurfaceAccessibilityInstrumentedTest`

Result: `OK (5 tests)`, 9.73 seconds. Evidence: `C:\LiliyaServer\logs\physical-expanded-regression-20261123.log`. Together with previous 5-test regression this covers 10 passing instrumented test cases in the isolated variant (not a full application acceptance test).

## Existing Qwen3 NoThink physical native inference

Used already-installed `pro.liliya.android.semanticprovider.qwenfd.nothink1105.test` (not the main `pro.liliya.app` production pipeline), invoking `Qwen3NoThinkQualityInstrumentedTest#four_no_think_quality_inferences` over USB ADB. Test result: `OK (1 test)` in 41.52 seconds. Model stage SHA-256 PASS, load 3436 ms, four completed inferences 7038 / 5446 / 5240 / 4818 ms, output byte lengths 40 / 34 / 40 / 34. `CLOSE_RESULT=0`, `STAGE_REMOVED=true`; battery 42%, temperature after 37.2 C. These prove native model generation in the specialized test host, not end-to-end production LiliyaCore chat acceptance. Evidence: `C:\LiliyaServer\logs\qwen3-nothink-physical-20261123.log`.

The production `PhysicalProductionTrustedQwenRuntimeInstrumentedTest` is a different acceptance test: it requires HTTPS activation endpoint, CA certificate, trusted license verification key and an app-private pinned GGUF. Do not invoke without explicitly staged prerequisites; it also deletes a one-time activation code file as part of its protocol.

## End-to-end first-chat acceptance prerequisites (not yet executed)

The physical `DevelopmentFirstWorkingLiliyaColdStartInstrumentedTest` requires `stories15M-q4_0.gguf` (19,077,344 bytes) plus the `multilingual-e5-small-liliya-v0.1.onnx` and tokenizer test assets. Inspection of the installed isolated androidTest APK archive found zero entries matching those asset names; do not report this test as passed or execute it until fixtures are assembled. The test also recursively deletes dedicated fixture/staging and semantic directories inside its **target test app** context, so preserve the original production app and run only in isolated package. This fixture tests Stories15M, not Qwen3.

The existing full-size Qwen3 file is present on shared phone storage at `/sdcard/Download new/Qwen3-1.7B-Q4_K_M.gguf` (1,282,439,264 bytes), but that alone does not establish app-private staging, licensing, or end-to-end product chat. Phone storage observed 15 GB free. Avoid unnecessary model copies.
