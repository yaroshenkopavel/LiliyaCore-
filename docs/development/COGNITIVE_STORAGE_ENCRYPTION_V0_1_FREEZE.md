# Cognitive Storage Encryption v0.1 — Freeze Checkpoint

Status: **FROZEN pending this checkpoint gate**.

## Frozen direction

`persistent cognitive payload → explicit encryption profile → exact DEK identity/generation → authenticated ciphertext envelope → exact wrapped-DEK binding → purpose-specific key-protector boundary → bounded plaintext consumer`

Mandatory separations:

`Persistence != Encryption != Ciphertext != DEK != Wrapping Key != Device Key != Enrollment != License != Capability != Authority != Execution`

`Key possession != permission`

`Successful unwrap != License entitlement`

`Successful decrypt != Authority grant`

`License expiry != cognitive-data destruction`

## Frozen v0.1 guarantees

- persistent cognitive payloads are sealed before the frozen persistence commit boundary and reopened only after authenticated envelope validation;
- the cryptographic profile is fixed to AES-256-GCM with a 96-bit nonce and 128-bit authentication tag;
- envelope authentication binds store, entity, exact entity generation, schema/version and exact `(CognitiveDekId, CognitiveDekGeneration)`;
- production nonce generation is supplied by an explicit cryptographic boundary and does not depend on a hidden global clock or deterministic source;
- DEK ownership is exact, monotonic and ABA-safe; duplicate live ownership and generation overflow fail closed;
- encrypted payload, wrapped-DEK and key-material wrappers use defensive copying and redacted rendering;
- raw plaintext, raw DEK material, wrapped bytes, nonce, tag, ciphertext and secret exception messages are not normal observability payloads;
- durable persistence never substitutes authentication failure, key loss or protector loss with a missing/empty record;
- the frozen persistence store remains the durable source of truth and is not replaced by encryption readiness metadata;
- key protection is a separate purpose-specific boundary and is not retrofitted into the frozen signing-only Device Key v0.1 capability surface;
- Android cognitive key protection uses a dedicated non-exportable Android Keystore AES-256-GCM key;
- StrongBox requests do not silently downgrade in the same operation; lower security requires a new explicit request;
- Android protector ownership binds exact logical id/generation to an opaque platform-instance reference and rejects stale/ABA state;
- Android wrap uses provider-generated randomized GCM IV and records the exact 12-byte IV in the wrapped envelope;
- unwrap authenticates exact DEK/protector/purpose/profile binding before material is released;
- mutable temporary raw DEK/plaintext buffers are cleared on reviewed paths where practical;
- rotation readiness tracks exact committed-ciphertext dependencies and prevents old-DEK retirement while committed ciphertext still depends on it;
- stale migration/release workers cannot overwrite newer committed dependency state;
- recovery classification is explicit for missing/invalidated/stale protector, wrapped-DEK unavailability and ciphertext authentication failure;
- dependency/readiness rendering redacts store/entity/key identifiers;
- ownership/readiness registries are composition/process-local and do not become global authorization registries;
- no License or Authority policy is embedded in the primitive encryption/key-protector layer;
- no hidden retry, replay or reconciliation mechanism is claimed by v0.1;
- Core remains Android-framework-free; concrete Android Keystore integration remains in the separate `:android-device-key` module.

## Slice evidence

Architecture contract PR #48 established the v0.1 boundary and was merged as `6c6926a90008e179b942920373471f21494fce0e` after exact-head and merge/main Core + Android GREEN gates.

Slice 1 PR #49 added Core encryption models and exact DEK ownership. It merged as `c3078d6642540ef05226ecb5539df3be32e00a93`; merge/main run `33370679028` was GREEN for both required jobs.

Slice 2 PR #50 added the platform-neutral AEAD/provider boundary and canonical authenticated associated data. It merged as `267a9c386ca6d49df287f67deb677f6f2ba4b1c5` after exact-head GREEN.

Slice 3 PR #51 added the encrypted persistent cognitive storage adapter over the frozen persistence contract. It merged as `a0356e9499fc0874d77dd69bce53da2a3a382d6d` after exact-head GREEN and subsequent merge/main GREEN.

Slice 4 PR #52 added the dedicated Android cognitive key protector and real Android Keystore instrumentation. Its first Android instrumentation gate correctly exposed invalid caller-supplied IV use with randomized encryption required; the implementation was fixed so Android Keystore generates the IV. Exact head `4cbd69b51ee839aae08ba3cb7336c7bbdcd73543` then passed run `33376255796` GREEN for Core and Android instrumentation. PR #52 merged as `f5ff43ad9253a028f48248f1a704afce6807aa4d`; merge/main run `33376947732` was GREEN for both required jobs.

Slice 5 PR #53 added rotation/recovery/concurrency/privacy readiness. Exact head `83ee7cc1571a5e6b71f2512439585048db5a5886` passed run `33378435420` GREEN for Core and Android instrumentation. PR #53 merged as `6a19045c9e0b4b5f843d21552e03cb9b7466d0ac`.

Verified PR #53 merge/main gate:

- run `33379516527` — `Test LiliyaCore` GREEN;
- run `33379516527` — `Android Keystore Instrumentation` GREEN.

## Readiness audit

The reviewed v0.1 surface preserves the contract boundaries across Core crypto, encrypted persistence, Android key protection and rotation/recovery readiness:

- no generic crypto executor was introduced;
- no Device Key unwrap capability was reintroduced;
- no License/Authority coupling was added to the crypto primitive or protector boundary;
- no Android framework dependency was introduced into Core;
- exact generation/platform-instance ownership is enforced at both DEK and protector boundaries;
- authenticated substitution resistance covers store/entity/generation/schema/DEK and protector binding;
- durable commit truth remains the frozen persistence path;
- key loss and authentication failures remain explicit fail-closed recovery states;
- reviewed rendering/normal observability surfaces are structural/redacted rather than secret-bearing.

## Explicit limitations

Cognitive Storage Encryption v0.1 does not claim cross-device key recovery, cloud escrow, backup restoration, hidden migration retry/reconciliation, cross-domain transactions, exactly-once migration, License entitlement, Authority grant or execution permission.

The Android emulator gate demonstrates concrete Android Keystore behavior in the tested API 35 x86_64 emulator environment. It does not prove StrongBox or TEE availability on an arbitrary user device; hardware-backed claims remain conditional on runtime-observed platform evidence.

Best-effort buffer clearing does not protect plaintext from a privileged process debugger, memory forensics or a compromised OS/runtime.

## Freeze checkpoint gate

This document is the Slice 6 formal freeze checkpoint. The phase becomes formally **FROZEN** only when this exact checkpoint head passes both required CI jobs, the PR merges using the verified expected head, and the resulting merge/main commit also passes both required jobs.

After that gate closes, the next roadmap phase is the protected model package/loader. That later phase must consume this frozen encryption boundary without weakening the separations above.
