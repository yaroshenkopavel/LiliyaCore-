# Reasoning Foundation v0.1 — Freeze Contract

Status: **FROZEN**

Verified through merged PRs #107, #108 and #109.

## Purpose

Reasoning Foundation v0.1 defines an explicit caller-declared deliberation/analysis boundary. It stores structural provenance, premises, analysis and conclusion without treating that conclusion as truth, confidence, trust, approval, permission, executable action or autonomous instruction.

Mandatory invariant:

`Reasoning != Decision != Authority != Execution`

A reasoning artifact is deliberative data only. It is not evidence that a conclusion is correct, approved, authorized, safe, scheduled or executed.

## Frozen model

`ReasoningArtifactId + ReasoningOrigin + ordered ReasoningPremise list + analysis + conclusion + createdAt → exact ReasoningGeneration ownership`

Core types:

- `ReasoningArtifactId` — nonblank structural identity;
- `ReasoningPremiseId` — nonblank premise identity;
- `ReasoningOrigin` — caller-declared source ID plus optional structural source reference;
- `ReasoningPremise` — caller-declared premise statement only;
- `ReasoningArtifact` — caller-declared premises, analysis and conclusion;
- `ReasoningGeneration` — exact positive generation;
- `ReasoningOwnership` — exact install/remove ownership handle.

## Frozen guarantees

### Structural integrity

- artifact and premise IDs are nonblank;
- premise statements, analysis and conclusion are nonblank;
- an artifact contains at least one premise;
- premise IDs are unique inside one artifact;
- caller-provided premise lists are defensively copied;
- artifact equality is value-based over ID, origin, premises, analysis, conclusion and createdAt.

### Exact ownership and concurrency

- duplicate artifact ID registration rejects without replacement;
- each successful registration receives an exact positive generation;
- removal is bound to the exact stored entry/generation;
- stale ownership cannot remove a later replacement;
- repeated removal fails closed;
- concurrent registration of the same artifact ID has exactly one winner;
- independent `ReasoningComposition` instances remain isolated;
- the same artifact ID may exist independently in separate compositions.

### Read semantics

- `find`, `inspect`, `contains`, `snapshot`, and `snapshotEntries` expose controlled read views;
- snapshots are deterministic by `createdAt`, then artifact ID;
- returned snapshot lists are detached views and are not mutable live store handles.

### Privacy and observability

- premise statements are redacted from `ReasoningPremise.toString()`;
- premise, analysis and conclusion payloads are redacted from `ReasoningArtifact.toString()`;
- lifecycle observability metadata contains structural IDs, provenance, generation, premise count and timestamps only;
- premise, analysis and conclusion payloads are not included in lifecycle metadata;
- lifecycle metadata does not claim Decision, approval, Authority, Capability, Execution, truth, confidence or trust semantics.

### Correlation

- installation creates a root `LogContext`;
- removal is recorded with a child context;
- child removal receives a fresh correlation ID whose `parentCorrelationId` equals the install correlation ID;
- no ThreadLocal or hidden global context propagation is introduced.

## Explicit exclusions

Reasoning Foundation v0.1 does **not**:

- create a general Decision;
- choose which real-world action should be taken;
- score truth, confidence, trust, certainty, safety or utility;
- produce an `ExecutionRequest`;
- authorize a capability or scope;
- create an Authority grant;
- call an executor;
- mutate Memory or Knowledge;
- approve learning or controlled-learning application;
- schedule/retry/background work;
- create Autonomy or Agent semantics;
- control Android/device/browser/shell capabilities;
- provide persistent/crash-durable reasoning storage;
- imply successful execution or real-world effect.

## Reopening rule

This baseline may be reopened only for a demonstrated correctness/security/privacy defect or a deliberately reviewed higher-layer integration need. Reopening requires focused executable contracts, exact-head Core CI GREEN, architecture/privacy/security audit, exact-head merge, and journal update.

## Next cognitive stage

The next cognitive architecture stage is **Decision Foundation v0.1**.

Decision must consume explicit structural inputs without silently becoming Authority or Execution. Mandatory invariant:

`Decision != Authority != Execution`

Decision v0.1 must remain a recorded choice/outcome boundary only. It must not grant permission, execute actions, mutate downstream state merely because a choice exists, or become Autonomy/Agents.

The existing Learning Decision foundation remains a domain-specific learning decision layer and does not substitute for this future general Decision boundary.

Autonomy and Agents remain deferred until general Decision/orchestration and controlled-governance boundaries are separately implemented, audited and frozen.