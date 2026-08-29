# ARCHITECTURE BASELINE

## Foundation chain — FROZEN

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Core Foundation v0.1 is frozen.

Hard foundation invariants:

- one runtime state authority;
- Lifecycle orchestrates Runtime rather than shadowing state;
- explicit lifecycle/recovery ownership;
- synchronous deterministic in-process events;
- listener failures isolated and observable;
- exact registration/ownership handles and stale/ABA-safe removal;
- transactional module/service installation;
- raw mutable registries encapsulated by composition boundaries;
- important operations observable through Logging + Diagnostics with explicit `LogContext` correlation;
- no hidden global logger/context acquisition.

## Capability & Authority v0.1 — FROZEN

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Hard invariants:

- default deny;
- capability existence is not permission;
- exact principal + capability + scope matching;
- strict expiry (`now < expiresAt`);
- bounded one-level delegation only;
- delegation cannot amplify authority;
- Authority is observable;
- authorization evidence is not durable future permission;
- Authority never performs execution.

## Execution v0.1 — FROZEN

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants:

- unknown/mismatched action-capability mapping rejects before executor;
- denied Authority means zero executor calls;
- executor exceptions are isolated and observable;
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
- Orchestration Intent.

## Controlled Learning Application v0.1 — FROZEN

`Candidate → Decision → Policy → Application Intent → exact preflight → fresh Authority → prepared mutation → exact claim → Memory/Knowledge write → exact completion → completed structural outcome`

Important architecture precedent:

- APPROVE is not Authority and not execution;
- structural application intent is not permission;
- exact target/provenance is checked again at mutation time;
- fresh Authority is adjacent to the downstream write;
- exact claims serialize mutation ownership;
- semantic replay returns retained structural outcome without another write;
- compensation uses exact returned downstream ownership;
- payload content is excluded from lifecycle metadata;
- v0.1 is composition-local/in-memory and does not claim crash-durable exactly-once.

## Learning Consolidation v0.1 — FROZEN

`completed controlled outcomes → consolidation proposal → exact consolidation ownership → controlled Candidate bridge → Learning Candidate → Decision → Policy → Application → fresh Authority → controlled apply`

The consolidation bridge creates only an ordinary Candidate and cannot bypass Decision, Policy, Application, Authority or controlled apply.

Canonical contract: `LEARNING_CONSOLIDATION_V0_1_FREEZE.md`.

## Planning Foundation v0.1 — FROZEN

`PlanningOrigin + caller-declared goal + ordered PlanningStep list → PlanningProposal → exact PlanningGeneration ownership`

Mandatory invariant:

`Plan != Decision != Authority != Execution`

Planning is descriptive structural data only. It has exact ownership, stale/ABA-safe removal, deterministic detached snapshots, composition isolation, redacted goal/step payloads, explicit install→remove correlation and no Authority/Execution/downstream side effects.

Canonical contract: `PLANNING_V0_1_FREEZE.md`.

## Reasoning Foundation v0.1 — FROZEN

`ReasoningOrigin + ordered premises + caller-declared analysis + conclusion → ReasoningArtifact → exact ReasoningGeneration ownership`

Mandatory invariant:

`Reasoning != Decision != Authority != Execution`

Reasoning conclusions remain deliberative data. They do not imply Decision, truth, confidence, trust, Authority or Execution.

Canonical contract: `REASONING_V0_1_FREEZE.md`.

## Decision Foundation v0.1 — FROZEN

`exact structural Planning/Reasoning references + caller-declared DecisionOption list + selected option + rationale → DecisionRecord → exact DecisionGeneration ownership`

Mandatory invariant:

`Decision != Authority != Execution`

Hard invariants:

- exact structural Planning/Reasoning provenance without hidden lookup;
- alternatives and selected option are caller-declared structural data;
- selected option must exist but does not become permission;
- duplicate IDs reject without replacement;
- exact generation ownership and stale/ABA-safe removal;
- one-winner same-ID concurrency;
- composition isolation;
- deterministic detached snapshots;
- option descriptions/rationale redacted from lifecycle observability;
- install→remove explicit correlation lineage;
- no Authority, Capability grant, scheduling, Execution, Memory/Knowledge mutation, Autonomy or Agents.

Canonical contract: `DECISION_V0_1_FREEZE.md`.

## Orchestration Intent Foundation v0.1 — FROZEN

Frozen structural boundary:

`OrchestrationIntentId + exact (DecisionId, DecisionGeneration, selected DecisionOptionId) provenance + caller-declared intent description + createdAt → exact OrchestrationGeneration ownership`

Mandatory invariant:

`Decision != Orchestration Intent != Authority != Execution`

Hard invariants:

- intent identity is explicit and nonblank;
- Decision ID, generation and selected option ID are preserved exactly as provenance;
- creation/install performs no hidden Decision lookup;
- Decision provenance is data only, not proof of current validity or permission;
- private intent description is nonblank and redacted from rendering/lifecycle metadata;
- duplicate intent IDs reject without replacement;
- exact positive generation ownership and stale/ABA-safe removal;
- repeated removal fails closed;
- concurrent same-ID registration has one winner;
- compositions are isolated and may independently own the same intent ID;
- snapshots are deterministic detached views;
- install→remove uses explicit root/child `LogContext` lineage;
- lifecycle metadata contains no Authority/Capability/permission/Execution/scheduler/Autonomy/Agent/truth-confidence/trust semantics;
- v0.1 performs no scheduling, dispatch, executor call, Memory/Knowledge mutation or real-world side effect.

Canonical contract: `ORCHESTRATION_V0_1_FREEZE.md`.

## Controlled Orchestration Authorization / Execution Bridge — NEXT

The next layer may connect an exact orchestration intent toward the frozen Capability/Authority and Execution systems, but only through a separate fail-closed governance boundary.

Required direction:

`exact OrchestrationIntent → exact live provenance preflight → trusted action/capability resolution → fresh Authority → Execution`

Mandatory invariant:

`Orchestration Intent != Authorization != Execution`

The first implementation slice should establish **preflight and authorization inputs before any executor integration**.

Required contracts:

- exact orchestration intent ID+generation lookup/validation;
- exact retained Decision ID+generation validation;
- selected option consistency with the retained Decision;
- trusted action identifier mapping to required capability and scope;
- explicit principal and reason provenance;
- unknown action, stale provenance or mismatch rejects before Authority/Execution;
- fresh scope-correct Authority adjacent to the execution boundary;
- denied Authority causes zero executor calls;
- old Decision, OrchestrationIntent, preflight receipts or prior Authority decisions are evidence only, never durable permission;
- privacy-safe observability and explicit correlation continuity;
- no hidden scheduler, Autonomy or Agent behavior.

Only after this controlled bridge is independently implemented, audited and frozen may Autonomy design begin. Agents remain deferred until Autonomy boundaries are explicit and frozen.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission. Staging is not activation. Rollback viability is retained until commit/retention policy permits cleanup.

Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

Hard invariants:

- License != Authority;
- device binding uses cryptographic enrollment/non-exportable Android Keystore or StrongBox keys rather than HWID-derived secrets;
- protected model/runtime keys and user cognitive-data keys are separate domains;
- license expiry/revocation must not intentionally destroy user Memory/Knowledge;
- protected failures are explicit fail-closed;
- signing/private master keys are never embedded as trust roots in application binaries;
- Update, Licensing, Authority and Execution remain distinct boundaries.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Deferred roadmap

After the controlled Orchestration→Authority→Execution bridge:

- bridge readiness/freeze;
- Autonomy foundation with explicit governance and bounded initiative;
- Agents only after Autonomy boundaries are frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox device enrollment;
- protected model package/streaming loader;
- offline licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification before protected distribution.

All future layers must preserve provenance, observability, exact ownership, fail-closed Authority, privacy, rollback/safety and composition isolation.
