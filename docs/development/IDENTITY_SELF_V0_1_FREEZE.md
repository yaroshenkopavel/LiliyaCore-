# Identity / Self Foundation v0.1 Freeze

Date: 2026-08-28

Baseline before this documentation checkpoint: `4366edcc1f124e4cba715a5d0f882a2eb20f20a2`.

Identity / Self Foundation v0.1 is structurally complete and ready to freeze after PRs #44-#46 passed exact-head Core CI and architecture/readiness audits.

## Verified implementation checkpoints

### PR #44 — Single Current Self Store Foundation

Final exact head: `ed4a7f9c1cba513bf9fd4b5571da5ac25b449531`.
Core CI #434: GREEN.
Merge commit: `47a54e285cdbad613ee99bd3f3d86597cc08b27f`.

Introduced structural Self models and the internal single-slot `SelfStore`. One store owns at most one current Self regardless of identity ID. Successful registration owns an exact positive `SelfGeneration`; removal compares the exact lifecycle entry, so stale ownership cannot remove a later replacement. Concurrent installation has exactly one winner.

`SelfOrigin.Knowledge(itemId, generation)` records an exact structural Knowledge identity/generation reference. `SelfOrigin.Declared(sourceId, sourceReference)` records caller-declared attribution only. Neither origin form establishes truth, trust, confidence, verification, authority, or permission.

### PR #45 — Self Composition Ownership

Final exact head: `17926fed36c0fbdc1025ced34fc9e6778eff31b2`.
Core CI #439: GREEN.
Merge commit: `4e7379869c8249b67c33bca8109b24ce7973b63e`.

Introduced `SelfComposition` as the production ownership boundary around the internal single-slot store. Production callers receive controlled `install/current/inspect/isInstalled/remove` semantics and exact `SelfOwnership`; raw `SelfStore` and `SelfRegistration` are not exposed as production public surface.

Install and removal use fresh Foundation root correlation contexts. Lifecycle observability includes structural identity/generation/origin metadata but intentionally excludes `SelfName` and does not create personality, trust, truth, confidence, or authority semantics.

### PR #46 — Readiness Contract Hardening

Final exact head: `71c89255337376e4b053443c1f5e64d2584188e8`.
Core CI #443: GREEN.
Merge commit: `4366edcc1f124e4cba715a5d0f882a2eb20f20a2`.

Test-only hardening locked two final readiness boundaries: `SelfOrigin.Knowledge(itemId, generation)` is a structural reference that does not perform hidden Knowledge lookup or verification, and `SelfIdentity.createdAt` remains a caller-supplied identity value preserved unchanged through composition and ownership.

## Frozen guarantees

- `SelfIdentityId`, `SelfName`, `SelfSourceId`, and optional `SelfSourceReference` are explicit nonblank structural values.
- `SelfIdentity` is immutable.
- a Self store/composition has at most one current Self at a time, even if competing identities use different IDs.
- a successful installation owns one exact positive `SelfGeneration`.
- `SelfGeneration` is an opaque in-memory lifecycle identity, not a timestamp, truth version, personality revision, durable cross-process identity, trust score, or authority token.
- after removal, a later replacement receives a distinct generation.
- stale ownership cannot remove or otherwise affect a later replacement Self.
- concurrent installation has exactly one winner.
- `current()` returns the immutable current identity and `inspect()` returns the identity plus exact lifecycle generation.
- `SelfOrigin.Knowledge(itemId, generation)` is a structural reference only; it does not prove that the referenced Knowledge item exists, remains current, is accessible, is correct, is trusted, or is true.
- `SelfOrigin.Declared` is attribution only; it does not imply verification, authority, permission, truth, trust, or confidence.
- `SelfIdentity.createdAt` is caller-supplied and is not a trusted runtime clock or source-observation timestamp.
- `SelfName` is a structural designation only. It does not define personality, behavior, values, tone, policy, preferences, goals, or autonomy.
- lifecycle registration/removal/rejection paths are observable through injected `CoreObservability`.
- lifecycle metadata does not include `SelfName`; Identity/Self v0.1 does not make personality content part of observability semantics.
- `SelfComposition` privately owns the mutable store; raw store/registration primitives are not production public surface.
- Identity/Self v0.1 does not automatically promote Memory or Knowledge into identity truth.
- Identity/Self v0.1 has no hidden Knowledge verification, no trust engine, no confidence/truth scoring, no personality model, no values/preferences policy, no learning, no reflection, no planning, no agents, no persistence/database adapter, no background worker, no autonomous mutation, no Execution coupling, and no Android/device integration.

## Non-goals and future boundaries

Identity / Self v0.1 deliberately answers only: what exact structural Self identity is currently installed, who owns its lifecycle, and what structural origin attribution accompanies it.

It does not answer whether an identity claim is trustworthy or authorized. Those concerns belong to the later Trust / Security stage.

It does not define how Liliya speaks, behaves, values things, expresses preferences, or maintains a persona. Those concerns belong to the later Personality stage.

It does not learn, reflect, plan, create agents, or autonomously mutate identity. Those concerns remain later explicit stages and must not be inferred from this freeze.

## Freeze rule

Identity / Self Foundation v0.1 semantics are frozen by this checkpoint. Reopening them requires an explicitly scoped later Identity/Self revision through the normal workflow:

feature branch → PR → exact-head Core CI GREEN → architecture/security audit → exact-head merge.

Next architecture stage after this freeze: `Trust / Security Foundation v0.1`.
