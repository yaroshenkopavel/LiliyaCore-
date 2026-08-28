# CURRENT STATE

Last journal update: 2026-08-28

## Main baseline

`main` SHA: `bb591d367af738107a5733b1d278603d22c96984`

This commit merged PR #29 `Authority v0.1: Capability Authority Composition Ownership` after Core CI #355 succeeded for exact head `7b5109da1914e67d0d7be27bf5a1d1d275cc2bc8` and the final cross-layer readiness audit passed.

Status:

- Core Foundation v0.1: FROZEN.
- Authority policy layer v0.1: FROZEN.
- Capability Registry v0.1: FROZEN.
- Authority Grant Registry v0.1: FROZEN.
- Capability & Authority composition ownership: FROZEN.
- Broader Capability & Authority stage: FROZEN.
- Execution Foundation: MERGED as a dormant low-level primitive.
- Execution composition ownership PR #23: PARKED / CLOSED WITHOUT MERGE.
- Execution v0.1: NOT FROZEN.
- Memory stage: NOT STARTED.

## Capability Registry checkpoint

PR #24 `Capability v0.1: Observable Exact-Ownership Registry` merged after Core CI #326 succeeded for exact head `43bc28032ae104e95cba1ce34a9f58862383aaa2`.

Merge commit:

`a1429661e5bb827d8898d3702808b4433e656df4`

Confirmed invariants:

- capability identity is `CapabilityId` shared with Authority;
- provider identity is explicit through `CapabilityProviderId`;
- duplicate capability IDs are rejected without replacement;
- exact registration ownership prevents stale/ABA unregister;
- concurrent registration produces exactly one owner;
- lifecycle transitions are observable through injected `CoreObservability`;
- capability presence does not imply permission;
- registry stores metadata/ownership only and has no execution authority.

## Authority delegation expiry hardening

PR #25 `Authority v0.1: Strict Delegation Expiry` passed Core CI #330 for exact head `1180730981a8ffb729887312847d56c835929a55`.

Frozen rule:

- bounded delegated grant requires `expiresAt > now`;
- `expiresAt <= now` is denied;
- delegated grants cannot outlive bounded direct source grants;
- only direct grants can be delegation sources.

## Authority Grant Registry checkpoint

PR #28 `Authority v0.1: Exact Grant Lifecycle Registry` merged after Core CI #343 succeeded for exact head `db6011320e07e191157e2c41d1ea2abe6c84711d`.

Merge commit:

`03c60fea8ea7592f52ffd0ad390867a01c22ff56`

Frozen invariants:

- exact ownership by `(principal, capability, scope)`;
- duplicate active direct grants are rejected;
- `expiresAt <= now` is rejected at registration;
- expired grants are absent from active reads;
- expired tuple replacement is atomic;
- stale/ABA revoke cannot remove replacement ownership;
- concurrent registration keeps a single current owner;
- registry stores direct grants only;
- registry does not authorize or execute capabilities.

## Capability & Authority composition freeze

PR #29 `Authority v0.1: Capability Authority Composition Ownership` passed Core CI #355 for exact head `7b5109da1914e67d0d7be27bf5a1d1d275cc2bc8` and was merged as:

`bb591d367af738107a5733b1d278603d22c96984`

The final cross-layer audit confirmed:

- frozen `FoundationComposition` internals were not modified;
- `CapabilityRegistry`, `AuthorityGrantRegistry`, `AuthorityManager`, `AuthorityPolicy`, and delegation policy/manager are not exposed as raw mutable production internals;
- direct grants cannot be registered for absent capabilities;
- capability presence still does not grant Authority;
- authorization is built from current active ownership state and remains default-deny;
- capability unregister immediately invalidates grants belonging to that exact capability generation;
- re-registering the same `CapabilityId` cannot resurrect grants from an older capability generation;
- direct grant ownership has an exact source generation token;
- revoke/re-register of an equivalent direct grant cannot resurrect an older delegated grant;
- delegated grants are bound to both exact capability generation and exact direct-source generation;
- direct-source revoke or expiry immediately invalidates dependent delegated authorization;
- delegated grants cannot become delegation sources, preventing transitive redelegation;
- exact delegated revoke is observable and stale-safe;
- mutation and authorization snapshot creation are serialized at the composition ownership boundary to close check/mutation races;
- authority/delegation decisions continue through observable managers with composition-created correlation contexts;
- no Execution integration was introduced in the Capability & Authority freeze.

## Execution status

PR #20 `Execution v0.1: Authority-Gated Execution Foundation` is already merged as a low-level primitive.

Verified boundary:

`ExecutionRequest → trusted action/capability binding → AuthorityManager → ExecutionExecutor → ExecutionResult`

PR #23 `Execution v0.1: Composition Ownership` reached GREEN Core CI #321 but was intentionally closed without merge because the roadmap audit found that Execution had advanced before Capability & Authority was complete.

That stage-order blocker is now removed because Capability & Authority is frozen.

PR #23 remains historical/parked evidence only. Do not merge the old PR blindly; re-audit/rebuild Execution composition against the newly frozen `CapabilityAuthorityComposition` boundary.

## Current development direction

Next allowed architecture work:

`Execution composition ownership / Execution readiness v0.1`

Required gates:

1. integrate the existing dormant Execution Foundation through the frozen Capability & Authority production boundary;
2. concrete `ExecutionExecutor` ownership must remain private to composition;
3. callers must not receive a raw executor or raw authority bypass path;
4. execution must require registered capability plus current authority at the point of execution;
5. capability/grant revoke, expiry, or generation replacement must fail-close subsequent execution;
6. preserve action-to-capability binding and observability/correlation guarantees from PR #20;
7. run exact-head Core CI and final Execution cross-layer readiness audit;
8. only then freeze Execution v0.1 and decide whether the roadmap advances to Memory.

Do not start Memory yet.

## Workflow note

During PR #25 handling, `main` was briefly moved directly to the PR head by mistake. The ref was restored and repository state reconciled to the already verified merged implementation without additional production changes.

During PR #29 construction, an initial tree was based on an older checkpoint and showed unintended deletions. This was detected before merge; the branch was rebuilt from the exact current `main` tree, leaving only the intended composition and contract files.

Durable workflow remains: feature branch → PR → GREEN CI → architecture audit → exact-head merge; no intentional direct-to-main development.
