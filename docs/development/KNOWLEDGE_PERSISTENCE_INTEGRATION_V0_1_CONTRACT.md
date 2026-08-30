# Knowledge Persistence Integration v0.1 Contract

Status: ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET FROZEN

## Purpose

Define the first storage-neutral persistence boundary for the already-frozen Knowledge domain without changing Knowledge into Authority, policy, trust, confidence or execution state.

Selected direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

This stage builds on the already-frozen Persistent Cognitive Storage v0.1 primitive and the already-frozen Memory Persistence Integration v0.1 pattern.

## Mandatory separation

`Knowledge != Persistence != Encryption != License != Authority != Cognitive Permission`

`KnowledgeOrigin != credential != capability != permission != Authority receipt`

Persisted Knowledge remains cognitive state. Persisting or reopening it does not grant permission, execution capability or Authority.

## Existing Knowledge semantics that must remain unchanged

The integration must preserve the current frozen Knowledge contract:

- exact `KnowledgeItemId` identity;
- positive `KnowledgeGeneration` ownership;
- duplicate live-ID rejection;
- stale/ABA-safe exact-generation removal;
- deterministic snapshots ordered by `createdAt`, then item ID;
- caller-supplied `createdAt` as an ordering value;
- private Knowledge content absent from operational observability;
- process/composition isolation unless backend sharing is explicit.

The current generation allocator consumes generations before duplicate rejection. The persistence integration must not silently redefine that frozen in-memory behavior. Persisted compositions may use persistent-store-assigned generations, but exact restored ownership and monotonic high-watermark semantics must remain explicit.

## Knowledge origin fidelity

The codec and restoration path must preserve origin exactly.

### Memory origin

`KnowledgeOrigin.Memory(recordId, generation)` must round-trip exactly, including both:

- `MemoryRecordId`;
- exact `MemoryGeneration`.

A Memory origin is structural provenance / consistency evidence only. It does not require a live Memory lookup during Knowledge creation or hydration and does not by itself prove that the referenced Memory still exists.

Persistence must not reinterpret a Memory origin as permission, trust, confidence, credential, capability or Authority.

### Declared origin

`KnowledgeOrigin.Declared(sourceId, sourceReference)` must round-trip exactly, including optional source reference.

Declared provenance remains attribution only. Persistence must not add implicit trust, confidence or authenticity semantics.

## Canonical Knowledge persistent record

The integration must define one explicit schema ID and positive schema version for Knowledge records.

The codec must deterministically preserve:

- `KnowledgeItemId`;
- origin type;
- all origin fields;
- content;
- `createdAt`;
- persistent entity ID equal to the Knowledge item ID at the integration boundary.

Malformed payload, unsupported schema ID/version, invalid field encoding, invalid origin encoding, mismatched persistent entity ID, mismatched timestamp or trailing/partial bytes must fail explicitly and fail closed.

No Knowledge content or raw payload bytes may appear in normal `toString`, logs, diagnostics or integration failure text by default.

## Restoration boundary

Hydration must not inject arbitrary internal maps.

The Knowledge domain must gain a reviewed internal/domain-owned restoration boundary that can construct a Knowledge store from:

- detached `KnowledgeItemSnapshot` entries;
- exact persisted `KnowledgeGeneration` values;
- persisted generation high-watermark.

Restoration must reject at least:

- negative high-watermark;
- live generation above high-watermark;
- duplicate live Knowledge item ID;
- duplicate live Knowledge generation;
- semantically invalid decoded item/origin.

Restoration must publish no partial Knowledge composition when any entry is corrupt, incompatible or invalid.

## Durable create ordering

Persisted Knowledge creation must be durable-first:

`validate Knowledge item → encode → persistent install/commit → exact committed Knowledge install locally → success`

The public success result must not be returned before durable commit acknowledgement.

If durable install fails or conflicts:

- the Knowledge item remains locally absent;
- no generated local ownership may be published;
- no hidden retry, refresh or reconciliation is allowed.

The local Knowledge generation must match the committed persistent generation exactly.

If durable commit succeeds but the exact local install cannot be completed, the integration must return an explicit failure. It must not invent a different generation or report success. Any compensation must be exact-generation and must never remove a newer replacement.

## Durable removal ordering

Persisted Knowledge removal must be exact-generation and durable-first:

`validate exact Knowledge ownership → durable exact-generation remove → exact local remove → success`

If durable remove fails or conflicts:

- local Knowledge remains live;
- the operation returns explicit rejected/failed outcome;
- no hidden retry is performed.

A stale Knowledge ownership must never remove a newer replacement generation from either persistent or local state.

If durable removal commits but exact local removal cannot complete, the integration must return explicit failure rather than claiming atomic success.

## Reopen semantics

Reopen must be fail-closed and atomic at the composition boundary:

`open persistent store → validate store → decode every live Knowledge record → validate exact IDs/origins/generations → restore Knowledge store with persisted high-watermark → publish ready composition`

`Corrupt`, `Incompatible` and backend `Failed` outcomes must remain explicit.

No partially hydrated Knowledge composition may escape on any reopen failure.

A reopen must preserve:

- exact item IDs;
- exact Knowledge generations;
- exact origin;
- exact content and timestamp;
- deterministic snapshots;
- persisted generation high-watermark across removals and reopen.

## Backend sharing and concurrency

A single persisted Knowledge composition may serialize its integration mutation pipeline to prevent a durable/local race.

Independent compositions explicitly sharing the same backend and store ID remain optimistic-CAS participants. A stale composition must receive an explicit conflict/rejection rather than hidden refresh/retry/reconciliation.

Backend instances remain isolated unless sharing is explicit.

No scheduler, distributed lock, consensus, merge engine or background reconciliation is part of v0.1.

## Privacy and observability

Knowledge content is private cognitive payload.

Allowed structural metadata includes identifiers, generations, schema information, origin type and structural provenance identifiers where already allowed by the frozen Knowledge contract.

Forbidden in normal operational observability by default:

- Knowledge content;
- persistent payload bytes;
- exception messages that may contain private payload;
- arbitrary backend details containing cognitive text.

Public failure rendering must prefer structural category/reason text and exception class rather than exception message.

## Security / encryption boundary

This contract is storage-engine-neutral and encryption-ready, not an encryption implementation.

It does not select Android, filesystem layout, SQLite, SQLCipher, Keystore or any specific serialization/encryption library.

Future authenticated-encryption adapters must preserve this exact identity/generation/origin/reopen contract.

Knowledge encryption keys must remain separate from vendor licensing/model entitlement. License expiry must not intentionally destroy or make user Knowledge unrecoverable.

## Non-goals

Knowledge Persistence Integration v0.1 does not add:

- Learning persistence;
- automatic Memory existence validation for `KnowledgeOrigin.Memory`;
- trust/confidence scoring;
- semantic deduplication;
- automatic Knowledge merging;
- scheduler/background jobs;
- hidden retry/refresh/reconciliation;
- Android/device storage;
- SQLite/SQLCipher/Keystore;
- encryption implementation;
- licensing behavior;
- Authority or execution permission semantics.

## First implementation slice gates

Before this integration can be frozen, executable contracts must prove at minimum:

1. deterministic Knowledge codec round-trip for `KnowledgeOrigin.Memory`;
2. deterministic Knowledge codec round-trip for `KnowledgeOrigin.Declared`, including optional source reference;
3. malformed/incompatible decode fails explicitly without content leakage;
4. exact Knowledge generation restoration;
5. persisted generation high-watermark restoration across reopen;
6. durable commit precedes successful local publication;
7. failed durable create keeps Knowledge locally absent;
8. durable exact-generation remove precedes local removal;
9. failed/conflicting durable remove keeps local Knowledge live;
10. stale ownership cannot remove a newer persisted replacement generation;
11. reopen restores exact item/origin/generation/content/time and deterministic snapshots;
12. corrupt/incompatible/open failure publishes no partial Knowledge composition;
13. same-composition concurrent mutations do not create local generation races;
14. explicitly shared backend preserves visible CAS conflict semantics without hidden retry;
15. failure rendering and observability do not expose Knowledge content or backend exception messages.

## Freeze criteria

Knowledge Persistence Integration v0.1 may be frozen only after:

- codec compatibility is GREEN;
- exact-generation restoration/high-watermark contracts are GREEN;
- durable create/remove ordering is GREEN;
- stale/ABA ownership contracts are GREEN;
- reopen atomicity/failure contracts are GREEN;
- concurrency/shared-backend semantics are GREEN;
- privacy/failure rendering contracts are GREEN;
- exact-head Core CI is GREEN;
- implementation/readiness audit finds no blocking correctness/privacy/ownership defect;
- freeze checkpoint PR exact-head and merge/main Core CI are GREEN.

Until then this document is the architecture contract, not a frozen implementation claim.
