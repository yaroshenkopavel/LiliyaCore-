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
- logging and diagnostics remain Foundation infrastructure and must not be bypassed by direct console output;
- persistence, encryption, licensing, Authority and cognitive permission remain separate;
- frozen baselines are not casually redesigned.

## Frozen persistence baselines

Persistent Cognitive Storage v0.1 is fully frozen.

Memory Persistence Integration v0.1 is fully frozen.

Knowledge Persistence Integration v0.1 is fully frozen.

Learning Persistence Integration v0.1 implementation is frozen pending the documentation-checkpoint merge.

Current verified code baseline before the documentation checkpoint:

`b04bbd6020ff9c9807e7db4f378d969534cee362`

Latest Learning readiness exact-head Core CI: `33323246383` GREEN.

Latest Learning readiness merge/main Core CI: `33323408553` GREEN.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

## Learning Persistence Integration v0.1 frozen boundary

Direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Durable prepare:

`validate plan → encode prepared → durable commit → exact committed local install → Prepared`

Durable removal:

`validate exact unclaimed ownership → durable exact-generation remove → exact local remove → success`

Durable completion:

`validate exact active claim + exact receipt → one durable exact prepared→completed transition → exact local completion/index publication → success`

The integration preserves exact mutation IDs/generations, persistent high-watermark, full Memory/Knowledge payload fidelity, exact idempotency/completed indexes, deterministic live ordering, stale/ABA-safe ownership, fail-closed reopen, zero claim resurrection, active-claim removal blocking, same-composition serialization and explicit shared-backend CAS conflict semantics.

The generic persistence layer contains one narrow internal exact transition primitive used by Learning completion. It performs one backend CAS revision and preserves generation/high-watermark. It is not a scheduler, retry engine, distributed transaction system or Authority mechanism.

## Logging and diagnostics boundary

Persistence/Learning operational observability may expose approved structural fields only: IDs, generations, target, idempotency key, schema/version, timestamps and structural payload/downstream IDs.

Private Memory/Knowledge content, raw persistent bytes and backend exception messages must not appear in normal logs, diagnostics, `toString` or public integration failure rendering.

Logging and diagnostics stay within Foundation observability. Do not add `println`, `System.out`, direct payload dumps or alternative hidden logging paths.

## Authority and claim boundary

Mandatory separation:

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

A reopened prepared mutation is not pre-authorized. Persisted principal/application/decision/policy references remain structural historical state; fresh controlled authorization is required before downstream Memory/Knowledge mutation.

Claim tokens are process-local concurrency ownership only. They are never persisted or resurrected across reopen.

A completed Learning receipt is historical evidence, not a credential, capability, permission or Authority receipt.

## Critical retained crash-window limitation

Learning persistence does not create a transaction spanning Learning plus Memory/Knowledge.

The controlled application path still performs:

`downstream Memory/Knowledge mutation → durable Learning completion`

A crash/failure may occur between those boundaries. Therefore this freeze does not provide exactly-once downstream mutation, automatic replay, implicit retry, compensation or reconciliation.

Future cross-domain crash atomicity/exactly-once behavior requires a separate architecture contract and executable proof.

## Current active stage — Learning Persistence Integration v0.1 freeze checkpoint

The implementation slices are complete and GREEN. The only active work is the documentation/freeze checkpoint.

Do not add more Learning Persistence v0.1 production semantics unless the checkpoint audit or CI exposes a correctness/privacy/logging-diagnostics defect.

After the freeze documentation PR reaches exact-head Core CI GREEN, merge it with exact-head protection and verify merge/main Core CI. Only then mark Learning Persistence Integration v0.1 fully frozen.

Do not invent the next subsystem from chat history. Select the next controlled stage from current repository architecture/roadmap after the freeze checkpoint is verified on `main`.

## Explicit non-goals retained by the freeze

Keep outside Learning Persistence Integration v0.1:

- exactly-once downstream mutation guarantees;
- cross-domain Learning + Memory/Knowledge transaction;
- automatic crash replay;
- automatic retry/refresh/reconciliation;
- automatic downstream compensation;
- scheduler/background workers;
- persisted claim leases;
- distributed locks/consensus;
- Android/device storage;
- SQLite/SQLCipher;
- filesystem layout;
- Keystore/StrongBox;
- encryption implementation;
- licensing behavior;
- cloud sync;
- multi-writer merge/conflict resolution.

## Resume procedure

1. verify the documentation freeze PR exact head and Core CI;
2. read `LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md` and the architecture contract;
3. confirm the PR changes only freeze/state/handoff documentation;
4. merge only after exact-head Core CI GREEN with expected-head protection;
5. verify merge/main Core CI GREEN;
6. mark Learning Persistence Integration v0.1 fully frozen in project state;
7. audit current repository roadmap/architecture before selecting the next stage.
