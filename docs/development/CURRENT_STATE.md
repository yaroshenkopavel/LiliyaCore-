# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `c7a7866c199d42713c7047289db1e0f68559fcae`.

Recent milestones:

- PR #12 `Persistent Cognitive Storage v0.1: Freeze Checkpoint` → merge `54b2896957212ff7564b35fad7e39ccbeb3a8e92`, exact-head CI `33315840062` GREEN, merge/main CI `33315974315` GREEN;
- PR #13 `Memory Persistence Integration v0.1: Architecture Contract` → merge `d6acaacecd8419a94530431166fe50caea42ef78`, exact-head CI `33316237238` GREEN, merge/main CI `33316354287` GREEN;
- PR #14 `Memory Persistence Integration v0.1: Codec and Restoration Boundary` → merge `89d13fa43a0abc090075b93b7a558b48ce54859e`, exact-head CI `33316585365` GREEN, merge/main CI `33316735201` GREEN;
- PR #15 `Memory Persistence Integration v0.1: Durable Remember and Remove` → merge `ebd6f804d6b3d389c468c277559dfa71de105adb`, exact-head CI `33317081729` GREEN, merge/main CI `33317242966` GREEN;
- PR #16 `Memory Persistence Integration v0.1: Readiness Hardening` → merge `16a15c739cc96aaddc026aba3252750650432e73`, exact-head CI `33317555845` GREEN, merge/main CI `33317696880` GREEN;
- PR #17 `Memory Persistence Integration v0.1: Freeze Checkpoint` → merge `c7a7866c199d42713c7047289db1e0f68559fcae`, exact-head CI `33317960415` GREEN, merge/main CI `33318203580` GREEN.

## Frozen subsystem status

Persistent Cognitive Storage v0.1 generic primitive is fully **FROZEN**.

Memory Persistence Integration v0.1 is now fully **FROZEN**.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

Frozen Memory persistence direction:

`frozen Memory domain → canonical Memory codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Memory semantics`

Persisted write ordering:

`Memory encode → durable commit → exact committed Memory install → success`

Persisted removal ordering:

`exact persisted ownership → durable exact-generation remove → exact local remove → success`

Frozen guarantees include exact Memory ID/generation restoration, durable high-watermark restoration, stale/ABA-safe ownership, fail-closed atomic reopen, deterministic snapshots, same-composition mutation serialization, explicit shared-backend CAS conflict semantics and privacy-safe failure rendering.

Mandatory separation remains:

`Memory != Persistence != Encryption != License != Authority != Cognitive Permission`

Controlled Agent Coordination v0.1 and all earlier frozen Foundation/Memory/Knowledge/Planning/Reasoning/Decision/Orchestration/Autonomy/Agent boundaries remain unchanged.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Current active architecture stage

The active stage is **Knowledge Persistence Integration v0.1**.

Canonical architecture contract:

`KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`

Current frozen Knowledge is process-local. `KnowledgeStore` already preserves exact-generation registration/removal, duplicate live-ID rejection, stale/ABA-safe removal and deterministic snapshots. `KnowledgeOrigin.Memory` carries exact `MemoryRecordId + MemoryGeneration`; `KnowledgeOrigin.Declared` carries declared source provenance.

Selected direction:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

The integration must preserve exact Knowledge item IDs, generations, origins, caller-supplied timestamps, deterministic snapshots, stale/ABA-safe removal, privacy and composition/backend isolation.

A persisted `KnowledgeOrigin.Memory` remains structural provenance/consistency evidence only. It does not require a live Memory lookup during Knowledge create/hydration and does not grant permission, capability or Authority.

First production slice must remain storage-engine-neutral and must start with canonical Knowledge codec plus a reviewed Knowledge-owned exact-generation restoration boundary before durable create/remove wiring.

Do not broaden this stage into Learning persistence, Android, SQLite/SQLCipher, Keystore, encryption implementation, licensing, scheduler, automatic retry, semantic deduplication, trust/confidence scoring or multi-writer reconciliation.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Persistence != Encryption != License != Authority != Cognitive Permission`

`Knowledge != Persistence != Encryption != License != Authority != Cognitive Permission`

Persisted cognitive state remains state, not permission. Fresh Authority remains mandatory only at real side-effect boundaries.

## Known cross-cutting debt

1. Structural provenance/source references remain evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. Knowledge and completed learning outcomes remain non-crash-durable until their own reviewed persistence integrations are built.
4. Authenticated encryption and platform key management remain future adapters after storage-neutral domain integration boundaries are proven.
5. Shared-backend independently opened persisted compositions use explicit optimistic CAS conflict rather than hidden refresh/retry/reconciliation.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
