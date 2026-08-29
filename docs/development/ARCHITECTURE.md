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
- Autonomy;
- Controlled Autonomy Deliberation.

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
- fresh Authority is adjacent to both orchestration authorization and final Execution;
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
- exact Reflection provenance is data only;
- explicit declared provenance does not pretend a Goal/Context store exists;
- objective/trigger payload is private and redacted;
- priority is data only, not scheduling permission;
- finite attempt budget is data only until a controlled gate claims attempts;
- duplicate IDs reject without replacement;
- ownership is stale/ABA-safe and one-shot;
- compositions are isolated;
- snapshots are deterministic detached views;
- install→remove correlation is explicit root→child;
- no Decision/Orchestration call, Authority call, ExecutionRequest, executor, scheduler, background runner or Agent exists in the structural foundation.

Canonical contract: `AUTONOMY_V0_1_FREEZE.md`.

## Controlled Autonomy Deliberation v0.1 — FROZEN

Frozen full path:

`exact live AutonomyProposal → bounded exact attempt → AutonomyDeliberationRequest → fresh live deliberation preflight → Planning → Reasoning → Decision → OrchestrationIntent → final Autonomy execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`.

Hard invariants:

- attempts are claimed against exact Autonomy ID+generation;
- finite attempt budget is enforced without introducing a scheduler;
- cancellation is exact-generation scoped;
- stale generation does not consume replacement budget;
- stale cancellation does not affect replacement generation;
- deliberation requests preserve exact `(AutonomyProposalId, generation, attemptNumber)` provenance and have their own exact generation ownership;
- every downstream cognitive bridge performs a fresh deliberation preflight;
- Planning origin is constructed by the trusted bridge from exact Autonomy/request/attempt provenance;
- Reasoning requires exact live Planning generation and matching Autonomy provenance;
- Decision requires exact live Planning+Reasoning generations and exact structural inputs;
- Decision options, selected outcome and rationale remain caller-declared and are not permission;
- Orchestration bridge creates only a non-executing intent from exact Decision provenance;
- final `ControlledAutonomyExecution` revalidates the complete Autonomy→Planning→Reasoning→Decision→Orchestration chain before the first downstream Authority call;
- cancellation after OrchestrationIntent creation still causes zero executor calls and zero new downstream Authority decisions;
- stale Autonomy or stale cognitive/orchestration generation fails closed;
- after the Autonomy guard, frozen Controlled Orchestration independently revalidates Orchestration/Decision, trusted action mapping and fresh Authority;
- frozen Execution independently performs its own trusted mapping and a second fresh Authority immediately before executor;
- denied Authority means zero executor calls;
- success reaches executor exactly once;
- old attempt/preflight/readiness evidence is never durable permission;
- private autonomy, deliberation, planning, reasoning, decision and orchestration payload stays out of full-path observability;
- data-only Autonomy/Deliberation artifacts expose no Authority/Execution/scheduler/Agent methods;
- there is no hidden scheduler, recurring autonomous loop, self-spawning work or Agent behavior in v0.1.

Canonical contract: `CONTROLLED_AUTONOMY_V0_1_FREEZE.md`.

## Agents Foundation v0.1 — NEXT

Agents may represent bounded actor identity/role and future governed work ownership, but an Agent must not become a new authority, executor or scheduler bypass.

Required first direction:

`explicit Agent identity + caller-declared role/purpose + bounded structural constraints + exact AgentGeneration ownership → data-only Agent record`

Mandatory invariant:

`Agent != Autonomy != Decision != Authority != Execution`.

First slice requirements:

- exact Agent ID and positive generation ownership;
- explicit caller-declared role/purpose as private data;
- explicit bounded parent/origin/delegation references only if they can be represented without hidden lookup;
- no capability amplification from role/delegation metadata;
- duplicate rejection without replacement;
- stale/ABA-safe one-shot ownership;
- composition isolation;
- deterministic detached snapshots;
- privacy-safe observability/correlation;
- no scheduler, background runner, recursive loop or self-spawning;
- no direct Authority, Execution or tool/device access;
- no hidden Memory/Knowledge mutation;
- no Agent-created durable permission;
- any future agent-initiated work must enter the frozen Autonomy → Deliberation → Decision → Orchestration → Authority → Execution chain.

Agent runtime/lifecycle, delegation, coordination and multi-agent behavior must be separate later slices with their own bounded budgets, cancellation, ownership and governance audits.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission. Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Deferred roadmap

After Agents Foundation is separately implemented and frozen:

- controlled Agent→Autonomy bridge;
- bounded Agent lifecycle/cancellation;
- explicit non-amplifying Agent delegation/coordination;
- multi-agent behavior only after single-agent governance is frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, observability, explicit ownership, fail-closed Authority, privacy, rollback/safety and composition isolation.
