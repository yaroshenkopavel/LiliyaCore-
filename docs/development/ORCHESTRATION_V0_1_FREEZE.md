# Orchestration Intent Foundation v0.1 — Freeze Contract

Status: **FROZEN**

Verified through merged PRs #116, #117 and #118.

## Purpose

Orchestration Intent Foundation v0.1 defines a structural, non-executing boundary between an exact recorded general Decision and any future controlled path toward real action.

Mandatory invariant:

`Decision != Orchestration Intent != Authority != Execution`

An orchestration intent records caller-declared downstream intention with exact Decision provenance. It is not permission, an Authority result, a capability grant, an `ExecutionRequest`, a scheduler entry, an executor instruction or proof that an action should occur.

## Frozen model

`OrchestrationIntentId + exact Decision reference + caller-declared description + createdAt → exact OrchestrationGeneration ownership`

Core types:

- `OrchestrationIntentId` — nonblank structural intent identity;
- `OrchestrationGeneration` — exact positive generation;
- `OrchestrationDecisionReference` — exact structural `(DecisionId, DecisionGeneration, selected DecisionOptionId)` provenance;
- `OrchestrationIntent` — caller-declared non-executing downstream intention;
- `OrchestrationSnapshot` — exact intent plus generation;
- `OrchestrationOwnership` — controlled exact composition ownership handle.

## Frozen guarantees

### Structural integrity

- orchestration intent IDs are nonblank;
- orchestration generations are positive;
- intent description is nonblank;
- Decision provenance preserves exact Decision ID, exact Decision generation and selected Decision option ID;
- constructing or storing an intent performs no hidden Decision lookup;
- selected Decision option provenance remains structural data only.

### Exact ownership and concurrency

- duplicate orchestration intent IDs reject without replacement;
- each successful registration receives an exact positive generation;
- removal is bound to the exact stored entry/generation;
- stale ownership cannot remove a later replacement;
- repeated removal fails closed;
- concurrent same-ID registration has exactly one winner;
- independent `OrchestrationComposition` instances remain isolated;
- the same orchestration intent ID may exist independently in separate compositions.

### Read semantics

- controlled `find`, `inspect`, `contains`, `snapshot`, and `snapshotEntries` read views are available through composition;
- snapshots are deterministic by `createdAt`, then orchestration intent ID;
- returned snapshot lists are detached from later store mutation.

### Privacy and observability

- private intent description is redacted from `OrchestrationIntent.toString()`;
- private intent description is absent from lifecycle metadata;
- lifecycle metadata contains only structural intent identity/generation, exact Decision provenance and timestamp;
- lifecycle metadata must not claim approval, Authority, authorization, Capability, permission, Execution, executor, scheduling, Autonomy, Agent, truth, confidence or trust semantics.

### Correlation

- installation creates a root `LogContext`;
- removal is recorded with a child `LogContext`;
- child removal receives a fresh correlation ID with `parentCorrelationId` equal to the install correlation ID;
- no ThreadLocal or hidden global correlation mechanism is introduced.

## Explicit exclusions

Orchestration Intent Foundation v0.1 does **not**:

- validate Decision provenance against a live Decision store;
- interpret a selected option as permission;
- grant or resolve Capability;
- call Authority or create an Authority grant;
- authorize any scope;
- create or emit an `ExecutionRequest`;
- call an executor;
- schedule, queue, retry or dispatch work;
- mutate Memory or Knowledge;
- claim that the intended downstream action is safe, approved, trusted, true or correct;
- become an Autonomy controller;
- create or coordinate Agents;
- control Android/device/browser/shell capabilities;
- provide crash-durable persistence;
- imply that any real-world effect occurred.

## Controlled downstream rule

No future code may directly map a stored `OrchestrationIntent` to an executor call merely because the intent exists.

The controlled direction is:

`exact OrchestrationIntent → exact live provenance preflight → explicit action/capability resolution → fresh Authority → Execution`

A future controlled bridge must revalidate the exact retained orchestration intent and exact retained Decision provenance, resolve an allowed action/capability pair through trusted code, request fresh scope-correct Authority adjacent to execution, and then enter the already frozen Execution boundary.

Old Decision records, orchestration intents, validation receipts or Authority decisions are evidence only and must never become durable permission.

## Reopening rule

This baseline may be reopened only for a demonstrated correctness/security/privacy defect or deliberately reviewed higher-layer integration need. Reopening requires focused executable contracts, exact-head Core CI GREEN, architecture/privacy/security audit, exact-head merge, and journal update.

## Next architecture stage

The next stage is the **Controlled Orchestration Authorization / Execution Bridge foundation**.

Its first slice must be fail-closed and should begin with preflight/validation rather than execution.

Required invariant:

`Orchestration Intent != Authorization != Execution`

Before any executor integration, define:

- exact orchestration intent ID+generation preflight;
- exact Decision ID+generation+selected-option consistency check;
- trusted action identifier → required capability/scope resolution;
- explicit principal and reason provenance;
- fresh Authority immediately before crossing into Execution;
- zero executor calls on unknown action, mismatch, stale provenance or denied Authority;
- privacy-safe observability and one explicit correlation lineage;
- no Autonomy or Agent semantics.

Autonomy remains deferred until the controlled orchestration→Authority→Execution path is independently implemented, audited and frozen. Agents remain deferred until Autonomy boundaries are explicit and frozen.
