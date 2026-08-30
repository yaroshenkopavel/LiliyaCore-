# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Project type: core-only Kotlin/JVM foundation. Android/device adapters are not part of the current baseline.

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
- stale/ABA ownership must not delete replacement generations;
- capability is not permission; Authority is separate from Execution;
- structural provenance is evidence, not credential/capability/Authority;
- persistence, encryption, licensing, device enrollment, Authority and cognitive permission remain separate;
- private cognitive/security content stays out of normal observability;
- Foundation Logging/Diagnostics/CoreObservability must not be bypassed by direct console output;
- frozen baselines are not casually redesigned.

## Current verified baseline

Verified `main` before the License Core freeze-checkpoint PR:

`3b249b1d1f7b2c0128e8f3ca6fe4cdc449cb663b`

Latest merge/main Core CI:

`33327943577` — GREEN.

## Frozen persistence baselines

Persistent Cognitive Storage v0.1, Memory Persistence Integration v0.1, Knowledge Persistence Integration v0.1 and Learning Persistence Integration v0.1 are **FROZEN**.

Learning retains the explicit downstream crash window:

`downstream Memory/Knowledge mutation → durable Learning completion`

There is no exactly-once downstream guarantee, automatic replay, hidden retry or reconciliation.

The delayed Learning/Persistence post-freeze observability audit is now closed **CLEAN** and documented in the Learning and License freeze documents.

## License Core v0.1

Implementation slices #30–#34 are complete and verified. The current active gate is the documentation/freeze checkpoint.

Canonical documents:

- `SECURITY_LICENSING_V0_1_CONTRACT.md`
- `LICENSE_CORE_V0_1_CONTRACT.md`
- `LICENSE_CORE_V0_1_FREEZE.md`

Frozen direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit LicensePolicy → LicenseDecision → optional fresh scoped Authority request`

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

## License Core hard rules

- default deny;
- exact `(LicenseId, LicenseGeneration)` ownership;
- explicit time input, no hidden system clock;
- `notBefore` inclusive;
- `expiresAt` exclusive;
- **offline-lease semantics are mandatory in v0.1**;
- `offlineLeaseUntil` is exclusive when present;
- stale revocation/replay and suspicious time/replay state deny;
- envelope cannot select its own trust root;
- unsupported schema/algorithm, unknown key, trusted-key substitution, invalid signature and malformed canonical payload fail closed;
- exact product/feature and optional subject matching;
- old decisions/receipts are historical evidence only;
- License entitlement is not Authority;
- License denial means zero Authority calls in the integration gate;
- every License→Authority call performs a fresh License policy evaluation;
- normal observability excludes private subject, key material, raw payload and signature content;
- License expiry/denial does not intentionally destroy cognitive data.

## Freeze completion rule

Do not call License Core v0.1 fully frozen until the freeze-checkpoint PR itself has:

`exact-head Core CI GREEN → merge → merge/main Core CI GREEN`

## Next controlled stage

After that gate, start **Android device-key boundary** only through a new architecture contract.

Accepted roadmap:

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Do not jump directly to encryption/model loading before the Android device-key contract is reviewed and executable.

## Explicit non-goals of current core baseline

Current Core does not claim or implement:

- Android Keystore/StrongBox;
- hardware-backed device binding;
- attestation/Play Integrity;
- trusted monotonic device time;
- SQLite/SQLCipher cognitive storage;
- cognitive-store encryption;
- protected model decryption/streaming;
- online enrollment/refresh;
- universal anti-tamper or anti-dump protection.

## Resume procedure

1. verify current `main` SHA and latest merge/main Core CI;
2. if License Core freeze checkpoint is not yet merged+GREEN, finish that gate first;
3. otherwise read `SECURITY_LICENSING_V0_1_CONTRACT.md`, `LICENSE_CORE_V0_1_FREEZE.md` and current architecture before starting the Android device-key contract;
4. preserve all frozen exact ownership, Authority separation, privacy, observability and cognitive-data separation guarantees;
5. never infer hardware security from core-only Kotlin models.
