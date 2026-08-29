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

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy Foundation, Controlled Autonomy Deliberation, Agents Foundation, Controlled Agent Initiative and Controlled Agent Lifecycle v0.1 are frozen.

Canonical subsystem freeze documents remain the detailed source for each boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents add bounded actor identity and explicit lifecycle governance above Autonomy. Agent identity or lifecycle state never implies permission.

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

Hard invariants include exact-generation attempt accounting/cancellation, fresh provenance validation at every bridge, late-cancellation rejection before downstream Authority, zero executor calls for stale/cancelled/denied paths, no durable permission and no hidden scheduler.

Canonical contracts: `AUTONOMY_V0_1_FREEZE.md`, `CONTROLLED_AUTONOMY_V0_1_FREEZE.md`.

## Agents Foundation v0.1 — FROZEN

Structural boundary:

`explicit Agent identity + explicit origin + caller-declared private role/purpose + createdAt → AgentRecord → exact AgentGeneration ownership`

Mandatory invariant:

`Agent != Autonomy != Decision != Authority != Execution`.

Agent identity is structural data only. Role/purpose are private; ownership is stale/ABA-safe; no runtime loop, scheduler, self-spawn, delegation engine, tool/device access, Authority, Execution or hidden Memory/Knowledge mutation exists in the foundation.

Canonical contract: `AGENTS_V0_1_FREEZE.md`.

## Controlled Agent Initiative v0.1 — FROZEN

Frozen direction:

`exact live Agent → trusted Agent provenance → ordinary finite-budget AutonomyProposal → fresh Agent check before attempt claim → frozen Controlled Autonomy deliberation/cognitive path → fresh Agent check at final execution boundary → frozen Controlled Autonomy execution → fresh Authority → Execution`

Mandatory invariant:

`Agent != Autonomy != Deliberation != Decision != Authority != Execution`.

Hard invariants include trusted exact Agent provenance, zero writes from stale/removed Agents, bounded attempt ownership staying in Autonomy, late Agent-removal guard before downstream execution, no Agent-as-Authority semantics and no scheduler/self-spawn/delegation runtime.

Canonical contract: `CONTROLLED_AGENT_INITIATIVE_V0_1_FREEZE.md`.

## Controlled Agent Lifecycle v0.1 — FROZEN

Lifecycle boundary:

`exact AgentId + exact AgentGeneration → explicit ACTIVE / CANCELLED / STOPPED lifecycle state`

Mandatory invariant:

`Agent Identity != Agent Lifecycle != Autonomy != Authority != Execution`.

Hard invariants:

- lifecycle is separate from Agent registry presence;
- exact live Agent generation is required to activate lifecycle;
- lifecycle is absent until explicit activation;
- lifecycle ownership binds to exact Agent generation;
- `CANCELLED` and `STOPPED` are terminal in v0.1;
- repeated/competing terminal transitions fail closed;
- stale lifecycle ownership cannot affect replacement generation;
- replacement Agent does not inherit stale lifecycle state;
- exact ACTIVE lifecycle is mandatory at Agent initiative creation;
- exact ACTIVE lifecycle is mandatory immediately before bounded Autonomy attempt claim;
- exact ACTIVE lifecycle is mandatory immediately before final Agent delegation into frozen Controlled Autonomy execution;
- missing/CANCELLED/STOPPED lifecycle means zero writes/claims/downstream execution delegate calls at those boundaries;
- lifecycle state is governance evidence only, never capability, permission, Authority or execution right;
- no scheduler, recurring loop, pause/resume runtime, delegation engine or multi-agent behavior exists in v0.1.

Canonical contract: `AGENT_LIFECYCLE_V0_1_FREEZE.md`.

## Agent Delegation Foundation v0.1 — NEXT

Delegation comes only after single-Agent identity, initiative and lifecycle governance are frozen.

First direction:

`exact parent Agent generation + exact child Agent generation + explicit bounded relation → exact delegation generation ownership`

Required invariants:

- exact parent and child IDs+generations;
- self-delegation rejects by default;
- relation metadata is data only, not permission/capability/Authority;
- no capability or execution-right amplification;
- duplicate relation identity rejects without replacement;
- stale/ABA-safe exact ownership;
- composition isolation and deterministic detached snapshots;
- private role/purpose do not leak into delegation observability;
- no scheduler, initiative creation, Authority, Execution or tool access;
- no multi-agent runtime in the foundation;
- any later delegation-to-work bridge must revalidate both exact Agent generations and ACTIVE lifecycle before creating downstream work.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

## Deferred roadmap

After Agent Delegation Foundation is separately implemented and frozen:

- controlled delegation-to-Autonomy bridge with fresh parent/child lifecycle checks;
- bounded Agent coordination;
- multi-agent behavior only after single-Agent and delegation governance are frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, explicit ownership, observability, fail-closed Authority, privacy, rollback/safety and composition isolation.
