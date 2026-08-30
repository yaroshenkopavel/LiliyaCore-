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

Memory Persistence Integration v0.1 implementation/readiness baseline is:

`16a15c739cc96aaddc026aba3252750650432e73`

Merge/main Core CI: `33317696880` GREEN.

Memory persistence is frozen pending only its documentation-checkpoint merge.

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

## Next active stage — Knowledge Persistence Integration v0.1

After the Memory persistence freeze checkpoint is GREEN, start with a Knowledge persistence architecture contract before production changes.

Current frozen Knowledge is still process-local. Preserve:

- `KnowledgeItemId` exactly;
- exact `KnowledgeGeneration` ownership and monotonic restoration;
- duplicate live-ID rejection and stale/ABA-safe removal;
- deterministic snapshots ordered by `createdAt`, then ID;
- `KnowledgeOrigin.Memory(recordId, generation)` exactly;
- `KnowledgeOrigin.Declared(sourceId, sourceReference)` exactly;
- private Knowledge content outside operational observability;
- composition/backend isolation.

Selected direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

A durable `KnowledgeOrigin.Memory` is structural provenance only. It does not grant permission, Authority or execution capability and does not by itself prove that referenced Memory is currently live.

Keep Learning persistence, Android, SQLite/SQLCipher, Keystore, encryption implementation, licensing, scheduler, hidden retry and multi-writer reconciliation outside the first Knowledge slice.

## Resume procedure

1. verify current `main` SHA and latest merge/main CI;
2. if the Memory persistence freeze checkpoint PR is open, finish that exact-head/main gate first;
3. read `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md` and frozen Knowledge models/store/contracts;
4. draft `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md` before production changes;
5. design the narrowest Knowledge codec + reviewed exact-generation restoration boundary;
6. add executable compatibility/failure/privacy contracts before durable wiring;
7. keep platform storage/encryption decisions outside the core domain integration stage.
