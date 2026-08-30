# Memory Persistence Integration v0.1 — Architecture Contract

Status: **ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET FROZEN**

Selected after Persistent Cognitive Storage v0.1 freeze on verified `main` `54b2896957212ff7564b35fad7e39ccbeb3a8e92` with merge/main Core CI `33315974315` GREEN.

## Purpose

Integrate the frozen Memory domain with the frozen generic persistence primitive without weakening existing Memory ownership, privacy or composition-isolation guarantees.

The integration is core-only and storage-engine-neutral. It does not select Android, SQLite, SQLCipher, filesystem layout, Keystore, licensing, cloud sync or a serialization library.

## Dependency direction

`frozen Memory domain → canonical Memory codec → exact persistent record store → explicit hydration/restoration → frozen Memory semantics`

Mandatory separation:

`Memory != Persistence != Encryption != License != Authority != Cognitive Permission`

Persisted memory is state, not permission.

## Compatibility requirements

The integration must preserve:

- `MemoryRecordId` exactly;
- `MemoryGeneration` exact ownership semantics;
- store-global generation monotonicity across reopen;
- `MemoryProvenance`, including optional `sourceReference`;
- `content` and `createdAt` exactly at the codec boundary;
- duplicate live-ID rejection;
- stale/ABA-safe removal;
- deterministic snapshots ordered by `createdAt`, then ID;
- same-composition semantics and explicit backend sharing/isolation;
- existing public Memory contracts unless a demonstrated correctness need requires a focused versioned change;
- private Memory content outside operational observability.

## Codec boundary

The first slice may introduce a dedicated Memory persistence codec/adapter. It must encode/decode only Memory domain state required for exact restoration.

The codec must:

- use an explicit schema ID and positive schema version;
- reject malformed, incompatible or semantically invalid payloads explicitly;
- never log decoded Memory content or raw payload bytes;
- round-trip ID, provenance, content and timestamp deterministically;
- avoid deriving permission, Authority or cognitive policy from persisted fields.

## Restoration boundary

Hydration must not inject arbitrary entries into `MemoryStore` internals.

A reviewed restoration API must restore an exact committed `(MemoryRecord, MemoryGeneration)` while also restoring the Memory generation high-watermark. It must validate:

- positive generation;
- no duplicate live ID;
- no duplicate live generation within one Memory store generation domain;
- restored generation not above the persisted high-watermark;
- exact record identity;
- deterministic fail-closed behavior on malformed restoration state.

The restoration boundary is internal/domain-owned and is not a general bypass around normal Memory registration.

## Write path

For persisted Memory compositions, a successful `remember` must not be reported before durable persistence commit succeeds.

Required direction:

`validate Memory record → persistent install/commit → install exact committed Memory generation locally → return Memory ownership`

If durable commit fails, the Memory record must not become locally visible as successfully remembered.

If local restoration/install cannot accept the exact durable generation after the durable commit, the operation must fail closed and expose an explicit integration failure; it must not invent a different generation.

## Remove path

Removal must remain exact-generation owned.

Required direction:

`validate exact Memory ownership → durable exact-generation remove → remove same exact Memory generation locally → success`

A stale owner must never remove a newer persisted or in-memory replacement generation.

If durable remove fails or conflicts, local Memory state must remain live.

## Reopen / hydration

A persisted Memory composition reopen must:

1. open the persistent store explicitly;
2. fail closed on corrupt/incompatible/failed persistent state;
3. decode every live persistent Memory record through the Memory codec;
4. validate exact ID/generation consistency;
5. restore deterministic Memory entries and generation high-watermark;
6. expose a ready Memory composition only after the complete restoration succeeds.

Partial hydration must not be silently published as a complete Memory store.

## Failure outcomes

The first integration slice must distinguish at least:

- opened/restored successfully;
- rejected domain request;
- durable mutation failed/conflicted;
- corrupt persisted Memory payload/state;
- incompatible schema/version;
- backend/open failure;
- restoration invariant failure.

Sensitive content must not appear in failure text.

## Privacy

Memory `content` is private cognitive data.

Allowed operational metadata includes structural values such as Memory ID, generation, source ID/reference, schema/version and byte/count metrics. Memory content and decoded payload bytes remain excluded from normal logs, diagnostics and exception rendering.

## Explicit non-goals

Memory Persistence Integration v0.1 does not add:

- Knowledge persistence;
- Learning/idempotency persistence;
- Android storage;
- SQLite/SQLCipher;
- Keystore/StrongBox;
- authenticated encryption;
- cloud synchronization;
- backup/export product policy;
- licensing;
- Authority decisions;
- scheduler/retry/background sync;
- semantic memory merging or conflict resolution.

## First implementation slice

Build the narrowest executable path proving:

1. Memory codec deterministic round-trip preserves ID/provenance/content/time;
2. malformed/incompatible payload fails explicitly without content leakage;
3. exact Memory generation can be restored through a reviewed internal boundary;
4. Memory generation high-watermark remains monotonic after reopen;
5. persisted `remember` reports success only after durable commit;
6. failed durable commit leaves Memory locally absent;
7. exact persisted removal is stale/ABA-safe;
8. failed durable remove keeps local Memory live;
9. reopen restores deterministic snapshots and exact generations;
10. corrupt/incompatible/open failure does not publish a partially hydrated Memory composition;
11. independent backends isolate the same logical Memory store ID unless shared explicitly;
12. no Knowledge/Learning/Android/licensing/Authority/scheduler semantics enter the API.

## Freeze rule

Memory Persistence Integration v0.1 is not frozen until codec, restoration, durable write/remove ordering, reopen atomicity, privacy, exact ownership and readiness contracts are GREEN and a canonical freeze checkpoint merges with main CI GREEN.
