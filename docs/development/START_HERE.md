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
- Planning/Reasoning/Decision/Orchestration data never becomes permission by provenance alone;
- Autonomy/Agent/Delegation/Coordination remain governance/provenance layers;
- structural provenance strings are evidence, not cryptographic credentials/capabilities/Authority receipts;
- compound writes are TOCTOU-sensitive and require post-write fresh revalidation/compensation;
- private cognitive payloads stay out of operational observability;
- frozen baselines are not casually redesigned.

## Frozen baselines

Controlled Agent Coordination v0.1 is now frozen together with all earlier Agent/Autonomy/Orchestration/Decision/Reasoning/Planning and Foundation boundaries.

Canonical coordination freeze contract:

`CONTROLLED_AGENT_COORDINATION_V0_1_FREEZE.md`

Frozen coordination execution direction:

`exact live Coordination → exact participant ACTIVE governance → bounded Autonomy/attempt/deliberation → Planning → Reasoning → Decision → Orchestration Intent → final coordinated execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution`

No coordination-specific Authority, executor, scheduler, retry, fan-out, voting, quorum or consensus semantics may be added behind that freeze.

## Current active stage — Persistent Cognitive Storage v0.1

The selected next architecture stage is a core-only durable persistence foundation.

Canonical contract:

`PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`

Why this stage is next:

- Memory and Knowledge are currently process-local in-memory stores;
- generation ownership resets with process lifetime unless durable state exists;
- current learning completion/idempotency is not crash-durable;
- encrypted cognitive storage from the Security & Licensing contract needs a storage-neutral persistence boundary first;
- Android/Keystore/storage-engine choices should remain later adapters.

First implementation boundary:

`generic exact-generation persistent envelope/store → atomic durable commit → explicit reopen/recovery`

The first slice must not integrate Memory/Knowledge yet. It must first prove exact stale/ABA-safe ownership, monotonic generation restoration, deterministic reopen snapshots, explicit corrupt/incompatible recovery, failure atomicity, and privacy-safe rendering/observability.

Mandatory separation:

`Persistence != Encryption != License != Authority != Cognitive Permission`

## Resume procedure

1. verify current `main` SHA and merge/main CI;
2. read `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`;
3. inspect frozen Memory/Knowledge models/stores only as compatibility constraints, not redesign targets;
4. create the smallest persistence primitive slice on a feature branch;
5. add executable contracts before broad integration;
6. merge only after exact-head CI + architecture/privacy/readiness audit;
7. keep Android, SQLCipher/SQLite, Keystore and licensing out of the first core persistence slice.
