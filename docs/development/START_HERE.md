# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Current project type: core-only Kotlin/JVM foundation. Android/device adapters are not part of current `main`.

## Source of truth

Before changing code, read:

1. `CURRENT_STATE.md`;
2. `ARCHITECTURE.md`;
3. `STRUCTURE.md`;
4. `NUANCES.md`;
5. the canonical contract/freeze document for the touched subsystem;
6. production source and executable contracts;
7. current GitHub PR/CI state.

## Hard engineering rules

- work on feature branches;
- merge only after exact-head Core CI GREEN;
- verify merge/main CI after architectural slices;
- exact `(ID, generation)` ownership beats ID-only ownership;
- stale/ABA ownership must never delete a replacement generation;
- capability is not permission; Authority is separate from Execution;
- structural provenance strings are evidence, not credentials/capabilities/Authority receipts;
- private cognitive payloads stay out of operational observability;
- persistence, encryption, licensing, Authority and cognitive permission remain separate;
- frozen baselines are not casually redesigned.

## Frozen baselines

Persistent Cognitive Storage v0.1 is fully frozen.

Memory Persistence Integration v0.1 is fully frozen on verified `main`:

`c7a7866c199d42713c7047289db1e0f68559fcae`

Freeze checkpoint exact-head Core CI: `33317960415` GREEN.

Freeze checkpoint merge/main Core CI: `33318203580` GREEN.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

Frozen Memory persistence direction:

`frozen Memory domain → canonical Memory codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Memory semantics`

Write ordering:

`Memory encode → durable commit → exact committed Memory install → success`

Remove ordering:

`exact persisted ownership → durable exact-generation remove → exact local remove → success`

The integration preserves exact Memory IDs/generations, durable generation high-watermark, provenance/content/time codec fidelity, deterministic snapshots, stale/ABA-safe removal, fail-closed reopen, explicit shared-backend CAS conflict behavior and privacy-safe failure rendering.

It does not provide Android storage, SQLite/SQLCipher, Keystore, authenticated encryption, licensing, scheduler, hidden retry/refresh or multi-writer reconciliation.

Mandatory separation:

`Memory != Persistence != Encryption != License != Authority != Cognitive Permission`

## Current active stage — Knowledge Persistence Integration v0.1

Canonical contract:

`KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`

Selected direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

Current frozen Knowledge is process-local. Preserve exactly:

- `KnowledgeItemId`;
- `KnowledgeGeneration` ownership and persisted high-watermark monotonicity;
- duplicate live-ID rejection and stale/ABA-safe removal;
- deterministic snapshots ordered by `createdAt`, then ID;
- caller-supplied `createdAt`;
- `KnowledgeOrigin.Memory(recordId, generation)`;
- `KnowledgeOrigin.Declared(sourceId, sourceReference)`;
- private Knowledge content outside operational observability;
- composition/backend isolation.

`KnowledgeOrigin.Memory` is structural provenance only. It does not require a live Memory lookup for Knowledge create/hydration and does not grant permission, capability or Authority.

Durable create ordering must be:

`validate Knowledge → encode → durable commit → exact committed Knowledge install → success`

Durable removal ordering must be:

`validate exact Knowledge ownership → durable exact-generation remove → exact local remove → success`

Failed durable create must keep Knowledge locally absent. Failed/conflicting durable remove must keep local Knowledge live. Corrupt/incompatible/open failures must publish no partial Knowledge composition.

First implementation slice must be the narrowest codec + reviewed restoration boundary. Keep durable wiring separate until codec/restoration contracts are GREEN.

Keep Learning persistence, Android, SQLite/SQLCipher, Keystore, encryption implementation, licensing, scheduler, trust/confidence scoring, semantic deduplication, hidden retry and multi-writer reconciliation outside v0.1.

## Resume procedure

1. verify current `main` SHA and latest merge/main CI;
2. read `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md` plus frozen Knowledge and persistence contracts;
3. inspect `KnowledgeModels`, `KnowledgeStore`, `KnowledgeComposition` and executable Knowledge contracts;
4. implement the canonical Knowledge codec and reviewed exact-generation restoration boundary first;
5. add executable round-trip/malformed/restoration/high-watermark/privacy contracts;
6. only after that wire durable create/remove and reopen semantics;
7. merge each architectural slice only after exact-head Core CI GREEN and readiness audit;
8. keep platform storage/encryption decisions outside the core domain integration stage.
