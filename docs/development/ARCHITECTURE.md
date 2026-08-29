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

## Frozen cognitive/control foundations

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
- Controlled Orchestration;
- Autonomy.

Canonical subsystem contracts remain the detailed source for each frozen boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → exact preflight → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. It is not implicit permission propagation and cannot bypass deliberation, Authority or Execution.

## Planning Foundation v0.1 — FROZEN

`PlanningOrigin + caller-declared goal + ordered PlanningStep list → PlanningProposal → exact PlanningGeneration ownership`

`Plan != Decision != Authority != Execution`.

## Reasoning Foundation v0.1 — FROZEN

`ReasoningOrigin + ordered premises + analysis + conclusion → ReasoningArtifact → exact ReasoningGeneration ownership`

`Reasoning != Decision != Authority != Execution`.

## Decision Foundation v0.1 — FROZEN

`exact structural Planning/Reasoning references + alternatives + selected option + rationale → DecisionRecord → exact DecisionGeneration ownership`

`Decision != Authority != Execution`.

## Orchestration Intent Foundation v0.1 — FROZEN

`OrchestrationIntentId + exact Decision provenance + caller-declared intent description + createdAt → exact OrchestrationGeneration ownership`

`Decision != Orchestration Intent != Authority != Execution`.

## Controlled Orchestration v0.1 — FROZEN

`exact OrchestrationIntent → exact live provenance preflight → trusted action policy → execution-mapping consistency → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Decision != Orchestration Intent != Authorization != Execution`.

Hard invariants:

- every attempt validates exact OrchestrationIntent ID+generation;
- referenced Decision ID+generation+selected option are revalidated live;
- caller cannot forge capability/scope;
- stale/missing/mismatched provenance fails closed;
- mapping drift fails closed;
- fresh Authority is adjacent to both orchestration authorization and the final Execution boundary;
- stale/denied/mismatch paths cause zero executor calls;
- success reaches executor exactly once;
- executor failure is isolated;
- private cognitive payload stays out of Authority reason/full-path observability;
- old preflight/authorization evidence is never durable permission.

Canonical contract: `ORCHESTRATION_CONTROL_V0_1_FREEZE.md`.

## Autonomy Foundation v0.1 — FROZEN

Structural boundary:

`explicit autonomy provenance + caller-declared objective + trigger description + priority + finite attempt budget + createdAt → AutonomyProposal → exact AutonomyGeneration ownership`

Mandatory invariant:

`Autonomy != Decision != Authority != Execution`.

Hard invariants:

- exact proposal identity and positive generation ownership;
- `AutonomyOrigin.Reflection` preserves exact Reflection ID+generation as data only;
- `AutonomyOrigin.Declared` supports explicit external/goal-context provenance without claiming a Goal/Context store exists;
- no hidden provenance lookup;
- objective/trigger payload is private and redacted from lifecycle observability;
- priority is data only, not scheduling permission;
- finite attempt budget is data only, not an active retry loop;
- duplicate IDs reject without replacement;
- stale/ABA ownership cannot remove a replacement;
- repeated remove fails closed;
- same-ID concurrency has one winner per store;
- compositions are isolated;
- snapshots are deterministic detached views;
- install→remove correlation is explicit root→child;
- lifecycle metadata contains no Decision/Authority/Capability/Execution/scheduler/Agent semantics;
- no Decision/Orchestration call, Authority call, ExecutionRequest, executor, cognitive-store mutation, scheduler, background runner or Agent exists in v0.1.

Canonical contract: `AUTONOMY_V0_1_FREEZE.md`.

## Controlled Autonomy Deliberation Bridge — NEXT

The next layer may connect exact live Autonomy proposals into deliberation, but may not turn initiative into permission.

Required direction:

`exact live AutonomyProposal → controlled deliberation request → Planning/Reasoning/Decision → Orchestration Intent → exact preflight → fresh Authority → Execution`

Required invariants:

- exact Autonomy ID+generation is revalidated before downstream work;
- attempt ownership/accounting is explicit and bounded;
- cancellation/stale ownership is explicit and fail-closed;
- Autonomy cannot forge a Decision or Authority decision;
- no direct Autonomy→Authority or Autonomy→Execution path;
- stale/removed/cancelled autonomy provenance causes zero downstream work;
- private objective/trigger payload remains protected;
- first bridge slice remains non-executing and introduces no hidden scheduler;
- Agents remain deferred.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission. Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Deferred roadmap

After the controlled Autonomy deliberation/lifecycle boundary is separately implemented and frozen:

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
