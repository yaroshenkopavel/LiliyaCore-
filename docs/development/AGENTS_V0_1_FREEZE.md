# Agents Foundation v0.1 — FREEZE

Freeze date: 2026-08-29

Verified code baseline before this documentation checkpoint: `2fbbae4326b3ae45fe6094344498c6916e9bebf2`.

## Purpose

Agents Foundation v0.1 introduces a bounded structural identity/ownership record for future governed actors without introducing Agent runtime behavior, scheduling, delegation power or execution.

Frozen structural direction:

`explicit Agent identity + explicit origin + caller-declared private role/purpose + createdAt → AgentRecord → exact AgentGeneration ownership`

Mandatory invariant:

`Agent != Autonomy != Decision != Authority != Execution`.

## Frozen model

`AgentRecord` contains:

- exact `AgentId`;
- explicit `AgentOrigin`;
- caller-declared private `role`;
- caller-declared private `purpose`;
- `createdAt`.

Supported origins:

- `AgentOrigin.Declared(sourceId, sourceReference?)` for explicit caller-declared provenance;
- `AgentOrigin.Autonomy(proposalId, generation)` for exact structural Autonomy provenance as data only.

An origin reference is provenance, not live validation, delegation, permission or execution authority.

## Ownership guarantees

- every successful registration receives a positive exact `AgentGeneration`;
- duplicate Agent IDs reject without replacement;
- stale/ABA ownership cannot remove a replacement;
- removal is one-shot and repeated removal fails closed;
- same-ID concurrent registration has exactly one winner per store;
- `AgentComposition` owns a private `AgentStore` and exposes only controlled `AgentOwnership`;
- same Agent ID is independently owned across compositions;
- snapshots are deterministic detached list views ordered by `createdAt` then Agent ID;
- install observability begins at a root context;
- owned removal uses a child context with explicit parent correlation.

## Privacy and observability

- role and purpose are private payload;
- role and purpose are redacted from `AgentRecord.toString()`;
- role and purpose do not enter lifecycle log messages or metadata;
- lifecycle metadata is structural only: Agent identity, generation, origin type/reference and timestamp;
- exact Autonomy ID+generation may appear as structural provenance when that origin is used.

## Governance guarantees

Agent data API and lifecycle observability contain no semantics for:

- Authority or approval;
- capabilities or permissions;
- Execution or executor access;
- scheduling/background work;
- self-spawn or replication;
- tools/device access;
- delegation runtime or capability amplification.

A role or purpose never grants permission.

Exact Autonomy provenance never means the Autonomy proposal is still live and never grants permission to act.

## Explicit non-features

Agents Foundation v0.1 does **not** implement:

- Agent runtime loops;
- autonomous background execution;
- scheduler/queue ownership;
- self-spawning or recursive Agent creation;
- Agent-to-Agent delegation;
- multi-agent coordination;
- direct Agent→Autonomy execution attempts;
- direct Agent→Authority calls;
- direct Agent→Execution calls;
- direct tool/device/browser/shell access;
- Memory/Knowledge mutation;
- durable permission or capability assignment.

## Verified implementation history

- PR #139 `Agents v0.1: Structural Agent Foundation` — exact head `2b1ebb7b8a569c96e319c441b717ae4b3b1e89e1`, Core CI #911 GREEN, merge/new main `ea2b964efc443ae9c9b0d678129a834eaf33ca72`.
- PR #140 `Agents v0.1: Composition Ownership and Readiness` — exact head `a5f31ffccffafd812ade4ecfbeb1637114f0248d`, Core CI #917 GREEN, merge/new main `2fbbae4326b3ae45fe6094344498c6916e9bebf2`.

## Reopen rule

Reopen Agents Foundation v0.1 only for a demonstrated correctness, security, privacy, lifecycle or ownership defect, with a focused failing contract first and the normal exact-head CI/audit workflow.

## Next architecture stage

The next stage is **Controlled Agent Initiative v0.1**, beginning with a narrow Agent→Autonomy bridge.

Required first direction:

`exact live Agent ID+generation → fresh Agent preflight → caller-declared bounded initiative data → ordinary AutonomyProposal`

The bridge must:

- freshly validate exact Agent ID+generation immediately before creating Autonomy data;
- construct trusted structural Agent provenance for the Autonomy proposal rather than trusting caller-forged origin;
- create only an ordinary bounded `AutonomyProposal`;
- copy no private Agent role/purpose into observability or implicit permission fields;
- perform no attempt claim, scheduling, Decision, Authority or Execution;
- create zero Autonomy writes from stale/removed/replaced Agent provenance;
- preserve the frozen downstream path for all actual work:
  `Autonomy → Deliberation → Planning → Reasoning → Decision → Orchestration → Authority → Execution`.

Agent lifecycle/cancellation, delegation, coordination and multi-agent behavior remain separate later slices and must not be folded into the first bridge.
