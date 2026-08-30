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

Memory Persistence Integration v0.1 is fully frozen on checkpoint `c7a7866c199d42713c7047289db1e0f68559fcae`, exact-head CI `33317960415` GREEN, merge/main CI `33318203580` GREEN.

Knowledge Persistence Integration v0.1 is fully frozen on verified `main`:

`45e9ff178207a0249dff11c20665b5b02ff8de78`

Freeze checkpoint exact-head Core CI: `33320651334` GREEN.

Freeze checkpoint merge/main Core CI: `33320803828` GREEN.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

Frozen Knowledge persistence direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

The Knowledge integration preserves exact IDs/generations, persistent generation high-watermark, both origin forms, content/timestamps, deterministic snapshots, stale/ABA-safe removal, fail-closed reopen, explicit shared-backend CAS conflict behavior and privacy-safe failure rendering.

`KnowledgeOrigin.Memory` remains structural provenance only. Neither Knowledge origin form grants trust, permission, capability or Authority.

Mandatory separation:

`Knowledge != Persistence != Encryption != License != Authority != Cognitive Permission`

## Current active stage — Learning Persistence Integration v0.1

Canonical architecture contract:

`LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`

The current frozen Learning application mutation domain already preserves:

- exact `LearningApplicationMutationId` + positive generation ownership;
- exact idempotency-key reservation and completed-history lookup;
- duplicate live mutation/idempotency rejection;
- one active claim per exact live mutation;
- claim release distinct from completion;
- controlled internal completion using an exact application receipt;
- completed lookup by mutation ID and idempotency key;
- equal completed-plan replay returning `AlreadyCompleted(receipt)`;
- conflicting completed ID/key reuse rejection;
- deterministic live snapshots ordered by `createdAt`, then mutation ID;
- structural observability without private Memory/Knowledge payload content.

Selected persistence direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Mandatory separation:

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

A reopened prepared mutation is not pre-authorized. Persisted principal/application/decision/policy references remain recorded structural state; fresh controlled authorization is still required before downstream Memory/Knowledge mutation.

Critical crash-window rule: the existing Learning applier performs the downstream Memory/Knowledge mutation before Learning completion is recorded. Therefore persistence of Learning completion does not create an exactly-once cross-domain transaction. v0.1 must not hide that window with implicit replay, retry, compensation or reconciliation claims.

## First implementation boundary

The first production slice must be narrow:

`Learning prepared/completed canonical codecs + reviewed exact-generation/completed-index restoration boundary`

Do not wire durable prepare/remove/complete until codec/restoration contracts are GREEN.

The codec/restoration slice must cover both Learning payload targets, both completed downstream reference types, exact generations/high-watermark, exact idempotency/completed indexes, invalid live/completed overlap rejection, privacy-safe decode failure, and the rule that active claim tokens are never restored.

After that, split durable integration into reviewed slices for prepare/remove/complete/reopen, then readiness hardening, then freeze checkpoint.

## Explicit non-goals for v0.1

Keep these outside Learning Persistence Integration v0.1:

- exactly-once downstream mutation claims;
- cross-domain transactions spanning Learning and Memory/Knowledge;
- automatic crash replay;
- automatic compensation/reconciliation;
- scheduler/background workers;
- persisted claim leases;
- distributed locks/consensus;
- Android/device storage;
- SQLite/SQLCipher;
- Keystore/StrongBox;
- encryption implementation;
- licensing behavior;
- cloud sync;
- multi-writer merge.

## Resume procedure

1. verify current `main` SHA and latest merge/main CI;
2. read `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md` plus frozen persistence/Memory/Knowledge contracts;
3. inspect `LearningApplicationMutationModels`, `LearningApplicationMutationStore`, `LearningApplicationMutationComposition`, `LearningApplicationMutationApplier`, authorization boundaries, and executable Learning contracts;
4. implement canonical prepared/completed codecs and reviewed restoration boundary first;
5. prove exact restoration, idempotency/completed-index fidelity, no claim resurrection, malformed/privacy behavior;
6. only after codec/restoration GREEN wire durable prepare/remove/complete and reopen semantics;
7. preserve the downstream→Learning-completion crash-window limitation explicitly;
8. merge each architectural slice only after exact-head Core CI GREEN and readiness audit.
