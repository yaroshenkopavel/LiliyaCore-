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

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy Foundation, Controlled Autonomy Deliberation, Agents Foundation and Controlled Agent Initiative v0.1 are frozen.

Canonical subsystem freeze documents remain the detailed source for each boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents are bounded actor identity/lifecycle layers above Autonomy. Neither Agent nor Autonomy propagates implicit permission.

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

Hard invariants:

- exact Agent ID and positive generation ownership;
- declared origin or exact Autonomy ID+generation origin is data only;
- role/purpose are private and redacted;
- duplicate rejection without replacement;
- stale/ABA-safe one-shot ownership;
- composition isolation;
- deterministic detached snapshots;
- no runtime loop, scheduler, self-spawn, delegation engine, tool/device access, Authority, Execution or hidden Memory/Knowledge mutation.

Canonical contract: `AGENTS_V0_1_FREEZE.md`.

## Controlled Agent Initiative v0.1 — FROZEN

Frozen direction:

`exact live Agent → trusted Agent provenance → ordinary finite-budget AutonomyProposal → fresh Agent check before attempt claim → frozen Controlled Autonomy deliberation/cognitive path → fresh Agent check at final execution boundary → frozen Controlled Autonomy execution → fresh Authority → Execution`

Mandatory invariant:

`Agent != Autonomy != Deliberation != Decision != Authority != Execution`.

Hard invariants:

- Agent liveness is checked by exact ID+generation before Autonomy creation;
- Agent provenance on generated Autonomy data is trusted bridge-created, not caller-forged;
- private Agent role/purpose is not implicit initiative content or permission;
- stale/removed/replaced Agent creates zero Autonomy writes;
- fresh Agent liveness and exact trusted Autonomy provenance are checked again before the first attempt claim;
- attempt accounting remains owned solely by frozen Autonomy budget governance;
- Agent identity used at final execution is derived from the exact live deliberation→Autonomy provenance, not arbitrary caller side data;
- Agent liveness is revalidated again before delegation to frozen `ControlledAutonomyExecution`;
- late Agent removal/replacement causes zero downstream execution calls;
- downstream Controlled Autonomy still independently performs Autonomy/cognitive/orchestration validation plus fresh Authority/Execution;
- Agent data and initiative APIs do not expose permission/grant/scheduler/self-spawn/tool/delegation semantics;
- no Agent scheduler, background loop, self-replication, delegation/coordination or multi-agent behavior exists in v0.1.

Canonical contract: `CONTROLLED_AGENT_INITIATIVE_V0_1_FREEZE.md`.

## Controlled Agent Lifecycle v0.1 — NEXT

Single-Agent lifecycle governance comes before delegation or multi-agent behavior.

First direction:

`exact Agent ID+generation → explicit lifecycle state → generation-scoped active/cancelled/stopped ownership`

Required invariants:

- lifecycle state is explicit rather than inferred only from registry presence;
- lifecycle handles bind to exact Agent generation;
- stale handles cannot cancel/stop replacement generations;
- cancellation/stop transitions are deterministic and fail closed;
- cancelled/stopped Agents create zero new initiatives and zero new attempt claims;
- final Agent execution boundary revalidates lifecycle immediately before downstream Controlled Autonomy execution;
- lifecycle state grants no capability and performs no Authority/Execution;
- no scheduler or recurring loop in the first lifecycle stage;
- no delegation or multi-agent coordination until lifecycle is separately frozen.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

## Deferred roadmap

After Controlled Agent Lifecycle is implemented and frozen:

- explicit non-amplifying Agent delegation/coordination;
- multi-agent behavior only after single-Agent governance is frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, explicit ownership, observability, fail-closed Authority, privacy, rollback/safety and composition isolation.
