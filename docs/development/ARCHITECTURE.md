# ARCHITECTURE BASELINE

## Foundation chain — FROZEN

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Hard invariants:

- Runtime is the single runtime-state authority;
- Lifecycle orchestrates Runtime rather than shadowing state;
- mutable ownership is explicit and stale/ABA-safe;
- listener failures are isolated and observable;
- raw mutable registries stay behind composition boundaries;
- important operations use explicit structured correlation;
- no hidden global logger/context acquisition.

## Capability / Authority / Execution — FROZEN

`AuthorityRequest → AuthorityPolicy → AuthorityDecision`

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants: default deny; capability existence is not permission; exact principal+capability+scope matching; strict expiry; bounded non-amplifying delegation; unknown/mismatched action mapping rejects before executor; denied Authority means zero executor calls; old authorization evidence is never durable permission.

## Frozen cognitive/control foundations

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy Foundation, Controlled Autonomy Deliberation, Agents Foundation, Controlled Agent Initiative, Controlled Agent Lifecycle, Agent Delegation Foundation and Controlled Agent Delegation v0.1 are frozen.

Canonical freeze documents are the detailed source for each boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents add exact actor identity/lifecycle governance. Delegation adds exact parent/child structural provenance. None of these layers propagates implicit permission.

## Decision / Orchestration — FROZEN

`Decision → non-executing OrchestrationIntent → exact live preflight → trusted action policy → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Decision != Orchestration Intent != Authorization != Execution`.

## Autonomy — FROZEN

Structural Autonomy:

`explicit provenance + objective + trigger + priority + finite attempt budget → AutonomyProposal → exact AutonomyGeneration ownership`

Controlled path:

`exact live AutonomyProposal → bounded exact attempt → Deliberation → Planning → Reasoning → Decision → OrchestrationIntent → final Autonomy guard → Controlled Orchestration → fresh Authority → Execution`

Mandatory invariant:

`Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`.

## Agents / Initiative / Lifecycle — FROZEN

Agent identity:

`explicit Agent identity + origin + private role/purpose → AgentRecord → exact AgentGeneration ownership`

Controlled Agent initiative:

`exact live ACTIVE Agent → trusted Agent provenance → finite-budget AutonomyProposal → fresh Agent/lifecycle attempt guard → frozen Autonomy chain → final Agent/lifecycle execution guard`

Lifecycle:

`exact AgentId + AgentGeneration → explicit ACTIVE / CANCELLED / STOPPED state`

Mandatory invariant:

`Agent Identity != Agent Lifecycle != Autonomy != Authority != Execution`.

## Agent Delegation Foundation — FROZEN

Structural relation:

`exact parent Agent generation + exact child Agent generation + private purpose + createdAt → AgentDelegationRecord → exact AgentDelegationGeneration ownership`

Mandatory invariant:

`Delegation != Capability != Authority != Execution`.

Structural composition deliberately performs no Agent/lifecycle validation and creates no work.

## Controlled Agent Delegation v0.1 — FROZEN

Controlled delegated path:

`exact Delegation → fresh exact parent/child ACTIVE preflight → compensated child Agent Autonomy + exact delegation↔Autonomy binding → delegated attempt gate → frozen Autonomy cognitive path → final delegated execution guard → frozen ControlledAgentExecution → frozen Autonomy/Orchestration → fresh Authority → Execution`

Mandatory invariant:

`Delegation != Initiative != Attempt Evidence != Permission != Authority != Execution`.

Hard invariants:

- exact delegation generation is live-validated before use;
- exact parent and child Agent generations plus ACTIVE lifecycle are freshly validated;
- exact delegated Autonomy has a separate exact structural binding to delegation + child generation;
- one exact Autonomy generation cannot belong to multiple delegation relations;
- child initiative creation uses two delegation preflights around the Autonomy write to close the TOCTOU window;
- post-create validation or binding failure compensates the exact newly-created Autonomy before normal rejection;
- compensation failure is explicit and CRITICAL-observable;
- successful creation exposes one composite ownership/structural receipt, not independent mutable Autonomy/binding handles;
- delegated attempt claim validates binding + delegation/lifecycle before and after the claim;
- a post-claim governance race cancels exact Autonomy before returning rejection, so downstream attempt validation fails closed;
- final execution derives exact Autonomy from the live deliberation request and resolves the binding from it;
- stale/missing deliberation, binding, delegation, parent/child generation or terminal lifecycle causes zero downstream Agent-execution calls;
- Controlled Agent Delegation performs no Authority call and no Execution directly;
- private delegation purpose stays outside controlled-path observability;
- no scheduler, automatic recursive delegation, self-spawn, fan-out, consensus or multi-agent runtime exists.

Canonical contracts: `AGENT_DELEGATION_V0_1_FREEZE.md`, `CONTROLLED_AGENT_DELEGATION_V0_1_FREEZE.md`.

## Agent Coordination Foundation v0.1 — NEXT

The next layer is structural coordination only.

First direction:

`explicit Coordination identity + exact participant Agent generations + private coordination purpose + createdAt → CoordinationRecord → exact CoordinationGeneration ownership`

Required invariants:

- all participants use exact `(AgentId, AgentGeneration)` references;
- invalid/duplicate participant structures fail closed;
- coordination purpose is private/redacted;
- exact ownership is stale/ABA-safe and composition-isolated;
- coordination relation is data only, never capability/permission/Authority evidence;
- no scheduler, fan-out work creation, voting, consensus, automatic delegation, Autonomy write, Authority or Execution in the foundation;
- controlled coordination behavior is a later independent stage after structural coordination is frozen.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

## Deferred roadmap

After Agent Coordination Foundation is separately implemented and frozen:

- controlled bounded Agent coordination;
- multi-agent behavior only after coordination governance is frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, explicit ownership, observability, fail-closed Authority, privacy, rollback/safety and composition isolation.
