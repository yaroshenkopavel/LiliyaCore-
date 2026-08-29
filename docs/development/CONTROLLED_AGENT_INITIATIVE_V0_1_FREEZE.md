# Controlled Agent Initiative v0.1 — FROZEN

Freeze date: 2026-08-29

Verified code baseline before this documentation checkpoint: `00bb67b33293b7c6d7203fb2d1f22bfa1caed84e`.

## Boundary

Controlled Agent Initiative v0.1 connects a live exact Agent identity to the already frozen bounded Autonomy path without turning Agent identity, role or origin into permission.

Frozen direction:

`exact live Agent → trusted Agent provenance → finite-budget AutonomyProposal → fresh Agent check before attempt claim → frozen bounded Autonomy attempt → deliberation/cognitive chain → fresh Agent check immediately before Controlled Autonomy execution → frozen Controlled Autonomy → fresh Authority → Execution`

Mandatory invariant:

`Agent != Autonomy != Deliberation != Decision != Authority != Execution`.

## Verified implementation slices

- PR #142 — Agent→Autonomy bridge, exact head `80da71fa3c0196aed93108ebfe5f38d9c3bd03f2`, Core CI #924 GREEN, merge/new main `7d872b39246e72e859e43dfde75e7d316fe9d1b6`.
- PR #143 — exact Agent initiative attempt gate, exact head `56c3c4260df3876e5d66960d48a8bbc24567ec33`, Core CI #929 GREEN, merge/new main `4c07fcea94ad774b5cf01015bd58448d95d2794e`.
- PR #144 — final Agent execution guard, exact head `75048c925a328eaedd2a6d835a33812e8af17bf0`, Core CI #934 GREEN, merge/new main `7e893dd6703f673045f14e4294ede91050519b91`.
- PR #145 — readiness contracts, exact head `c134207776c9f4cd8707a2d37afc25427346165c`, Core CI #938 GREEN, merge/new main `00bb67b33293b7c6d7203fb2d1f22bfa1caed84e`.

## Frozen guarantees

### Agent→Autonomy creation

- exact Agent ID+generation is live-validated immediately before Autonomy installation;
- caller cannot forge Agent provenance in the generated Autonomy proposal;
- bridge constructs structural origin `agent:<id>@<generation>`;
- generated Autonomy proposal uses the ordinary frozen finite `AutonomyBudget`;
- Agent role/purpose are not silently copied into initiative objective/trigger;
- stale, removed or replaced Agent provenance creates zero Autonomy writes;
- duplicate Autonomy proposal IDs reject without replacement.

### Attempt boundary

- exact Agent liveness is checked again immediately before the first bounded Autonomy attempt claim;
- exact Autonomy generation and exact trusted Agent origin are required;
- removed/stale Agent or mismatched provenance causes zero Autonomy attempt claims;
- attempt count/budget remains owned by frozen `ControlledAutonomyDeliberationGate`;
- Agent layer does not create a scheduler or retry loop.

### Final side-effect boundary

- final Agent identity is derived from the exact live deliberation→Autonomy trusted provenance, not caller-supplied side data;
- exact live Agent generation is revalidated immediately before delegation to `ControlledAutonomyExecution`;
- Agent removal or stale replacement after attempt/deliberation creation causes zero downstream execution calls;
- stale deliberation generation also fails before downstream execution;
- after Agent validation, frozen Controlled Autonomy still independently revalidates Autonomy/cognitive/orchestration provenance and uses fresh Authority/Execution boundaries;
- Agent never authorizes, grants or executes by itself.

### Privacy and observability

- Agent role/purpose remain private structural payload;
- private role/purpose are absent from Agent initiative observability;
- Agent data/readiness APIs contain no permission/capability/scheduler/self-spawn/tool/delegation semantics;
- structural IDs/generations may be observed; private cognitive payload remains governed by downstream frozen contracts.

## Explicit non-goals

Controlled Agent Initiative v0.1 does **not** provide:

- an Agent scheduler or background runner;
- recurring autonomous Agent loops;
- self-spawning or recursive Agent creation;
- parent/child Agent delegation;
- multi-agent coordination;
- Agent-owned capability grants;
- direct Authority calls from Agent identity;
- direct Execution/tool/device access;
- hidden Memory/Knowledge mutation;
- durable permission from Agent role, Agent generation or prior initiative evidence.

## Freeze rule

This baseline must not be reopened merely to add convenience APIs or Agent power. Reopen only for a demonstrated correctness, safety, security or privacy defect, with focused contracts, exact-head Core CI GREEN and a fresh audit.

## Next stage

The next Agent architecture stage is **Controlled Agent Lifecycle v0.1**.

It must define explicit lifecycle/cancellation state separately from Agent identity and separately from Autonomy cancellation. The first slice must remain non-scheduling and non-executing.

Required properties include:

- exact Agent generation-scoped lifecycle ownership;
- explicit active/cancelled/stopped state rather than inferring lifecycle from registry presence alone;
- stale lifecycle handles cannot affect replacement Agent generations;
- lifecycle cancellation must fail closed before new initiative creation/attempts and at final Agent execution guard;
- no scheduler or recurring loop;
- no delegation or multi-agent coordination yet;
- no Authority/Execution bypass.

Delegation/coordination remains later than single-Agent lifecycle governance.
