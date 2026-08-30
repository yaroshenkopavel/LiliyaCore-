# Persistent Cognitive Storage v0.1 — Primitive Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline: `a6ed4893e0e792575d4f2b6246e0a48e72f851b2`.

Verified implementation slices:

- PR #9 — Architecture Contract, merge `60601ec5e98362dc7df34b006b4d7eb903ad71c8`, exact-head CI `33313485724` GREEN, merge/main CI `33313692213` GREEN;
- PR #10 — Durable Record Store, exact head `c67e9b1f85d5c6d9e83e29c174dfae5ff8d3b0ed`, merge `2d0744f09e92e11a9917f9615b06870b0e9d0969`, exact-head CI `33314024175` GREEN, merge/main CI `33314620220` GREEN;
- PR #11 — Readiness Hardening, corrected exact head `d8decb41ff59588c1ee9a8c06eb0689fb1982aa8`, merge `a6ed4893e0e792575d4f2b6246e0a48e72f851b2`, exact-head CI `33315017295` GREEN, merge/main CI `33315169997` GREEN.

Canonical architecture contract: `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`.

## Frozen primitive boundary

`canonical persistent record → exact-generation ownership → backend revision CAS → durable commit acknowledgement → explicit reopen/recovery validation`

The frozen primitive is storage-engine-neutral and core-only. It does not select Android, SQLite, SQLCipher, filesystem layout, Keystore, licensing, cloud synchronization, or a serialization library.

## Mandatory separation

`Persistence != Encryption != License != Authority != Cognitive Permission`

A durable record is state, not permission. Persistence does not authorize cognition or execution. Licensing does not own user cognitive data. Encryption remains a later adapter boundary.

## Frozen guarantees

- persistent store, entity, schema and generation identities are explicit structural types;
- generation ownership is exact and stale/ABA-safe;
- duplicate live entity IDs reject without replacing the current record;
- removal is bound to exact `(entityId, generation)` ownership;
- a removed entity reinstalled later receives a newer store-global durable generation;
- the generation high-watermark survives reopen and is not derived only from currently live records;
- backend writes use an explicit expected-revision compare-and-set boundary;
- successful install/remove is published locally only after backend `Committed` acknowledgement;
- backend commit failure is surfaced as `Failed` and does not publish the candidate into local store state;
- backend revision acknowledgement must advance monotonically or the store fails closed locally;
- backend revision conflict is rejected rather than silently overwriting newer durable state;
- reopen distinguishes Missing, Loaded, Corrupt, Incompatible and Failed outcomes;
- loaded state rejects store-ID mismatch, map-key/record-ID mismatch, generations above the high-watermark and duplicate live generations;
- deterministic snapshots sort by `createdAt` then entity ID;
- payload bytes are defensively copied at model/store/backend boundaries used by the primitive;
- payload content is redacted from normal record/payload rendering and excluded from operational persistence observability;
- the public backend SPI is adapter-implementable without Authority, licensing, Android, scheduler or cognitive-policy semantics;
- independent backend instances isolate the same logical store ID; sharing requires an explicitly shared backend instance.

## Failure and recovery rule

`Committed`, `Rejected` and `Failed` are intentionally distinct outcomes. A caller must never infer durable success from an in-memory candidate before backend acknowledgement.

A corrupt or incompatible load is not silently converted into an empty store. Recovery is explicit and fail-closed.

A backend `Failed` outcome means the store cannot claim success. Platform adapters remain responsible for their own durable transaction semantics and must not report `Committed` before their durable boundary is satisfied.

## Exact ownership / ABA rule

String IDs are labels, not ownership handles. Durable mutation belongs only to the exact generation returned by the successful install. A stale handle cannot remove a later replacement generation.

The durable high-watermark remains monotonic even when the highest live generation was removed, so reopen cannot recycle an older generation value.

## Privacy rule

Persistent payload bytes are private user data. Operational persistence events may carry structural IDs, schema/version, generation, byte counts, timestamps and failure categories, but must not emit decoded payload content.

Future encrypted adapters must preserve this rule and add authenticated encryption without turning keys, licenses or device identifiers into cognitive Authority.

## Backend SPI rule

`PersistentRecordBackend` is a storage adapter boundary, not a database implementation. Implementations may use a future database or file engine, but they must preserve expected-revision atomicity, detached state semantics, explicit load outcomes and durable acknowledgement semantics.

The in-memory backend is a contract/development implementation only; it is not a claim of process-crash durability.

## Explicit non-goals

This freeze does not provide:

- MemoryStore or KnowledgeStore persistence integration;
- Android/database/file-system storage;
- SQLite/SQLCipher selection;
- Keystore/StrongBox keys;
- authenticated encryption at rest;
- backup/export policy;
- cloud or multi-device synchronization;
- crash-durable learning exactly-once semantics;
- licensing;
- Authority or permission decisions;
- scheduler/retry behavior;
- cognitive policy.

## Next controlled integration boundary

The next persistence work must integrate one frozen cognitive domain at a time through a reviewed hydration/commit adapter. It must preserve existing domain IDs, exact generations, provenance, deterministic snapshots, stale/ABA safety, composition isolation and privacy without injecting arbitrary internal map state.

Memory integration should be proven independently before broad Knowledge/Learning persistence is attempted.

## Freeze decision

With the generic primitive, recovery validation, exact ownership, privacy, failure atomicity and readiness contracts GREEN on baseline `a6ed4893e0e792575d4f2b6246e0a48e72f851b2`, the Persistent Cognitive Storage v0.1 primitive is frozen once this documentation checkpoint is merged and its merge/main Core CI is GREEN.

Future changes to this frozen primitive require a demonstrated correctness/security need, focused executable contracts, exact-head CI, readiness reasoning and a journal update.
