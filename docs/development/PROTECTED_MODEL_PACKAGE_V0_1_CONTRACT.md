# Protected Model Package / Loader v0.1 — Contract

Status: **ARCHITECTURE CONTRACT — implementation not yet frozen**.

## Purpose

Protected Model Package / Loader v0.1 defines how LiliyaCore represents, verifies, authorizes, opens and hands off protected model artifacts without collapsing encryption, licensing, device binding or runtime execution into one trust decision.

Canonical direction:

`protected model package → canonical manifest validation → integrity/authenticity verification → fresh entitlement/policy decision → exact key-resolution boundary → authenticated model decryption/open → bounded loader handoff → runtime model consumer`

## Mandatory separations

`Model Package != Model Payload != Package Signature != Encryption != Model DEK != Key Protector != Device Key != Enrollment != License != Capability != Authority != Execution`

`Valid package signature != License entitlement`

`License entitlement != model-key possession`

`Successful unwrap/decrypt != Authority grant`

`Authority grant != successful model execution`

`Device Key possession != model decryption permission`

`License expiry != protected-model destruction`

## Compatibility with frozen foundations

Protected Model v0.1 MUST NOT weaken or retrofit the frozen boundaries of:

- License Core v0.1;
- Android Device Key v0.1;
- Cognitive Storage Encryption v0.1;
- persistent cognitive storage and its durable commit semantics.

Android Device Key v0.1 remains signing-only (`SIGN_CHALLENGE`). It is not a model wrapping/decryption key and must not acquire a generic unwrap capability.

Cognitive Storage Encryption protects cognitive persistent payloads. Protected model encryption is a separate domain with separate identifiers, package metadata, key lifecycle and rotation/recovery semantics.

## Protected model package identity

Every protected package has exact logical identity and version/generation. At minimum the package contract binds:

- package format/version;
- model/package ID;
- exact model version or generation;
- model format/profile;
- expected plaintext size bounds;
- ciphertext payload digest or authenticated payload reference;
- encryption profile and model-DEK reference where encryption is used;
- signature algorithm/profile and signer identity reference;
- compatibility metadata required by the reviewed model runtime;
- optional structural product/SKU entitlement selector, but never a bearer permission token.

Package identifiers, versions and generations are structural metadata, not authorization.

## Canonical manifest

The manifest has one deterministic canonical representation for signing and verification.

Rules:

- duplicate keys/fields are rejected;
- unknown security-critical fields are rejected for v0.1 unless the format explicitly marks them ignorable;
- integer/string/byte encodings are unambiguous;
- ordering is canonical;
- signed bytes exclude mutable runtime state;
- signatures cover the exact security-critical manifest and the exact protected payload binding;
- manifest parsing is bounded and fail-closed.

No parser may silently normalize conflicting representations into one accepted package.

## Authenticity and integrity

Protected model package verification is explicit and typed.

The verifier must establish:

1. manifest structural validity;
2. supported package/profile version;
3. exact payload binding;
4. cryptographic signature validity against the configured trust anchor/key set;
5. no stale or substituted model/package identity;
6. no algorithm/profile downgrade.

A valid signature proves authenticity/integrity relative to the configured signer trust anchor. It does not grant entitlement, key unwrap, Authority or execution.

## Model encryption profile

When the model payload is encrypted, v0.1 uses authenticated encryption only.

Preferred profile:

`AES-256-GCM / 96-bit nonce / 128-bit authentication tag`

The authenticated context binds at least:

- protected package ID;
- exact package/model version or generation;
- model format/profile;
- exact model-DEK ID/generation;
- payload segment/chunk identity where chunking is used;
- package format/version.

No ECB, unauthenticated encryption, CBC-without-MAC, caller-selected arbitrary algorithm, or silent downgrade is permitted.

## Model DEK and key-resolution boundary

Model DEKs are domain-specific and distinct from cognitive-storage DEKs.

Exact ownership is `(ModelDekId, ModelDekGeneration)` or an equivalent explicit type pair.

Key-resolution/decryption is performed through a purpose-specific boundary. It must not expose a generic `decrypt(anything)` or `unwrap(anything)` primitive.

A future Android implementation may use a dedicated Android Keystore protector/KEK for protected-model keys, with its own identity, generation, platform reference, requested/actual security-level evidence and lifecycle. This is separate from Android Device Key v0.1.

Raw model DEKs and KEKs must not be serialized into ordinary state or observability.

## License and policy integration

The primitive package parser/verifier/decrypter does not embed License Core or Authority decisions.

A higher layer performs a fresh entitlement/policy decision before opening protected payloads. Any model-key release must be scoped to the exact package/model identity and generation being opened.

`License evidence != durable permission`

`License expiry != erase package`

Offline operation may later rely on the frozen License/offline-lease semantics, but Protected Model v0.1 does not invent a second licensing system.

## Loader handoff

The loader exposes only reviewed, bounded plaintext to the runtime model consumer.

Requirements:

- validate size bounds before allocation/decryption where possible;
- avoid durable plaintext copies;
- avoid temp-file plaintext unless a later platform integration explicitly requires and separately reviews it;
- clear mutable temporary plaintext/key buffers on best-effort basis after handoff/failure;
- no assumption that JVM/OS memory clearing defeats privileged memory inspection;
- never treat malformed/authentication-failed package as missing/empty model;
- fail closed on truncation, substitution, unsupported format/profile or key-resolution failure.

Streaming/chunked loading is allowed only if every chunk is independently and canonically bound to the package/model identity and sequence, and reordering/omission/duplication is rejected.

## Exact state and concurrency

Protected-package open state is exact to package/model identity and generation.

Stale workers must not publish a model handle after a newer generation/package has replaced the expected target.

No process-global mutable key/package registry is allowed unless explicitly owned by a composition and tested for isolation.

Concurrent opens must not permit one package's key/material/verified state to satisfy another package.

## Rotation and update compatibility

Model package/key rotation is explicit:

`publish new protected package/key generation → verify exact package → authorize exact generation → open/validate → switch runtime ownership → retire old package/key only when no live dependency requires it`

No hidden retry, replay, reconciliation or silent rollback is claimed in v0.1.

Update System integration is a later phase and must preserve exact package identity/version, authenticity and rollback policy. Update transport success alone never makes a model trusted or executable.

## Recovery and failure semantics

Typed failure categories must distinguish at least:

- malformed/unsupported package;
- signature/authenticity failure;
- payload binding/digest failure;
- entitlement/policy rejection;
- model key unavailable/missing;
- key protector invalidated/stale;
- authenticated decryption failure;
- truncated/corrupt payload;
- runtime format incompatibility;
- stale ownership/concurrent replacement;
- provider/internal failure without secret exception messages.

Key loss or invalidation is not equivalent to model absence and does not authorize package deletion.

## Privacy and observability

Normal observability may contain only structural safe metadata such as operation, fixed failure category, package format/version, non-secret counters and throwable class where already permitted by Foundation policy.

It must never contain:

- model plaintext or prompt/model data extracted from the model;
- raw model DEK/KEK material;
- wrapped key bytes;
- raw nonce/tag/ciphertext payloads;
- signing private keys or secret trust material;
- bearer license/lease/proof material;
- secret-bearing exception messages;
- direct `println`, `System.out`, `System.err` or `printStackTrace` bypasses.

Identifiers that may reveal product/device/account-specific information should render redacted unless explicitly classified safe.

## v0.1 implementation slices

Slice 1 — Core package/manifest/signature/encryption models and exact ownership.

Slice 2 — Canonical manifest codec + signature/integrity verification boundary and deterministic contracts.

Slice 3 — Protected payload crypto/key-resolution boundary + bounded loader handoff contracts.

Slice 4 — Android purpose-specific protected-model key protector/runtime integration where required.

Slice 5 — License-policy composition, stale/concurrency/recovery/rotation/privacy readiness contracts without embedding License inside crypto primitives.

Slice 6 — formal freeze checkpoint.

## Freeze gate

Protected Model Package / Loader v0.1 becomes **FROZEN** only after:

- architecture contract is merged on exact-head required CI;
- all implementation slices are merged through exact-head Core + required Android CI;
- real Android key-protector/runtime evidence exists for any Android-specific key path introduced by this phase;
- package canonicalization/authenticity/integrity/substitution/anti-downgrade tests are GREEN;
- exact model-DEK/key-protector ownership, stale/ABA, concurrency and isolation contracts are GREEN;
- License/policy separation is audited;
- no plaintext/key/secret observability leakage is found in reviewed production paths;
- formal freeze PR exact-head CI is GREEN;
- freeze PR merges using verified expected head;
- resulting merge/main Core + required Android CI is GREEN.

Until that final merge/main gate closes, this phase is not frozen and runtime-hardening work does not begin.
