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

Persistent Cognitive Storage v0.1 generic durable primitive is fully frozen on verified `main`:

`54b2896957212ff7564b35fad7e39ccbeb3a8e92`

Merge/main Core CI: `33315974315` GREEN.

Canonical persistence documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`

Frozen persistence primitive direction:

`canonical persistent record → exact-generation ownership → backend revision CAS → durable commit acknowledgement → explicit reopen/recovery validation`

Controlled Agent Coordination v0.1 and all earlier frozen Foundation/Memory/Knowledge/Planning/Reasoning/Decision/Orchestration/Autonomy/Agent boundaries remain frozen.

Mandatory separation:

`Persistence != Encryption != License != Authority != Cognitive Permission`

## Current active stage — Memory Persistence Integration v0.1

Canonical contract:

`MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`

Selected direction:

`frozen Memory domain → canonical Memory codec → exact persistent record store → explicit hydration/restoration → frozen Memory semantics`

Required compatibility constraints:

- preserve `MemoryRecordId` exactly;
- preserve exact `MemoryGeneration` ownership and store-global monotonicity across reopen;
- preserve provenance/source reference, content and timestamp at the codec boundary;
- preserve duplicate rejection and stale/ABA-safe removal;
- preserve deterministic snapshots and composition/backend isolation;
- keep private Memory content out of operational observability;
- hydrate only through a reviewed Memory-owned restoration boundary;
- do not redesign frozen Memory public contracts without a demonstrated correctness requirement.

Write ordering for persisted Memory must be fail-closed:

`validate → durable commit → exact local install → success`

Remove ordering must be exact-generation and durable-first:

`validate exact ownership → durable exact remove → exact local remove → success`

A failed durable write must leave Memory locally absent. A failed/conflicting durable remove must keep local Memory live. Corrupt/incompatible/open failures must not publish a partially hydrated Memory composition.

The first integration slice remains core-only and storage-engine-neutral. Keep Knowledge/Learning, Android, SQLite/SQLCipher, Keystore, authenticated encryption, licensing and scheduler semantics out.

## Resume procedure

1. verify current `main` SHA and latest merge/main CI;
2. read `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md` plus frozen Memory and persistence contracts;
3. inspect `MemoryModels`, `MemoryStore`, `MemoryComposition` and Memory executable contracts;
4. implement the narrowest Memory codec + reviewed restoration boundary before broad wiring;
5. add executable compatibility/failure/privacy contracts;
6. merge only after exact-head CI + architecture/privacy/readiness audit;
7. keep platform storage/encryption choices out of this core integration stage.
