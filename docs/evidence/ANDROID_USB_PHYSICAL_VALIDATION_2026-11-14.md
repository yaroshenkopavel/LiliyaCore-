# Android USB physical validation — 2026-11-14

## Scope and provenance

- Device: physical Android 14 / ARM64 Redmi Note 12s, USB ADB connected through ARC v0.8.0 on Windows.
- Isolated package: `pro.liliya.app.asfh`; test instrumentation: `pro.liliya.app.asfh.test/androidx.test.runner.AndroidJUnitRunner`.
- Production `pro.liliya.app` package was not replaced.
- Source branch: `test/pr743-isolated-asfh-build-20261113`.
- Build 43587783350: commit `ce5830b4ed5a4c4e1b672b0ad01cdcaf66357160`, GitHub Actions success; APKs installed and physical tests executed.
- Build 43589215495: commit `3e567ae51169e0bca83fde97cb2d3f9e2ea31feb`, GitHub Actions success; APKs installed and physical tests executed.
- SHA256SUMS from second artifact matched both APKs; app signer SHA256 `b04992d3784447a6a01099f6ad2c7f7196b6cffd86c9f378cc4b406d48e7d953`, matching previously installed isolated app. Qwen3 GGUF remained in isolated app files after replacement.

## Physical tests, final installed build (3e567ae5)

All four invocations returned `OK (1 test)` via `adb shell am instrument -w -r -e class ...`:

| Test method (class) | Result | Instrumentation time |
| --- | --- | --- |
| `LiliyaActivitySavedStateResumeInstrumentedTest#terminal_after_saved_state_is_consumed_on_resume_without_recreation` | PASS | 1.965 s |
| `LiliyaActivitySavedStateChatTerminalInstrumentedTest#terminal_after_saved_state_renders_once_and_is_retained_until_recreated_activity_consumes_it` | PASS | 1.977 s |
| `LiliyaActivityLifecycleStateInstrumentedTest#in_flight_chat_recreation_reattaches_without_duplicate_inference` | PASS | 1.925 s |
| `LiliyaActivityConsumedTerminalChatInstrumentedTest#matching_terminal_result_renders_even_if_an_earlier_listener_consumed_snapshot` | PASS | 1.791 s |

## Failure and fix history

1. Initial installed isolated APK: `LiliyaActivitySavedStateResumeInstrumentedTest` FAILED at line 69. A completed reply appeared in the Activity while its state was saved. Fix `ce5830b4`: defer completion until lifecycle restoration. On `ce5830b4`, this test PASS.
2. On `ce5830b4`, `LiliyaActivitySavedStateChatTerminalInstrumentedTest` FAILED at line 105: the test still expected the old immediate render during saved state. Updated expectation in `3e567ae5` to require no render before recreation, and exactly one reply after recreation. Both lifecycle tests PASS on physical device with `3e567ae5`.
3. `ProtectedModelPackagerAndroidProbeInstrumentedTest#exact_android_packager_rejection_reason_is_visible` FAILED due to `java.io.FileNotFoundException: stories15M-q4_0.gguf`. Still unresolved; fixture missing in test packaging. This is not a demonstrated Qwen3 inference failure.
4. Prior cold-start prerequisite test failed due to missing ONNX semantic asset. Not resolved in this report.

## Earlier USB checks (before 3e567ae5)

- 11 physical PASS from initial USB validation: nine security/ASF executor tests and two ASF-H resource modes.
- ASF-H `FULL_ONLY` and `HIERARCHICAL` each PASS with 160 measured rounds and identical quality fingerprint. Device evidence class: `asf-h-physical-synthetic-runtime-summary-v1`. Actual elapsed: 1686 ms vs 1651 ms; modeled inference units: 480000 vs 204000. These are **synthetic modeled inference units**, not measured Qwen3 tokens or a proven real-world speedup.
- Subsequent physical PASS checks included Activity draft retention, in-flight recreation, accessibility, asynchronous startup, terminal chat rendering, saved-state chat terminal (before behavior fix), local model import recreation, and encrypted product-auth import.
- Test count across multiple runs is not a unique-test pass count. For a reproducible current-build suite, rerun all selected cases against the final APK.

## Outstanding acceptance gates

- Supply missing `stories15M-q4_0.gguf` test fixture or adapt packaging test to use a valid bundled fixture, then rerun.
- Restore exact ONNX semantic bundle prerequisite.
- Run actual Qwen3 inference and first production chat only after explicit activation/license and required test fixtures are present; do not equate synthetic ASF-H measurements with actual LLM inference.
- Keep complete raw instrumentation logs for final acceptance; this report records the observed status lines and failure locations.
