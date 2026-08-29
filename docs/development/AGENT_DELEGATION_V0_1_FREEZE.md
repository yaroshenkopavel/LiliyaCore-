# Agent Delegation Foundation v0.1 — Freeze Contract

Status: **FROZEN** after structural, composition-ownership and readiness verification.

Verified code baseline before this documentation checkpoint: `1ae28519572cd3b969b729ac164af9061c98763b`.

## Purpose

Agent Delegation Foundation v0.1 records an exact structural relationship between one parent Agent generation and one child Agent generation. It is deliberately data-only. Delegation does not grant capability, permission, Authority, execution rights, scheduling rights, initiative rights, tool access or any other runtime power.

## Frozen model

`exact parent AgentId+AgentGeneration + exact child AgentId+AgentGeneration + private purpose + createdAt → AgentDelegationRecord → exact AgentDelegationGeneration ownership`

Mandatory invariant:

`Delegation != Capability != Authority != Execution`.

## Verified guarantees

- exact parent and child Agent ID+generation references are preserved as structural data;
- self-delegation rejects by default;
- delegation purpose is private and redacted from rendering/lifecycle observability;
- duplicate delegation identity rejects without replacement;
- exact-generation ownership is stale/ABA-safe;
- repeated removal fails closed;
- same delegation ID is isolated across independent compositions;
- deterministic detached snapshots are provided;
- install root → remove child correlation lineage is explicit;
- raw mutable delegation store remains private behind `AgentDelegationComposition`;
- structural composition has no dependency on `AgentComposition` or `ControlledAgentLifecycle` and therefore performs no hidden endpoint liveness validation;
- data/ownership APIs contain no Capability, Authority, permission, Execution, scheduler, tool, initiative, spawn or replication semantics;
- no Agent initiative is created by this foundation;
- no scheduler, recurring loop, delegation engine, multi-agent runtime, Authority call, Execution call or tool/device access exists here.

## CI history

- PR #151 `Agent Delegation v0.1: Structural Delegation Foundation` — exact head `8f65360b5a7dd07b4b742b118155b48c5653b6a3`, Core CI #968 GREEN, merge/new main `f3e00d87d26d106e457fd6cc870afaf914d0c658`.
- PR #152 `Agent Delegation v0.1: Composition Ownership` — exact head `e7bfb8f0481e9efb62372ce380d70989966859ca`, Core CI #973 GREEN, merge/new main `0a68a56d8d5f0fdeddeae795f1f40432b3a247e6`.
- PR #153 `Agent Delegation v0.1: Readiness Contracts` — initial CI #977 RED because the readiness reflection test assumed an exact JVM method count; production code remained GREEN. The test was corrected to verify required ownership accessors and absence of power methods without depending on Kotlin synthetic/JVM method shape. Final exact head `68e143ff86850775a94f9512dbffca1dd8fb8ad5`, Core CI #981 GREEN, merge/new main `1ae28519572cd3b969b729ac164af9061c98763b`.

## Explicit non-goals

This v0.1 foundation does not prove that either delegation endpoint is currently live or ACTIVE. It intentionally does not convert a delegation relation into work, Autonomy, permission or execution.

Those checks belong to the next separately governed layer.

## Next controlled stage

**Controlled Agent Delegation Bridge v0.1**.

First direction:

`exact Delegation ID+generation → fresh exact parent Agent validation + ACTIVE lifecycle → fresh exact child Agent validation + ACTIVE lifecycle → caller-declared bounded initiative data → ordinary AutonomyProposal`

Required properties for the first controlled bridge:

- revalidate exact delegation generation immediately before use;
- revalidate exact parent and child Agent generations immediately before downstream work creation;
- require ACTIVE lifecycle for both exact endpoints;
- create zero downstream writes for missing/stale/CANCELLED/STOPPED parent or child;
- never inherit or amplify capability, Authority, permission or execution rights from the parent;
- delegation relation itself remains structural evidence only;
- downstream work must still use frozen Controlled Agent Initiative / Controlled Autonomy / Authority / Execution boundaries;
- no scheduler, recursive delegation, self-spawn or multi-agent runtime in the first controlled bridge.

Multi-agent coordination remains deferred until Controlled Agent Delegation is separately implemented, audited and frozen.
