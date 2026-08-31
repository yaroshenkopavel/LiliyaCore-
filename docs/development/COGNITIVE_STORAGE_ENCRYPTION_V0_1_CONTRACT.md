# Cognitive Storage Encryption v0.1 — Architecture Contract

Status: **ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET STARTED / NOT FROZEN**

Selected after Android Device Key v0.1 freeze closeout on verified `main` `2aab6175e8aad9513382968c3357965c04b15fb7`, with merge/main run `33366740469` GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

## Purpose

Cognitive Storage Encryption v0.1 adds authenticated encryption at rest for persistent user cognitive payloads while preserving the already-frozen persistence, Memory, Knowledge, Learning, License Core and Android Device Key boundaries.

The phase protects stored cognitive payload confidentiality and integrity. It does **not** turn encryption keys, device evidence, License evidence or successful decryption into cognitive permission or execution Authority.

Primary direction:

`persistent cognitive payload → explicit encryption profile → exact DEK identity/generation → authenticated ciphertext envelope → exact wrapped-DEK binding → purpose-specific key-protector boundary → bounded plaintext consumer`

## Mandatory separation

`Persistence != Encryption != Ciphertext != DEK != Wrapping Key != Device Key != Enrollment != License != Capability != Authority != Execution`

`Key possession != permission`

`Successful unwrap != License entitlement`

`Successful decrypt != Authority grant`

`License expiry != cognitive-data destruction`

`Device loss != permission to silently erase ciphertext`

User cognitive data and vendor-owned model/license assets remain separate cryptographic domains.

## Critical compatibility rule with frozen Device Key v0.1

Android Device Key v0.1 is frozen with the single capability `SIGN_CHALLENGE` and a concrete EC P-256 signing implementation. It has **no DEK wrap/unwrap capability or API**.

Therefore Cognitive Storage Encryption v0.1 must not pretend that the frozen Device Key signing key can encrypt, wrap or unwrap cognitive DEKs, and it must not add such semantics retroactively to the frozen Device Key contract.

If a platform wrapping key is required, this phase owns a **separate purpose-specific key-protector/KEK boundary**. On Android, the preferred implementation is a non-exportable Android Keystore key created for cognitive key protection, with its own key identity/profile/security evidence and lifecycle semantics.

Frozen Device Key evidence may be referenced as optional structural device/enrollment binding evidence where a higher policy explicitly requires it. Such evidence is not the wrapping key and is not permission.

## Frozen dependencies that must remain intact

This phase builds on:

- Persistent Cognitive Storage v0.1 — frozen exact-generation durable record primitive;
- Memory Persistence Integration v0.1 — frozen;
- Knowledge Persistence Integration v0.1 — frozen;
- Learning Persistence Integration v0.1 — frozen;
- License Core v0.1 — frozen;
- Android Device Key v0.1 — frozen.

The encryption layer may wrap the persistent payload boundary, but it may not weaken exact `(entityId, generation)` ownership, backend revision CAS, durable acknowledgement, deterministic reopen validation, persistence-domain isolation or the frozen cognitive-domain semantics.

## v0.1 cryptographic profile

The first accepted data-encryption profile is:

`AES-256-GCM / 96-bit nonce / 128-bit authentication tag`

Required rules:

- no ECB;
- no unauthenticated encryption;
- no CBC-without-MAC construction;
- no algorithm chosen from untrusted ciphertext metadata without allowlist validation;
- no silent algorithm downgrade;
- nonce uniqueness is mandatory for every encryption under the same exact DEK generation;
- production nonce generation must use a cryptographically secure source supplied through the crypto/platform boundary;
- Core must not hide a global RNG or global clock; deterministic tests use injected deterministic fakes;
- raw DEKs are never serialized as ordinary application state or rendered in logs/errors.

Future algorithm/profile additions require an explicit versioned contract and migration path; they are not implicit fallbacks.

## DEK ownership model

A cognitive data-encryption key is identified by exact structural ownership:

`(CognitiveDekId, CognitiveDekGeneration)`

A string/key ID alone is never sufficient ownership.

For v0.1, the preferred granularity is a store/profile-scoped DEK generation rather than one durable DEK per record. Each encrypted record binds to the exact DEK reference used to seal it.

Required invariants:

- generations are positive and monotonic within their DEK ownership domain;
- duplicate live DEK ownership rejects;
- stale DEK ownership cannot retire, replace or authorize a newer generation;
- rotation creates a new generation; it does not mutate the cryptographic identity of the old generation in place;
- old DEK generations remain available for explicitly tracked migration/recovery until no committed ciphertext depends on them;
- a DEK may not be retired merely because a newer generation exists;
- generation overflow fails closed.

## Authenticated ciphertext envelope

Persistent plaintext payload bytes must be transformed into a canonical encrypted envelope before the durable backend boundary.

The authenticated envelope must bind at least:

- envelope format/version;
- encryption profile/algorithm identifier;
- persistent store identity;
- persistent entity identity;
- exact persistent entity generation;
- schema/version identity;
- exact DEK identity and generation;
- nonce;
- ciphertext;
- authentication tag or provider-equivalent authenticated output.

Structural binding fields belong in canonical AEAD associated data or an equivalent canonical authenticated representation. They must not be trusted merely because they are adjacent to ciphertext.

Copying a valid ciphertext envelope to a different store/entity/generation/schema/DEK binding must fail authentication or canonical validation rather than decrypt successfully.

Ciphertext bytes are not permission and should not be emitted into normal observability even though they are encrypted.

## Wrapped-DEK envelope

A raw cognitive DEK must not be persisted directly.

Durable DEK state uses a versioned wrapped-DEK envelope bound to:

- exact `CognitiveDekId` and `CognitiveDekGeneration`;
- encryption profile/key purpose;
- exact key-protector identity/generation or equivalent exact platform-key reference;
- wrapping algorithm/profile;
- canonical context/domain binding;
- wrapped key bytes plus authentication data required by the wrapping primitive.

The envelope must reject:

- unknown algorithms/profiles;
- malformed metadata;
- ID/generation mismatch;
- protector/key-reference mismatch;
- cross-domain substitution;
- stale protector ownership where the platform can prove replacement;
- authentication failure.

A wrapped-DEK envelope is key material and must be treated as sensitive even though the DEK is not plaintext inside it. Raw wrapped bytes do not belong in logs, exception text or ordinary `toString()` rendering.

## Key-protector boundary

Cognitive Storage Encryption owns a narrow key-protector SPI. It is not a generic crypto executor or arbitrary secret store.

Conceptual operations:

`create protector → inspect exact protector state → wrap exact DEK → unwrap exact wrapped-DEK envelope → retire exact protector`

Required properties:

- platform key material is non-exportable where the platform supports that property;
- requested security properties and observed actual security properties remain distinct;
- no same-operation silent security downgrade;
- exact protector identity/generation/reference is checked before and after sensitive operations where replacement/ABA is possible;
- operation results are typed and fail closed;
- secret-bearing platform exception messages are not forwarded into normal observability;
- no generic `encrypt(anything)` or `decrypt(anything)` API is introduced through this key-protector seam.

On Android, the first concrete candidate is a dedicated Android Keystore AES-GCM key protector for cognitive DEKs. This is a **new encryption-phase key family**, not a mutation of the frozen EC signing Device Key.

## Policy and Authority boundary

The encryption primitive protects confidentiality/integrity; it does not mint permission.

Protected assistant use that requires License and/or Authority must follow the existing fresh-policy direction **before protected use**. A prior License receipt, enrollment reference, Device Key proof or successful unwrap is not durable permission.

At the same time, commercial License expiry must not intentionally make legitimate user cognitive data unrecoverable. Therefore:

- the encryption key hierarchy must not derive the only recoverable cognitive DEK solely from a License token/signature;
- License expiry/revocation must not automatically destroy wrapped DEKs or ciphertext;
- user recovery/export is a distinct policy path from protected assistant use;
- any future policy-gated unwrap facade must distinguish operation purpose and must not cache a past Authority decision as a permanent key capability.

The first crypto primitive should remain free of embedded License or Authority logic. Higher integration layers supply fresh access decisions where required.

## Persistence integration boundary

Encryption is inserted before durable payload commit:

`domain codec → canonical plaintext payload bytes → authenticated seal → encrypted persistent payload → frozen PersistentRecordStore/backend commit`

On read:

`committed encrypted persistent payload → canonical envelope validation → exact DEK resolve/unwrap → authenticated open → bounded plaintext payload → existing reviewed domain restoration`

Rules:

- durable success is still determined only by the frozen persistence commit acknowledgement;
- plaintext must never be written as the durable record payload as an intermediate step;
- decrypt failure must not be reinterpreted as a missing/empty cognitive store;
- authentication failure is explicit corruption/security failure, not best-effort decode;
- restoration still uses the existing reviewed domain restoration boundaries and validation;
- encryption may not inject arbitrary internal Memory/Knowledge/Learning map state.

## Plaintext lifetime and bounded consumers

Decrypted cognitive plaintext is inherently sensitive.

v0.1 must minimize plaintext scope:

- decrypt only for a named bounded consumer;
- avoid storing duplicate long-lived plaintext copies in encryption-layer state;
- clear mutable temporary key/plaintext buffers where practical and meaningful, without claiming impossible guarantees for immutable/JVM-managed copies;
- never write decrypted temporary payloads to ordinary files for convenience;
- never expose decrypted payload bytes through structural diagnostics.

This contract does not claim that a privileged/rooted attacker with process-memory access can never observe plaintext while it is legitimately in use.

## Rotation and migration

DEK rotation and protector-key rotation are explicit migration protocols, not hidden replacement.

Required high-level sequence for DEK rotation:

`create new DEK generation → persist wrapped new DEK → migrate records with exact CAS/commit semantics → verify committed migration state → retire old DEK only when no committed record depends on it`

Required rules:

- interruption must leave a deterministically recoverable old/new committed state;
- partial migration is represented explicitly;
- stale workers cannot overwrite records already migrated by a newer generation;
- no old DEK is destroyed while durable records still require it;
- migration metadata itself is exact-generation/ABA-safe;
- retry/reconciliation is never hidden; if introduced later it is explicit and observable structurally.

Protector-key rotation follows the same principle: rewrap DEKs first, verify durable binding, then retire old protector ownership only after all required wrapped envelopes have moved.

## Recovery and device-loss semantics

Key loss is distinct from ciphertext deletion.

Factory reset, app-data loss, Android Keystore reset, protector invalidation or device migration may make a locally wrapped DEK unavailable. The system must report this explicitly; it must not silently replace the user cognitive store with empty state or delete ciphertext to hide the failure.

A future recovery/export/backup design may add separately protected recovery material. It is outside the first implementation slice and requires its own policy and threat-model review.

No claim of cross-device recovery is made by v0.1 until such a mechanism exists and is tested.

## Failure model

The phase must use typed structural failures. Expected categories include at least:

- malformed envelope;
- unsupported envelope/profile/algorithm;
- missing DEK;
- stale DEK ownership;
- missing/invalidated/stale key protector;
- required security level unavailable;
- wrap rejected/failed;
- unwrap rejected/failed;
- nonce/profile validation failure;
- ciphertext authentication failure;
- encrypt/decrypt provider failure;
- persistence commit conflict/failure;
- migration conflict/incomplete state;
- cleanup failure where generated key material cannot be safely rolled back.

Failure rendering may contain category, structural IDs/generations, approved profile identifiers and exception class names. It must not contain cognitive plaintext, raw DEKs, raw wrapped-DEK bytes, raw ciphertext/tag/nonce bundles, platform secrets or secret-bearing exception messages.

## Observability/privacy

Foundation Logging/Diagnostics/CoreObservability remains the only normal production observability path.

Approved structural metadata may include:

- store/entity/DEK/protector IDs in redacted or approved structural form;
- generations;
- schema/envelope/profile versions;
- operation categories;
- security-level category;
- byte/count metrics;
- migration phase;
- typed failure category.

Never emit:

- cognitive plaintext;
- raw DEKs or KEKs;
- wrapped-DEK bytes;
- raw nonces/tags/ciphertext envelopes;
- Android Keystore private/secret key material;
- License bearer evidence;
- raw proof/attestation evidence;
- secret-bearing exception messages.

Direct `println`, `System.out`, `System.err`, `printStackTrace` and equivalent production bypasses remain forbidden in reviewed paths.

## Concurrency and isolation

Independent compositions/backends remain isolated unless an explicit shared backend/key domain is supplied.

Concurrent seal/open/rotation/migration must preserve exact ownership. Required contracts include:

- stale DEK generation cannot authorize a newer generation;
- stale protector reference cannot unwrap after a proven platform replacement;
- concurrent rotation cannot retire a still-referenced old DEK;
- concurrent record migration uses exact persisted generation/revision preconditions;
- no global mutable key registry or hidden process-wide crypto state is introduced.

## Explicit non-goals of v0.1 first implementation

The first implementation does not provide:

- protected model package encryption;
- cloud or multi-device synchronization;
- cross-device recovery by itself;
- backup product policy;
- Play Integrity as a trust root;
- generic attestation service;
- licensing service or offline-lease issuance;
- billing;
- generic secret vault;
- generic crypto executor;
- arbitrary application-file encryption;
- SQLCipher selection as a substitute for record-level contract semantics;
- exactly-once cross-domain Learning reconciliation;
- a claim that rooted/privileged runtime memory cannot be inspected.

## Implementation plan

### Slice 1 — Core encryption models and exact ownership

Add platform-neutral models/contracts for:

- `CognitiveDekId` / generation / exact reference;
- encryption profile and envelope version;
- authenticated ciphertext envelope structural binding;
- wrapped-DEK envelope structural binding;
- typed failures/results;
- detached/redacted rendering;
- exact DEK ownership store with generation-overflow fail-closed behavior.

No Android imports, no real key-protector implementation and no persistent integration yet.

### Slice 2 — Crypto/provider abstraction and deterministic contracts

Add narrow AEAD + nonce-source abstractions and deterministic test implementations. Prove canonical associated-data binding, tamper rejection semantics, stale/mismatched metadata rejection, defensive byte copying and privacy-safe rendering.

No generic crypto executor.

### Slice 3 — Encrypted Persistent Cognitive Storage adapter

Integrate seal/open around the frozen persistent payload boundary while preserving exact generations, backend revision CAS, durable acknowledgement, reopen validation and existing Memory/Knowledge/Learning restoration semantics.

Prove that durable backend state contains encrypted envelopes rather than cognitive plaintext.

### Slice 4 — Android cognitive key protector

Add a separate Android Keystore key-protector implementation for cognitive DEK wrap/unwrap plus emulator instrumentation. Do not modify the frozen Device Key v0.1 capability surface.

Prove exact protector lifecycle, wrap/unwrap, replacement/ABA rejection where representable, invalidation/failure mapping, alias privacy and no key export.

### Slice 5 — Rotation, recovery, concurrency and privacy readiness

Add explicit DEK/protector rotation/migration state, crash/interruption contracts, stale-worker rejection, key-loss behavior, logging/privacy audit and cleanup/invalidation hardening.

### Slice 6 — Freeze checkpoint

Perform architecture/security/privacy/logging/readiness audit, exact-head required CI, freeze documentation, merge, and merge/main required CI before calling Cognitive Storage Encryption v0.1 frozen.

## First-slice executable contracts

The first code slice must prove at least:

1. exact DEK `(id, generation)` ownership;
2. duplicate live ID rejects without replacement;
3. stale ownership cannot retire a newer DEK generation;
4. generation allocation fails closed on overflow;
5. profile/algorithm values are explicit allowlisted structural types;
6. ciphertext and wrapped-DEK envelopes defensively copy byte input;
7. raw plaintext/key/wrapped-key bytes are absent from normal rendering;
8. unknown/malformed envelope metadata fails closed;
9. structural foundation contains no Android, License, Authority, scheduler or generic crypto-executor semantics;
10. same logical IDs remain composition/store-instance isolated unless explicit shared state is supplied.

## Freeze rule

Cognitive Storage Encryption v0.1 is not frozen until:

- all implementation slices required by this contract are complete;
- real Android key-protector runtime/instrumentation evidence exists;
- exact ownership, AEAD binding, wrapped-DEK binding, persistence integration, rotation/recovery, concurrency and privacy/readiness contracts are GREEN;
- a focused security/privacy/logging audit is complete;
- the freeze checkpoint exact head passes all required Core and Android CI;
- the freeze PR merges with expected head;
- merge/main passes all required Core and Android CI again.

Until then, no downstream protected-model phase may treat cognitive-storage key handling as a frozen security primitive.
