# Qwen3 physical acceptance using existing verified phone file — 2026-11-02

## Confirmed physical facts
- Redmi Note 12s GitHub self-hosted ARM64 runner executes successfully (safe-gates run 41363748804, attempts 1 and 2).
- Source GGUF at `/storage/emulated/0/Download new/Qwen3-1.7B-Q4_K_M.gguf`.
- Exact source identity confirmed by SHA256 in run 41362940094 (prior filename): `d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5`, 1282439264 bytes, GGUF v3. After rename, safe-gates run 41363748804 confirms new path, size and magic; it did not recompute SHA after rename.
- Recheck at 2026-11-02 01:47 UTC: battery 100%, 35.6 C, MemAvailable 1501728 kB, SwapFree 3484420 kB. These are a point-in-time observation, not a sustained-temperature guarantee.

## Current test's actual loading path
- `android-offline-semantic-provider/src/androidTest/kotlin/pro/liliya/android/semanticprovider/OfflineSemanticProviderProductionGenerationCandidateInstrumentedTest.kt` calls `provisionExactCandidate`.
- That function opens `URL(QWEN_URL).openConnection()`, streams the entire ~1.28 GB from remote into `LargeProtectedModelStagingCoordinator` as encrypted 4 MiB segments, checks SHA256, publishes, activates through `AndroidLlamaCppCognitiveModelAssembly` and infers.
- Its test first creates a 20,000-entry ONNX semantic index; this is not a minimal short-generation smoke.
- Its current policy is contextTokens=2048, maxPromptTokens=1536, maxGeneratedTokens=64, batchTokens=256, microBatchTokens=64, threadCount=4, useMmap=true.
- `withCleanRoots` deletes staging and semantic test roots; using the existing test against arbitrary production app data is not acceptable.
- Current primary bridge `tools/physical-runner/liliya_physical_rish_bridge.py` exposes fixed installation/instrumentation endpoints and does not offer a general local-file-to-test-app ingestion endpoint.

## Required design change before executing on phone
1. Add a dedicated isolated test and isolated package identity, avoiding `pro.liliya.app` and existing semantic packages.
2. Supply an explicit, fixed local-source mechanism using Android's supported URI/file access and read-only permission. Do not assume app-private Android instrumentation can open Termux's `/storage/emulated/0/Download new` path directly under scoped storage.
3. Ensure exact size and SHA256 are verified at the source boundary; do not silently fall back to network.
4. Reuse already-downloaded source bytes where permitted. If protected staging mandates separate encrypted payload storage, measure free space and clearly record any unavoidable copy; do not claim zero-copy.
5. Build and attest isolated APK identities, exact source SHA and bundle digests on hosted CI; separately approve any installation of new isolated packages before running.
6. For first smoke, omit 20k-entry ONNX rebuild, limit to 16–32 output tokens and a single simple deterministic prompt; set explicit time and memory bounds. Measure baseline, active and post-close PSS/RSS and battery/thermal. Stop for thermal danger and out-of-memory signals.
7. Confirm actual response is nonblank, native runtime completes, model is retired, and resource state recovers; preserve job logs and attach acceptance evidence.

## Explicit non-goals and hazards
- Do not launch `qwen3-candidate-physical-evidence.yml`, `qwen3-russian-context-physical-evidence.yml`, or `qwen3-rc-resource-metrics.yml` as-is: they include a remote large-download gate and potentially reset/reinstall semantic test packages.
- Do not modify, rename, delete or relocate the only verified GGUF.
- Do not claim that verified GGUF header/SHA or successful isolated llama cancellation proves full Qwen3 generation.
- No laptop dependency for preflight and hosted CI; mobile physical execution must be independently verified.

## Next engineering task
Prototype an isolated **local-source provisioning adapter** with testable URI permission handling and explicit source identity validation. Require a dry-run of file-access permissions, available disk space, battery and temperature before committing to protected staging. Retain an unmodified production package.
