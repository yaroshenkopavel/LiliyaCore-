# Controlled Agent Lifecycle v0.1 — Freeze Contract

Status: **FROZEN**

Verified code baseline before documentation merge: `8cdcc214d3e4fc60620b727f50a52a89d085e5e6`.

## Boundary

`Agent Identity != Agent Lifecycle != Autonomy != Deliberation != Authority != Execution`

Controlled Agent Lifecycle v0.1 introduces explicit exact-generation lifecycle governance for a single Agent. It does not introduce a scheduler, recurring loop, delegation engine, multi-agent coordination, Authority or Execution power.

## Model

Lifecycle is keyed by exact `(AgentId, AgentGeneration)` and has explicit states:

- `ACTIVE`
- `CANCELLED`
- `STOPPED`

Lifecycle is separate from Agent registry presence. An Agent record existing does not imply an ACTIVE lifecycle, and a terminal lifecycle state remains addressable for its exact old generation after the Agent record is removed.

## Frozen guarantees

- lifecycle activation requires an exact live Agent ID+generation;
- lifecycle is absent until explicitly activated;
- activation creates exact-generation `ACTIVE` state only;
- lifecycle ownership is exact-generation scoped;
- `CANCELLED` and `STOPPED` are terminal in v0.1;
- repeated or competing terminal transitions fail closed;
- stale lifecycle ownership cannot mutate a replacement Agent generation;
- replacement Agent generation does not inherit or reactivate stale lifecycle state;
- lifecycle state grants no capability, permission, Authority or execution right;
- lifecycle API contains no scheduler, tool, self-spawn, delegation or multi-agent semantics;
- private Agent role/purpose remain outside lifecycle observability.

## Mandatory integration gates

Exact ACTIVE lifecycle is revalidated at every controlled Agent boundary that can advance work:

1. before Agent initiative creates an `AutonomyProposal`;
2. before Agent initiative claims a bounded Autonomy attempt;
3. immediately before final Agent delegation into frozen `ControlledAutonomyExecution`.

Therefore:

- missing lifecycle → zero new Agent-originated Autonomy writes;
- `CANCELLED`/`STOPPED` lifecycle → zero new Agent-originated Autonomy writes;
- missing/terminal lifecycle → zero new Agent-originated attempt claims;
- cancellation/stop after deliberation creation → zero downstream execution delegate calls;
- stale Agent/lifecycle generation remains isolated from replacement generation.

These gates do not replace frozen Autonomy, Authority or Execution checks. They add a single-Agent lifecycle barrier before the already-governed downstream chain.

## Non-goals

v0.1 does not provide:

- scheduling or timers;
- recurring/background Agent loops;
- pause/resume semantics;
- automatic restart;
- delegation or parent/child Agent relationships;
- multi-agent coordination;
- self-spawning or replication;
- capability inheritance/amplification;
- direct tool/device access;
- direct Authority or Execution access;
- persistence/crash recovery of lifecycle state.

## Verified implementation history

- PR #147 `Controlled Agent Lifecycle v0.1: Exact Lifecycle Foundation`
  - exact head `8da34225c3e5f1ee12a0402457a34d42713467ba`
  - Core CI #945 GREEN
  - merge/new main `9c0a71e079ca0da819c2ffe61ead976852b6e714`
- PR #148 `Controlled Agent Lifecycle v0.1: Integrate Lifecycle Gates`
  - exact head `66b68aaf5c1133c1179658d402510b6c86a9bab5`
  - Core CI #956 GREEN
  - merge/new main `8e3312b8f620a38ccf64e143dacd91f38d41de63`
- PR #149 `Controlled Agent Lifecycle v0.1: Readiness Contracts`
  - exact head `1c65e3203f6b876790f34f7fcbd659b0d4a08e9c`
  - Core CI #960 GREEN
  - merge/new main `8cdcc214d3e4fc60620b727f50a52a89d085e5e6`

## Next architecture stage

The next stage is **Agent Delegation Foundation v0.1**, beginning structural/data-only.

Initial direction:

`exact parent Agent generation + exact child Agent generation + explicit bounded delegation relation → exact delegation generation ownership`

Mandatory next-stage rules:

- delegation metadata is not capability or permission;
- no capability amplification;
- both endpoints use exact Agent generations;
- stale/replaced parent or child provenance fails closed;
- delegation does not schedule, create initiatives, call Authority or execute;
- lifecycle liveness/ACTIVE state must later be revalidated by any controlled delegation-to-work bridge;
- no multi-agent runtime until delegation foundation and single-Agent lifecycle are separately frozen.
