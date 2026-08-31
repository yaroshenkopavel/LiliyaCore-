# CURRENT STATE

Last journal update: 2026-08-31

## Current verified baseline

Current verified `main`:

`91c43d83cd2077bdb3c14d6bb7cbdb20c7c4cd27`

Latest verified merge/main Core CI:

`33363644453` — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

## Frozen persistence baselines

- Persistent Cognitive Storage v0.1 — **FROZEN**;
- Memory Persistence Integration v0.1 — **FROZEN**;
- Knowledge Persistence Integration v0.1 — **FROZEN**;
- Learning Persistence Integration v0.1 — **FROZEN**.

Learning retains the explicit cross-domain crash window:

`downstream Memory/Knowledge mutation → durable Learning completion`

There is no exactly-once downstream guarantee, automatic replay, hidden retry or reconciliation.

## Learning/Persistence observability audit

The delayed Learning/Persistence observability audit was completed and corrected in PR #39.

Result: **CLEAN for the audited Learning/Persistence production paths**.

Foundation is not a universal throwable-message sanitizer. Secret-bearing throwables must not be forwarded unsanitized because Foundation logging/diagnostics can retain `throwable.message` when a throwable is explicitly supplied.

## License Core v0.1

License Core v0.1 is **FROZEN**.

Frozen direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit policy decision → optional scoped Authority request → controlled protected use`

Mandatory separations remain:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

## Android Device Key v0.1

Android Device Key v0.1 implementation and readiness gates are complete. The subsystem is at its **freeze checkpoint PR**; it becomes formally **FROZEN** only after that checkpoint PR itself passes exact-head Core + Android instrumentation, merges, and its merge/main gate is GREEN.

Canonical documents:

- `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`
- `ANDROID_DEVICE_KEY_V0_1_FREEZE.md`

Frozen direction:

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

Mandatory separation:

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

Completed implementation/readiness slices:

- PR #37 — platform-neutral models, security levels, typed failures and exact ownership;
- PR #38 — adapter abstraction and deterministic fake contracts;
- PR #40 — Core-side Android Keystore creation/resolution/security inspection boundary;
- PR #41 — proof-of-possession/signing and structural enrollment boundary, platform-instance ABA hardening and detached capability state;
- PR #43 — explicit no-same-operation fallback, malformed-metadata handling, concurrency/privacy readiness hardening;
- PR #44 — concrete `:android-device-key` module using real Android Keystore plus emulator instrumentation;
- PR #45 — final readiness cleanup removing pre-freeze `UNWRAP_WRAPPED_KEY` surface.

Verified PR #44 evidence:

- exact-head run `33360156420` — Core GREEN + Android instrumentation GREEN;
- merge `cdc2e279f5b3bf56f7c14a0f3cc00f82ff7aac09`;
- merge/main run `33362045123` — Core GREEN + Android instrumentation GREEN.

Verified PR #45 evidence:

- exact head `88c7789ba3acb689fba5083eb6586ddabf51fc33`;
- exact-head run `33363112163` — Core GREEN + Android instrumentation GREEN;
- merge `91c43d83cd2077bdb3c14d6bb7cbdb20c7c4cd27`;
- merge/main run `33363644453` — Core GREEN + Android instrumentation GREEN.

Current guarantees include exact `(DeviceKeyId, DeviceKeyGeneration)` ownership, generation-overflow fail-closed behavior, opaque platform-instance evidence, no same-operation StrongBox downgrade, typed malformed/invalidation/cleanup outcomes, exact signing/ABA checks, post-sign ownership recheck, redacted rendering, private-key non-export, and structural enrollment separation.

Concrete Android runtime evidence now exists. The emulator validates Android Keystore integration/lifecycle behavior, but it does **not** prove that arbitrary user hardware provides StrongBox or TEE. Hardware-backed claims remain conditional on runtime-observed evidence from the actual device.

No DEK unwrap API is part of v0.1. DEK wrapping/unwrap belongs to the later cognitive-storage-encryption phase and must remain separately policy/Authority bound.

## Current next step

Finish the Android Device Key v0.1 freeze checkpoint PR. Require:

`exact-head Core GREEN + Android Keystore Instrumentation GREEN → merge → merge/main Core GREEN + Android Keystore Instrumentation GREEN`

Only then mark Android Device Key v0.1 **FROZEN** and proceed to cognitive storage encryption.

## Accepted security roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

## Logging and diagnostics

Foundation Logging/Diagnostics/CoreObservability remains mandatory cross-cutting infrastructure.

Operational observability may expose approved structural IDs/generations/categories, but must not expose private key material, DEKs, raw attestation, raw proof/signature material, cognitive/model plaintext or secret-bearing exception messages.

Direct console output remains forbidden in reviewed production paths.

## Repository continuity

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains backup/migration history only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → architecture/security/privacy/logging-diagnostics/readiness audit → exact-head merge → merge/main CI GREEN → journal/freeze checkpoint`
