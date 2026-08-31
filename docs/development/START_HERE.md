# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Project type: Kotlin core foundation with a separate concrete Android Device Key platform module.

## Source of truth

Before changing code, read:

1. `CURRENT_STATE.md`;
2. `ARCHITECTURE.md`;
3. `STRUCTURE.md`;
4. `NUANCES.md`;
5. canonical contract/freeze docs for the touched subsystem;
6. production source and executable contracts;
7. current GitHub PR/CI state.

## Hard engineering rules

- work on feature branches;
- merge only after exact-head required CI GREEN;
- verify merge/main CI after architectural/security slices;
- exact `(ID, generation)` ownership beats ID-only ownership;
- stale/ABA ownership must not delete or authorize replacement generations;
- platform alias alone is not sufficient ABA evidence when replacement can reuse it;
- capability is not permission; Authority is separate from Execution;
- structural provenance/enrollment/device evidence is not credential/capability/Authority;
- persistence, encryption, licensing, device enrollment, Authority and cognitive permission remain separate;
- private cognitive/security content stays out of normal observability;
- Foundation Logging/Diagnostics/CoreObservability must not be bypassed by direct console output;
- Foundation itself can retain throwable messages when an unsanitized throwable is supplied, so secret-bearing throwables must not be forwarded blindly;
- frozen baselines are not casually redesigned.

## Current verified baseline

Verified `main`:

`91c43d83cd2077bdb3c14d6bb7cbdb20c7c4cd27`

Latest merge/main CI:

`33363644453` — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

## Frozen persistence baselines

Persistent Cognitive Storage v0.1, Memory Persistence Integration v0.1, Knowledge Persistence Integration v0.1 and Learning Persistence Integration v0.1 are **FROZEN**.

The delayed Learning/Persistence observability audit is closed **CLEAN for the audited Learning/Persistence production paths** through corrective PR #39.

## License Core v0.1

License Core v0.1 is **FROZEN**.

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

## Android Device Key v0.1

Implementation and readiness work are complete. The subsystem is at its formal freeze checkpoint.

Read:

- `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`
- `ANDROID_DEVICE_KEY_V0_1_FREEZE.md`

Direction:

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

Mandatory separation:

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

Completed work includes exact generation ownership, typed failure/invalidation semantics, explicit no-same-operation fallback, defensive state detachment, privacy/redaction gates, platform-instance ABA protection, proof signing, structural enrollment, concrete Android Keystore implementation, post-generation cleanup hardening and real emulator instrumentation.

Important platform boundary:

- `:core` remains Android-framework-free;
- `:android-device-key` contains the concrete Android Keystore adapter;
- emulator instrumentation proves concrete Android Keystore runtime integration/lifecycle behavior;
- emulator success does **not** prove StrongBox/TEE availability on arbitrary user hardware;
- hardware-backed claims require runtime-observed security-level evidence on the actual device;
- v0.1 exposes only `SIGN_CHALLENGE`; no DEK unwrap capability/API is frozen into Device Key.

Verified final readiness evidence:

- PR #44 exact-head `33360156420` GREEN for Core + Android instrumentation;
- PR #44 merge/main `33362045123` GREEN for Core + Android instrumentation;
- PR #45 exact head `88c7789ba3acb689fba5083eb6586ddabf51fc33`;
- PR #45 exact-head `33363112163` GREEN for Core + Android instrumentation;
- PR #45 merge `91c43d83cd2077bdb3c14d6bb7cbdb20c7c4cd27`;
- PR #45 merge/main `33363644453` GREEN for Core + Android instrumentation.

## Current next step

Finish the Android Device Key v0.1 freeze checkpoint PR itself.

Required gate:

`exact-head Core GREEN + Android Keystore Instrumentation GREEN → merge → merge/main Core GREEN + Android Keystore Instrumentation GREEN`

Only after that gate is closed may Android Device Key v0.1 be called **FROZEN**.

Then proceed to cognitive storage encryption. Any wrapped-DEK/unwrap API must be introduced in that later phase through a separately reviewed exact binding/policy/Authority contract.

## Accepted roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

## Resume procedure

1. verify current `main` SHA and active freeze PR/CI;
2. if the Device Key freeze PR gate is active, finish that exact gate before any next-phase mutation;
3. after freeze merge/main GREEN, mark Device Key v0.1 FROZEN in state docs;
4. then begin cognitive storage encryption without adding DEK unwrap semantics retroactively to the frozen Device Key contract.
