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

## Capability / Authority / Execution — FROZEN

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants: default deny; capability existence is not permission; exact principal+capability+scope matching; strict expiry; bounded non-amplifying delegation; unknown/mismatched action mapping rejects before executor; denied Authority means zero executor calls; Authority never performs execution; old authorization evidence is never durable permission.

## Frozen cognitive/control foundations

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy Foundation, Controlled Autonomy Deliberation, Agents Foundation, Controlled Agent Initiative, Controlled Agent Lifecycle and Agent Delegation Foundation v0.1 are frozen.

Canonical subsystem freeze documents remain the detailed source for each boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents add bounded actor identity and explicit lifecycle governance above Autonomy. Agent Delegation currently records structural parent/child relationships only. Identity, lifecycle or delegation never implies permission.

## Decision / Orchestration — FROZEN

Planning, Reasoning and Decision remain distinct data boundaries. Decision is recorded choice only.

Controlled side-effect direction:

`Decision → non-executing OrchestrationIntent → exact live preflight → trusted action policy → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Decision != Orchestration Intent != Authorization != Execution`.

## Autonomy Foundation / Controlled Autonomy — FROZEN

Structural Autonomy:

`explicit provenance + objective + trigger + priority + finite attempt budget → AutonomyProposal → exact AutonomyGeneration ownership`

Controlled path:

`exact live AutonomyProposal → bounded exact attempt → Deliberation → Planning → Reasoning → Decision → OrchestrationIntent → final Autonomy guard → Controlled Orchestration → fresh Authority → Execution`

Mandatory invariant:

`Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`.

## Agents / Controlled Initiative / Lifecycle — FROZEN

Agent structural boundary:

`explicit Agent identity + origin + private role/purpose → AgentRecord → exact AgentGeneration ownership`

Controlled Agent initiative:

`exact live ACTIVE Agent → trusted Agent provenance → finite-budget AutonomyProposal → fresh Agent/lifecycle check before attempt → frozen Autonomy chain → fresh Agent/lifecycle check before final downstream execution`

Lifecycle boundary:

`exact AgentId + AgentGeneration → explicit ACTIVE / CANCELLED / STOPPED state`

Mandatory invariant:

`Agent Identity != Agent Lifecycle != Autonomy != Authority != Execution`.

Missing/CANCELLED/STOPPED lifecycle fails closed at initiative creation, attempt claim and final Agent execution guard. Lifecycle state is governance evidence only, never permission.

Canonical contracts: `AGENTS_V0_1_FREEZE.md`, `CONTROLLED_AGENT_INITIATIVE_V0_1_FREEZE.md`, `AGENT_LIFECYCLE_V0_1_FREEZE.md`.

## Agent Delegation Foundation v0.1 — FROZEN

Structural boundary:

`exact parent AgentId+AgentGeneration + exact child AgentId+AgentGeneration + private purpose + createdAt → AgentDelegationRecord → exact AgentDelegationGeneration ownership`

Mandatory invariant:

`Delegation != Capability != Authority != Execution`.

Hard invariants:

- exact parent and child generation references are structural data only;
- self-delegation rejects by default;
- relation purpose is private/redacted;
- duplicate relation identity rejects without replacement;
- exact ownership is stale/ABA-safe and one-shot;
- composition isolation and deterministic detached snapshots are explicit;
- raw mutable delegation store remains private;
- structural composition intentionally has no `AgentComposition` or `ControlledAgentLifecycle` dependency;
- data and ownership APIs expose no Capability/Authority/permission/Execution/scheduler/tool/initiative/spawn/replication semantics;
- no Agent work is created by this foundation;
- no scheduler, recurring loop, delegation engine, multi-agent runtime, Authority or Execution exists here.

Canonical contract: `AGENT_DELEGATION_V0_1_FREEZE.md`.

## Controlled Agent Delegation Bridge v0.1 — NEXT

The next layer may use a structural delegation relation to create bounded downstream initiative only after fresh endpoint governance checks.

First direction:

`exact Delegation ID+generation → fresh parent Agent ID+generation + ACTIVE lifecycle → fresh child Agent ID+generation + ACTIVE lifecycle → caller-declared bounded initiative data → ordinary AutonomyProposal`

Required invariants:

- exact delegation generation is live-validated immediately before use;
- parent and child Agent generations are both live-validated immediately before downstream work creation;
- ACTIVE lifecycle is mandatory for both exact endpoints;
- stale/removed/replaced/CANCELLED/STOPPED endpoint causes zero downstream writes;
- relation itself is never capability, permission, Authority or execution evidence;
- parent capability/permission/execution rights are not inherited or amplified by child;
- downstream work must still enter frozen Agent Initiative / Autonomy / Authority / Execution boundaries;
- no scheduler, recursive delegation, self-spawn or multi-agent runtime in the first controlled bridge.

Multi-agent coordination remains deferred until Controlled Agent Delegation is separately frozen.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

## Deferred roadmap

After Controlled Agent Delegation is separately implemented and frozen:

- bounded Agent coordination;
- multi-agent behavior only after delegation governance is frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, explicit ownership, observability, fail-closed Authority, privacy, rollback/safety and composition isolation.
