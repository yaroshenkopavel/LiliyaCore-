# CURRENT STATE

Last journal update: 2026-08-31

## Current verified baseline

Current verified `main`:

`2aab6175e8aad9513382968c3357965c04b15fb7`

Latest verified merge/main CI:

`33366740469` — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

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

Android Device Key v0.1 is **FROZEN**.

Canonical documents:

- `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md`
- `ANDROID_DEVICE_KEY_V0_1_FREEZE.md`

Final freeze checkpoint was PR #46; freeze closeout was PR #47.

Verified final baseline:

- PR #46 exact head `8565a65348d800d646a1760bf99c34579e3a00c1`;
- PR #46 exact-head run `33364507220` — Core GREEN + Android instrumentation GREEN;
- PR #46 merge `2cc6279ef481915531267ac52ce06ff3c36036a6`;
- PR #46 merge/main run `33365191210` — Core GREEN + Android instrumentation GREEN;
- PR #47 merge `2aab6175e8aad9513382968c3357965c04b15fb7`;
- PR #47 merge/main run `33366740469` — Core GREEN + Android instrumentation GREEN.

Frozen Device Key v0.1 exposes only `SIGN_CHALLENGE`. It does **not** expose a DEK wrap/unwrap capability or API. Concrete Android runtime evidence validates Keystore integration/lifecycle on the emulator but does not prove StrongBox/TEE availability on arbitrary hardware.

## Cognitive Storage Encryption v0.1

Status: **ARCHITECTURE CONTRACT ACTIVE — IMPLEMENTATION NOT YET STARTED / NOT FROZEN**.

Canonical contract:

`COGNITIVE_STORAGE_ENCRYPTION_V0_1_CONTRACT.md`

Direction:

`persistent cognitive payload → explicit encryption profile → exact DEK identity/generation → authenticated ciphertext envelope → exact wrapped-DEK binding → purpose-specific key-protector boundary → bounded plaintext consumer`

Mandatory separation:

`Persistence != Encryption != Ciphertext != DEK != Wrapping Key != Device Key != Enrollment != License != Capability != Authority != Execution`

Critical compatibility rule: this phase must not retrofit DEK wrapping/unwrapping into frozen Android Device Key v0.1. The frozen Device Key is an EC P-256 signing/proof boundary. Cognitive encryption therefore owns a separate purpose-specific key-protector/KEK boundary; on Android the preferred future implementation is a dedicated non-exportable Android Keystore key for cognitive DEK protection.

Initial cryptographic profile selected by the architecture contract:

`AES-256-GCM / 96-bit nonce / 128-bit authentication tag`

The contract requires exact `(CognitiveDekId, CognitiveDekGeneration)` ownership, canonical AEAD binding to persistent store/entity/generation/schema/DEK metadata, raw-DEK non-persistence, exact wrapped-DEK/protector binding, no same-operation silent downgrade, explicit rotation/migration/recovery semantics, bounded plaintext lifetime and structural observability with no plaintext/DEK/wrapped-key/secret-exception leakage.

License expiry must not intentionally destroy user cognitive data. Protected assistant use may still require fresh higher-layer policy/Authority, but successful unwrap/decrypt is not permission and the crypto primitive itself does not cache or mint Authority.

## Current next step

Complete the Cognitive Storage Encryption v0.1 architecture-contract PR and require exact-head Core + Android CI GREEN, merge with expected head, then verify merge/main Core + Android CI GREEN.

After the architecture contract is accepted, begin Slice 1 only:

`platform-neutral encryption models + exact DEK ownership + envelope structural types + typed failures + privacy-safe rendering`

Do not begin Android key-protector implementation or persistence encryption integration before Slice 1 contracts are independently GREEN and reviewed.

## Accepted security roadmap

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

## Logging and diagnostics

Foundation Logging/Diagnostics/CoreObservability remains mandatory cross-cutting infrastructure.

Operational observability may expose approved structural IDs/generations/categories, but must not expose private key material, DEKs, wrapped-DEK bytes, raw ciphertext envelopes, cognitive/model plaintext, raw attestation/proof material or secret-bearing exception messages.

Direct console output remains forbidden in reviewed production paths.

## Repository continuity

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains backup/migration history only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → architecture/security/privacy/logging-diagnostics/readiness audit → exact-head merge → merge/main CI GREEN → journal/freeze checkpoint`
