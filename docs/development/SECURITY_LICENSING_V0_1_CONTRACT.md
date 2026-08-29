# LiliyaCore — Security & Licensing v0.1 Architecture Contract

Status: **ARCHITECTURE CONTRACT ONLY — NOT IMPLEMENTED**

This document defines the future security/licensing boundary for Liliya's Android runtime, protected local AI assets, cognitive state, updates and offline operation.

The objective is **defense in depth**, not a false promise that a sufficiently privileged attacker can never reverse engineer a client device. Security must combine cryptographic key isolation, signed entitlements, authenticated encrypted storage, exact ownership, Authority, update trust, runtime hardening, observability and recovery.

## 1. Protected perimeter

The system must protect at minimum:

1. **Application/runtime code** — APK/AAB-delivered application, native libraries, runtime adapters and security-sensitive configuration.
2. **Model assets** — GGUF/ORT/ExecuTorch or future model packages marked as protected.
3. **Cognitive state** — Memory, Knowledge, embeddings/vector indexes, graphs, context, conversation history, reflection/learning state and future persistent cognitive stores.
4. **Update artifacts** — Android application/runtime packages and internal Liliya packages governed by `UPDATE_SYSTEM_V0_1_CONTRACT.md`.
5. **License state** — entitlements, feature grants, expiry/offline lease, device enrollment, revocation and audit state.
6. **Cryptographic material** — device wrapping keys, model/database DEKs, update/package keys, signing trust roots and rotation metadata.

Vendor-owned model/code assets and user-owned cognitive data are different security domains. License failure must never intentionally corrupt or destroy user-owned memory.

## 2. Threat model

Assume an attacker may obtain or attempt:

- APK/native binaries and encrypted assets;
- copied application storage and backups where accessible;
- decompiled Kotlin/Java and disassembled native code;
- rooted/compromised devices;
- debugger/runtime instrumentation such as Frida/gdb on compromised environments;
- RAM/process inspection where device privilege allows it;
- replayed old manifests/licenses;
- modified local clock;
- network interception or malicious update delivery;
- removal/bypass of high-level license checks.

Do not assume anti-debugging, obfuscation, string encryption or client-only checks are trust anchors. They are delay/detection layers.

## 3. Security dependency direction

Mandatory conceptual chain:

`License Identity/Entitlement → License Policy → Device Key Boundary → Asset/Store Key Unwrap → Protected Loader/Storage → Authority → Controlled Use`

Online-assisted chain when available:

`App/Device Integrity Signal → Backend Verification → Signed License/Lease → Local Verification → Authority → Protected Use`

Hard invariants:

- encrypted asset possession != permission to decrypt/use;
- valid signature != installation/use permission;
- license entitlement != general Authority grant;
- network origin != trust;
- Kotlin/Java checks cannot be the only enforcement for protected model/native assets;
- native checks cannot be the only protection of cognitive/user data;
- prior authorization/licensing receipts are evidence, not durable future permission;
- protected capability denial is explicit and fail-closed;
- security failure must never intentionally produce subtly corrupted AI output.

## 4. Device cryptographic root

### 4.1 Android Keystore / StrongBox

Preferred device root: a **non-exportable Android Keystore key**. Prefer StrongBox when available and justified; otherwise use hardware-backed Trusted Environment when available under a documented fallback policy.

The architecture must **not** derive master keys from IMEI, Android ID, serial, advertising ID or an invented raw `HWID`.

Preferred device-key pattern:

1. generate/import a wrapping key into Android Keystore;
2. verify security level/capabilities;
3. keep model/database/update Data Encryption Keys (DEKs) out of source/binaries;
4. store DEKs only in wrapped/enveloped form;
5. unwrap/use them through the Keystore-backed boundary;
6. rotate DEKs independently from the device wrapping key when possible.

A hardware-backed key is a cryptographic device anchor, not a universal user identity.

### 4.2 Key hierarchy

Future design must distinguish at least:

- `DeviceWrappingKey` — non-exportable Keystore/StrongBox key;
- `LicenseEnvelopeKey` or equivalent entitlement-unwrapping material;
- `ModelAssetKey` per model/package/key epoch;
- `CognitiveStoreKey` per profile/store/key epoch;
- `UpdateStagingKey` where local encrypted staging requires it;
- public signing trust roots and key-rotation metadata.

Vendor model keys and user cognitive-data keys must not be unnecessarily coupled.

## 5. Licensing Core contract

Future Core should model explicit equivalents of:

- `LicenseId`
- `LicenseSubject`
- `LicenseProductId`
- `LicenseFeature`
- `LicenseEntitlement`
- `LicenseVersion`
- `LicenseIssuedAt`
- `LicenseNotBefore`
- `LicenseExpiry`
- `LicenseOfflineLease`
- `LicenseDeviceBinding`
- `LicensePolicy`
- `LicenseStatus`
- `LicenseDecision`
- `LicenseReceipt`
- `LicenseRevocationEpoch`
- `LicenseKeyId`

Names may change, responsibilities may not disappear.

### 5.1 Signed envelope

License/lease data must be signed, versioned and canonicalized. JWT/JWS may be used if the profile is tightly constrained, but JWT is not architecturally mandatory.

Validation must cover:

- trusted signing key ID;
- allowed signature algorithm;
- product/feature entitlement;
- subject/enrollment policy;
- issued/not-before/expiry semantics;
- schema/license version;
- revocation/key epoch;
- device-binding evidence where used;
- anti-replay nonce/sequence where protocol requires it.

Forbidden:

- `alg=none`;
- algorithm confusion;
- caller-selected trust root;
- arbitrary embedded verification key accepted from the license itself;
- treating signature validity as equivalent to Authority.

### 5.2 Offline-first licensing

Liliya is offline-first, so licensing must support an explicit offline policy. Possible product policies include:

- perpetual offline entitlement;
- signed time-bounded offline lease;
- feature-specific lease;
- explicit grace period;
- permanently local base capability plus online premium capabilities.

Local wall-clock alone is not a strong rollback detector. Future implementation should combine signed lease epochs, last trusted time/sequence, monotonic evidence where available, server state when online and fail-closed policy for suspicious rollback.

### 5.3 Device enrollment/binding

Binding must use **cryptographic enrollment**, not raw HWID comparison.

Preferred future flow:

`Keystore keypair/public attestation evidence → enrollment service → signed device binding → license/lease references enrolled device key`

Device transfer, device replacement, factory reset, Keystore invalidation and app-data loss require explicit re-enrollment/recovery semantics.

## 6. Model asset protection

### 6.1 Encrypted package format

Protected model assets may use authenticated chunk/tensor encryption with AES-256-GCM or ChaCha20-Poly1305.

Requirements:

- protected plaintext model file is never intentionally written to ordinary app storage as a temporary file;
- every protected chunk is authenticated;
- nonce reuse under the same key is forbidden;
- allocation/parsing-critical metadata is authenticated;
- model/package version and exact asset identity are bound into authenticated metadata;
- asset keys are never hard-coded in APK/native binaries.

### 6.2 Streaming/chunk loader

Large models should be loaded through bounded authenticated decryption:

`resolve exact asset → fresh license/Authority check → obtain/unwrap DEK → authenticate chunk → decrypt bounded buffer → backend consume → zero scratch/key material when lifecycle ends`

Exact implementation depends on llama.cpp/ExecuTorch/ORT/NPU backend behavior.

Custom tensor layout/permutation/XOR masking may be used as obfuscation only. It is not cryptography.

### 6.3 License-to-asset binding

A valid entitlement may be cryptographically required for asset-key unwrap so removing a top-level `if (licensed)` is insufficient.

Preferred boundary:

`valid signed entitlement + exact model/version + enrolled device → asset-key unwrap authorization`

Do **not** intentionally modify Attention/MLP math to produce NaNs, zeros or plausible-but-wrong output on license failure. Denial must be explicit before protected inference proceeds.

## 7. Cognitive memory / user-data protection

Persistent Memory, Knowledge, embeddings, vector indexes, graph/context and future cognitive stores must use authenticated encryption at rest.

Possible implementation: audited encrypted SQLite/SQLCipher or an application-level authenticated encrypted storage design.

Hard requirements:

- cognitive-store keys are not derived solely from a license token/signature;
- license expiry cannot intentionally make legitimate user memory irrecoverable;
- migrations are transactional/rollback-aware;
- sensitive plaintext is excluded from Logging/Diagnostics;
- key rotation supports interruption-safe migration;
- backups/exports require explicit product/user-consent policy;
- exact version/generation ownership is retained for rollback/stale protection.

User data and vendor entitlement are intentionally separate key/recovery domains.

## 8. Runtime anti-tamper / anti-dump hardening

These are secondary layers, not security roots.

Potential measures:

- release build with debugging disabled;
- symbol visibility minimization;
- shipped binary stripping while retaining private symbol files for crash diagnosis;
- compiler/linker hardening supported by Android NDK;
- native integrity checks on high-value paths;
- debugger/instrumentation detection as a risk signal;
- process dumpability restrictions where platform-compatible;
- minimized plaintext/key lifetime;
- explicit zeroization of dedicated sensitive buffers;
- avoiding secrets in immutable strings/logs/crash reports;
- integrity verification of native/model packages before use.

Hard rule: no design document may claim RAM dumping is impossible on a rooted/fully compromised device.

`ptrace`, `TracerPid`, Frida detection and similar checks can raise attacker cost but can be bypassed by sufficiently privileged attackers.

## 9. Native obfuscation

Native obfuscation is optional defense-in-depth, not an architectural dependency.

Required release posture:

- minimize exported native symbols;
- strip production deliverables where appropriate while retaining secure private symbol maps;
- never embed signing private keys or long-lived DEKs;
- minimize sensitive string literals;
- reproducibly pin/version NDK/toolchain;
- preserve crash diagnostics and CI reproducibility.

Obfuscator-LLVM/O-LLVM or alternatives may be adopted only after compatibility, performance, maintenance and reproducibility testing.

## 10. App integrity and online signals

When distributed through Google Play, Play Integrity may be used as an additional risk/attestation signal for application recognition, device integrity and licensing-related policy.

It must not be the sole protection of offline assets or offline licensing.

Backend-oriented integrity verdicts must be verified through the intended server protocol; backend secrets must never be embedded in the client.

For sideload/non-Play distribution, the same Core license/device-key model remains valid, with a different enrollment/integrity provider.

## 11. License Authority boundary

Licensing and Authority are separate.

Proposed future relationship:

`LicenseVerifier.verify(...) → LicenseDecision`

then, where protected capability requires it:

`LicenseDecision.Entitled + exact capability/scope → AuthorityRequest → AuthorityDecision`

A license cannot silently grant arbitrary execution/device control. Authority remains fail-closed and scoped.

Protected model loading should have its own trusted capability/scope, e.g. conceptually:

- `model.asset.decrypt`
- `model.inference.run`
- `cognitive.store.open`
- `license.device.enroll`
- `license.refresh`
- `update.install` / `update.activate`

Exact IDs are implementation decisions.

## 12. Update System integration

Security & Licensing and Update System must compose without bypasses.

Required chain for a protected update:

`Signed Update Manifest → package signature/hash → compatibility → license/entitlement policy → Authority → download → stage → verify → activate → health-check → commit/rollback`

Rules:

- update signing key and license signing key should be independently rotatable;
- an update package does not gain trust merely because the license permits the product;
- a license does not permit activation of an unsigned/untrusted package;
- staged encrypted models remain encrypted at rest;
- rollback must retain the key material/version metadata needed to reopen the prior viable generation;
- key rotation/migration is staged and rollback-aware.

## 13. Revocation and key rotation

Future infrastructure must support:

- signing key IDs and epochs;
- overlapping old/new verification keys during controlled rotation;
- revocation metadata;
- compromised model/package key replacement;
- license reissue;
- device re-enrollment;
- store-key rotation without deleting user data;
- update trust-root rotation through a separately trusted path.

No single hard-coded forever key should be required for the lifetime of Liliya.

## 14. Recovery, backup and device loss

Security design must define separately:

- commercial entitlement recovery;
- user cognitive-data recovery;
- device enrollment recovery;
- model re-download/re-key;
- factory reset behavior;
- corrupted Keystore state;
- device migration/export.

A commercial anti-copy mechanism must not accidentally become an unrecoverable single point of failure for user-owned memory.

## 15. Observability and privacy

Security-relevant state transitions are observable through Logging/Diagnostics, but secrets/content are redacted.

Safe structural metadata may include:

- license ID/hash or redacted stable reference;
- product/feature ID;
- key ID/epoch;
- asset/package ID/version;
- decision/rejection code;
- device-enrollment generation;
- operation correlation ID.

Never log:

- private keys;
- DEKs;
- unwrapped database/model keys;
- raw license bearer secrets;
- model plaintext chunks;
- Memory/Knowledge plaintext;
- full attestation/integrity tokens unless explicitly secured for diagnostic capture.

## 16. Failure semantics

Security/licensing failures must be typed and explicit, for example:

- license missing;
- signature invalid;
- license expired/not-yet-valid;
- feature not entitled;
- device not enrolled;
- device binding mismatch;
- revocation detected;
- clock rollback/suspicious time;
- asset key unavailable;
- package authentication failed;
- cognitive store authentication failed;
- integrity policy rejected;
- Authority denied;
- recovery/re-enrollment required.

Protected operation performs zero side effects when authorization fails before the side-effect boundary.

## 17. What this architecture intentionally rejects

The following ideas are **not accepted as primary architecture**:

1. deriving AES master keys directly from HWID/IMEI/Android ID;
2. making the license JWT signature itself the only database key derivation input;
3. one startup `checkLicense()` as the only gate;
4. intentionally corrupting neural math to hide license failure;
5. claiming `PR_SET_DUMPABLE`, `TracerPid` or Frida checks prevent all RAM extraction;
6. treating O-LLVM/obfuscation as cryptographic protection;
7. storing long-lived shared decryption keys in C++/Kotlin source;
8. tying user memory irreversibly to a commercial token;
9. allowing Liliya Network to deliver executable code that bypasses signed Update System + Authority.

## 18. Phased implementation roadmap

### Phase A — Core Security & Licensing contracts

Before Android implementation:

- immutable license models;
- exact generation ownership;
- signature/trust-root abstraction;
- `LicensePolicy` / `LicenseDecision`;
- offline lease semantics;
- revocation/key-epoch semantics;
- explicit relation to Authority;
- privacy-safe observability;
- contract tests for expiry, stale generations, replay, default deny and isolation.

### Phase B — Android device-key boundary

- Android Keystore adapter;
- StrongBox/TEE capability detection;
- non-exportable wrapping key;
- enrollment keypair/device binding;
- key invalidation/recovery behavior;
- test hardware-backed vs software fallback policy.

### Phase C — Cognitive storage encryption

- encrypted persistent Memory/Knowledge/context/vector storage;
- independent cognitive-store key hierarchy;
- migration/key rotation;
- backup/export/recovery policy;
- interruption and corruption tests.

### Phase D — Protected model package/loader

- authenticated encrypted model package format;
- server/build CLI packager;
- chunk/tensor authenticated loader;
- no plaintext temp file;
- exact asset/version binding;
- asset-key unwrap through license/device boundary;
- backend-specific memory lifecycle/zeroization.

### Phase E — Runtime hardening

- release NDK hardening;
- symbol stripping/visibility;
- optional obfuscation evaluation;
- debugger/instrumentation risk signals;
- crash/symbol management;
- integrity/self-check contracts.

### Phase F — Licensing service / offline leases

- enrollment;
- entitlement issuance;
- signed offline lease refresh;
- device transfer/revocation;
- trusted-time/rollback policy;
- optional Play Integrity-backed server signals.

### Phase G — Update System integration

- protected model/runtime packages delivered through signed Update System;
- entitlement checks before protected activation;
- key/version migration;
- rollback-compatible key retention;
- license/update trust-root rotation.

### Phase H — Red-team/readiness

Before declaring protection ready:

- APK/native static analysis;
- key extraction attempts;
- copied-storage/device-migration tests;
- replay/clock rollback tests;
- rooted/instrumented-device tests;
- model plaintext temp-file audit;
- crash/log secret-leak audit;
- update/license bypass attempts;
- recovery after lost/invalidated Keystore;
- user-memory recovery/export verification.

## 19. Definition of Done

Security & Licensing v0.1 is not ready until executable contracts and Android tests prove at minimum:

1. copied encrypted protected model assets cannot be loaded without authorized key unwrap;
2. app/native removal of a single high-level license check is insufficient to expose the asset key;
3. user cognitive stores remain authenticated encrypted at rest;
4. cognitive-store protection is not irreversibly tied to commercial license expiry;
5. stale/replayed/expired/not-entitled licenses fail closed;
6. exact device enrollment generations cannot remove/replace newer enrollment state;
7. protected operation performs zero downstream use when license/Authority is denied;
8. no plaintext protected model temp file is created by the supported loader;
9. security logs contain no key/model/memory plaintext;
10. update activation cannot bypass signature + compatibility + license policy + Authority;
11. key rotation/revocation paths are tested;
12. recovery behavior for device replacement/Keystore invalidation is documented and tested;
13. documentation explicitly states the limits of anti-debug/anti-dump protection.

## 20. Relationship to existing architecture

This contract does not weaken existing invariants:

- `Authority` remains separate from capability and Execution.
- Trust/Security structural trust anchors are not authority grants or license entitlements.
- Update System network delivery is not trust.
- Memory/Knowledge exact ownership/generation semantics remain authoritative.
- Observability/privacy contracts remain mandatory.
- future Android/native adapters cannot bypass Core security boundaries.

The intended long-term security chain is:

`Signed Entitlement → Exact Device Enrollment → Keystore-backed Key Boundary → Fresh License Policy → Authority → Protected Asset/Store Access → Controlled Operation → Observable Result`
