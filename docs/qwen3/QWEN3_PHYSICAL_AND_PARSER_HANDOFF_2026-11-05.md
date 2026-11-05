# Qwen3 physical validation and parser handoff — 2026-11-05

Status: **physical no-think test PASS; parser CI not yet verified**.
Scope: isolated test branch `test/qwen3-timed-instrumentation-2026-11-05`. Do not change or restart the main `pro.liliya.app`, Termux, Shizuku, proot Ubuntu, or phone GitHub runner.

## Hardware and model
- Physical device: Redmi Note 12s, Android, ARM64.
- GGUF: `Qwen3-1.7B-Q4_K_M.gguf`, 1,282,439,264 bytes; SHA256 `d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5`.
- Model access via SAF grant in isolated test host.

## Earlier 20-call JNI stress test
- GitHub Actions run: `42069304647`.
- 20/20 native inference calls completed; ~111.365 s; model load ~2962 ms; individual inference ~4.6–5.8 s; PSS approximately 1.36 million KiB; temperature 34.6→36.8 °C; native close 0; staged model deleted.
- With 16-token cap and default template, output was unfinished `<think>` reasoning instead of final Russian answers. This established a quality problem, **not** a native execution failure.

## Isolated no-think test
- Test commit: `d57b2fac0bd83cc55f0863e8f9f3b5cf62a76646`.
- Artifact workflow commit: `e962d7d3e59ec5cea91d5ffb278a12b82be11e4a`.
- Successful build workflow run: `42072938367`; artifact `qwen3-nothink1105-apks` id `13435924530`.
- APK application ID: `pro.liliya.android.semanticprovider.qwenfd.nothink1105`; instrumentation APK: same ID with `.test`.
- Both APKs installed by the user using Android system installer; `pm list packages` confirmed both. An initial silent `pm install` returned `INSTALL_FAILED_USER_RESTRICTED`; no protection was disabled.
- User ran `Qwen3NoThinkQualityInstrumentedTest#four_no_think_quality_inferences`. **`OK (1 test)`, four of four calls completed**; `QWEN3_NOTHINK_STAGE_SHA256=PASS`, `CLOSE_RESULT=0`, `STAGE_REMOVED=true`.
- Model load: 2606 ms; max generated tokens: 64; individual inference: 5465, 4759, 5185, 4724 ms; test elapsed 30.677 s.
- PSS: baseline 1,388,123 KiB; post-call 1,393,315 / 1,391,342 / 1,391,264 / 1,391,278 KiB. Temperatures: 34.6, 34.6, 35.0, 35.3 °C.
- Outputs 1 and 3: `<think>\n\n</think>\n\nЗдравствуйте.` (40 UTF-8 bytes; SHA256 `dd2d8a752cbe3db23cca899a1ac69d2787a1bf1e45294a4fd3e39361951f1452`).
- Outputs 2 and 4: `<think>\n\n</think>\n\nКрасный.` (34 UTF-8 bytes; SHA256 `3beae6cc9493a8d64a91782248ef14dc428a132c2437723461f7976216da6b7a`).
- These are short completed Russian answers, but still include empty thinking tags. Four repetitions alone do not establish broad quality or long-term memory stability.

## Follow-up source changes (test branch only)
- `6ef8e058f8714d7c6f3c13fedb8b61342e2eff55`: added opt-in `Qwen3FinalAnswerParser` under `android-llama-cpp-engine/src/main/kotlin/pro/liliya/android/llamacppengine/`.
- `7b568403b9a17ccb75ba45c3186155a281708753`: seven unit tests for Russian output, incomplete thinking, blank final, malformed repeated thinking and preservation of plain text.
- `f80d6165af4e06ffc8bcc742f9ec92689fd6bed0`: GitHub Actions workflow `.github/workflows/qwen3-answer-parser-tests.yml`, invoking `:android-llama-cpp-engine:testDebugUnitTest` with the parser test class.
- **No CI PASS has been observed yet for parser tests.** Parser is not yet integrated into the main inference path; it must remain opt-in pending validation.

## Next actions
1. Inspect GitHub Actions run for `qwen3-answer-parser-tests.yml`; record exact run ID, conclusion and any failures.
2. If tests pass, assess integration boundary and add tests that preserve streaming/cancellation semantics. Avoid blindly stripping tags from all models.
3. Run additional isolated physical Russian quality/context tests only after local/CI verification; never overwrite an installed test APK with a different debug signing key without a new package ID.
4. Update this handoff with verified CI evidence and any new commit hashes. Never claim tests passed based solely on successful source commits.
