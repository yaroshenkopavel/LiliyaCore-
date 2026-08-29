# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Current verified code `main`: `00bb67b33293b7c6d7203fb2d1f22bfa1caed84e`.

Latest verified milestones:

- PR #141 `Agents v0.1: Freeze and Journal Checkpoint` — exact docs head `1d068220f6f6662724b60a9e2c1bba173f0bbb13`, Core CI #919 GREEN, merge/new main `5b0cdba2c7f2fb6f62aab92fba93cad28caecebb`;
- PR #142 `Controlled Agent Initiative v0.1: Agent to Autonomy Bridge` — exact head `80da71fa3c0196aed93108ebfe5f38d9c3bd03f2`, Core CI #924 GREEN, merge/new main `7d872b39246e72e859e43dfde75e7d316fe9d1b6`;
- PR #143 `Controlled Agent Initiative v0.1: Exact Attempt Gate` — exact head `56c3c4260df3876e5d66960d48a8bbc24567ec33`, Core CI #929 GREEN, merge/new main `4c07fcea94ad774b5cf01015bd58448d95d2794e`;
- PR #144 `Controlled Agent Initiative v0.1: Final Execution Guard` — exact head `75048c925a328eaedd2a6d835a33812e8af17bf0`, Core CI #934 GREEN, merge/new main `7e893dd6703f673045f14e4294ede91050519b91`;
- PR #145 `Controlled Agent Initiative v0.1: Readiness Contracts` — exact head `c134207776c9f4cd8707a2d37afc25427346165c`, Core CI #938 GREEN, merge/new main `00bb67b33293b7c6d7203fb2d1f22bfa1caed84e`.

## Frozen subsystem status

The following v0.1 boundaries are frozen:

- Core Foundation;
- Capability & Authority;
- Execution;
- Memory;
- Knowledge;
- Identity / Self;
- Trust / Security;
- Personality;
- Reflection;
- Learning Candidate / Decision / Policy / Application Intent / Controlled Application / Consolidation;
- Planning;
- Reasoning;
- Decision;
- Orchestration Intent;
- Controlled Orchestration;
- Autonomy Foundation;
- Controlled Autonomy Deliberation;
- Agents Foundation;
- Controlled Agent Initiative **pending this documentation-checkpoint merge**.

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts, not implemented runtime subsystems.

## Current control chain

`Agent → bounded Autonomy initiative → bounded attempt → Deliberation → Planning → Reasoning → Decision → Orchestration Intent → Agent/Autonomy final guards → fresh Authority → Execution`

Important: the arrow means controlled provenance flow, not permission propagation.

Mandatory invariants:

`Agent != Autonomy != Deliberation != Decision != Authority != Execution`

`Decision != Orchestration Intent != Authorization != Execution`

## Controlled Agent Initiative v0.1

Frozen direction:

`exact live Agent → trusted Agent provenance → finite-budget AutonomyProposal → fresh Agent check before attempt claim → frozen bounded Autonomy attempt/deliberation chain → fresh Agent check immediately before frozen Controlled Autonomy execution → fresh Authority → Execution`

Key guarantees:

- exact Agent ID+generation is revalidated at initiative creation;
- caller cannot forge the Agent provenance used by the bridge;
- generated initiative is an ordinary finite-budget `AutonomyProposal`;
- role/purpose are not implicit objective, permission or capability;
- stale/removed/replaced Agent causes zero Autonomy writes at creation;
- fresh Agent liveness/provenance is checked again before bounded attempt claim;
- removed/stale Agent or mismatched Agent origin causes zero attempt claims;
- attempt budget remains owned by frozen Autonomy governance;
- final Agent identity is derived from live deliberation→Autonomy provenance, not separate caller data;
- Agent liveness is checked again immediately before frozen Controlled Autonomy execution;
- late Agent removal or replacement causes zero downstream execution calls;
- Agent never grants Authority and never executes directly;
- no scheduler, background Agent loop, self-spawn, delegation engine or multi-agent coordination exists in v0.1.

Canonical contract: `CONTROLLED_AGENT_INITIATIVE_V0_1_FREEZE.md`.

## Current next action

The next architecture stage is **Controlled Agent Lifecycle v0.1**.

First direction:

`exact Agent ID+generation → explicit generation-scoped lifecycle state → controlled active/cancelled/stopped transitions`

The first lifecycle slice must remain non-scheduling and non-executing.

Required guarantees:

- lifecycle state is explicit and separate from registry existence;
- lifecycle ownership is exact Agent-generation scoped;
- stale lifecycle handles cannot affect a replacement Agent generation;
- cancellation/stop is idempotent or explicitly fail-closed by contract;
- cancelled/stopped Agents cannot create new initiatives or claim new attempts;
- final Agent execution guard must reject cancelled/stopped Agent lifecycle before downstream execution;
- no scheduler, recurring loop, delegation or multi-agent behavior yet;
- no Authority or Execution bypass.

Delegation/coordination comes only after single-Agent lifecycle governance is implemented, audited and frozen.

Persistent encrypted storage, Android integration, Update runtime and Security/Licensing runtime remain separate later stages.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge with expected head SHA → journal checkpoint`

No intentional direct-to-main development.
