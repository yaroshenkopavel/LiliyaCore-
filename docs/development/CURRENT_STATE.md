# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `0d3027d2e3bf0bbbf3af185662d06558a28dcf80`.

Recent migrated-repository milestones:

- PR #1 `Controlled Agent Coordination v0.1: Reasoning Bridge` → merge `249ae23947c3a707d6d03dfb31503d1d858cd873`, exact-head CI `33309793507` GREEN, merge/main CI `33310005179` GREEN;
- PR #3 `Controlled Agent Coordination v0.1: Decision Bridge` → merge `50878737b6b1bf7c7a29c4c55a01d17146465118`, exact-head CI `33310766579` GREEN, merge/main CI `33311105604` GREEN;
- PR #4 `Controlled Agent Coordination v0.1: Orchestration Intent Bridge` → merge `5f01871e20de1e53d6aaaee9c4543d9c8da12c09`, exact-head CI `33311350333` GREEN, merge/main CI `33311517776` GREEN;
- PR #6 `Controlled Agent Coordination v0.1: Execution Guard` → merge `8d572988630f6bf3e235d273e08c40966b802b61`, exact-head CI `33312194584` GREEN, merge/main CI `33312349829` GREEN;
- PR #7 `Controlled Agent Coordination v0.1: Readiness Gate` → merge `51c19d07710a0606cb619f9164e0bd6ab8f4414f`, exact-head CI `33312562461` GREEN, merge/main CI `33312678259` GREEN;
- PR #8 `Controlled Agent Coordination v0.1: Freeze Checkpoint` → merge `0d3027d2e3bf0bbbf3af185662d06558a28dcf80`, exact-head CI `33313002771` GREEN, merge/main CI `33313140393` GREEN.

## Frozen subsystem status

Frozen v0.1 boundaries include:

- Core Foundation;
- Capability & Authority;
- Execution;
- Memory;
- Knowledge;
- Identity / Self;
- Trust / Security;
- Personality;
- Reflection;
- Learning foundations;
- Planning;
- Reasoning;
- Decision;
- Orchestration Intent;
- Controlled Orchestration;
- Autonomy Foundation;
- Controlled Autonomy Deliberation;
- Agents Foundation;
- Controlled Agent Initiative;
- Controlled Agent Lifecycle;
- Agent Delegation Foundation;
- Controlled Agent Delegation;
- Agent Coordination Foundation;
- **Controlled Agent Coordination v0.1**.

Canonical controlled-coordination freeze contract: `CONTROLLED_AGENT_COORDINATION_V0_1_FREEZE.md`.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Frozen governed control paths

Direct Agent:

`Agent identity + exact ACTIVE lifecycle → bounded Autonomy initiative → Deliberation → Planning → Reasoning → Decision → Orchestration Intent → final Agent/Autonomy guards → fresh Authority → Execution`

Delegated Agent:

`exact Delegation → fresh parent/child ACTIVE preflight → compensated child Autonomy + exact binding → delegated attempt gate → frozen Autonomy cognitive chain → final delegated execution guard → frozen Agent execution guard → fresh Authority → Execution`

Controlled Coordination:

`exact live Coordination → exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated multi-participant initiative → transactional bounded attempts → exact coordination↔attempt binding → compensated exact deliberation → exact live deliberation preflight → ordinary Planning → ordinary Reasoning → ordinary Decision → ordinary Orchestration Intent → final coordinated execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution`

Hard invariants remain:

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Coordination Readiness != Work != Permission != Authority != Execution`

`Coordinated Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Permission != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

## Controlled Agent Coordination v0.1 frozen guarantees

- exact participant/generation provenance and fresh ACTIVE lifecycle validation;
- exact stale/ABA-safe work and attempt ownership;
- bounded attempts remain owned by frozen Agent/Autonomy gates;
- compensated multi-store and cognitive writes with post-write fresh revalidation;
- exact compensation owns only the exact generation created by the current operation;
- a newer replacement generation is preserved when stale ownership can no longer remove;
- coordinated Planning/Reasoning/Decision/Orchestration install ordinary frozen subsystem data only;
- final coordinated execution repeats fresh readiness plus exact Planning → Reasoning → Decision → Orchestration validation immediately before downstream delegation;
- stale coordination/governance/cognitive/orchestration state means zero downstream Authority/executor calls;
- the final guard delegates only to frozen `ControlledOrchestrationExecution`;
- structural provenance strings are evidence/consistency markers only, never credentials or permission;
- private cognitive content stays outside coordination operational observability;
- no coordination-specific Capability grant, Authority grant, executor, scheduler, retry loop, implicit fan-out, voting, quorum or consensus semantics.

## Current active architecture stage

The next stage is **Persistent Cognitive Storage v0.1**.

Rationale:

- current `MemoryStore` and `KnowledgeStore` use process-local `ConcurrentHashMap` state and process-local generation counters;
- current learning idempotency/completed outcomes are explicitly not crash-durable;
- Security & Licensing requires authenticated encrypted cognitive storage, but platform/device keys should not be invented before a storage-neutral core boundary exists;
- offline-first Memory/Knowledge requires deterministic durable recovery before Android/device integration.

Canonical design contract: `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`.

Selected dependency direction:

`frozen cognitive domain → canonical persistent envelope → atomic exact-generation durable store → explicit recovery → later encrypted/platform adapter`

Mandatory separation:

`Persistence != Encryption != License != Authority != Cognitive Permission`

### First implementation slice

Create a small core-only `persistence` foundation for a generic exact-generation durable record store. It must be storage-engine-neutral and must not yet retrofit Memory/Knowledge.

Required contracts:

- duplicate live ID rejects without replacement;
- stale ownership cannot remove a newer durable generation;
- generation state restores monotonically after reopen;
- deterministic detached snapshots survive reopen;
- backend commit failure never reports success;
- corrupt/incompatible persisted state is explicit, never silently treated as empty;
- payload/private bytes stay out of normal rendering/observability;
- no Android, Keystore, license, Authority, scheduler or cognitive-policy semantics.

Only after this primitive is independently GREEN/readiness-audited should Memory/Knowledge integration begin.

## Known cross-cutting debt

1. Structural provenance strings/source references are evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. In-memory exact ownership/idempotency guarantees are not crash-durable across process restart; Persistent Cognitive Storage v0.1 is now the selected architecture stage to address the underlying durable-state primitive without weakening frozen domain boundaries.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
