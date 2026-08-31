# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Project type: Kotlin core foundation with separate concrete Android security/platform modules.

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

`2aab6175e8aad9513382968c3357965c04b15fb7`

Latest merge/main CI:

`33366740469` — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

## Frozen baselines

Persistent Cognitive Storage v0.1, Memory Persistence Integration v0.1, Knowledge Persistence Integration v0.1, Learning Persistence Integration v0.1, License Core v0.1 and Android Device Key v0.1 are **FROZEN**.

The delayed Learning/Persistence observability audit is closed **CLEAN for the audited Learning/Persistence production paths** through corrective PR #39.

## Android Device Key v0.1 boundary

Read:

- `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`
- `ANDROID_DEVICE_KEY_V0_1_FREEZE.md`

Frozen Device Key v0.1 provides exact device-key ownership, security-level evidence, proof signing and structural enrollment using a concrete Android Keystore EC P-256 signing boundary.

Important hard limit:

- v0.1 exposes only `SIGN_CHALLENGE`;
- there is no frozen DEK wrap/unwrap capability/API;
- the EC signing key must not be treated as a cognitive DEK wrapping key;
- emulator evidence proves concrete Keystore runtime/lifecycle behavior, not StrongBox/TEE availability on arbitrary hardware.

Final Device Key freeze/closeout baseline:

- PR #46 merge `2cc6279ef481915531267ac52ce06ff3c36036a6`, merge/main `33365191210` GREEN;
- PR #47 merge `2aab6175e8aad9513382968c3357965c04b15fb7`, merge/main `33366740469` GREEN.

## Cognitive Storage Encryption v0.1

Status: **ARCHITECTURE CONTRACT ACTIVE — IMPLEMENTATION NOT YET STARTED / NOT FROZEN**.

Read first:

`COGNITIVE_STORAGE_ENCRYPTION_V0_1_CONTRACT.md`

Direction:

`persistent cognitive payload → explicit encryption profile → exact DEK identity/generation → authenticated ciphertext envelope → exact wrapped-DEK binding → purpose-specific key-protector boundary → bounded plaintext consumer`

Mandatory separation:

`Persistence != Encryption != Ciphertext != DEK != Wrapping Key != Device Key != Enrollment != License != Capability != Authority != Execution`

Selected initial data-encryption profile:

`AES-256-GCM / 96-bit nonce / 128-bit authentication tag`

Critical architecture correction: Cognitive Storage Encryption owns a **separate purpose-specific key-protector/KEK boundary**. It must not retrofit DEK wrapping/unwrapping into frozen Android Device Key v0.1. On Android, the preferred future key protector is a dedicated non-exportable Android Keystore key for cognitive DEK protection, with its own identity/profile/security/lifecycle contract.

The encryption contract requires exact `(CognitiveDekId, CognitiveDekGeneration)` ownership, canonical AEAD binding to store/entity/generation/schema/DEK metadata, raw-DEK non-persistence, exact wrapped-DEK/protector binding, explicit rotation/migration/recovery semantics, fail-closed authentication, bounded plaintext lifetime and privacy-safe observability.

License expiry must not intentionally destroy legitimate user cognitive data. Successful unwrap/decrypt is not Authority. Higher protected-use layers may require fresh policy/Authority, while recovery/export remains a distinct future policy path.

## Current next step

Finish the Cognitive Storage Encryption v0.1 architecture-contract PR first.

Required gate:

`exact-head Core + required Android CI GREEN → merge with expected head → merge/main Core + Android CI GREEN`

Only after that gate is closed begin Slice 1:

`platform-neutral encryption models + exact DEK ownership + envelope structural types + typed failures + privacy-safe rendering`

Do not start Android key-protector implementation, persistent encrypted payload integration, rotation or recovery code before Slice 1 is independently GREEN and reviewed.

## Accepted roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

## Resume procedure

1. verify current `main` SHA and latest required CI;
2. read the frozen Persistent Cognitive Storage and Android Device Key contracts plus `COGNITIVE_STORAGE_ENCRYPTION_V0_1_CONTRACT.md`;
3. if the encryption architecture PR/CI gate is active, finish that exact gate before implementation;
4. after architecture merge/main GREEN, begin only Slice 1;
5. preserve exact ownership, fail-closed AEAD binding, Device Key freeze compatibility, License/Authority separation and strict privacy rules throughout the phase.
