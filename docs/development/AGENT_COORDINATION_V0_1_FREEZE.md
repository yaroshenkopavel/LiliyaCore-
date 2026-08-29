# Agent Coordination Foundation v0.1 — Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline: `78d06f0226105314a45f01833a12029fdabe8a5b`.

Implemented through:

- PR #164 `Agent Coordination v0.1: Structural Coordination Foundation` — exact head `411fc9572b18dcf7dac71fc5a17661087f1ec099`, Core CI #1035 GREEN;
- PR #165 `Agent Coordination v0.1: Composition Ownership and Readiness` — exact head `3c400f34968e58eaef01929378ed0ef9c3ced32e`, Core CI #1037 GREEN, merge/new main `78d06f0226105314a45f01833a12029fdabe8a5b`.

## Frozen structural direction

`Coordination identity + exact participant Agent generations + private purpose + createdAt → AgentCoordinationRecord → exact AgentCoordinationGeneration ownership`

Mandatory invariant:

`Coordination != Capability != Authority != Execution`.

## Frozen guarantees

- coordination ID is explicit and non-blank;
- generation is positive and exact;
- every participant is an exact `(AgentId, AgentGeneration)` structural reference;
- at least two participants are required;
- duplicate exact participant references are rejected;
- multiple generations of the same Agent ID in one coordination are rejected;
- participant ordering is normalized deterministically;
- coordination purpose is private and redacted from rendering and lifecycle observability;
- duplicate coordination IDs reject without replacement;
- stale/ABA ownership cannot remove a replacement;
- removal is one-shot and fail-closed;
- concurrent same-ID registration has one winner per store;
- snapshots are deterministic detached views;
- `AgentCoordinationComposition` keeps the mutable store private and exposes exact ownership only;
- same coordination ID is independently owned across compositions;
- install root → remove child correlation lineage is explicit;
- structural composition has no Agent registry, Agent lifecycle or Agent delegation dependency;
- coordination data/ownership expose no Capability, Authority, permission, Execution, scheduler, fan-out, voting, consensus, delegation, Autonomy, tool or self-spawn semantics.

## Explicit non-goals of v0.1

This frozen foundation does **not**:

- prove that participants are currently live;
- prove that participant lifecycle is ACTIVE;
- create Agent delegation relations;
- create Autonomy initiatives or attempts;
- schedule or fan out work;
- perform voting, quorum, consensus or leader election;
- call Authority or Execution;
- grant capability or permission;
- implement multi-agent runtime behavior.

Structural participant references are provenance data only and may be stale until a later controlled bridge revalidates them.

## Required next boundary

The next stage is **Controlled Agent Coordination v0.1**.

The first slice must remain evidence-only:

`exact Coordination ID+generation → fresh coordination lookup → fresh exact participant Agent-generation validation → fresh ACTIVE lifecycle validation → structural readiness evidence`

Required first-slice guarantees:

- every participant is revalidated by exact Agent ID+generation;
- every participant must have exact ACTIVE lifecycle;
- stale/removed/replaced/terminal participant fails closed;
- readiness evidence contains structural provenance only and is not permission;
- private coordination purpose does not enter readiness evidence or observability;
- no delegation creation, Autonomy creation, attempt claim, scheduler, fan-out, voting/consensus, Authority or Execution.

Only after this evidence-only boundary is independently GREEN/audited may later slices consider bounded coordination behavior.
