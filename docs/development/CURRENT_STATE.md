# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `16a15c739cc96aaddc026aba3252750650432e73`.

Recent milestones:

- PR #12 `Persistent Cognitive Storage v0.1: Freeze Checkpoint` → merge `54b2896957212ff7564b35fad7e39ccbeb3a8e92`, exact-head CI `33315840062` GREEN, merge/main CI `33315974315` GREEN;
- PR #13 `Memory Persistence Integration v0.1: Architecture Contract` → merge `d6acaacecd8419a94530431166fe50caea42ef78`, exact-head CI `33316237238` GREEN, merge/main CI `33316354287` GREEN;
- PR #14 `Memory Persistence Integration v0.1: Codec and Restoration Boundary` → merge `89d13fa43a0abc090075b93b7a558b48ce54859e`, exact-head CI `33316585365` GREEN, merge/main CI `33316735201` GREEN;
- PR #15 `Memory Persistence Integration v0.1: Durable Remember and Remove` → merge `ebd6f804d6b3d389c468c277559dfa71de105adb`, exact-head CI `33317081729` GREEN, merge/main CI `33317242966` GREEN;
- PR #16 `Memory Persistence Integration v0.1: Readiness Hardening` → merge `16a15c739cc96aaddc026aba3252750650432e73`, exact-head CI `33317555845` GREEN, merge/main CI `33317696880` GREEN.

## Frozen subsystem status

Persistent Cognitive Storage v0.1 generic primitive is fully **FROZEN**.

Memory Persistence Integration v0.1 has completed implementation and readiness hardening and is **FROZEN pending the Memory persistence freeze documentation checkpoint merge**.

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

Frozen guarantees include exact Memory ID/generation restoration, durable high-watermark restoration, stale/ABA-safe ownership, fail-closed atomic reopen, deterministic snapshots, same-composition mutation serialization, explicit shared-backend CAS conflict semantics, and privacy-safe failure rendering.

Mandatory separation remains:

`Memory != Persistence != Encryption != License != Authority != Cognitive Permission`

Controlled Agent Coordination v0.1 and all earlier frozen Foundation/Memory/Knowledge/Planning/Reasoning/Decision/Orchestration/Autonomy/Agent boundaries remain unchanged.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Current active architecture stage

After the Memory persistence freeze checkpoint, the next controlled stage is **Knowledge Persistence Integration v0.1**.

Current Knowledge remains process-local. `KnowledgeStore` uses exact-generation registration/removal, duplicate live-ID rejection, stale/ABA-safe removal and deterministic snapshots. `KnowledgeOrigin.Memory` carries exact `MemoryRecordId + MemoryGeneration`; `KnowledgeOrigin.Declared` carries declared source provenance.

Selected direction for the next architecture contract:

`frozen Knowledge domain → canonical Knowledge codec → exact persistent record store → reviewed exact-generation hydration/restoration → frozen Knowledge semantics`

The Knowledge integration must preserve exact Knowledge IDs/generations/origin, deterministic snapshots, privacy and composition isolation. A persisted Memory origin remains provenance/consistency evidence, not permission or Authority.

Do not broaden the first Knowledge slice into Learning persistence, Android, SQLite/SQLCipher, Keystore, encryption, licensing, scheduler, automatic retry or multi-writer reconciliation.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Persistence != Encryption != License != Authority != Cognitive Permission`

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
