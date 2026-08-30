# Memory Persistence Integration v0.1 — Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline: `16a15c739cc96aaddc026aba3252750650432e73`.

Verified implementation slices:

- PR #13 — Architecture Contract, exact head `a82fcbd14d969d5d8f57885999f88b1cdc35f726`, merge `d6acaacecd8419a94530431166fe50caea42ef78`, exact-head CI `33316237238` GREEN, merge/main CI `33316354287` GREEN;
- PR #14 — Codec and Restoration Boundary, exact head `8375d943a9d4b73f11e4e32d5664c3fe7b73b406`, merge `89d13fa43a0abc090075b93b7a558b48ce54859e`, exact-head CI `33316585365` GREEN, merge/main CI `33316735201` GREEN;
- PR #15 — Durable Remember and Remove, exact head `17669baaaec794feb13a370774666de93738d83e`, merge `ebd6f804d6b3d389c468c277559dfa71de105adb`, exact-head CI `33317081729` GREEN, merge/main CI `33317242966` GREEN;
- PR #16 — Readiness Hardening, exact head `8ad2b6e502e21b74ca8ca8bdba7a327fb0264811`, merge `16a15c739cc96aaddc026aba3252750650432e73`, exact-head CI `33317555845` GREEN, merge/main CI `33317696880` GREEN.

Canonical architecture contract: `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`.

## Frozen integration boundary

`frozen Memory domain → canonical Memory codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Memory semantics`

Persisted remember ordering:

`Memory encode → durable persistent commit → exact committed Memory install → success`

Persisted removal ordering:

`exact persisted ownership → durable exact-generation remove → exact local remove → success`

## Frozen guarantees

- `MemoryRecordId` survives codec/reopen exactly;
- `MemoryGeneration` maps exactly to persistent generation ownership;
- Memory generation high-watermark is restored from durable persistent state and remains monotonic across reopen;
- `MemoryProvenance`, optional source reference, content and timestamp round-trip through the canonical codec;
- malformed payload, wrong schema/version and impossible restoration state fail explicitly;
- hydration is Memory-owned and reviewed; arbitrary internal map injection is not an integration API;
- complete reopen succeeds only after all live persistent records decode and restoration invariants validate;
- corrupt/incompatible/open/restoration failure does not publish a partially hydrated Memory composition;
- successful persisted `remember` is not visible locally before durable commit acknowledgement;
- failed durable install leaves Memory locally absent;
- exact persisted remove commits before exact local removal;
- failed/conflicting durable remove keeps local Memory live;
- stale/ABA ownership cannot remove a newer persisted replacement generation;
- same-composition persisted mutation pipelines are serialized across durable and local publication boundaries;
- shared-backend stale compositions surface explicit optimistic-CAS rejection rather than hidden refresh/retry or local publication;
- deterministic Memory snapshots retain `createdAt`, then ID ordering after reopen;
- normal Persistent Memory failure rendering excludes backend exception messages and private Memory content;
- independent backend instances remain isolated unless sharing is explicit.

## Failure and compensation rule

Durable success is never inferred from a local candidate. `Remembered` follows durable install acknowledgement and exact local installation.

If exact local installation unexpectedly rejects after a durable install, the integration attempts exact-generation durable compensation and reports failure; it never invents another Memory generation. A compensation failure remains explicit rather than being hidden.

A committed durable remove followed by an unexpected exact local removal failure is reported as failure; reopen remains the authoritative recovery path from durable state.

## Concurrency rule

One `PersistentMemoryComposition` serializes its mutation pipeline so another local mutation cannot interleave between its durable commit and exact local publication.

Two independently opened compositions over one shared backend are not a synchronized multi-writer cluster. The frozen persistence CAS boundary detects stale revisions; Memory integration does not add automatic refresh, retry, merge or reconciliation.

## Privacy and authority separation

Memory `content` is private cognitive data and stays out of normal operational observability and failure rendering. Structural IDs, generations, schema/version, provenance references, timestamps and byte/count metrics may be observed where already permitted by frozen contracts.

Mandatory separation remains:

`Memory != Persistence != Encryption != License != Authority != Cognitive Permission`

Persisted Memory is state, not permission. Nothing in this integration grants Authority or execution rights.

## Physical durability and encryption boundary

The integration is storage-engine-neutral. Physical crash durability depends on the concrete `PersistentRecordBackend`; the in-memory contract backend only survives reopen while that backend instance lives.

Memory Persistence Integration v0.1 does not itself provide authenticated encryption. The Security & Licensing architecture requirement for encrypted sensitive persistence remains a later backend/adapter responsibility and must preserve this frozen domain boundary.

## Explicit non-goals

This freeze does not add:

- Knowledge persistence;
- Learning/idempotency persistence;
- Android/device storage;
- SQLite/SQLCipher or filesystem layout;
- Keystore/StrongBox;
- authenticated encryption implementation;
- cloud sync or backup product policy;
- licensing semantics;
- Authority/capability decisions;
- scheduler/background sync;
- automatic retry/refresh;
- multi-writer merge/conflict resolution.

## Readiness conclusion

The final audit found no blocking correctness, privacy or ownership defect requiring another implementation slice before freeze. The remaining limitations are explicit boundaries rather than hidden guarantees: concrete backend durability/encryption, shared-backend stale-writer reconciliation, and persistence integration for other cognitive domains.

After this documentation checkpoint merges with exact-head and merge/main Core CI GREEN, Memory Persistence Integration v0.1 is fully frozen.

## Next controlled stage

The next selected stage is **Knowledge Persistence Integration v0.1**.

Current frozen Knowledge remains process-local and uses exact-generation registration/removal with deterministic snapshots. Knowledge origin can reference an exact `(MemoryRecordId, MemoryGeneration)` or a declared source. The next architecture contract must preserve those semantics while integrating through the already frozen persistence primitive; it must not infer permission or validity merely because referenced Memory/Knowledge state is durable.
