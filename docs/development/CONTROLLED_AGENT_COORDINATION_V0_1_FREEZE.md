# Controlled Agent Coordination v0.1 — Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline: `51c19d07710a0606cb619f9164e0bd6ab8f4414f`.

Verified migrated-repository slices:

- PR #1 — Reasoning Bridge, merge `249ae23947c3a707d6d03dfb31503d1d858cd873`, exact-head CI `33309793507` GREEN, merge/main CI `33310005179` GREEN;
- PR #3 — Decision Bridge, exact head `66529669acea25fb5a6ad247a0eb47c4d39d1a19`, merge `50878737b6b1bf7c7a29c4c55a01d17146465118`, exact-head CI `33310766579` GREEN, merge/main CI `33311105604` GREEN;
- PR #4 — Orchestration Intent Bridge, exact head `8472d6b03502abb7191334b096578900eb5e5c1a`, merge `5f01871e20de1e53d6aaaee9c4543d9c8da12c09`, exact-head CI `33311350333` GREEN, merge/main CI `33311517776` GREEN;
- PR #6 — Execution Guard, exact head `5acc64544014aa1ec2ad0e5f64cb8911788eef5f`, merge `8d572988630f6bf3e235d273e08c40966b802b61`, exact-head CI `33312194584` GREEN, merge/main CI `33312349829` GREEN;
- PR #7 — Readiness Gate, exact head `5fdc0ac989a9418e07aa4bfbf925cfa2de2c6845`, merge `51c19d07710a0606cb619f9164e0bd6ab8f4414f`, exact-head CI `33312562461` GREEN, merge/main CI `33312678259` GREEN.

Earlier controlled-coordination slices through Planning were implemented in the original repository and preserved by migration.

## Frozen governed path

`exact live Coordination → exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated multi-participant initiative → transactional bounded attempts → exact coordination↔attempt binding → compensated exact deliberation → exact live deliberation preflight → ordinary Planning → ordinary Reasoning → ordinary Decision → ordinary Orchestration Intent → final coordinated execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution`

## Mandatory invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Coordination Readiness != Work != Permission != Authority != Execution`

`Coordinated Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Permission != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

## Frozen guarantees

- exact coordination and exact participant Agent generations are revalidated at controlled readiness boundaries;
- participant lifecycle must be exact ACTIVE where coordinated readiness requires it;
- work and attempt bindings are exact-generation owned and stale/ABA-safe;
- bounded attempts remain owned by the existing Agent/Autonomy control gates;
- coordinated deliberation is derived from committed exact coordination-attempt provenance;
- Planning, Reasoning, Decision and Orchestration writes use ordinary frozen subsystem compositions rather than coordination-specific power models;
- every coordinated cognitive write uses fresh pre-write validation and fresh post-write revalidation;
- post-write governance/provenance changes compensate only the exact generation created by that bridge;
- failed stale-owner removal is not itself a fatal condition when a newer replacement generation is live; the replacement is preserved;
- compensation is an explicit `Failed` + CRITICAL condition only when the exact generation created by the operation remains live and cannot be removed;
- final execution revalidates exact coordinated readiness and the complete live Planning → Reasoning → Decision → Orchestration chain immediately before delegation;
- stale coordination, participant, attempt binding, deliberation or cognitive/orchestration generation fails closed before the first downstream Authority/executor path;
- the coordination execution guard delegates only to the existing frozen `ControlledOrchestrationExecution` boundary;
- fresh Authority and the frozen Execution boundary remain downstream owners of permission and side effects;
- no coordination-specific Capability grant, Authority grant, executor, scheduler, retry loop, voting, quorum, consensus or implicit fan-out semantics are introduced;
- private coordination purpose, deliberation objective, Planning goal/steps, Reasoning premise/analysis/conclusion, Decision option/rationale and Orchestration description remain outside coordination bridge observability;
- structural IDs, generations, counts and provenance references may be observable as consistency evidence.

## TOCTOU rule

A successful initial preflight is not enough for compound coordinated operations. Every write-capable bridge must revalidate fresh governance/provenance after the write and compensate its own exact generation when the state changed. The final execution guard similarly performs two coordinated readiness/chain validation passes before handing control to the frozen Authority-gated execution boundary.

## ABA rule

Exact ownership protects replacements. If an old ownership handle cannot remove because a newer generation replaced it, that newer generation must never be deleted by compensation. Only the exact generation created by the current operation belongs to that operation.

## Provenance rule

Source IDs and source-reference strings are structural consistency/evidence markers only. They are not cryptographic authenticity tokens, capabilities, permission receipts or Authority grants. Reproducing the same text cannot by itself authorize or execute anything.

## Explicit non-goals

Controlled Agent Coordination v0.1 does not provide distributed consensus, leader election, durable queues, background scheduling, automatic retries, network coordination, multi-process transactions, cryptographic provenance, or a new permission model.

## Freeze decision

With exact-head CI, merge/main CI, readiness contracts and final architecture/privacy/security audit complete, Controlled Agent Coordination v0.1 is frozen at baseline `51c19d07710a0606cb619f9164e0bd6ab8f4414f` once this documentation checkpoint is merged and its main CI is GREEN.

Future changes to this frozen boundary require a demonstrated correctness/security need, focused executable contracts, exact-head CI, readiness reasoning and a journal update.