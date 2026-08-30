# Android Device Key v0.1 Architecture Contract

Status: **ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET FROZEN**

## Purpose

Define Phase B of the accepted Security & Licensing roadmap: the Android device-key boundary that follows the frozen License Core v0.1 and precedes cognitive-storage encryption or protected-model key unwrap.

Selected direction:

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

This phase establishes a device cryptographic root boundary. It does not yet encrypt cognitive data or model assets, issue online licenses, or grant Authority by itself.

## Mandatory separation

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

`Keystore presence != hardware-backed proof`

`Hardware-backed proof != enrollment`

`Enrollment evidence != License entitlement`

`License entitlement != key unwrap permission != Authority`

No device-key receipt, alias, security-level report, enrollment reference or key handle may silently become a general permission token.

## Scope of Android Device Key v0.1

The implementation may introduce explicit equivalents of:

- `DeviceKeyId` / opaque key identity;
- `DeviceKeyGeneration` where exact replacement ownership is required;
- device-key algorithm/profile;
- `DeviceKeySecurityLevel`;
- `DeviceKeyCapabilities`;
- `DeviceKeyCreationRequest`;
- `DeviceKeyHandle` or opaque operation reference;
- `DeviceKeyStatus` / typed failure categories;
- structural device-enrollment reference;
- exact-generation device-key registry/state ownership where Core state is needed;
- Android Keystore adapter boundary;
- deterministic fake/in-memory adapter for Core tests;
- privacy-safe Foundation observability.

Names may differ if responsibilities remain explicit and executable contracts preserve this boundary.

## Explicit non-goals

Android Device Key v0.1 does **not** implement:

- cognitive-store encryption;
- SQLCipher/database encryption;
- protected model package encryption/decryption;
- model streaming loader;
- online enrollment service;
- license issuance or refresh;
- Play Integrity policy as a trust root;
- billing/purchase flows;
- Update System activation;
- general application attestation;
- universal anti-tamper/anti-dump protection;
- arbitrary secret storage API;
- generic executor/device-control Authority bypass.

Those belong to later phases.

## Device root requirements

The preferred root is a non-exportable Android Keystore key.

StrongBox is preferred when available and when the selected algorithm/profile is supported. A hardware-backed TEE may be accepted under an explicit fallback policy. Software-backed operation must never be silently reported as hardware-backed.

Forbidden as primary cryptographic roots:

- IMEI;
- Android ID;
- serial number;
- advertising ID;
- MAC address;
- concatenated device properties;
- hashes of ordinary identifiers treated as secret device keys;
- hard-coded shared device secrets in Kotlin/native code.

Ordinary identifiers may at most be structural metadata where privacy policy permits; they are not possession proofs.

## Key material rule

Private device-key material must remain non-exportable through the accepted adapter contract.

The architecture must not expose raw private-key bytes to Core callers. If a future platform API can export material for a selected key type, that profile is outside the trusted device-root path unless separately reviewed.

Public-key material or public enrollment evidence may be exported only through an explicit typed operation.

## Algorithm/profile rule

Accepted algorithms and parameters must be allow-listed by the adapter/profile, never chosen by untrusted payload content.

No silent algorithm fallback is allowed.

The contract must distinguish:

- requested key profile;
- actual generated key profile;
- security level actually achieved;
- capabilities actually available.

If the platform cannot satisfy a mandatory requested property, creation fails explicitly rather than weakening the request.

## Security-level evidence

The adapter must expose typed evidence describing the actual security level reported by Android Keystore APIs, for example conceptually:

- StrongBox-backed;
- hardware-backed TEE;
- software-backed;
- unavailable/unknown.

Exact Android API mapping belongs to the adapter implementation.

Hard rules:

- `requested StrongBox` does not imply `actual StrongBox`;
- unsupported StrongBox must follow an explicit caller policy: fail, or explicitly request an allowed fallback in a new operation;
- unknown security level never upgrades to hardware-backed;
- security-level evidence is structural evidence, not Authority.

## Creation and exact ownership

Device-key creation/replacement must use explicit ownership semantics.

If Core tracks live device-key state, it must preserve exact `(DeviceKeyId, DeviceKeyGeneration)` semantics consistent with frozen Foundation ownership rules:

- positive generation;
- duplicate live identity rejection unless replacement is a separately reviewed exact transition;
- stale ownership cannot delete or replace a newer key generation;
- one-shot exact removal/retirement semantics;
- deterministic detached snapshots;
- composition isolation by default;
- no global mutable key registry.

Platform aliases alone must not be treated as sufficient stale/ABA protection if replacement can reuse an alias.

## Key creation ordering

A successful public creation result may be returned only after the platform confirms the key exists and its actual properties have been inspected.

Conceptual path:

`validate request → platform generate → inspect actual key/security properties → exact local publication → Created`

If post-generation validation fails, the implementation may attempt exact cleanup of only the key it created. Cleanup failure remains explicit and observable; no hidden substitution is allowed.

## Key operation boundary

Core must interact with the device key through typed cryptographic operations rather than raw key extraction.

Potential operations may include only what later phases require, such as:

- sign challenge/enrollment transcript;
- unwrap a wrapped DEK under a separately reviewed profile;
- derive/use a platform-protected operation where supported.

Do not add a broad generic `doCrypto(bytes)` escape hatch.

Every operation must validate exact key identity/profile/capability before use and return typed failures.

## Enrollment/binding boundary

Device enrollment is separate from key existence.

Future service flow remains conceptually:

`device public key / approved attestation evidence → enrollment service → signed enrollment reference → License/lease references enrollment`

Phase B may define the local structural models and proof-of-possession challenge operation needed for that flow, but it does not invent server trust.

An enrollment reference is structural evidence. It is not a License, capability, Authority receipt or DEK.

## Attestation boundary

Key attestation may be supported where Android/platform capabilities allow it, but it is not mandatory for every device profile and must not be treated as self-validating.

Hard rules:

- raw attestation chains/tokens are not logged by default;
- attestation verification policy belongs to an explicit trust boundary, typically server-side or a separately reviewed verifier;
- absence of attestation must not be silently reported as verified hardware identity;
- Play Integrity and Keystore attestation are distinct signals.

## Invalidation and recovery

The architecture must make key invalidation and loss explicit.

Typed outcomes must distinguish at least conceptually:

- key missing;
- key permanently invalidated;
- key temporarily unavailable;
- unsupported algorithm/profile;
- unsupported StrongBox/security requirement;
- operation rejected by platform;
- authentication requirement not satisfied where applicable;
- malformed/unsupported stored metadata;
- exact ownership stale;
- cleanup/retirement failure.

Factory reset, app-data loss, Keystore reset/invalidation and device migration do not preserve implicit possession. Re-enrollment/recovery is a separate later workflow.

No key-loss path may intentionally delete unrelated user cognitive data.

## Authentication policy boundary

If a selected device-key profile requires user authentication, that requirement must be explicit in the profile and surfaced in operation results.

The architecture must not assume biometric/device-credential behavior without an explicit Android adapter policy.

User authentication is not itself License entitlement or Authority.

## Future DEK wrapping/unwrap relation

Phase B creates the root needed by later phases, but does not yet define cognitive/model encryption formats.

Expected later dependency:

`fresh License/Authority/policy as required → exact device-key handle → exact wrapped DEK envelope → controlled unwrap → bounded protected consumer`

Hard rules already fixed now:

- wrapped DEKs are separate objects from device keys;
- device private material is not exported to derive ad-hoc keys in application code;
- model DEKs and cognitive-store DEKs remain separate domains;
- License signatures/tokens are not used as device wrapping keys.

## License Core relation

Frozen License Core v0.1 remains unchanged.

Device-key state does not replace `LicenseVerifier`, `LicensePolicy`, replay/revocation checks or the fresh License→Authority boundary.

Future device-binding policy may consume an opaque enrollment/device-key reference, but a device-key match alone never creates `LicenseDecision.Entitled`.

## Authority relation

Device-key operations that can release protected key material or unlock protected assets may require a separately mapped capability/scope and fresh Authority at the real side-effect boundary.

This architecture contract does not grant such Authority automatically.

Mandatory invariant:

`DeviceKey possession/evidence != Capability != Authority != Execution`.

## Observability and privacy

All device-key lifecycle and operation diagnostics use Foundation Logging/Diagnostics/CoreObservability.

Safe structural metadata may include:

- redacted/stable device-key ID;
- exact generation;
- requested/actual algorithm profile;
- requested/actual security level;
- operation category;
- capability category;
- typed failure/rejection code;
- enrollment generation/reference hash or other approved stable reference;
- correlation ID.

Never log by default:

- private key material;
- wrapped or unwrapped DEK plaintext where secret-bearing;
- raw challenge signatures where policy treats them as sensitive;
- raw attestation chains/tokens;
- biometric/device credential data;
- raw license bearer evidence;
- Memory/Knowledge/model plaintext;
- secret-bearing exception messages.

Public failure rendering exposes structural category and exception class where useful, not secret-bearing platform exception text.

No direct `println`, `System.out`, `printStackTrace` or hidden global logger path is allowed.

## Determinism and platform split

Core-domain contracts must remain deterministic and testable without a real Android device.

Therefore the implementation should separate:

- platform-neutral models/policy/ownership contracts;
- an Android Keystore adapter interface/boundary;
- deterministic fake/test implementation;
- Android-specific implementation in an appropriate platform module when that module exists.

Core tests must not require Android framework classes, real hardware keys, network or global wall-clock behavior.

Android instrumentation/device tests are required before claiming real hardware-backed behavior.

## Concurrency/isolation

Creation, replacement, retirement and key operations must define serialization/ownership semantics explicitly.

Separate compositions remain isolated unless a shared platform key namespace/backing adapter is intentionally supplied.

If the Android Keystore namespace is inherently process/application shared, the adapter must make that fact explicit and Core must not pretend composition isolation extends to the OS key namespace.

No hidden retry, refresh, reconciliation or global mutable cache is introduced.

## Failure semantics

Security-sensitive failures are explicit and fail closed.

A failure to validate requested key properties, resolve the exact key, verify required security level, satisfy a required operation policy or complete a cryptographic operation yields no protected downstream result.

No fallback operation may silently downgrade security.

## First implementation slices

Proceed through narrow reviewed slices:

1. platform-neutral device-key models, security levels, typed failures and exact ownership contract;
2. device-key adapter abstraction + deterministic fake contracts;
3. Android Keystore creation/resolution/security-level inspection boundary;
4. proof-of-possession/signing operation and structural enrollment reference boundary;
5. invalidation/recovery/concurrency/privacy readiness hardening;
6. freeze checkpoint.

Do not add cognitive-store or model DEK unwrap until this Phase B boundary is frozen, unless a separately reviewed dependency proves the minimum required unwrap semantics without collapsing the phase separation.

## Executable readiness gates

Before Android Device Key v0.1 can freeze, executable contracts must prove at minimum:

1. blank/invalid key IDs and unsupported profiles reject;
2. requested mandatory security properties cannot silently downgrade;
3. actual security level is reported explicitly;
4. unknown security level never becomes hardware-backed;
5. private key bytes are not exposed through Core APIs;
6. exact `(DeviceKeyId, DeviceKeyGeneration)` ownership is stale/ABA-safe where state ownership exists;
7. duplicate/replacement semantics are explicit;
8. failed creation does not publish a ready key;
9. post-generation validation failure cannot publish a ready key and cleanup behavior is explicit;
10. missing/invalidated/unavailable keys fail closed;
11. unsupported operation/capability fails before cryptographic use;
12. proof-of-possession uses the exact resolved key and cannot substitute another key identity;
13. enrollment evidence remains structural and is not License/Authority;
14. compositions/domain registries are isolated except for explicitly shared platform namespace behavior;
15. concurrent creation/retirement cannot violate exact ownership invariants;
16. normal logs/diagnostics/failure rendering contain no private key, DEK, raw attestation token, cognitive/model plaintext or secret-bearing exception message;
17. no direct console logging bypass exists in new production paths;
18. deterministic Core tests require no Android framework/hardware/network;
19. Android-specific tests verify actual Keystore behavior before hardware-backed claims are documented;
20. License Core v0.1 and Authority boundaries remain unchanged.

## Freeze criteria

Android Device Key v0.1 may be frozen only after:

- architecture contract exact-head and merge/main Core CI are GREEN;
- platform-neutral model/ownership contracts are GREEN;
- fake adapter contracts are GREEN;
- Android adapter creation/resolution/security inspection is tested on appropriate Android runtime/instrumentation infrastructure;
- invalidation/recovery/failure semantics are explicit;
- concurrency/isolation behavior is documented and tested;
- privacy/logging audit is CLEAN;
- implementation/readiness audit finds no blocking correctness/security/ownership defect;
- freeze checkpoint exact-head and merge/main gates are GREEN.

Until then this document is an architecture contract, not a hardware-security claim.

## Relationship to roadmap

This is Phase B of `SECURITY_LICENSING_V0_1_CONTRACT.md`.

After Phase B freezes, the next planned security phase is:

`cognitive storage encryption`

That later phase must consume the frozen device-key boundary without exposing raw device private material or weakening frozen License/Authority semantics.
