# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Project type: core-only Kotlin/JVM foundation. Concrete Android framework adapters are not yet part of the repository baseline.

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
- merge only after exact-head Core CI GREEN;
- verify merge/main CI after architectural slices;
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

`6c66017136b4fcd4fcbba62530c3ecee7fae7dc7`

Latest merge/main Core CI:

`33336902044` — GREEN.

## Frozen persistence baselines

Persistent Cognitive Storage v0.1, Memory Persistence Integration v0.1, Knowledge Persistence Integration v0.1 and Learning Persistence Integration v0.1 are **FROZEN**.

Learning retains the explicit downstream crash window:

`downstream Memory/Knowledge mutation → durable Learning completion`

There is no exactly-once downstream guarantee, automatic replay, hidden retry or reconciliation.

The delayed Learning/Persistence observability audit is closed **CLEAN for the audited Learning/Persistence production paths** through corrective PR #39.

## License Core v0.1

License Core v0.1 is **FROZEN**. Freeze PR #35 and its merge/main Core CI gate completed GREEN.

Frozen direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit LicensePolicy → LicenseDecision → optional fresh scoped Authority request`

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

Offline-lease policy semantics are already part of the frozen local model. Later service issuance/refresh may provide evidence but may not weaken the fail-closed local policy.

## Android Device Key v0.1

Phase B architecture is accepted, but the overall Device Key boundary is **NOT FROZEN**.

Canonical document:

`ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`

Direction:

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

Mandatory separation:

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

Completed slices:

- PR #37 — models/security levels/typed failures/exact ownership;
- PR #38 — adapter abstraction + deterministic fake;
- PR #40 — Core-side Keystore create/resolve/security-inspection boundary;
- PR #41 — proof-of-possession/signing + structural enrollment, platform-instance ABA hardening, defensive state detachment.

Important current semantics:

- local ownership is exact `(DeviceKeyId, DeviceKeyGeneration)`;
- Android-facing state may carry opaque `DeviceKeyPlatformReference` so a reused alias cannot hide platform replacement;
- signing re-inspects exact platform state and exact platform instance;
- proof publication rechecks local generation/state after signing;
- missing `SIGN_CHALLENGE`, missing key, stale generation or stale platform instance fail closed;
- challenge/signature/platform/enrollment references do not expose raw proof material in rendering;
- enrollment evidence is not License, Capability, Authority or Execution;
- private key material is never exported through Core APIs;
- Core has no concrete Android Keystore implementation yet and therefore makes no real hardware-backed claim.

## Current next step

Proceed with slice 5:

`invalidation/recovery/concurrency/privacy readiness hardening`

Close typed invalidation/recovery outcomes, concurrency races, fallback-policy semantics, detached-state boundaries, logging/privacy gates and any remaining readiness blockers.

Then perform the readiness audit and slice 6 freeze checkpoint. Do not call Android Device Key v0.1 frozen until exact-head and merge/main Core CI are GREEN and all required readiness/security/privacy gates are clean.

A real Android platform module plus runtime/instrumentation tests are still required before documenting actual StrongBox/TEE hardware-backed behavior.

## Accepted roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Do not jump to cognitive/model DEK unwrap or encryption before Device Key v0.1 freezes unless a separate reviewed dependency explicitly preserves the phase boundary.

## Resume procedure

1. verify current `main` SHA and latest merge/main Core CI;
2. read `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md` and the current device-key production/tests;
3. if a Device Key PR or CI gate is active, finish that exact gate before mutating the next slice;
4. otherwise continue slice 5 readiness hardening;
5. preserve License/Authority/cognitive-data separation and never infer hardware security from Core-only models.
