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

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning foundations, Planning, Reasoning, Decision, Orchestration Intent, Controlled Orchestration, Autonomy Foundation, Controlled Autonomy Deliberation, Agents Foundation, Controlled Agent Initiative, Controlled Agent Lifecycle, Agent Delegation Foundation, Controlled Agent Delegation, Agent Coordination Foundation and **Controlled Agent Coordination v0.1** are frozen.

Canonical freeze documents are the detailed source for each frozen boundary.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy is a governed initiative layer around this chain. Agents add exact actor identity/lifecycle governance. Delegation adds exact parent/child provenance. Coordination adds exact participant-set provenance plus controlled multi-participant transaction governance. None of these layers propagates implicit permission.

## Decision / Orchestration — FROZEN

`Decision → non-executing OrchestrationIntent → exact live preflight → trusted action policy → fresh Authority → frozen Execution → fresh Authority → executor`

Mandatory invariant:

`Decision != Orchestration Intent != Authorization != Execution`.

## Autonomy — FROZEN

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

## Controlled Agent Coordination v0.1 — FROZEN

Frozen governed path:

`exact live Coordination → exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated multi-participant initiative → transactional bounded attempts → exact coordination↔attempt binding → compensated exact deliberation requests → exact live deliberation preflight → ordinary frozen Planning → ordinary frozen Reasoning → ordinary frozen Decision → ordinary frozen Orchestration Intent → final coordinated execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution`

Mandatory invariants:

`Coordination Readiness != Work != Permission != Authority != Execution`

`Coordination Transaction != Permission != Authority != Execution`

`Coordination Attempt Transaction != Attempt Binding != Permission != Authority != Execution`

`Coordinated Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Permission != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`.

Hard guarantees:

- exact coordination generation and exact participant Agent generations are freshly live-validated at controlled readiness boundaries;
- participant lifecycle must be exact ACTIVE where readiness requires it;
- coordination work and attempt binding ownership are exact-generation and stale/ABA-safe;
- multi-participant initiative and deliberation transactions are compensated if post-write governance changes;
- bounded attempt accounting remains delegated to frozen Agent/Autonomy gates;
- exact coordination-attempt provenance is committed before coordinated deliberation/cognitive work proceeds;
- live deliberation preflight re-derives the exact Autonomy attempt and rechecks exact attempt binding;
- coordinated Planning, Reasoning, Decision and Orchestration write only ordinary frozen subsystem data;
- each write-capable coordinated cognitive bridge follows `fresh preflight → exact source validation → write → fresh preflight → exact source revalidation`;
- any post-write governance/provenance change compensates only the exact downstream generation created by the operation;
- stale ownership cannot remove a newer replacement generation;
- failed exact removal is CRITICAL only if the exact created generation remains live and cannot be removed; a newer replacement generation is preserved;
- the final coordinated execution guard repeats fresh readiness and full Planning → Reasoning → Decision → Orchestration validation immediately before delegation;
- stale coordination/governance/cognitive/orchestration state means zero downstream Authority/executor calls;
- the final guard delegates only to existing frozen `ControlledOrchestrationExecution`;
- fresh Authority and frozen Execution remain the only permission/side-effect path;
- structural provenance strings/source references are evidence/consistency markers only, not cryptographic authenticity, capability, permission or Authority tokens;
- private coordination purpose, deliberation objective, Planning goal/steps, Reasoning premise/analysis/conclusion, Decision options/rationale and Orchestration description remain outside coordination-bridge observability;
- no coordination-specific Capability grant, Authority grant, scheduler, retry loop, executor, implicit fan-out, voting, quorum, consensus or leader-election semantics.

Canonical contract: `CONTROLLED_AGENT_COORDINATION_V0_1_FREEZE.md`.

## Cross-cutting debt that does not weaken frozen boundaries

- Structural provenance strings are not cryptographic provenance; future authenticity requirements need an explicit cryptographic design rather than pretending current strings are credentials.
- Some compound cognitive operations do not share one correlation root across every frozen subsystem boundary; fix deliberately rather than introducing hidden global context.
- In-memory ownership/idempotency is not crash-durable exactly-once across process restart; persistence requires an explicit transaction/outcome store.

## Repository continuity

Active repository: `yaroshenkopavel/LiliyaCore-`.

The prior `Vikrot123/LiliyaCore` repository is migration history/backup only. GitHub service metadata identities such as old PR numbers remain historical references.

Active source of truth is current `main`, executable contracts, canonical freeze documents and current CI evidence.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

License != Authority; device binding uses cryptographic enrollment/Keystore rather than HWID-derived trust; protected model/runtime keys and user cognitive-data keys remain separate domains.

## Deferred roadmap

After Controlled Agent Coordination v0.1 freeze:

- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox enrollment;
- protected model package/streaming loader;
- licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification.

Any bounded multi-agent runtime behavior must be built only through the frozen coordination governance and must not mutate the frozen coordination foundation into a scheduler/consensus/permission subsystem.

All future layers must preserve exact provenance, explicit ownership, observability, fail-closed Authority, privacy, rollback/safety and composition isolation.