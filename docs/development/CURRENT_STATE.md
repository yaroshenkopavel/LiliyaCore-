# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `54b2896957212ff7564b35fad7e39ccbeb3a8e92`.

Recent migrated-repository milestones:

- PR #9 `Persistent Cognitive Storage v0.1: Architecture Contract` → merge `60601ec5e98362dc7df34b006b4d7eb903ad71c8`, exact-head CI `33313485724` GREEN, merge/main CI `33313692213` GREEN;
- PR #10 `Persistent Cognitive Storage v0.1: Durable Record Store` → merge `2d0744f09e92e11a9917f9615b06870b0e9d0969`, exact-head CI `33314024175` GREEN, merge/main CI `33314620220` GREEN;
- PR #11 `Persistent Cognitive Storage v0.1: Readiness Hardening` → corrected exact head `d8decb41ff59588c1ee9a8c06eb0689fb1982aa8`, merge `a6ed4893e0e792575d4f2b6246e0a48e72f851b2`, exact-head CI `33315017295` GREEN, merge/main CI `33315169997` GREEN;
- PR #12 `Persistent Cognitive Storage v0.1: Freeze Checkpoint` → merge `54b2896957212ff7564b35fad7e39ccbeb3a8e92`, exact-head CI `33315840062` GREEN, merge/main CI `33315974315` GREEN.

## Frozen subsystem status

Persistent Cognitive Storage v0.1 generic primitive is now fully **FROZEN**.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`

Frozen primitive:

`canonical persistent record → exact-generation ownership → backend revision CAS → durable commit acknowledgement → explicit reopen/recovery validation`

Frozen guarantees include exact stale/ABA-safe ownership, durable generation high-watermark, explicit recovery/failure outcomes, deterministic detached snapshots, privacy-safe payload handling, public storage-engine-neutral backend SPI and explicit backend sharing/isolation semantics.

Mandatory separation remains:

`Persistence != Encryption != License != Authority != Cognitive Permission`

Controlled Agent Coordination v0.1 and all earlier frozen Foundation/Memory/Knowledge/Planning/Reasoning/Decision/Orchestration/Autonomy/Agent boundaries remain unchanged.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Current active architecture stage

The active stage is **Memory Persistence Integration v0.1**.

Canonical architecture contract:

`MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`

Selected direction:

`frozen Memory domain → canonical Memory codec → exact persistent record store → explicit hydration/restoration → frozen Memory semantics`

The integration must preserve existing Memory IDs, exact generations, provenance, deterministic snapshots, stale/ABA-safe removal, composition isolation and privacy. Hydration must use a reviewed Memory-owned restoration boundary rather than arbitrary internal map injection.

First implementation slice remains core-only and storage-engine-neutral. It must not broaden into Knowledge/Learning persistence or select Android, SQLite/SQLCipher, Keystore, encryption, licensing, scheduler or cognitive-policy semantics.

Required first-slice gates:

- deterministic Memory codec round-trip;
- explicit malformed/incompatible decode failure without content leakage;
- exact generation restoration plus generation high-watermark restoration;
- durable commit before successful `remember` publication;
- failed durable commit leaves Memory locally absent;
- durable exact-generation remove before local removal;
- failed/conflicting durable remove keeps local Memory live;
- reopen restores exact generations and deterministic snapshots;
- corrupt/incompatible/open failure publishes no partial Memory composition;
- backend-instance isolation unless sharing is explicit.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Memory != Persistence != Encryption != License != Authority != Cognitive Permission`

Persisted cognitive state remains state, not permission. Fresh Authority remains mandatory only at real side-effect boundaries.

## Known cross-cutting debt

1. Structural provenance/source references remain evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. Memory/Knowledge and completed learning outcomes remain non-crash-durable until each reviewed domain integration is built on the frozen persistence primitive.
4. Authenticated encryption and platform key management remain future adapters after storage-neutral domain integration boundaries are proven.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
