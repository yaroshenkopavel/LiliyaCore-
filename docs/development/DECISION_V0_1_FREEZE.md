# Decision Foundation v0.1 — Freeze Contract

Status: **FROZEN**

Verified through merged PRs #112, #113 and #114.

## Purpose

Decision Foundation v0.1 defines an explicit general recorded-choice/outcome boundary over caller-declared structural Planning and/or Reasoning inputs. It records alternatives, the selected option, rationale, timestamp and exact ownership without treating the selected option as permission, truth, confidence, trust, executable instruction or real-world effect.

Mandatory invariant:

`Decision != Authority != Execution`

A Decision record is a structural cognitive outcome only. It is not an Authority grant, capability approval, execution request, scheduler instruction, truth score or proof that the chosen option is safe or correct.

The general Decision foundation is distinct from the already frozen domain-specific Learning Decision foundation.

## Frozen model

`DecisionId + structural Planning/Reasoning input references + ordered DecisionOption list + selected DecisionOptionId + rationale + createdAt → exact DecisionGeneration ownership`

Core types:

- `DecisionId` — nonblank general decision identity;
- `DecisionOptionId` — nonblank option identity;
- `DecisionGeneration` — exact positive generation;
- `DecisionInputReference.Planning` — exact structural `(PlanningProposalId, PlanningGeneration)` reference;
- `DecisionInputReference.Reasoning` — exact structural `(ReasoningArtifactId, ReasoningGeneration)` reference;
- `DecisionOption` — caller-declared option description;
- `DecisionRecord` — immutable recorded decision data;
- `DecisionOwnership` — controlled exact install/remove ownership handle;
- `DecisionSnapshot` — exact decision plus generation.

## Frozen guarantees

### Structural integrity

- Decision and option IDs must be nonblank;
- every Decision contains at least one structural input reference;
- duplicate exact input references are rejected;
- every Decision contains at least one option;
- option IDs are unique within the Decision;
- the selected option ID must exist in the option list;
- rationale and option descriptions are nonblank;
- caller-provided input and option collections are defensively copied;
- Planning/Reasoning inputs are structural references only: Decision v0.1 performs no hidden lookup or validation against their stores.

### Exact ownership and concurrency

- duplicate Decision ID registration rejects without replacement;
- each successful registration receives an exact positive generation;
- removal is bound to the exact stored entry/generation;
- stale ownership cannot remove a later replacement;
- repeated removal fails closed;
- concurrent same-ID registration has exactly one winner;
- independent `DecisionComposition` instances remain isolated;
- the same Decision ID may exist independently in separate compositions.

### Read semantics

- `find`, `inspect`, `contains`, `snapshot`, and `snapshotEntries` expose controlled read views;
- snapshots are deterministic by `createdAt`, then Decision ID;
- returned snapshot lists are detached views and are not mutable live store handles.

### Privacy and observability

- option descriptions are redacted from `DecisionOption.toString()`;
- option descriptions and rationale are redacted from `DecisionRecord.toString()`;
- lifecycle observability contains structural IDs, exact generation, structural input counts, option count, selected option ID and timestamp only;
- option descriptions and rationale are excluded from lifecycle metadata;
- lifecycle metadata does not claim approval, Authority, Capability, permission, Execution, scheduling, truth, confidence or trust semantics.

The selected option ID is permitted structural metadata. Its presence means only "this option was recorded as selected".

### Correlation

- installation creates a root `LogContext`;
- removal is recorded with a child context;
- child removal receives a fresh correlation ID whose `parentCorrelationId` equals the install correlation ID;
- no ThreadLocal or hidden global context propagation is introduced.

## Explicit exclusions

Decision Foundation v0.1 does **not**:

- grant permission or Authority;
- create a capability grant;
- authorize any scope;
- emit or execute an `ExecutionRequest`;
- call an executor;
- schedule, retry or dispatch work;
- mutate Memory or Knowledge merely because a Decision exists;
- approve or apply learning outside the separate controlled-learning pipeline;
- resolve Planning/Reasoning references by hidden store lookup;
- claim that the selected option is true, safe, trusted, optimal or high-confidence;
- create Autonomy or Agent semantics;
- control Android/device/browser/shell capabilities;
- provide persistent/crash-durable Decision storage;
- imply successful execution or real-world effect.

## Downstream rule

Any future bridge from Decision toward real action must be a separately designed, separately audited subsystem.

The required direction is:

`Decision → explicit orchestration intent/bridge → Capability/Authority → Execution`

A future bridge must not turn a recorded selected option directly into permission or an executor call. It must preserve the existing fail-closed Capability/Authority/Execution boundaries.

Decision Foundation v0.1 intentionally does **not** implement this downstream bridge.

## Reopening rule

This baseline may be reopened only for a demonstrated correctness/security/privacy defect or a deliberately reviewed higher-layer integration need. Reopening requires focused executable contracts, exact-head Core CI GREEN, architecture/privacy/security audit, exact-head merge, and journal update.

## Next cognitive stage

The next cognitive architecture stage is the **explicit deliberation/orchestration bridge foundation**.

Its purpose is to translate an exact recorded Decision into a structural downstream orchestration intent without granting Authority or performing Execution.

The first design must preserve:

`Decision != Orchestration Intent != Authority != Execution`

Autonomy remains deferred until this bridge and its controlled-governance boundaries are separately implemented, audited and frozen. Agents remain deferred until Autonomy boundaries are themselves explicit and frozen.
