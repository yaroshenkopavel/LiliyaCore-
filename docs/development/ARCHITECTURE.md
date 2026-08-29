# ARCHITECTURE BASELINE

## Foundation chain — FROZEN

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Hard invariants:

- Runtime is the single runtime-state authority;
- Lifecycle orchestrates Runtime rather than shadowing state;
- mutable ownership is explicit and stale/ABA-safe;
- listener failures are isolated and observable;
- raw mutable registries stay behind composition boundaries;
- important operations use Logging + Diagnostics with explicit `LogContext` correlation;
- no hidden global logger/context acquisition.

## Capability & Authority v0.1 — FROZEN

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Hard invariants:

- default deny;
- capability existence is not permission;
- exact principal + capability + scope matching;
- strict expiry;
- bounded non-amplifying delegation;
- Authority is observable;
- authorization evidence is not durable permission;
- Authority never performs execution.

## Execution v0.1 — FROZEN

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants:

- unknown/mismatched action-capability mapping rejects before executor;
- denied Authority means zero executor calls;
- executor failures are isolated and observable;
- Execution performs controlled side effects but does not decide permission;
- Android/device/browser/shell adapters must remain behind this boundary.

## Frozen cognitive foundations

The following v0.1 foundations are frozen:

- Memory;
- Knowledge;
- Identity / Self;
- Trust / Security;
- Personality;
- Reflection;
- Learning Candidate;
- Learning Decision;
- Learning Policy;
- Learning Application Intent;
- Controlled Learning Application;
- Learning Consolidation;
- Planning;
- Reasoning;
- Decision;
- Orchestration Intent;
- Controlled Orchestration.

Canonical subsystem contracts remain the detailed source for each frozen boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → exact preflight → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

This chain expresses architectural sequencing only. No earlier record is implicit permission for a later stage.

## Planning Foundation v0.1 — FROZEN

`PlanningOrigin + caller-declared goal + ordered PlanningStep list → PlanningProposal → exact PlanningGeneration ownership`

`Plan != Decision != Authority != Execution`.

Planning is descriptive structural data only. Canonical contract: `PLANNING_V0_1_FREEZE.md`.

## Reasoning Foundation v0.1 — FROZEN

`ReasoningOrigin + ordered premises + analysis + conclusion → ReasoningArtifact → exact ReasoningGeneration ownership`

`Reasoning != Decision != Authority != Execution`.

Reasoning conclusions are deliberative data only. Canonical contract: `REASONING_V0_1_FREEZE.md`.

## Decision Foundation v0.1 — FROZEN

`exact structural Planning/Reasoning references + alternatives + selected option + rationale → DecisionRecord → exact DecisionGeneration ownership`

`Decision != Authority != Execution`.

A selected option is a recorded choice only. Canonical contract: `DECISION_V0_1_FREEZE.md`.

## Orchestration Intent Foundation v0.1 — FROZEN

`OrchestrationIntentId + exact Decision provenance + caller-declared intent description + createdAt → exact OrchestrationGeneration ownership`

`Decision != Orchestration Intent != Authority != Execution`.

An orchestration intent is structural downstream intention only. It neither validates that its Decision is still live nor grants permission, schedules work or executes anything.

Canonical contract: `ORCHESTRATION_V0_1_FREEZE.md`.

## Controlled Orchestration v0.1 — FROZEN

Frozen controlled side-effect path:

`exact OrchestrationIntent → exact live provenance preflight → trusted action policy → execution-mapping consistency → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Decision != Orchestration Intent != Authorization != Execution`.

Hard invariants:

- every attempt validates exact OrchestrationIntent ID+generation;
- referenced Decision ID+generation+selected option are revalidated live;
- caller supplies principal and action ID only, never capability/scope;
- capability/scope come from trusted action policy;
- orchestration policy capability must match the Execution action→capability mapping;
- stale/missing/mismatched provenance rejects fail-closed;
- mapping mismatch rejects before downstream power;
- orchestration performs a fresh scope-correct Authority decision;
- `ExecutionRequest` is built only from fresh structural authorization evidence;
- frozen Execution independently repeats mapping validation and fresh Authority immediately before executor;
- stale provenance, denied Authority and mapping drift cause zero executor calls;
- successful controlled execution reaches executor exactly once;
- executor failure is isolated and observable;
- private Decision rationale/option text and orchestration description do not enter Authority reason or full-path observability;
- prior preflight or authorization evidence cannot be reused as durable permission after revoke/replacement;
- no hidden scheduler, Autonomy or Agent behavior exists in v0.1.

Canonical contract: `ORCHESTRATION_CONTROL_V0_1_FREEZE.md`.

## Controlled Learning Application v0.1 — FROZEN

`Candidate → Decision → Policy → Application Intent → exact preflight → fresh Authority → prepared mutation → exact claim → Memory/Knowledge write → exact completion → completed structural outcome`

Architecture precedent preserved by Controlled Orchestration: structural intent is not permission; exact provenance and fresh Authority must be revalidated at the real side-effect boundary.

## Autonomy Foundation v0.1 — NEXT

Autonomy may decide or propose *whether/when controlled work should be attempted*, but it must not become Authority or Execution.

Required direction:

`Goals / Context / Reflection → explicit Autonomy proposal/intent → Decision → Orchestration Intent → controlled preflight/Authority → Execution`

Mandatory invariant:

`Autonomy != Decision != Authority != Execution`.

First structural slice must define:

- exact autonomy proposal/intent identity and generation ownership;
- explicit structural origin/provenance without hidden store lookup;
- caller/policy-declared trigger, priority and bounded budget semantics;
- no side effect, scheduler or executor in the first slice;
- stale-safe ownership, cancellation semantics and composition isolation;
- deterministic detached snapshots;
- privacy-safe observability/correlation;
- explicit prohibition on treating autonomy intent as approval, durable permission or execution;
- any future bridge must flow through frozen Decision → Orchestration → Authority → Execution rather than bypass it.

Agents remain deferred until Autonomy lifecycle, budgets, cancellation, scheduling and governance are explicit, implemented, audited and frozen.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission. Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Deferred roadmap

After Autonomy Foundation is separately implemented and frozen:

- controlled Autonomy→Decision/Orchestration bridge;
- Agents only after Autonomy governance is frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, observability, explicit ownership, fail-closed Authority, privacy, rollback/safety and composition isolation.
