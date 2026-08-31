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

`2cc6279ef481915531267ac52ce06ff3c36036a6`

Latest merge/main CI:

`33365191210` — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

## Frozen persistence baselines

Persistent Cognitive Storage v0.1, Memory Persistence Integration v0.1, Knowledge Persistence Integration v0.1 and Learning Persistence Integration v0.1 are **FROZEN**.

The delayed Learning/Persistence observability audit is closed **CLEAN for the audited Learning/Persistence production paths** through corrective PR #39.

## License Core v0.1

License Core v0.1 is **FROZEN**.

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

## Android Device Key v0.1

Android Device Key v0.1 is **FROZEN**.

Read:

- `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`
- `ANDROID_DEVICE_KEY_V0_1_FREEZE.md`

Direction:

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

Mandatory separation:

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

Frozen work includes exact generation ownership, typed failure/invalidation semantics, explicit no-same-operation fallback, defensive state detachment, privacy/redaction gates, platform-instance ABA protection, proof signing, structural enrollment, concrete Android Keystore implementation, post-generation cleanup hardening and real emulator instrumentation.

Important platform boundary:

- `:core` remains Android-framework-free;
- `:android-device-key` contains the concrete Android Keystore adapter;
- emulator instrumentation proves concrete Android Keystore runtime integration/lifecycle behavior;
- emulator success does **not** prove StrongBox/TEE availability on arbitrary user hardware;
- hardware-backed claims require runtime-observed security-level evidence on the actual device;
- v0.1 exposes only `SIGN_CHALLENGE`; no DEK unwrap capability/API is part of the frozen Device Key surface.

Final freeze evidence:

- PR #46 exact head `8565a65348d800d646a1760bf99c34579e3a00c1`;
- PR #46 exact-head run `33364507220` GREEN for Core + Android instrumentation;
- PR #46 merge `2cc6279ef481915531267ac52ce06ff3c36036a6`;
- PR #46 merge/main run `33365191210` GREEN for Core + Android instrumentation.

## Current next step

Begin Cognitive Storage Encryption v0.1 architecture and contract work.

Required direction:

`persistent cognitive payload → explicit encryption policy → exact DEK identity/generation → wrapped DEK binding → controlled device-key unwrap boundary → bounded plaintext consumer`

The new phase must define, before implementation:

- exact DEK ownership and generation semantics;
- canonical wrapped-DEK format and binding to the intended device-key/platform instance;
- explicit policy/Authority gating for unwrap/use;
- typed corruption, stale binding, key loss, invalidation, migration and recovery outcomes;
- crash consistency and persistence ordering;
- plaintext lifetime/bounded-consumer rules;
- structural observability that excludes DEKs, plaintext and secret-bearing backend exception messages;
- Android integration without retroactively expanding the frozen Device Key v0.1 contract.

## Accepted roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

## Resume procedure

1. verify current `main` SHA and latest required CI;
2. treat Android Device Key v0.1 as frozen and do not add DEK unwrap semantics back into it;
3. read the frozen persistent cognitive-storage contracts before defining encrypted-storage semantics;
4. create a dedicated Cognitive Storage Encryption v0.1 architecture/contract PR before implementation;
5. preserve exact ownership, fail-closed policy, Authority separation and privacy/observability rules throughout the new phase.
