# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `a6ed4893e0e792575d4f2b6246e0a48e72f851b2`.

Recent migrated-repository milestones:

- PR #8 `Controlled Agent Coordination v0.1: Freeze Checkpoint` → merge `0d3027d2e3bf0bbbf3af185662d06558a28dcf80`, exact-head CI `33313002771` GREEN, merge/main CI `33313140393` GREEN;
- PR #9 `Persistent Cognitive Storage v0.1: Architecture Contract` → merge `60601ec5e98362dc7df34b006b4d7eb903ad71c8`, exact-head CI `33313485724` GREEN, merge/main CI `33313692213` GREEN;
- PR #10 `Persistent Cognitive Storage v0.1: Durable Record Store` → merge `2d0744f09e92e11a9917f9615b06870b0e9d0969`, exact-head CI `33314024175` GREEN, merge/main CI `33314620220` GREEN;
- PR #11 `Persistent Cognitive Storage v0.1: Readiness Hardening` → corrected exact head `d8decb41ff59588c1ee9a8c06eb0689fb1982aa8`, merge `a6ed4893e0e792575d4f2b6246e0a48e72f851b2`, exact-head CI `33315017295` GREEN, merge/main CI `33315169997` GREEN.

## Frozen subsystem status

Frozen v0.1 boundaries include Core Foundation, Capability & Authority, Execution, Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy, Agents, Delegation and Controlled Agent Coordination.

Controlled Agent Coordination canonical freeze: `CONTROLLED_AGENT_COORDINATION_V0_1_FREEZE.md`.

Persistent Cognitive Storage v0.1 generic primitive has now completed implementation + readiness hardening and is **FROZEN pending the persistence freeze documentation checkpoint merge**.

Canonical persistence architecture contract: `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`.

Canonical persistence primitive freeze: `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Persistent Cognitive Storage v0.1 frozen primitive

Frozen direction:

`canonical persistent record → exact-generation ownership → backend revision CAS → durable commit acknowledgement → explicit reopen/recovery validation`

Frozen guarantees include:

- exact stale/ABA-safe durable ownership;
- store-global generation high-watermark restored after reopen;
- duplicate live ID rejection;
- backend expected-revision CAS;
- no local success before durable `Committed` acknowledgement;
- explicit `Missing / Corrupt / Incompatible / Failed` recovery outcomes;
- fail-closed recovery validation for key/record identity mismatch, impossible generation state and duplicate live generations;
- monotonic backend revision acknowledgement;
- deterministic detached snapshots;
- defensive payload copies and redacted persistence rendering/observability;
- explicit backend-instance isolation and explicit shared-backend semantics;
- public storage-engine-neutral backend SPI.

Mandatory separation remains:

`Persistence != Encryption != License != Authority != Cognitive Permission`

The primitive does not yet persist frozen Memory/Knowledge stores and does not add Android, SQLite/SQLCipher, Keystore, licensing, scheduler or cognitive-policy semantics.

## Current active architecture stage

After the persistence primitive freeze checkpoint, the next controlled stage is **Memory Persistence Integration v0.1**.

Selected direction:

`frozen Memory domain → reviewed persistence codec/adapter → exact persistent record store → explicit hydration/restoration → frozen Memory semantics`

This integration must preserve existing Memory IDs, exact generations, provenance, deterministic snapshots, stale/ABA-safe removal, composition isolation and privacy. Hydration must use a reviewed domain restoration boundary rather than arbitrary internal map injection.

The first Memory persistence integration slice must remain core-only and storage-engine-neutral. It must not select Android, SQLite/SQLCipher or Keystore and must not broaden into Knowledge/Learning persistence until Memory integration is independently GREEN/readiness-audited.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Persistence != Encryption != License != Authority != Cognitive Permission`

Planning/Reasoning/Decision/Orchestration data remains descriptive/governed state, never permission by provenance alone. Fresh Authority remains mandatory at real side-effect boundaries.

## Known cross-cutting debt

1. Structural provenance/source references remain evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. Memory/Knowledge and completed learning outcomes are still not process-crash durable until reviewed domain integration is built on the frozen persistence primitive.
4. Authenticated encryption and platform key management remain future adapters after storage-neutral domain integration boundaries are proven.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
