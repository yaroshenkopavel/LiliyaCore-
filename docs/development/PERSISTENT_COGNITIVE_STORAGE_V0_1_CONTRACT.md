# Persistent Cognitive Storage v0.1 — Architecture Contract

Status: **ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET FROZEN**

Selected after Controlled Agent Coordination v0.1 freeze on verified `main` `0d3027d2e3bf0bbbf3af185662d06558a28dcf80` with merge/main Core CI `33313140393` GREEN.

## Purpose

The next core architecture stage is durable local cognitive state. Current Memory and Knowledge stores are intentionally in-memory and exact-generation owned, but process restart loses state and resets generation counters. This contract defines the persistence boundary needed before crash-durable learning outcomes, encrypted user cognitive storage, Android storage adapters, backup/export policy, and later Security & Licensing integration.

The first implementation slice must remain core-only and storage-engine-neutral. It must not bind LiliyaCore to Android, SQLite, SQLCipher, filesystem layout, Keystore, licensing, cloud sync, or a specific serialization library.

## Dependency direction

`frozen cognitive domain model → canonical persistent envelope → atomic durable store boundary → recovery/read validation → domain restoration`

Future protected deployment direction:

`Persistent Cognitive Storage → authenticated encryption adapter → platform key boundary → Android Keystore/StrongBox`

Mandatory separation:

`Persistence != Encryption != License != Authority != Cognitive Permission`

A durable record is not permission to use or mutate it. A valid license is not ownership of user cognitive data. Storage encryption is not Authority.

## v0.1 scope

The foundation may define:

- stable store/profile identity;
- schema/version identity;
- exact persisted entity identity;
- exact persisted generation;
- canonical payload bytes or codec-neutral payload representation;
- integrity metadata/checksum interface suitable for later authenticated-encryption wrapping;
- atomic put-if-absent / exact-generation replace or remove semantics where explicitly required;
- deterministic snapshot/read ordering;
- explicit durable commit outcome;
- explicit recovery outcome for missing/corrupt/incompatible state;
- crash-safe transaction boundary abstractions;
- test in-memory durable backend used only as a contract implementation.

The v0.1 foundation must not silently make current Memory/Knowledge stores persistent. Integration is a later controlled slice after the persistence primitive itself is independently GREEN and audited.

## Exact ownership and ABA

Persistent ownership must preserve the same rule as frozen in-memory foundations: string IDs are identity labels, not ownership handles.

Durable mutation must be bound to exact `(entityId, generation)` state. A stale owner must never remove or overwrite a newer persisted generation.

Required compare-and-set style invariant:

`expected exact generation → durable mutation → success only if expected generation is still current`

A failed stale mutation is not automatically corruption. If a newer generation is live, it must be preserved.

Generation restoration after restart must be monotonic with persisted state. Restart must never recreate generation `1` for an entity/store when a higher durable generation already exists in that generation domain.

## Atomicity and crash semantics

A successful API result must not be returned before the durable boundary reports commit success.

The contract must distinguish at least:

- `Committed` — the exact mutation is durably acknowledged;
- `Rejected` — precondition/conflict/incompatible request, no requested state committed;
- `Failed` — storage/integrity/I/O failure where success cannot be claimed.

No API may return successful completion merely because an in-memory mutation happened before durable commit.

For compound durable state, the implementation must use one explicit transaction/commit boundary or compensation protocol with executable contracts. Partial success must not be silently reported as complete.

Crash recovery must be deterministic: after reopen, state must correspond to a committed durable point, never an invented blend of old/new writes.

## Data integrity and corruption

Corrupt or schema-incompatible persisted data fails closed. Do not silently coerce malformed bytes into domain records or discard corruption and continue as if state were empty.

Recovery must expose typed/structural outcomes such as missing, incompatible, corrupt, or failed. Sensitive payload content must not be copied into diagnostic/log messages.

Integrity metadata in core is not a substitute for authenticated encryption. Later encrypted adapters must authenticate ciphertext plus structural metadata/version/domain binding.

## Privacy

Persistent cognitive payloads are private user data.

Operational observability may include structural fields such as store ID, entity ID, generation, schema version, operation type, byte/count metrics, and failure category. It must not include Memory content, Knowledge content, conversation text, reflection text, reasoning text, private goals, or decoded payload bytes.

`toString()`/exception text for persistence envelopes and receipts must avoid exposing payload content by default.

## Encryption and key-domain boundary

Core v0.1 persistence is encryption-ready but must not invent device keys.

The future security adapter must preserve:

- user cognitive-data key domain separate from vendor model/license key domain;
- no key derived solely from IMEI/Android ID/raw HWID;
- no license expiry path that intentionally destroys user cognitive data;
- authenticated encryption at rest;
- interruption-safe key rotation/migration;
- explicit backup/export/recovery policy.

These requirements align with `SECURITY_LICENSING_V0_1_CONTRACT.md`; Android Keystore/StrongBox belongs to a later platform adapter, not this core foundation.

## Memory / Knowledge integration boundary

Current frozen `MemoryStore` and `KnowledgeStore` remain authoritative for their v0.1 in-memory semantics until a dedicated integration slice is reviewed.

A future persistence integration must preserve:

- exact IDs and generations;
- deterministic snapshots;
- provenance/origin fields;
- stale/ABA-safe removal;
- privacy-safe observability;
- composition isolation;
- current public frozen Memory/Knowledge contracts unless a demonstrated correctness requirement justifies a focused versioned change.

Hydration/restoration must not bypass domain validation by injecting arbitrary internal map entries. The persistence adapter must use a reviewed restoration boundary with explicit contracts.

## Learning/idempotency boundary

Current completed learning mutation outcomes are process-local only. Persistent storage is a prerequisite for claiming crash-durable replay/idempotency, but persistence alone does not grant exactly-once semantics.

Any later durable learning outcome store must bind semantic identity, mutation ID, idempotency key, target, result, and exact durable commit state. Reusing a key for a different semantic operation must still reject.

## Explicit non-goals of the first slice

The first Persistent Cognitive Storage v0.1 implementation does not provide:

- Android database integration;
- SQLCipher/SQLite selection;
- Keystore/StrongBox keys;
- cloud synchronization;
- multi-device merge/conflict resolution;
- backup product policy;
- licensing;
- Authority decisions;
- model asset encryption;
- transparent persistence retrofitted into all frozen compositions;
- crash-durable exactly-once learning by itself.

## First implementation slice

Build a small `persistence` foundation with executable contracts for one generic exact-generation durable record store.

Required first-slice contracts:

1. put/install returns exact durable generation ownership;
2. duplicate live ID rejects without replacement;
3. stale exact ownership cannot remove a replacement generation;
4. generation sequence restores monotonically after reopen from persisted backend state;
5. deterministic detached snapshots survive reopen;
6. failed backend commit is `Failed` and never reported as success;
7. corrupt/incompatible stored envelope is surfaced explicitly and not treated as empty state;
8. payload/private bytes do not appear in normal rendering or observability;
9. structural foundation has no Authority, licensing, Android, scheduler or cognitive-policy semantics;
10. same logical store ID remains composition/backend-instance isolated unless an explicit shared backend is supplied.

Only after this primitive is GREEN and readiness-audited should a second slice connect Memory/Knowledge through a reviewed hydration/commit boundary.

## Freeze rule

Persistent Cognitive Storage v0.1 is not frozen until the primitive, recovery semantics, exact ownership, privacy, failure atomicity and readiness contracts are independently GREEN and a canonical freeze checkpoint is merged with merge/main CI GREEN.
