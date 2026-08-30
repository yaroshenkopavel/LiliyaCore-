# LiliyaCore — Security & Licensing v0.1 Architecture Contract

Status: **ACTIVE ROADMAP — PHASE A LICENSE CORE IMPLEMENTED / FREEZE CHECKPOINT IN PROGRESS**

## Objective

Security & Licensing uses defense in depth rather than claiming that a privileged attacker can never reverse engineer a client device.

Protected domains include application/runtime code, protected model assets, cognitive state, update artifacts, License state and cryptographic material.

Vendor-owned model/code assets and user-owned cognitive data remain separate security domains. License failure must never intentionally corrupt or destroy legitimate user memory.

## Threat model

Assume attackers may obtain APK/native binaries, copied app storage, decompiled/disassembled code, rooted/instrumented devices, RAM/process access where privileges permit it, replayed old manifests/licenses, modified local time or hostile network/update delivery.

Obfuscation, anti-debugging and client-only checks are defense-in-depth, not trust anchors.

## Security dependency direction

Accepted phase order:

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Hard invariants:

- encrypted asset possession != permission to decrypt/use;
- valid signature != entitlement decision != Authority grant;
- network origin != trust;
- prior authorization/licensing receipts are evidence, not durable future permission;
- protected capability denial is explicit and fail closed;
- security failure must not intentionally produce subtly corrupted AI output.

## Phase A — License Core v0.1

Status: **IMPLEMENTED; canonical freeze checkpoint pending final exact-head + merge/main CI gate**.

Canonical documents:

- `LICENSE_CORE_V0_1_CONTRACT.md`
- `LICENSE_CORE_V0_1_FREEZE.md`

Phase A provides:

- immutable License models;
- exact-generation ownership;
- canonical signed entitlement evidence;
- exact trusted verification-key lookup;
- typed fail-closed verification;
- explicit `LicensePolicy` / `LicenseDecision`;
- **required offline-lease policy semantics**;
- revocation/replay semantics;
- controlled License→Authority integration;
- privacy-safe observability;
- executable readiness contracts for expiry/time boundaries, stale generations, replay/revocation, default deny, concurrency/isolation and observability.

Phase A remains core-only and does not claim hardware/device security.

## Offline-first licensing

Offline behavior is explicit rather than optional.

Frozen Core policy semantics include an `offlineLeaseUntil` exclusive deadline when present, explicit suspicious time/replay fail-closed handling and no hidden global system clock.

A future Licensing Service phase may issue/refresh signed offline leases, support device transfer/revocation and provide stronger trusted-time evidence. It does not replace the Phase A local policy semantics.

Local wall clock alone is not a strong rollback detector.

## Phase B — Android device-key boundary

Next phase, not implemented yet.

Preferred device root is a non-exportable Android Keystore key, using StrongBox when available/appropriate and a documented fallback policy otherwise.

Forbidden as primary cryptographic device roots:

- IMEI;
- Android ID;
- serial number;
- advertising ID;
- hashes/concatenations of ordinary device properties treated as secret HWID keys.

Phase B must define key generation/use, security-level evidence, invalidation/recovery and enrollment/binding semantics before cognitive/model encryption depends on it.

## Key hierarchy direction

Future layers distinguish at least:

- device wrapping key;
- License/enrollment wrapping material where required;
- model asset DEKs;
- cognitive-store DEKs;
- update staging keys where required;
- public signing trust roots and rotation metadata.

Vendor model keys and user cognitive-data keys must not be unnecessarily coupled.

## Phase C — Cognitive storage encryption

Future authenticated encryption at rest for Memory, Knowledge, embeddings/vector indexes, graph/context and other persistent cognitive stores.

Hard rules:

- cognitive-store keys are not derived solely from a License token/signature;
- License expiry must not intentionally make legitimate user memory irrecoverable;
- migrations/key rotation are interruption/rollback aware;
- sensitive plaintext remains outside normal observability;
- exact version/generation ownership is preserved.

## Phase D — Protected model package/loader

Future protected assets use authenticated encryption and exact asset/version binding.

Expected direction:

`resolve exact asset → fresh License/Authority check → controlled DEK unwrap → authenticate/decrypt bounded chunk → backend consume`

Protected plaintext model files must not be intentionally staged as ordinary temporary plaintext files. License failure is explicit before inference; neural math is never intentionally corrupted to disguise denial.

## Phase E — Runtime hardening

Defense-in-depth only: release hardening, symbol minimization/stripping with private symbols retained for diagnosis, supported compiler/linker hardening, optional instrumentation risk signals, minimized plaintext/key lifetime and integrity checks.

No anti-debug/Frida/ptrace technique is a universal security root.

## Phase F — Licensing service / offline lease issuance+refresh

Future service phase may add enrollment, entitlement issuance, signed offline lease refresh, device transfer/revocation, trusted-time/rollback evidence and optional server-verified integrity signals.

Backend secrets never belong in the client.

## Phase G — Update System integration

Protected updates preserve independent trust roots for update signing and License signing.

Conceptual chain:

`Signed Update Manifest → package verification → compatibility → License policy → Authority → stage → verify → activate → health-check → commit/rollback`

A valid License never makes an unsigned package trusted; a trusted package does not itself grant entitlement.

## Phase H — Red-team/readiness

Before declaring platform protection ready, test static extraction, copied storage/device migration, replay/clock rollback, rooted/instrumented devices, plaintext temp files, crash/log secret leakage, key rotation and recovery paths.

## Authority boundary

Licensing and Authority remain separate.

Frozen Phase A path where both are required:

`fresh Verified entitlement → fresh LicensePolicy evaluation → fresh exact AuthorityRequest → AuthorityDecision`

License denial means zero Authority calls. Authority remains the permission boundary before real side effects.

## Failure semantics

Security failures are typed/explicit and fail closed: signature invalid, License expired/not-yet-valid/offline-lease expired, feature mismatch, stale replay/revocation, suspicious time, device enrollment/binding failure, key unavailable, package/store authentication failure, integrity rejection, Authority denial or recovery/re-enrollment requirement.

Protected operation performs zero side effects when authorization fails before the side-effect boundary.

## Observability/privacy

Security transitions use Foundation Logging/Diagnostics/CoreObservability.

Safe metadata is structural: License/asset/package IDs, versions/generations, key IDs/epochs and typed decision/rejection categories.

Never log private keys, DEKs, unwrapped storage/model keys, raw bearer evidence, model/cognitive plaintext or full attestation tokens by default.

## Explicitly rejected primary architectures

- HWID/IMEI/Android-ID-derived master keys;
- License signature/token as the sole cognitive database-key source;
- one startup `checkLicense()` as the only protected-use gate;
- intentionally corrupting neural math on denial;
- claiming dumpability/anti-debug checks prevent all RAM extraction;
- treating obfuscation as cryptography;
- long-lived shared decryption keys embedded in Kotlin/C++ source;
- irreversibly tying user memory recovery to a commercial token;
- executable delivery that bypasses signed Update System + Authority.

## Current transition

Once `LICENSE_CORE_V0_1_FREEZE.md` is merged and the resulting merge/main Core CI is GREEN, Phase A is fully **FROZEN** and work moves to a separately reviewed Android device-key architecture contract.