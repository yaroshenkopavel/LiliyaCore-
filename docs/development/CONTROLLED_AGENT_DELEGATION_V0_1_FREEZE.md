# Controlled Agent Delegation v0.1 — Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline before this documentation checkpoint:

`cc747b83fe58da3d8abf68e05bc169d8a5d6e1d3`

## Purpose

Controlled Agent Delegation v0.1 allows one exact structural Agent delegation relation to originate bounded child-Agent Autonomy work without turning delegation into capability, permission, Authority, scheduling or execution power.

The layer deliberately reuses already-frozen Agent, Autonomy, Orchestration, Authority and Execution boundaries rather than introducing a parallel execution path.

## Frozen path

`exact structural Delegation → fresh parent/child ACTIVE preflight → compensated child Agent initiative + exact delegation↔Autonomy binding → delegated attempt gate → Autonomy deliberation/cognitive chain → final delegated execution guard → frozen ControlledAgentExecution → frozen Controlled Autonomy/Orchestration → fresh Authority → Execution`

Mandatory invariant:

`Delegation != Initiative != Attempt Evidence != Permission != Authority != Execution`

## Exact live preflight

For one exact `AgentDelegationId + AgentDelegationGeneration`, preflight requires:

- exact live delegation generation;
- exact live parent Agent generation;
- parent lifecycle `ACTIVE`;
- exact live child Agent generation;
- child lifecycle `ACTIVE`.

The result is structural readiness evidence only. It performs no downstream write and grants no permission.

## Exact delegated-work binding

Delegated work is represented separately from frozen `AutonomyOrigin` by an exact structural binding:

`exact Delegation generation + exact child Agent generation + exact Autonomy generation`.

The exact Autonomy reference is the unique binding key. One exact Autonomy generation cannot be bound to multiple delegation relations.

The binding itself grants no permission and performs no work.

## Compensated delegated initiative transaction

Creation follows this order:

1. fresh exact delegation/parent/child ACTIVE preflight;
2. ordinary child Agent initiative creation through frozen `ControlledAgentInitiative`;
3. second fresh exact delegation preflight to close the TOCTOU window around the Autonomy write;
4. exact evidence equality check;
5. exact delegation↔Autonomy binding install;
6. only then return `Created`.

If step 3–5 fails after Autonomy creation, the exact newly-created Autonomy ownership must be removed before a normal rejection can be returned.

If compensation itself cannot remove that exact Autonomy ownership, the bridge returns explicit `Failed` and emits CRITICAL observability. Compensation failure is never hidden as an ordinary rejection.

Successful callers receive one composite ownership plus structural receipt. Raw mutable Autonomy ownership and binding ownership are not exposed independently.

Composite removal is fail-closed:

- remove exact Autonomy first;
- only then remove its exact structural binding;
- if Autonomy removal fails, binding remains intact;
- if binding cleanup fails after Autonomy removal, the remaining cleanup can be retried and the failure is CRITICAL-observable.

## Delegated attempt governance

Before one bounded child-Agent attempt claim, the gate checks:

- exact live delegation↔Autonomy binding;
- exact delegation generation;
- exact parent/child Agent generations;
- parent/child `ACTIVE` lifecycle.

Actual attempt accounting remains owned by frozen Agent/Autonomy governance.

Immediately after the claim, the binding and delegation/lifecycle are checked again.

If governance changes in the preflight→claim race window:

- the claim is never returned to the caller;
- the exact Autonomy generation is cancelled;
- downstream validation of the claimed attempt fails closed.

A race may consume at most one already-bounded attempt, but it cannot create reusable downstream deliberation evidence.

## Final delegated execution guard

The final guard does not accept an arbitrary delegation ID alongside an execution request.

It instead:

1. reads the exact live deliberation request;
2. verifies exact deliberation generation;
3. derives exact Autonomy ID+generation from that live deliberation request;
4. resolves the exact delegation↔Autonomy binding from that derived reference;
5. freshly revalidates exact delegation generation and parent/child ACTIVE lifecycle;
6. verifies preflight evidence matches the exact binding;
7. re-reads the binding immediately before downstream delegation;
8. delegates only to frozen `ControlledAgentExecution`.

This prevents attaching a live delegation to an unrelated execution chain.

Stale/missing deliberation, binding, delegation, parent/child generation or terminal lifecycle causes zero downstream `ControlledAgentExecution` calls.

## Authority and execution boundary

Controlled Agent Delegation:

- defines no capability grant;
- performs no Authority call;
- creates no `ExecutionRequest` itself;
- calls no executor directly;
- does not inherit or amplify parent permission into the child;
- does not turn lifecycle or delegation readiness into durable permission.

The existing frozen downstream chain retains fresh Authority and Execution validation.

## Privacy

Private delegation purpose is not copied into:

- child Autonomy objective/trigger unless separately caller-declared as initiative content;
- readiness evidence;
- binding metadata beyond structural IDs/generations;
- attempt observability;
- final execution guard observability.

## Explicitly absent from v0.1

Controlled Agent Delegation v0.1 contains no:

- scheduler or recurring Agent loop;
- self-spawn/self-replication;
- recursive automatic delegation;
- fan-out work dispatch;
- voting or consensus runtime;
- multi-agent parallel execution runtime;
- tool/device access outside frozen Execution;
- Capability/Authority amplification.

## Verified implementation milestones

- PR #155 — Exact Live Preflight, exact head `7967f2e95a2701a44220d99078d7a34d82e19e94`, Core CI #992 GREEN, merge `6ab6f1bdd46a17af775ab0bc5513c6cc8befa915`.
- PR #156 — duplicate preflight implementation, intentionally closed unmerged after #155 superseded it.
- PR #157 — Exact Delegated Work Binding, exact head `d691fdc98079b2a2232e7cb30d253dbad0ab268f`, Core CI #999 GREEN, merge `3bcf3f12269e6c98b9ac4a0f90dee328449b17a9`.
- PR #158 — Delegated Work Binding Ownership, exact head `d9107753adbfbb28765799d96f0b059af7a43f2e`, Core CI #1004 GREEN, merge `7e0fba5e876cc0f7849e40b63a9d8d16f22f422e`.
- PR #159 — Compensated Delegated Initiative, hardened exact head `4cd4238ade81b0816670091d607c8052e3aca4cd`, Core CI #1013 GREEN, merge `73414c2fcf0a4e0ae1ea14dd59355cd1c9375649`.
- PR #160 — Delegated Attempt Gate, exact head `24fac0d7b0035ca96bc2a74d15bdc520241b187f`, Core CI #1018 GREEN, merge `2853e576d14588911cb9b1d21518adfc72ba6318`.
- PR #161 — Final Execution Guard, exact head `1867c9166051dd7e9de0b48628e8a63c7d95d097`, Core CI #1023 GREEN, merge `52705124ddc0f3772100e525e99f51217837b4b0`.
- PR #162 — Readiness Contracts, exact head `a3c947dc661b078d9d594977ef59ef82c04c5a98`, Core CI #1027 GREEN, merge/current verified code main `cc747b83fe58da3d8abf68e05bc169d8a5d6e1d3`.

## Next architecture stage

After this freeze checkpoint, the next stage is **Agent Coordination Foundation v0.1**.

The first coordination slice must be structural/data-only: exact participant Agent generations, explicit coordination identity/ownership and private coordination purpose. It must not introduce scheduler, fan-out, voting, consensus, work dispatch, Authority, Execution or multi-agent runtime behavior.

Controlled coordination behavior comes only after the structural foundation is independently implemented, audited and frozen.
