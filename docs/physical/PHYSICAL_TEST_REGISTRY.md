# LiliyaCore — physical test evidence registry

Updated: 2026-11-05. This is a **living, non-exhaustive inventory**, not a claim that every historical run was recovered. Each entry distinguishes observed physical execution from CI build success, source-only tests, and user-reported console output. Do not rerun completed tests solely to populate this registry.

| ID | Date | Device / channel | Test / outcome | Evidence / run | Evidence level | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| PHY-001 | 2026-09-30 | Redmi Note 12s (23030RAC7Y), USB ADB, ARM64, SDK 34 | ASF-H FULL_ONLY and HIERARCHICAL instrumentation passed; measured PSS peaks 58,945 / 59,929 KiB; verdict explicitly `MEASURED_NO_PRODUCT_BUDGET_APPLIED` | [Committed JSON](../../evidence/asf-h/asf-h-usb-adb-physical-resource-evidence-e66f4fa7.json), source commit `e66f4fa762595e204675fddb73ade3ba976839b3` | **Repository evidence inspected** | Retain raw evidence; do not misstate as product-budget acceptance |
| PHY-002 | 2026-10-08 | Redmi Note 12s self-hosted runner | ASF-H resource/thermal evidence job succeeded | GitHub run reported as succeeded in prior handoff, **run ID not yet recovered** | **Historical report only** | Locate exact run and artifact |
| PHY-003 | 2026-10-13 | Redmi Note 12s / ADB, Windows laptop | `AsfHierarchicalPhysicalResourceInstrumentedTest` FULL_ONLY and HIERARCHICAL reported `OK (1 test)` | [GitHub run 38194163562](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/38194163562): **physical job SKIPPED**; build and contract jobs SUCCESS; APK bundle artifact 11723145498 | **GitHub jobs and artifact metadata inspected; this run is NOT physical execution evidence** | Recover separate physical execution log; do not attribute device PASS to this run |
| PHY-004 | 2026-10-03 | Windows laptop licensing + Android workflow | Revoke script reported `REVOKE_LICENSE=PASS`, `REVOCATION_EPOCH_ADVANCED=PASS`, `ENTITLEMENT_STATE=REVOKED`, `ACTIVE_BINDINGS=0` | Historical console transcript; no attached artifact ID | **User-provided historical output** | Recover signed audit / device-side post-contact evidence |
| PHY-005 | 2026-11-01 | Redmi Note 12s self-hosted runner 2.338.0 | `phone-preflight`, `inventory`, physical-llama and `phone-smoke` eventually succeeded after earlier failures | Runner console timestamps in prior conversation, no exact GitHub run IDs | **User-provided runner console output** | Recover workflow run IDs and individual acceptance logs; do not collapse failures into pass |
| PHY-006 | 2026-11-02 | Redmi Note 12s self-hosted runner | Qwen3 file safe-gates attempts 1 and 2 passed; file size and magic verified, earlier SHA verified | [Run 41363748804](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/41363748804), [SHA run 41362940094](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/41362940094); [plan](QWEN3_EXISTING_FILE_EXECUTION_PLAN_2026-11-02.md) | **Repository documentation inspected** | Do not misstate file preflight as native inference |
| PHY-007 | 2026-11-05 | Redmi Note 12s isolated Qwen3 JNI | 20/20 inferences completed; ~111.365 s; 16-token cap led to incomplete thinking output; PSS stable, cleanup passed | [APK build [run 42069304647](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/42069304647) (job SUCCESS, artifact 13437300132, not physical execution), [handoff](../qwen3/QWEN3_PHYSICAL_AND_PARSER_HANDOFF_2026-11-05.md) | **Physical results documented from earlier execution; build job independently verified** | Preserve original full log as artifact where available |
| PHY-008 | 2026-11-05 | Redmi Note 12s isolated Qwen3 JNI | 4/4 `/no_think` generations, Russian final answers `Здравствуйте.` and `Красный.`; model load 2606 ms; inferences 5465/4759/5185/4724 ms; PSS ~1.39 million KiB; 34.6–35.3 C; close 0 and cleanup true; `OK (1 test)` | **User-supplied full instrumentation console output** in chat; build [run 42072938367](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/42072938367) (job SUCCESS, artifact 13435924530), [handoff](../qwen3/QWEN3_PHYSICAL_AND_PARSER_HANDOFF_2026-11-05.md) | **Direct physical log observed; CI run is build, not physical execution** | Archive device log with SHA256 if available |

## Inventory rules

1. **Evidence levels:** repository evidence inspected; direct console log observed; historical report; source-only test present; pending/unverified. A test source file alone is **not** evidence that it ran.
2. **Distinct outcomes:** `workflow SUCCESS` may indicate only build/preflight; `OK (1 test)` plus physical-device evidence establishes instrumentation execution. Preserve failures and retries.
3. **Required fields for future entries:** test ID, UTC date, physical device/ABI, exact test class and package, Git SHA, workflow run and attempt, artifact/log link, verdict, model identity if relevant, memory/thermal where relevant, cleanup and known limitations.
4. **Safety:** do not restart Termux, Shizuku, proot Ubuntu, phone runner or main `pro.liliya.app`; do not install test APK over an existing package signed by a different CI debug key.
5. **Scope:** this register is an initial historical reconciliation. Earlier Keystore, provisioning, licensing, ASF and semantic tests require artifact-level enumeration before claiming complete coverage.

## Related source-only tests discovered (execution not established by file existence)
- `android-device-key/src/androidTest/kotlin/pro/liliya/android/devicekey/AndroidSystemKeystorePlatformInstrumentedTest.kt`
- `android-app/src/androidTest/kotlin/pro/liliya/app/PhysicalLaptopRevocationPostContactInstrumentedTest.kt`
- `android-app/src/androidTest/kotlin/pro/liliya/app/PhysicalProductionTrustedQwenRuntimeInstrumentedTest.kt`
- `android-semantic-test-host/src/androidTest/kotlin/pro/liliya/android/semanticprovider/host/Qwen3LocalFdBridgeInstrumentedTest.kt`
- `android-durable-persistence/src/androidTest/kotlin/pro/liliya/android/persistence/AsfAtomicArtifactDurabilityInstrumentedTest.kt`

## Unresolved audit work
- Enumerate all past GitHub Actions physical runs and artifacts, reconcile duplicates and failed attempts.
- Find archived device-side instrumentation logs and add exact SHAs / run attempt IDs.
- Link production license revocation to device-side post-contact results, not only backend script output.
- Preserve verified test evidence in stable repository docs, not solely ephemeral Actions artifacts.

## GitHub Actions metadata cross-check — 2026-11-05

Connector query checked latest-attempt jobs and artifact metadata (first page) for five run IDs:

| Run | Jobs (verified) | Artifacts (verified) | Physical execution conclusion |
| --- | --- | --- | --- |
| [38194163562](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/38194163562) | Contract SUCCESS; APK build SUCCESS; **physical ARM64 SKIPPED** | `11723145498`, `asf-h-physical-test-bundle-fa721c4dc571c9f7ffee67b1dabd15378832a4a5` | **NOT demonstrated by this run** |
| [41363748804](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/41363748804) | safe-gates SUCCESS | None returned | File preflight only |
| [41362940094](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/41362940094) | gguf-file SUCCESS | None returned | File identity only |
| [42069304647](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/42069304647) | build SUCCESS | `13437300132`, `qwen3-diag1105-apks` | Build only; 20-call physical result from separate phone log |
| [42072938367](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/42072938367) | build SUCCESS | `13435924530`, `qwen3-nothink1105-apks` | Build only; 4-call physical result from user-supplied instrumentation output |

Artifact metadata is not the same as downloading and inspecting artifact contents. An expired/retained status is point-in-time. Do not infer that a skipped physical job passed because the workflow or its build jobs succeeded.

## Further audit — ASF603 supplemental workflow (2026-11-05)

- [Run 38258728790](https://github.com/yaroshenkopavel/LiliyaCore-/actions/runs/38258728790): job **`Verify pinned operator ASF603 V2 ZIP (not official ARM64 acceptance)` = SUCCESS**.
- Artifact metadata: `11751202168`, `asf603-operator-v2-supplemental-verification`, not expired at check time.
- **Classification: operator supplemental verification, not a physical ARM64 acceptance.** This run must not be counted as a successful device-side ASF test.
- GitHub commit searches for `ASF-H physical`, `license physical` and `phone-preflight` returned candidate historical commits; search hits are **leads, not proof of execution**. Exact runs and device logs still require correlation.

### Historical verification priority

1. Locate a genuine successful phone-side ASF-H run and inspect its physical job, rather than reusing run `38194163562` (physical job skipped).
2. Match license provisioning/revocation scripts to a **device-side** instrumentation log and explicit verdict.
3. Match November phone-preflight and smoke runner console timestamps to GitHub run IDs; preserve initial failures as well as later successes.
4. Archive standalone phone instrumentation logs with SHA256; the Qwen3 no-think test was run via `rish am instrument`, not as the hosted APK-build job.
