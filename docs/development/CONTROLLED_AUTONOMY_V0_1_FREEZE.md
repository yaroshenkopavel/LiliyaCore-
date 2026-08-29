# Controlled Autonomy v0.1 — FREEZE

Freeze date: 2026-08-29

Verified code baseline before this documentation checkpoint: `f0745ff5b177bbc75c402b475f092b82ad6dbd64`.

## Purpose

Controlled Autonomy v0.1 connects a bounded explicit `AutonomyProposal` into the already frozen cognitive/control chain without allowing initiative to become implicit permission or direct execution.

Frozen direction:

`exact live AutonomyProposal → bounded exact attempt → AutonomyDeliberationRequest → fresh live deliberation preflight → Planning → Reasoning → Decision → OrchestrationIntent → final Autonomy execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`.

## Frozen guarantees

### Bounded initiative and cancellation

- each attempt is claimed against exact `AutonomyProposalId + AutonomyGeneration`;
- the proposal's finite `AutonomyBudget.maxAttempts` is enforced;
- stale proposal generations do not consume replacement budget;
- cancellation is exact-generation scoped;
- stale cancellation cannot cancel a replacement generation;
- no scheduler/background runner is introduced by the attempt gate.

### Deliberation ownership

- `AutonomyDeliberationRequest` preserves exact `(AutonomyProposalId, AutonomyGeneration, attemptNumber)` provenance;
- deliberation requests have their own exact positive generation ownership;
- duplicate request IDs reject without replacement;
- removal is one-shot and stale/ABA-safe;
- same-ID compositions are isolated;
- snapshots are deterministic detached views;
- private deliberation objective remains redacted from lifecycle observability.

### Fresh live preflight

Every downstream deliberation attempt revalidates:

- exact deliberation request ID+generation;
- exact Autonomy proposal ID+generation;
- that the referenced attempt was actually claimed;
- that the attempt is still within its exact live generation state;
- that the Autonomy deliberation has not been cancelled.

Old readiness evidence is data only and is never durable permission.

### Controlled cognitive chain

Planning bridge:

- performs fresh deliberation preflight immediately before Planning install;
- caller cannot forge Planning origin;
- trusted Planning origin is constructed from exact Autonomy request/generation/attempt provenance;
- stale/cancelled deliberation produces zero Planning writes.

Reasoning bridge:

- performs fresh deliberation preflight;
- requires exact live Planning ID+generation;
- verifies Planning belongs to the same exact Autonomy request/attempt chain;
- constructs trusted Reasoning origin from exact Autonomy+Planning provenance;
- stale/unrelated/cancelled provenance produces zero Reasoning writes.

Decision bridge:

- performs fresh deliberation preflight;
- requires exact live Planning and Reasoning generations;
- verifies both belong to the same exact Autonomy chain;
- Decision structural inputs are exact Planning/Reasoning references;
- options, selected outcome and rationale remain caller-declared;
- Decision remains a recorded choice, not permission;
- stale/unrelated/cancelled provenance produces zero Decision writes.

Orchestration bridge:

- performs fresh deliberation preflight;
- requires exact live Planning, Reasoning and Decision generations;
- verifies the Decision structural inputs match this exact Autonomy chain;
- creates only a non-executing `OrchestrationIntent` from exact Decision generation + selected option;
- stale/cancelled/mismatched provenance produces zero Orchestration writes.

### Final side-effect guard

A previously created `OrchestrationIntent` is not sufficient to execute autonomous work.

Immediately before delegating to frozen Controlled Orchestration, `ControlledAutonomyExecution` revalidates:

- fresh deliberation request/generation;
- fresh Autonomy generation and claimed attempt;
- current cancellation state;
- exact Planning generation and trusted Autonomy origin;
- exact Reasoning generation and trusted Planning origin;
- exact Decision generation and exact Planning+Reasoning structural inputs;
- exact Orchestration generation and selected Decision option provenance.

This final guard runs before the first downstream Authority call.

Therefore:

- cancellation after `OrchestrationIntent` creation still causes zero executor calls;
- late cancellation causes zero new downstream Authority decisions;
- stale Autonomy replacement causes zero executor calls;
- stale Orchestration generation causes zero executor calls;
- denied Authority causes zero executor calls;
- successful exact live path reaches the executor exactly once.

After the Autonomy guard succeeds, frozen Controlled Orchestration still independently performs its own live Orchestration/Decision preflight, trusted action→capability mapping, fresh Authority, frozen Execution validation and a second fresh Authority immediately before executor.

No Autonomy evidence is durable permission.

## Privacy and observability

- Autonomy objective and trigger are private;
- deliberation objective is private;
- Planning goal/step descriptions are private;
- Reasoning premise/analysis/conclusion are private;
- Decision option descriptions/rationale are private;
- Orchestration description is private;
- full-path observability contains structural identifiers/generations/counts/policy data only;
- data-only Autonomy/Deliberation artifacts expose no Authority/Execution/scheduler/Agent methods;
- evidence rendering contains structural identity only.

## Explicit non-features

Controlled Autonomy v0.1 does **not** introduce:

- a hidden or recurring scheduler;
- background autonomous loops;
- self-spawning work;
- recursive goal generation;
- Agents or multi-agent behavior;
- direct Autonomy→Authority calls;
- direct Autonomy→Execution calls;
- durable permission from old evidence;
- bypass of frozen Decision, Orchestration, Authority or Execution boundaries.

## Verified implementation history

- PR #128 — exact attempt gate; Core CI #857 GREEN; merge `b01dc502886550c70fcf252de69bf22d900f0172`.
- PR #129 — structural deliberation request; Core CI #863 GREEN; merge `0cdcc8e8d6bc0a0489dbe4d0d1648c48d99ecf85`.
- PR #130 — deliberation composition ownership; Core CI #868 GREEN; merge `be460ef75f9035471e99884688f8b7e64bfea2a1`.
- PR #131 — exact live deliberation preflight; Core CI #874 GREEN; merge `019afa977dde7ff66649da457807b15f1424ba35`.
- PR #132 — controlled Planning bridge; Core CI #879 GREEN; merge `0ed368075d054d7cb138f11ec9b3186f2e1bd2f9`.
- PR #133 — controlled Reasoning bridge; Core CI #884 GREEN; merge `e3e54777b059936e27d650fbddbfe39f02d0215b`.
- PR #134 — controlled Decision bridge; Core CI #889 GREEN; merge `276e79ef75f796c443a54c6353fa86370aaf685b`.
- PR #135 — controlled Orchestration bridge; Core CI #894 GREEN; merge `e9393b05ab8c5462dda8ea7d64de945288dc8951`.
- PR #136 — final Autonomy execution guard; Core CI #899 GREEN; merge `5e04635681f59678a0d0b7fe3cea5b9ddb3f8ce8`.
- PR #137 — final readiness contracts; Core CI #903 GREEN; merge `f0745ff5b177bbc75c402b475f092b82ad6dbd64`.

## Reopen rule

Reopen Controlled Autonomy v0.1 only for a demonstrated correctness, security, privacy, lifecycle or provenance defect, with a focused failing contract first and the normal exact-head CI/audit workflow.

## Next architecture stage

With Autonomy lifecycle, bounded attempts, cancellation, deliberation and side-effect governance now explicit and frozen, the next architecture stage may begin **Agents Foundation v0.1**.

Agents must initially remain structural and non-executing. An Agent must not become a new Authority, executor, scheduler bypass or recursive self-spawning primitive. Any future agent-initiated work must flow through the frozen Autonomy → Deliberation → Decision → Orchestration → Authority → Execution chain.
