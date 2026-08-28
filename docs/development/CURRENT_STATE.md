# CURRENT STATE

Last journal update: 2026-08-28

## Main baseline

`main` implementation SHA: `1180730981a8ffb729887312847d56c835929a55`

This is the verified implementation checkpoint after PR #25 `Authority v0.1: Strict Delegation Expiry`.

Status:

- Core Foundation v0.1: FROZEN.
- Authority policy layer v0.1: FROZEN semantics retained and hardened.
- Capability Registry v0.1: MERGED.
- Broader Capability & Authority stage: IN PROGRESS / NOT FROZEN.
- Execution Foundation: MERGED as a low-level primitive.
- Execution composition ownership PR #23: PARKED / CLOSED WITHOUT MERGE.
- Execution v0.1: NOT FROZEN and must not advance until Capability & Authority is completed.

## Capability Registry checkpoint

PR #24 `Capability v0.1: Observable Exact-Ownership Registry` merged after Core CI #326 succeeded for exact head `43bc28032ae104e95cba1ce34a9f58862383aaa2`.

Merge commit:

`a1429661e5bb827d8898d3702808b4433e656df4`

Confirmed invariants:

- capability identity remains compatible with Authority `CapabilityId`;
- provider identity is explicit through `CapabilityProviderId`;
- duplicate capability IDs are rejected without replacing the current owner;
- exact registration ownership prevents stale/ABA unregister from removing a replacement owner;
- concurrent registration of one capability produces exactly one winner;
- register/reject/unregister/stale-unregister transitions are observable through injected `CoreObservability`;
- the registry contains metadata/ownership only and has no execution callback;
- capability presence does not grant Authority;
- no global/singleton registry is introduced.

## Authority delegation expiry hardening

PR #25 `Authority v0.1: Strict Delegation Expiry` passed Core CI #330 for exact head `1180730981a8ffb729887312847d56c835929a55` and was merged through the PR gate.

Hardening rule:

- a bounded delegated grant must satisfy `expiresAt > now` at creation time;
- `expiresAt < now` is denied;
- `expiresAt == now` is denied;
- existing source-grant activity checks remain unchanged;
- delegated grants still cannot outlive bounded source grants.

This closes the previously identified edge case where an already-expired delegated grant could be returned as granted.

## Execution status

PR #20 `Execution v0.1: Authority-Gated Execution Foundation` is already merged in `main` as a low-level primitive.

Its verified boundary remains:

`ExecutionRequest → trusted action/capability binding → AuthorityManager → ExecutionExecutor → ExecutionResult`

Important: this does NOT mean the Execution stage is complete.

PR #23 `Execution v0.1: Composition Ownership` reached GREEN Core CI #321 and passed its local composition audit, but a broader roadmap audit found that Execution had advanced before Capability & Authority was complete. PR #23 was therefore intentionally closed without merge. Its branch is preserved for later reuse.

Do not reopen or reimplement Execution composition until the Capability & Authority stage is frozen.

## Current stage: Capability & Authority completion

The active architecture work is NOT Execution and NOT Memory yet.

Remaining mandatory gates:

1. Introduce `AuthorityGrantRegistry` / controlled grant lifecycle with exact ownership handles.
2. Direct grant creation and revocation must be observable and stale/ABA-safe.
3. Expiry semantics must remain fail-closed.
4. Delegation must consume trusted direct-grant state without enabling transitive redelegation.
5. Introduce upper Capability & Authority composition ownership without modifying frozen Foundation internals.
6. Production callers should receive controlled managers/facades rather than raw mutable policy/registry internals.
7. Run Core CI and final cross-layer readiness audit.
8. Only then declare Capability & Authority frozen and advance to Memory.

## Current development direction

Immediate next implementation:

`AuthorityGrantRegistry v0.1`

Required properties:

- exact registration/revocation handle;
- stale/ABA revoke prevention;
- deterministic snapshot of active direct grants;
- explicit expiry-aware visibility;
- injected `CoreObservability` for grant/revoke/reject transitions;
- no hidden global state;
- no privilege amplification;
- direct grants remain structurally distinct from delegated grants;
- registry must not itself execute capabilities.

After that, integrate Capability Registry + Authority lifecycle through an upper composition boundary and perform a full Capability & Authority readiness audit.

## Workflow note

During PR #25 handling, `main` was briefly moved directly to the PR head by mistake. The ref was immediately restored to the previous verified merge checkpoint before performing the normal PR merge with exact-head verification. Durable workflow remains: feature branch → PR → GREEN CI → audit → exact-head merge; no intentional direct-to-main development.
