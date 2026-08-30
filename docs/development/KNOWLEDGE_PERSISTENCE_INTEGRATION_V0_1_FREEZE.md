# Knowledge Persistence Integration v0.1 — Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline: `450e65b2c0d3a53a4e4389532c15653accc27a64`.

Verified implementation slices:

- PR #18 — Architecture Contract, exact head `fa3127889f7226fb6b3f589b4965c60b76e19ebb`, merge `d3a4b1264954bcad89415b2a7192e0f3aa62e928`, exact-head CI `33318537838` GREEN, merge/main CI `33318684331` GREEN;
- PR #19 — Codec and Restoration Boundary, exact head `2730c262d5cf65a7e30cfaf29dd4e6e270fecd21`, merge `ff270676ab9d78a142340f4e438de3ca01202379`, exact-head CI `33318917579` GREEN, merge/main CI `33319314032` GREEN;
- PR #20 — Durable Create Remove and Reopen, exact head `026278516b0e1d9b07ae45ec7260ba8b0b3c7106`, merge `aeb3652d9e0488f95444e49551519dc81eadb665`, exact-head CI `33319625540` GREEN, merge/main CI `33319765350` GREEN;
- PR #21 — Readiness Hardening, exact head `0dd0eef62a85db47cbd749593cf1fa01031ae9b3`, merge `450e65b2c0d3a53a4e4389532c15653accc27a64`, exact-head CI `33320271163` GREEN, merge/main CI `33320431935` GREEN.

Canonical architecture contract: `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`.

## Frozen integration boundary

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

Persisted create ordering:

`Knowledge encode → durable persistent commit → exact committed Knowledge install → success`

Persisted removal ordering:

`exact persisted ownership → durable exact-generation remove → exact local remove → success`

## Frozen guarantees

- `KnowledgeItemId` survives codec/reopen exactly;
- `KnowledgeGeneration` maps exactly to persistent generation ownership;
- Knowledge generation high-watermark is restored from persistent state and remains monotonic across reopen;
- `KnowledgeOrigin.Memory(recordId, generation)` round-trips exactly and remains structural provenance only;
- `KnowledgeOrigin.Declared(sourceId, sourceReference?)` round-trips exactly and remains attribution only;
- Knowledge content and caller-supplied `createdAt` round-trip through the canonical codec;
- malformed/trailing payload, wrong schema/version, persistent ID mismatch, timestamp mismatch and impossible restoration state fail explicitly and closed;
- hydration is Knowledge-owned and reviewed; arbitrary internal map injection is not an integration API;
- complete reopen succeeds only after all live persistent records decode and restoration invariants validate;
- corrupt/incompatible/open/restoration failure does not publish a partially hydrated Knowledge composition;
- successful persisted `create` is not visible locally before durable commit acknowledgement;
- failed/conflicting durable install leaves Knowledge locally absent;
- exact persisted remove commits before exact local removal;
- failed/conflicting durable remove keeps local Knowledge live;
- stale/ABA ownership cannot remove a newer persisted replacement generation;
- same-composition persisted mutation pipelines are serialized across durable and local publication boundaries;
- independently opened compositions over one explicitly shared backend surface optimistic-CAS conflict instead of hidden refresh/retry/reconciliation or local publication;
- deterministic Knowledge snapshots retain `createdAt`, then ID ordering after reopen;
- normal Persistent Knowledge failure rendering excludes backend exception messages and private Knowledge content;
- independent backend instances remain isolated unless sharing is explicit.

## Origin rule

A persisted `KnowledgeOrigin.Memory` is provenance/consistency evidence, not proof that referenced Memory still exists. Hydration does not require a live Memory lookup.

A persisted `KnowledgeOrigin.Declared` is attribution, not an authenticity or trust assertion.

Neither origin form is a credential, capability, permission, Authority receipt or execution grant.

## Failure and compensation rule

Durable success is never inferred from a local candidate. `Created` follows durable install acknowledgement and exact local installation.

If exact local installation unexpectedly rejects after a durable install, the integration attempts exact-generation durable compensation and reports failure; it never invents another Knowledge generation. A compensation failure remains explicit rather than hidden.

A committed durable remove followed by an unexpected exact local removal failure is reported as failure; reopen remains the authoritative recovery path from persistent state.

## Concurrency rule

One `PersistentKnowledgeComposition` serializes its mutation pipeline so another local mutation cannot interleave between durable commit and exact local publication.

Two independently opened compositions over one shared backend are not a synchronized multi-writer cluster. The frozen persistence CAS boundary detects stale revisions; Knowledge integration does not add automatic refresh, retry, merge or reconciliation.

## Privacy and authority separation

Knowledge `content` is private cognitive data and stays out of normal operational observability and failure rendering. Structural IDs, generations, schema/version, origin type, provenance identifiers and timestamps may be observed where already permitted by frozen contracts.

Mandatory separation remains:

`Knowledge != Persistence != Encryption != License != Authority != Cognitive Permission`

Persisted Knowledge is state, not permission. Nothing in this integration grants Authority or execution rights.

## Physical durability and encryption boundary

The integration is storage-engine-neutral. Physical crash durability depends on the concrete `PersistentRecordBackend`; the in-memory contract backend only survives reopen while that backend instance lives.

Knowledge Persistence Integration v0.1 does not itself provide authenticated encryption. The Security & Licensing architecture requirement for encrypted sensitive persistence remains a later backend/adapter responsibility and must preserve this frozen domain boundary.

## Explicit non-goals

This freeze does not add:

- Learning persistence;
- automatic live-Memory validation for `KnowledgeOrigin.Memory`;
- trust/confidence scoring or semantic deduplication;
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

The final audit found no blocking correctness, privacy or ownership defect requiring another implementation slice before freeze. The remaining limitations are explicit boundaries rather than hidden guarantees: concrete backend durability/encryption, shared-backend stale-writer reconciliation, live provenance validation and persistence integration for later cognitive domains.

After this documentation checkpoint merges with exact-head and merge/main Core CI GREEN, Knowledge Persistence Integration v0.1 is fully frozen.

## Next controlled stage

The next selected stage is **Learning Persistence Integration v0.1 architecture work**.

Learning persistence must begin with a separate reviewed contract. It must preserve the already frozen separation between cognitive learning state, persistence, authority and execution; it must not infer exactly-once learning, retry, scheduling or idempotency guarantees merely because durable storage exists.
