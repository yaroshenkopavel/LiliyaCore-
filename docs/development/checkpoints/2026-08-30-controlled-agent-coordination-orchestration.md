# Controlled Agent Coordination — Orchestration Intent Checkpoint

Date: 2026-08-30

## Verified baseline

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Current verified code main: `5f01871e20de1e53d6aaaee9c4543d9c8da12c09`.

Recent migrated-repository sequence:

- PR #1 Reasoning Bridge — exact head `8fcd00e325d27f4612a4280845838d0812cdf256`, exact-head Core CI `33309793507` GREEN, merge `249ae23947c3a707d6d03dfb31503d1d858cd873`, merge/main CI `33310005179` GREEN;
- PR #2 Reasoning progress journal — merge `2c80a5750a8472cd6bc39481201ae479cdc9cc7c`, merge/main CI `33310459043` GREEN;
- PR #3 Decision Bridge — exact head `66529669acea25fb5a6ad247a0eb47c4d39d1a19`, exact-head Core CI `33310766579` GREEN, merge `50878737b6b1bf7c7a29c4c55a01d17146465118`, merge/main CI `33311105604` GREEN;
- PR #4 Orchestration Intent Bridge — exact head `8472d6b03502abb7191334b096578900eb5e5c1a`, exact-head Core CI `33311350333` GREEN, merge `5f01871e20de1e53d6aaaee9c4543d9c8da12c09`, merge/main CI `33311517776` GREEN.

## Controlled coordination path now implemented

`exact Coordination → fresh participant ACTIVE readiness → exact coordination↔Autonomy work binding → compensated initiative → transactional bounded attempts → exact coordination↔attempt binding → compensated deliberation → live deliberation preflight → frozen Planning → frozen Reasoning → frozen Decision → frozen Orchestration Intent`

The cognitive-chain bridges remain data/control-boundary bridges. They do not create permission or Authority.

## Decision Bridge guarantees

The coordinated Decision bridge:

- requires fresh coordinated deliberation readiness;
- requires exact live Planning and Reasoning generations;
- requires Planning and Reasoning provenance to match the same coordinated deliberation chain;
- installs through ordinary frozen `DecisionComposition`;
- records exact Planning and Reasoning input references in the Decision;
- revalidates readiness, Planning and Reasoning after the write;
- compensates only the exact newly-created Decision generation on a post-write race;
- preserves a newer replacement Decision generation when an old ownership handle is stale;
- returns explicit `Failed` and emits CRITICAL observability if the same created Decision generation remains live but compensation cannot remove it;
- keeps option descriptions and rationale outside bridge observability;
- exposes no Orchestration, permission, Authority, scheduler or Execution semantics.

## Orchestration Intent Bridge guarantees

The coordinated Orchestration bridge:

- requires fresh coordinated deliberation readiness;
- requires exact live Planning, Reasoning and Decision generations;
- checks the exact coordinated Planning → Reasoning → Decision chain before writing;
- installs only ordinary frozen `OrchestrationIntent` data;
- captures exact Decision ID, Decision generation and selected option;
- revalidates coordinated readiness plus Planning/Reasoning/Decision after the write;
- verifies the selected option still matches the Decision after the write;
- compensates only the exact newly-created Orchestration generation on stale governance/provenance;
- preserves a newer replacement Orchestration generation when old compensation ownership is stale;
- returns explicit `Failed` and emits CRITICAL observability if the same created generation remains live but cannot be compensated;
- keeps the private orchestration description outside bridge observability;
- performs no authorization, scheduler action or Execution.

## Security / architecture interpretation

Structural `sourceId/sourceReference` provenance is evidence/consistency metadata only. It is not a cryptographic credential, capability, permission or Authority grant.

The exact live checks make structural provenance useful for binding the chain, while the real permission boundary remains the existing frozen Authority/Execution architecture.

Mandatory invariant:

`Coordination != Orchestration Intent != Permission != Authority != Execution`

## Next slice

The next controlled slice is a final coordinated execution guard, not a new Authority implementation.

Target shape:

`exact live coordinated cognitive chain + exact Orchestration generation → fresh coordination/lifecycle/attempt/deliberation guard → existing frozen Agent/Autonomy/Controlled Orchestration execution path`

The guard must fail before the first downstream Authority call when coordination governance is stale and must therefore produce zero executor calls on stale coordination.

It must preserve all existing Agent/Autonomy late-cancellation checks and all existing fresh Authority checks. No coordination-specific permission shortcut is allowed.

After this final guard, the remaining work for Controlled Agent Coordination v0.1 should be readiness contracts, freeze documentation, final CI/audit and journal checkpoint.
