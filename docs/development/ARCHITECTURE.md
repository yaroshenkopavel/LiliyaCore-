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

Hard invariants: default deny; capability existence is not permission; exact principal+capability+scope matching; strict expiry; bounded non-amplifying delegation; authorization evidence is not durable permission; Authority never performs execution.

## Execution v0.1 — FROZEN

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants: unknown/mismatched action-capability mapping rejects before executor; denied Authority means zero executor calls; executor failures are isolated and observable; device/browser/shell adapters remain behind this boundary.

## Frozen cognitive/control foundations

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning Candidate, Learning Decision, Learning Policy, Learning Application Intent, Controlled Learning Application, Learning Consolidation, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy, Controlled Autonomy Deliberation and Agents v0.1 are frozen.

Canonical subsystem contracts remain the detailed source for each frozen boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → exact preflight → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents are bounded actor identity/ownership records above Autonomy. Neither is implicit permission.

## Planning / Reasoning / Decision — FROZEN

Planning: `PlanningOrigin + goal + ordered steps → PlanningProposal → exact PlanningGeneration`.

Reasoning: `ReasoningOrigin + premises + analysis + conclusion → ReasoningArtifact → exact ReasoningGeneration`.

Decision: `exact Planning/Reasoning references + alternatives + selected option + rationale → DecisionRecord → exact DecisionGeneration`.

Mandatory: `Plan != Reasoning != Decision != Authority != Execution` where each record remains data at its own boundary.

## Orchestration Intent / Controlled Orchestration — FROZEN

`Decision → non-executing OrchestrationIntent → exact live preflight → trusted action policy → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant: `Decision != Orchestration Intent != Authorization != Execution`.

Stale provenance, denied Authority and mapping drift fail closed before executor. Old preflight/authorization evidence is never durable permission.

Canonical contracts: `ORCHESTRATION_V0_1_FREEZE.md`, `ORCHESTRATION_CONTROL_V0_1_FREEZE.md`.

## Autonomy Foundation / Controlled Autonomy Deliberation — FROZEN

Structural Autonomy:

`explicit provenance + objective + trigger + priority + finite attempt budget → AutonomyProposal → exact AutonomyGeneration ownership`

Controlled path:

`exact live AutonomyProposal → bounded attempt → AutonomyDeliberationRequest → fresh live preflight → Planning → Reasoning → Decision → OrchestrationIntent → final Autonomy execution guard → Controlled Orchestration → fresh Authority → Execution → fresh Authority → executor`

Mandatory invariant:

`Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`.

Hard invariants include exact-generation attempt accounting/cancellation, fresh provenance validation at every cognitive bridge, final full-chain Autonomy validation before the first downstream Authority call, zero executor calls after late cancellation/stale provenance/denied Authority, exactly one executor call on success, no durable permission and no hidden scheduler/self-spawn/Agent runtime.

Canonical contracts: `AUTONOMY_V0_1_FREEZE.md`, `CONTROLLED_AUTONOMY_V0_1_FREEZE.md`.

## Agents Foundation v0.1 — FROZEN

Structural boundary:

`explicit Agent identity + explicit origin + caller-declared private role/purpose + createdAt → AgentRecord → exact AgentGeneration ownership`

Mandatory invariant:

`Agent != Autonomy != Decision != Authority != Execution`.

Hard invariants:

- exact Agent ID and positive generation ownership;
- `AgentOrigin.Declared` is explicit external provenance only;
- `AgentOrigin.Autonomy` preserves exact Autonomy proposal ID+generation as data only;
- origin references do not imply live validity, permission or delegation;
- role and purpose are private and redacted from rendering/lifecycle observability;
- duplicate IDs reject without replacement;
- stale/ABA ownership cannot remove replacement;
- removal is one-shot;
- concurrent same-ID registration has one winner per store;
- `AgentComposition` keeps `AgentStore` private and exposes controlled exact ownership;
- same-ID compositions are isolated;
- snapshots are deterministic detached views;
- install→remove correlation is root→child;
- Agent data API and lifecycle metadata contain no Authority/Capability/permission/Execution/scheduler/self-spawn/tool/delegation semantics;
- no Agent runtime loop, background runner, scheduler, self-replication, delegation engine, tool/device access, Authority call, Execution call or Memory/Knowledge mutation exists in v0.1.

Canonical contract: `AGENTS_V0_1_FREEZE.md`.

## Controlled Agent Initiative v0.1 — NEXT

The next layer may convert exact live Agent identity into bounded Autonomy data, but it must not turn role or identity into permission.

First direction:

`exact live Agent ID+generation → fresh Agent preflight → caller-declared bounded initiative data → ordinary AutonomyProposal`

Required invariants:

- exact Agent ID+generation is live-validated immediately before Autonomy install;
- caller cannot forge the Autonomy origin used by the bridge;
- trusted structural provenance encodes exact Agent identity/generation;
- role/purpose are not copied into permissions or observability and are not automatically treated as the initiative objective;
- caller separately declares objective, trigger, priority and finite budget;
- stale/removed/replaced Agent provenance causes zero Autonomy writes;
- bridge performs no attempt claim, scheduling, Planning, Reasoning, Decision, Orchestration, Authority or Execution;
- created Autonomy data must use the already frozen Controlled Autonomy path for all downstream work;
- no self-spawning or recursive Agent behavior.

Agent lifecycle/cancellation, delegation/coordination and multi-agent behavior remain separate later stages with bounded ownership/governance contracts.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission. Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Deferred roadmap

After Controlled Agent Initiative is separately implemented and frozen:

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
