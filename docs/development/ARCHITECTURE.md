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

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy Foundation, Controlled Autonomy Deliberation, Agents Foundation, Controlled Agent Initiative, Controlled Agent Lifecycle, Agent Delegation Foundation, Controlled Agent Delegation and Agent Coordination Foundation v0.1 are frozen.

Controlled Agent Coordination v0.1 is in progress and is not yet frozen.

Canonical freeze documents are the detailed source for each frozen boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents add exact actor identity/lifecycle governance. Delegation adds exact parent/child provenance. Coordination adds exact participant-set provenance plus controlled multi-participant transaction governance. None of these layers propagates implicit permission.

## Decision / Orchestration — FROZEN

`Decision → non-executing OrchestrationIntent → exact live preflight → trusted action policy → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant: `Decision != Orchestration Intent != Authorization != Execution`.

## Autonomy — FROZEN

`explicit provenance + objective + trigger + priority + finite attempt budget → AutonomyProposal → exact AutonomyGeneration ownership`

Controlled path:

`exact live AutonomyProposal → bounded exact attempt → Deliberation → Planning → Reasoning → Decision → OrchestrationIntent → final Autonomy guard → Controlled Orchestration → fresh Authority → Execution`

Mandatory invariant: `Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`.

## Agents / Initiative / Lifecycle — FROZEN

Agent identity:

`explicit Agent identity + origin + private role/purpose → AgentRecord → exact AgentGeneration ownership`

Controlled Agent initiative:

`exact live ACTIVE Agent → trusted Agent provenance → finite-budget AutonomyProposal → fresh Agent/lifecycle attempt guard → frozen Autonomy chain → final Agent/lifecycle execution guard`

Lifecycle:

`exact AgentId + AgentGeneration → explicit ACTIVE / CANCELLED / STOPPED state`

Mandatory invariant: `Agent Identity != Agent Lifecycle != Autonomy != Authority != Execution`.

## Agent Delegation / Controlled Delegation — FROZEN

Structural relation:

`exact parent Agent generation + exact child Agent generation + private purpose + createdAt → AgentDelegationRecord → exact AgentDelegationGeneration ownership`

Controlled delegated path:

`exact Delegation → fresh exact parent/child ACTIVE preflight → compensated child Agent Autonomy + exact delegation↔Autonomy binding → delegated attempt gate → frozen Autonomy cognitive path → final delegated execution guard → frozen ControlledAgentExecution → fresh Authority → Execution`

Mandatory invariants:

`Delegation != Capability != Authority != Execution`

`Delegation != Initiative != Attempt Evidence != Permission != Authority != Execution`.

Hard guarantees include exact endpoint/lifecycle revalidation, compensated two-store creation, post-claim race cancellation, final binding/delegation/lifecycle revalidation and zero downstream execution calls on stale governance.

Canonical contracts: `AGENT_DELEGATION_V0_1_FREEZE.md`, `CONTROLLED_AGENT_DELEGATION_V0_1_FREEZE.md`.

## Agent Coordination Foundation v0.1 — FROZEN

Structural relation:

`Coordination identity + exact participant Agent generations + private purpose + createdAt → AgentCoordinationRecord → exact AgentCoordinationGeneration ownership`

Mandatory invariant:

`Coordination != Capability != Authority != Execution`.

Hard invariants:

- at least two exact participant Agent references are required;
- duplicate exact references are rejected;
- multiple generations of one Agent ID in one coordination are rejected;
- participant order is normalized deterministically;
- private purpose is redacted;
- exact ownership is stale/ABA-safe and one-shot;
- private store is composition-owned and composition-isolated;
- snapshots are deterministic detached views;
- structural composition has no Agent registry/lifecycle/delegation dependency;
- coordination data exposes no scheduler, fan-out, voting, consensus, delegation, Autonomy, Authority or Execution semantics;
- structural coordination does not prove participant liveness and creates no work.

Canonical contract: `AGENT_COORDINATION_V0_1_FREEZE.md`.

## Controlled Agent Coordination v0.1 — IN PROGRESS

Implemented governed path through Planning:

`exact Coordination ID+generation → fresh exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated participant initiatives → transactional bounded attempt claims → exact coordination↔attempt binding → compensated deliberation transaction → exact live deliberation preflight → ordinary frozen Planning install → post-write governance revalidation → exact compensation on stale governance`

Current mandatory invariants:

`Coordination Readiness != Work != Permission != Authority != Execution`

`Coordination Transaction != Permission != Authority != Execution`

`Coordination Attempt Transaction != Attempt Binding != Permission != Authority != Execution`

`Coordinated Deliberation != Planning != Reasoning != Decision != Permission != Authority != Execution`

`Coordinated Planning != Reasoning != Decision != Permission != Authority != Execution`.

Hard guarantees implemented through PR #177:

- exact coordination generation and every exact participant Agent generation are freshly live-validated;
- every participant lifecycle must be exact ACTIVE at controlled preflight boundaries;
- coordination work is bound to a complete deterministic participant→Autonomy set;
- one exact Autonomy generation cannot be reused across coordination work sets;
- multi-participant initiative creation is compensated if a later participant or post-write governance check fails;
- bounded attempt accounting remains delegated to frozen Agent/Autonomy gates;
- exact coordination-attempt provenance is committed before a coordinated attempt receipt is returned;
- exact deliberation requests are created from committed attempt provenance and compensated as one transaction;
- live deliberation preflight re-derives the exact Autonomy attempt and rechecks the exact coordination-attempt binding;
- coordinated Planning writes only ordinary frozen Planning data;
- coordinated Planning performs a fresh preflight before the write and a fresh preflight after the write;
- if governance changes after Planning install, the exact newly-created Planning generation is removed;
- if exact compensation cannot restore the invariant, the bridge returns explicit failure and emits CRITICAL observability;
- private coordination purpose, deliberation objective, Planning goal and Planning steps are not bridge-observable content;
- no scheduler, voting/consensus, implicit permission, Authority or Execution is introduced by any implemented coordination slice.

### Next controlled cognitive slice

Preferred next stage:

`exact live coordinated deliberation + exact live coordinated Planning generation → ordinary frozen Reasoning data`

Required design constraints:

- use fresh coordinated deliberation preflight immediately before Reasoning installation;
- require exact live Planning generation that belongs to the same structural coordination/deliberation provenance;
- install through ordinary frozen Reasoning composition rather than creating a coordination-specific reasoning authority;
- revalidate coordination/deliberation/Planning provenance immediately after the write;
- compensate the exact newly-created Reasoning generation on a post-write governance race;
- treat compensation failure as explicit failure, never successful rejection;
- keep private cognitive text outside bridge observability;
- expose no Decision, Orchestration, permission, Authority, scheduler or Execution semantics.

### Known cross-cutting debt, not a bridge-local blocker

Structural provenance strings/source references are evidence and consistency markers, not cryptographic or capability-authenticity tokens. A caller being able to construct the same string is not equivalent to gaining Authority or Execution; downstream trusted layers must continue to combine provenance with fresh exact live evidence.

Correlation is also fragmented across some compound cognitive operations because frozen subsystem compositions may create their own operation roots. This is architectural debt to address deliberately later; do not silently introduce unrelated hidden context or widen one cognitive bridge PR into a cross-cutting correlation redesign.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

## Deferred roadmap

After Controlled Agent Coordination is independently implemented and frozen:

- bounded multi-agent behavior only through frozen coordination governance;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

All future layers must preserve exact provenance, explicit ownership, observability, fail-closed Authority, privacy, rollback/safety and composition isolation.
