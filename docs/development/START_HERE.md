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
- failed stale removal is not automatically fatal when a newer generation is live;
- capability is not permission; Authority is separate from Execution;
- fresh Authority is mandatory at real side-effect boundaries;
- structural provenance strings are evidence, not credentials/capabilities/Authority receipts;
- compound writes are TOCTOU-sensitive and require post-write fresh revalidation/compensation;
- private cognitive payloads stay out of operational observability;
- persistence, encryption, licensing, Authority and cognitive permission remain separate;
- frozen baselines are not casually redesigned.

## Frozen baselines

Controlled Agent Coordination v0.1 and all earlier Agent/Autonomy/Orchestration/Decision/Reasoning/Planning/Foundation boundaries are frozen.

Persistent Cognitive Storage v0.1 generic durable primitive is **frozen pending its documentation-checkpoint merge** on verified code baseline:

`a6ed4893e0e792575d4f2b6246e0a48e72f851b2`

Canonical persistence documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`

Frozen persistence primitive direction:

`canonical persistent record → exact-generation ownership → backend revision CAS → durable commit acknowledgement → explicit reopen/recovery validation`

It guarantees exact stale/ABA-safe ownership, durable generation high-watermark, explicit failure/recovery outcomes, deterministic detached snapshots, privacy-safe payload handling and a public storage-engine-neutral backend SPI.

It does **not** yet persist Memory/Knowledge and does not provide Android, SQLite/SQLCipher, Keystore, authenticated encryption, licensing, scheduler or cognitive-policy semantics.

Mandatory separation:

`Persistence != Encryption != License != Authority != Cognitive Permission`

## Current active stage — Memory Persistence Integration v0.1

After the persistence freeze documentation checkpoint is GREEN, integrate the frozen Memory domain first.

Selected direction:

`frozen Memory domain → reviewed persistence codec/adapter → exact persistent record store → explicit hydration/restoration → frozen Memory semantics`

Required compatibility constraints:

- preserve current Memory IDs and exact generations;
- preserve provenance/origin fields;
- preserve deterministic snapshots;
- preserve stale/ABA-safe removal;
- preserve composition isolation;
- keep private memory content out of operational observability;
- do not inject arbitrary internal map entries during hydration;
- do not redesign the frozen Memory public contract without a demonstrated correctness requirement and focused versioned contracts.

The first integration slice remains core-only and storage-engine-neutral. Keep Knowledge/Learning, Android, SQLCipher/SQLite, Keystore and licensing out until Memory integration is independently GREEN and readiness-audited.

## Resume procedure

1. verify current `main` SHA and merge/main CI;
2. if the persistence freeze checkpoint PR is still open, finish that exact-head/main CI gate first;
3. read `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md` plus frozen Memory models/store/contracts;
4. design the narrowest reviewed Memory codec/restoration boundary;
5. add executable compatibility contracts before broad wiring;
6. merge only after exact-head CI + architecture/privacy/readiness audit;
7. keep platform storage/encryption decisions out of this core integration slice.
