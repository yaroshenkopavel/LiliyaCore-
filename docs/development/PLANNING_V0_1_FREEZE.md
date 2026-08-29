# Planning Foundation v0.1 — Freeze Contract

Status: **FROZEN**

Verified through merged PRs #103, #104 and #105.

## Purpose

Planning Foundation v0.1 defines a structural proposal boundary for possible future actions. It records caller-declared planning goals and ordered descriptive steps without granting permission, selecting an executable action, or performing a side effect.

Mandatory invariant:

`Plan != Decision != Authority != Execution`

A stored plan is not evidence that an action is correct, approved, permitted, scheduled, or executed.

## Frozen model

`PlanningProposalId + PlanningOrigin + goal + ordered PlanningStep list + createdAt → exact PlanningGeneration ownership`

Core types:

- `PlanningProposalId` — nonblank structural identity;
- `PlanningStepId` — nonblank step identity;
- `PlanningOrigin` — caller-declared source ID plus optional structural source reference;
- `PlanningStep` — descriptive step only;
- `PlanningProposal` — caller-declared goal and ordered steps;
- `PlanningGeneration` — exact positive generation;
- `PlanningOwnership` — exact install/remove ownership handle.

## Frozen guarantees

### Structural integrity

- proposal IDs and step IDs are nonblank;
- goal and step descriptions are nonblank;
- a proposal contains at least one step;
- step IDs are unique inside one proposal;
- caller-provided step lists are defensively copied;
- proposal equality is value-based over ID, origin, goal, steps and createdAt.

### Exact ownership and concurrency

- duplicate proposal ID registration rejects without replacement;
- each successful registration receives an exact positive generation;
- removal is bound to the exact stored entry/generation;
- stale ownership cannot remove a later replacement;
- repeated removal fails closed;
- concurrent registration of the same proposal ID has exactly one winner;
- independent `PlanningComposition` instances remain isolated.

### Read semantics

- `find`, `inspect`, `contains`, `snapshot`, and `snapshotEntries` expose controlled read views;
- snapshots are deterministic by `createdAt`, then proposal ID;
- returned snapshot lists are detached views and are not mutable live store handles.

### Privacy and observability

- goal content is redacted from `PlanningProposal.toString()`;
- step description content is redacted from `PlanningStep.toString()`;
- lifecycle observability metadata contains structural IDs, provenance, generation, step count and timestamps only;
- goal and step description payloads are not included in lifecycle metadata;
- Planning lifecycle metadata does not claim Decision, approval, Authority, Capability, Execution, success-of-action, or learned-state semantics.

### Correlation

- installation creates a root `LogContext`;
- removal is recorded with a child context;
- child removal receives a fresh correlation ID whose `parentCorrelationId` equals the install correlation ID;
- no ThreadLocal or hidden global context propagation is introduced.

## Explicit exclusions

Planning Foundation v0.1 does **not**:

- choose which real-world action should be taken;
- produce an `ExecutionRequest`;
- authorize a capability or scope;
- create an Authority grant;
- call an executor;
- mutate Memory or Knowledge;
- approve learning or controlled-learning application;
- schedule/retry/background work;
- create Autonomy or Agent semantics;
- control Android/device/browser/shell capabilities;
- provide persistent/crash-durable plan storage;
- imply truth, confidence, trust, priority, feasibility, safety, or successful execution.

## Reopening rule

This baseline may be reopened only for a demonstrated correctness/security/privacy defect or a deliberately reviewed higher-layer integration need. Reopening requires focused executable contracts, exact-head Core CI GREEN, architecture/privacy/security audit, exact-head merge, and journal update.

## Next cognitive stage

The next cognitive architecture stage is **Reasoning Foundation v0.1**.

Reasoning must remain a deliberative/analysis boundary over explicit inputs and structural planning context. Mandatory invariant:

`Reasoning != Decision != Authority != Execution`

Reasoning v0.1 must not silently turn analysis into permission, action selection, device execution, autonomous control, or downstream cognitive mutation.

Autonomy and Agents remain deferred until Reasoning and later Decision/orchestration boundaries are separately implemented, audited, and frozen.
