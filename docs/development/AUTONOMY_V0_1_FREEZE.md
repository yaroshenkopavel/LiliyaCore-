# Autonomy Foundation v0.1 — Freeze Contract

Status: **FROZEN** after verified structural, composition-ownership, readiness and CI gates.

## Frozen boundary

`explicit autonomy provenance + caller-declared objective + trigger description + priority + finite attempt budget + createdAt → AutonomyProposal → exact AutonomyGeneration ownership`

Mandatory invariant:

`Autonomy != Decision != Authority != Execution`.

Autonomy v0.1 is structural initiative data only. It does not authorize, schedule, execute, mutate cognitive stores or instantiate Agents.

## Verified implementation milestones

- PR #125 `Autonomy v0.1: Structural Proposal Foundation` — exact head `8590823906af86c970fd5031d9b320ba5158fdb5`; Core CI #839 GREEN; merge/new main `7c620159050f4deef13ae7a034c09b10d56df96d`.
- PR #126 `Autonomy v0.1: Composition Ownership and Readiness` — first exact head `10bf196ebece714992979a428823613dab05c3bc` exposed a test-only parameter-name compile error in Core CI #846; final corrected exact head `cfcf4cbaefa9b04e5887bcb51ce49f0aff2aeaa5`; Core CI #850 GREEN; merge/new main `eabaf41b1cd5e180998ef3f1388ab6e73bdce88b`.

## Frozen guarantees

- `AutonomyProposalId` is explicit and nonblank;
- `AutonomyGeneration` is positive and exact;
- `AutonomyOrigin.Reflection` preserves exact `(ReflectionRecordId, ReflectionGeneration)` provenance as data only;
- `AutonomyOrigin.Declared` permits explicit external/goal-context provenance without pretending a Goal/Context store exists;
- no hidden provenance lookup occurs during registration/install;
- objective and trigger description are required but redacted from rendering and lifecycle observability;
- priority is structural data only and does not imply scheduling order or authority;
- `AutonomyBudget.maxAttempts` is finite positive data only and does not start retry/scheduler behavior;
- duplicate IDs reject without replacement;
- stale/ABA ownership cannot remove a replacement;
- repeated removal fails closed;
- same-ID concurrent registration has exactly one winner per store;
- independent compositions may own the same ID without interference;
- snapshots are deterministic detached list views;
- public composition exposes controlled exact ownership, not raw mutable store access;
- install uses a root `LogContext`; owned remove uses a child context with explicit parent correlation;
- lifecycle metadata contains no Decision, Authority, Capability, permission, Execution, scheduler, Agent, truth/confidence or trusted semantics;
- no Decision/Orchestration calls exist in v0.1;
- no Authority call or capability grant exists in v0.1;
- no `ExecutionRequest` or executor call exists in v0.1;
- no Memory/Knowledge mutation exists in v0.1;
- no scheduler, background runner, recursive self-spawning or Agent framework exists in v0.1.

## Explicit non-guarantees

Autonomy v0.1 does not yet provide:

- trigger evaluation;
- scheduling;
- retries or attempt accounting runtime;
- cancellation runtime;
- proposal selection;
- Decision creation;
- OrchestrationIntent creation;
- Authority acquisition;
- execution;
- Agents;
- persistence/crash recovery.

The presence of an `AutonomyProposal` is not permission, a Decision, a scheduled job or evidence that anything will happen.

## Required next governance layer

The next architecture stage may bridge exact live Autonomy proposals toward deliberation, but must preserve the frozen control chain.

Required direction:

`exact live AutonomyProposal → controlled deliberation request → Planning/Reasoning/Decision → Orchestration Intent → exact preflight → fresh Authority → Execution`

The first bridge slice must stop before side effects. It must not turn Autonomy priority/budget into implicit scheduler permission.

Required invariants:

- exact Autonomy ID+generation revalidation;
- explicit bounded attempt/cancellation ownership before any recurring runtime behavior;
- Autonomy cannot forge Decision or Authority;
- downstream Decision remains a separate cognitive outcome;
- downstream Orchestration remains separate from Authorization/Execution;
- any real side effect must still cross fresh Capability/Authority and frozen Execution;
- stale/removed Autonomy provenance must fail closed;
- privacy-safe structural observability only;
- Agents remain deferred until Autonomy lifecycle/governance is separately frozen.

## Reopening rule

Reopen this frozen baseline only for demonstrated correctness, security, privacy or architecture defects, with focused executable contracts, exact-head Core CI GREEN, final audit and journal update.
