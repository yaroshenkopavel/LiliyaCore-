# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `45e9ff178207a0249dff11c20665b5b02ff8de78`.

Recent persistence milestones:

- PR #12 `Persistent Cognitive Storage v0.1: Freeze Checkpoint` → merge `54b2896957212ff7564b35fad7e39ccbeb3a8e92`, exact-head CI `33315840062` GREEN, merge/main CI `33315974315` GREEN;
- PR #17 `Memory Persistence Integration v0.1: Freeze Checkpoint` → merge `c7a7866c199d42713c7047289db1e0f68559fcae`, exact-head CI `33317960415` GREEN, merge/main CI `33318203580` GREEN;
- PR #18 `Knowledge Persistence Integration v0.1: Architecture Contract` → merge `d3a4b1264954bcad89415b2a7192e0f3aa62e928`, exact-head CI `33318537838` GREEN, merge/main CI `33318684331` GREEN;
- PR #19 `Knowledge Persistence Integration v0.1: Codec and Restoration Boundary` → merge `ff270676ab9d78a142340f4e438de3ca01202379`, exact-head CI `33318917579` GREEN, merge/main CI `33319314032` GREEN;
- PR #20 `Knowledge Persistence Integration v0.1: Durable Create Remove and Reopen` → merge `aeb3652d9e0488f95444e49551519dc81eadb665`, exact-head CI `33319625540` GREEN, merge/main CI `33319765350` GREEN;
- PR #21 `Knowledge Persistence Integration v0.1: Readiness Hardening` → merge `450e65b2c0d3a53a4e4389532c15653accc27a64`, exact-head CI `33320271163` GREEN, merge/main CI `33320431935` GREEN;
- PR #22 `Knowledge Persistence Integration v0.1: Freeze Checkpoint` → merge `45e9ff178207a0249dff11c20665b5b02ff8de78`, exact-head CI `33320651334` GREEN, merge/main CI `33320803828` GREEN.

## Frozen subsystem status

Persistent Cognitive Storage v0.1 generic primitive is fully **FROZEN**.

Memory Persistence Integration v0.1 is fully **FROZEN**.

Knowledge Persistence Integration v0.1 is fully **FROZEN**.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

Frozen Knowledge persistence direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

Persisted create ordering:

`Knowledge encode → durable commit → exact committed Knowledge install → success`

Persisted removal ordering:

`exact persisted ownership → durable exact-generation remove → exact local remove → success`

Verified guarantees include exact Knowledge ID/generation restoration, persistent generation high-watermark restoration, exact origin fidelity for both Memory and Declared origins, caller-supplied timestamp/content fidelity, stale/ABA-safe ownership, deterministic snapshots, fail-closed atomic reopen, same-composition mutation serialization, explicit shared-backend CAS conflict semantics and privacy-safe failure rendering.

`KnowledgeOrigin.Memory` remains structural provenance only and does not require a live Memory lookup during create or hydration. Neither Knowledge origin form grants trust, permission, capability or Authority.

Mandatory separation remains:

`Knowledge != Persistence != Encryption != License != Authority != Cognitive Permission`

Controlled Agent Coordination v0.1 and all earlier frozen Foundation/Memory/Knowledge/Planning/Reasoning/Decision/Orchestration/Autonomy/Agent boundaries remain unchanged.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Current active architecture stage

The active stage is **Learning Persistence Integration v0.1**.

Canonical architecture contract:

`LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`

Current frozen Learning application mutation state is process-local. The store preserves exact mutation ID/generation ownership, live idempotency-key reservation, single active claim, controlled exact-receipt completion, completed outcome lookup by mutation ID and idempotency key, deterministic live snapshots, and stale/ABA-safe ownership.

Selected direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Critical architecture rule: durable Learning state does **not** imply exactly-once downstream Memory/Knowledge mutation. The existing controlled applier mutates downstream state before Learning completion is recorded, so a crash/failure window remains between those boundaries. v0.1 must preserve that limitation explicitly and must not add hidden replay/retry/reconciliation.

A reopened prepared mutation is not pre-authorized merely because its principal/application/decision/policy references were persisted. Fresh controlled authorization remains required before any downstream application.

First production slice must remain storage-engine-neutral and start with canonical prepared/completed codecs plus a reviewed Learning-owned restoration boundary before durable prepare/remove/complete wiring.

Do not broaden this stage into Android, SQLite/SQLCipher, Keystore, encryption implementation, licensing, scheduler, persisted claim leases, distributed coordination, exactly-once claims, automatic replay, cloud sync or multi-writer reconciliation.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Persistence != Encryption != License != Authority != Cognitive Permission`

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

Persisted cognitive/control state remains state, not permission. Fresh Authority remains mandatory at real side-effect boundaries.

## Known cross-cutting debt

1. Structural provenance/source references remain evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. Completed Learning outcomes and live prepared Learning mutations remain non-crash-durable until Learning Persistence Integration v0.1 is implemented and frozen.
4. Authenticated encryption and platform key management remain future adapters after storage-neutral domain integration boundaries are proven.
5. Shared-backend independently opened persisted compositions use explicit optimistic CAS conflict rather than hidden refresh/retry/reconciliation.
6. Learning application still has a downstream-mutation → Learning-completion crash window; no exactly-once cross-domain guarantee exists.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
