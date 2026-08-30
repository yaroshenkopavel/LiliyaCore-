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

## Frozen persistence baselines

Persistent Cognitive Storage v0.1 is fully frozen.

Memory Persistence Integration v0.1 is fully frozen on verified checkpoint `c7a7866c199d42713c7047289db1e0f68559fcae` with exact-head CI `33317960415` GREEN and merge/main CI `33318203580` GREEN.

Knowledge Persistence Integration v0.1 implementation/readiness is verified on code baseline:

`450e65b2c0d3a53a4e4389532c15653accc27a64`

Readiness exact-head Core CI: `33320271163` GREEN.

Readiness merge/main Core CI: `33320431935` GREEN.

Knowledge Persistence Integration v0.1 is **FROZEN pending documentation-checkpoint merge**.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

Frozen Knowledge persistence direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

Create ordering:

`Knowledge encode → durable commit → exact committed Knowledge install → success`

Remove ordering:

`exact persisted ownership → durable exact-generation remove → exact local remove → success`

The integration preserves exact Knowledge IDs/generations, durable generation high-watermark, both origin forms, content and caller-supplied timestamps, deterministic snapshots, stale/ABA-safe removal, fail-closed reopen, explicit shared-backend CAS conflict behavior and privacy-safe failure rendering.

`KnowledgeOrigin.Memory(recordId, generation)` remains structural provenance only; hydration does not require a live Memory lookup. `KnowledgeOrigin.Declared(sourceId, sourceReference?)` remains attribution only. Neither grants trust, permission, capability or Authority.

It does not provide Android storage, SQLite/SQLCipher, Keystore, authenticated encryption, licensing, scheduler, hidden retry/refresh or multi-writer reconciliation.

Mandatory separation:

`Knowledge != Persistence != Encryption != License != Authority != Cognitive Permission`

## Current checkpoint

The current branch/PR should be a docs-only **Knowledge Persistence Integration v0.1 Freeze Checkpoint** based on verified `main` `450e65b2c0d3a53a4e4389532c15653accc27a64`.

Before declaring the subsystem fully frozen:

1. verify the freeze checkpoint exact head has Core CI GREEN;
2. verify the PR changes only the intended documentation files;
3. merge with exact-head protection;
4. verify merge/main Core CI GREEN;
5. only then treat Knowledge Persistence Integration v0.1 as fully frozen.

## Next controlled stage — Learning Persistence Integration v0.1

After the Knowledge freeze checkpoint is fully GREEN, begin **Learning Persistence Integration v0.1** with an architecture contract only.

The first task is to inspect the already frozen Learning domain and its executable contracts, then define the durable boundary before production code changes.

The Learning persistence contract must preserve existing identity/generation/provenance semantics and keep these concerns separate:

`Learning != Persistence != Encryption != License != Authority != Execution`

Do not infer exactly-once learning, idempotency, retry, scheduling, distributed coordination or execution permission from durable storage. Any such guarantee requires its own explicit contract and executable proof.

Keep Android, SQLite/SQLCipher, Keystore, encryption implementation, licensing, scheduler, cloud sync and multi-writer reconciliation outside this stage unless separately selected and reviewed.

## Resume procedure

1. verify current `main` SHA and latest merge/main CI;
2. finish the Knowledge Persistence freeze checkpoint if it is still open;
3. read `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md` before touching frozen Knowledge persistence code;
4. after full freeze, inspect Learning production models/store/composition and executable contracts;
5. draft `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md` before any Learning persistence production changes;
6. split implementation into narrow codec/restoration, durable mutation/reopen, readiness, and freeze slices only after the architecture contract is GREEN.
