# CURRENT STATE

Last journal update: 2026-08-31

## Current verified baseline

Current verified `main`:

`6c66017136b4fcd4fcbba62530c3ecee7fae7dc7`

Latest verified merge/main Core CI:

`33336902044` — GREEN.

## Frozen persistence baselines

- Persistent Cognitive Storage v0.1 — **FROZEN**;
- Memory Persistence Integration v0.1 — **FROZEN**;
- Knowledge Persistence Integration v0.1 — **FROZEN**;
- Learning Persistence Integration v0.1 — **FROZEN**.

Canonical persistence documents remain the subsystem contract/freeze documents under `docs/development/`.

Learning retains the explicit cross-domain crash window:

`downstream Memory/Knowledge mutation → durable Learning completion`

There is no exactly-once downstream guarantee, automatic replay, hidden retry or reconciliation.

## Learning/Persistence observability audit

The delayed Learning/Persistence observability audit was completed and corrected in PR #39.

Result: **CLEAN for the audited Learning/Persistence production paths**.

Important caveat: Foundation is not a universal throwable-message sanitizer. Callers must not forward secret-bearing throwables unsanitized because Foundation logging/diagnostics can retain `throwable.message` when a throwable is explicitly supplied.

The audited Learning/Persistence paths keep private cognitive payload and backend exception-message content out of normal operational observability and public failure rendering.

## License Core v0.1

License Core v0.1 is **FROZEN**.

Freeze checkpoint PR #35 and merge/main gate completed GREEN.

Frozen direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit policy decision → optional scoped Authority request → controlled protected use`

Mandatory separations remain:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

Offline-lease semantics are part of the frozen local policy model; future licensing-service work may issue/refresh evidence but may not weaken those fail-closed semantics.

## Android Device Key v0.1

Phase B architecture is accepted. The overall boundary is **NOT FROZEN** yet.

Canonical contract:

`ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`

Accepted direction:

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

Mandatory separation:

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

Completed implementation slices:

- PR #37 — platform-neutral models, security levels, typed failures and exact ownership;
- PR #38 — adapter abstraction and deterministic fake contracts;
- PR #40 — Core-side Android Keystore creation/resolution/security inspection boundary;
- PR #41 — proof-of-possession/signing and structural enrollment boundary, including platform-instance ABA hardening and detached capability state.

Latest accepted merge/main baseline is PR #41 on `6c66017136b4fcd4fcbba62530c3ecee7fae7dc7`, Core CI `33336902044` GREEN.

Current guarantees include:

- exact local `(DeviceKeyId, DeviceKeyGeneration)` ownership;
- opaque `DeviceKeyPlatformReference` to distinguish platform replacement under a reused alias;
- proof signing requires exact current local generation and exact platform instance;
- proof publication rechecks local state after signing;
- challenge/signature/platform/enrollment references are not rendered as raw proof material;
- enrollment evidence is structural and is not License, Capability or Authority;
- no raw private-key extraction path exists in Core;
- Core remains Android-framework-free and makes no real hardware-backed claim.

## Current next step

Proceed with Android Device Key v0.1 slice 5:

`invalidation/recovery/concurrency/privacy readiness hardening`

Before freeze, close remaining readiness concerns including typed failure granularity, explicit invalidation/recovery behavior, concurrency races, privacy/logging audit, defensive state boundaries and fallback semantics against the canonical contract.

After slice 5, perform the Android Device Key v0.1 readiness audit and slice 6 freeze checkpoint. The phase may be called frozen only after exact-head and merge/main Core CI are GREEN and all readiness/security/privacy gates are satisfied.

Real Android Keystore/StrongBox behavior still requires a future Android platform module plus Android runtime/instrumentation tests before any hardware-backed claim.

## Accepted security roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Do not start cognitive/model DEK unwrap or encrypted storage before the Device Key boundary freezes unless a separately reviewed dependency explicitly preserves phase separation.

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

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/logging-diagnostics/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
