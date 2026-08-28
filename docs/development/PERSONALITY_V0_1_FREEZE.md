# Personality Foundation v0.1 Freeze

Frozen on 2026-08-29 after the verified PR #52-#54 implementation sequence.

## Verified implementation

### PR #52 — Explicit Profile Store Foundation

- Final exact head: `e56409ec82e2ce50b1e10988bf4bac53f6b12633`.
- Core CI #473: GREEN.
- Merge commit: `663099f40db6a5a7f0c35b8137ed98ee5dd9e759`.

Introduced explicit structural `PersonalityProfile` models targeted to exact Self `(SelfIdentityId, SelfGeneration)`, nonblank key/value personality attributes with duplicate-key rejection, defensive copying of caller-provided attribute lists, caller-declared provenance, caller-supplied `createdAt`, exact positive `PersonalityGeneration`, duplicate profile-ID rejection, stale/ABA-safe removal, deterministic snapshots, and concurrent same-ID one-winner behavior.

A security audit found that the initial `PersonalityProfile.toString()` rendered raw attribute values. That head was not merged. The profile string representation was redacted to expose only structural summary data such as `attributeCount`, a contract was added, and Core CI #473 passed on the corrected exact head above.

### PR #53 — Composition Ownership

- Final exact head: `d198479532c7e03c036999b351578d97c5fbdb23`.
- Core CI #478: GREEN.
- Merge commit: `159caeedf350a1ced1c5ca39a22228675e2f26a8`.

Introduced `PersonalityComposition` as the production ownership boundary around the internal `PersonalityProfileStore`. Public callers receive controlled install/read/inspect/snapshot APIs and exact `PersonalityOwnership`; raw store/registration primitives remain outside the public production surface. Install/remove operations use fresh Foundation root contexts, and lifecycle metadata excludes personality attribute values.

### PR #54 — Readiness Contract Hardening

- Final exact head: `9e445ef6f8c726d990a0960ef65be108c8ad3798`.
- Core CI #482: GREEN.
- Merge commit: `8676e31ca42221886fbe69f9c256fbc870aeab4b`.

Test-only hardening locked caller-supplied `createdAt`, composition isolation, composition-local generation semantics, structural-only Self targeting, absence of implicit behavior/trust/authority effects, and redacted profile string representation. No production API or runtime behavior changed.

## Frozen guarantees

- `PersonalityProfileId`, `PersonalityAttributeKey`, `PersonalityAttributeValue`, `PersonalitySourceId`, and optional `PersonalitySourceReference` are explicit nonblank structural values.
- A `PersonalityProfile` targets exact Self `(SelfIdentityId, SelfGeneration)` structurally only. Personality v0.1 performs no hidden `SelfComposition` lookup and does not verify Self existence, correctness, authenticity, or current availability.
- A profile must contain at least one attribute, and attribute keys within one profile must be unique.
- Caller-provided attribute lists are defensively copied so later mutation of the caller's original mutable list cannot mutate the profile.
- Personality attributes are explicit stored data only. Their keys or values do not automatically change behavior, prompts, response style, decisions, authority, execution, trust, or identity.
- `PersonalityProvenance` is caller-declared attribution only. It does not prove correctness, authority, trust, truth, confidence, or authenticity.
- `PersonalityProfile.createdAt` is caller-supplied and preserved unchanged; it is not a trusted runtime/source clock or proof of chronology.
- `PersonalityGeneration` is a positive opaque in-memory lifecycle identity owned by one personality store/composition lifecycle.
- Duplicate profile IDs are rejected without replacing the current profile.
- Successful installation owns one exact generation; stale ownership cannot remove a later replacement.
- Same-ID replacement receives a distinct generation within the same store lifecycle.
- Equal numeric generation values across different `PersonalityComposition` instances do not imply shared ownership, global identity, or shared state.
- `PersonalityComposition` instances are isolated; the same profile ID may exist independently in different compositions.
- Concurrent same-ID registration has exactly one winner per store.
- Deterministic snapshots order by caller-supplied `createdAt`, then profile ID; this ordering is not truth, priority, preference strength, or causal ordering.
- Lifecycle observability uses structural metadata such as profile ID, generation, attribute count, provenance identifiers, and exact Self target; personality attribute values are not written into lifecycle metadata.
- `PersonalityProfile.toString()` is redacted and does not render personality attribute values.
- `PersonalityComposition` privately owns mutable personality storage and uses fresh Foundation root contexts for install/remove operations.
- Raw `PersonalityProfileStore` and `PersonalityProfileRegistration` are not production public surface.

## Explicit exclusions

Personality Foundation v0.1 does **not** provide:

- a behavior engine or behavioral policy evaluator;
- prompt construction, prompt injection, system-prompt mutation, response-style rendering, or automatic tone application;
- trait inference from conversation, Memory, Knowledge, Trust, Self, external data, or model output;
- preference ranking, trait weighting, scoring, confidence, truth, or psychological/personality-model inference;
- adaptation, learning, reflection-driven personality mutation, autonomous personality changes, or background workers;
- automatic conversion of `SelfName`, Self origin, Memory/Knowledge provenance, or Trust anchors into personality attributes;
- trust, authentication, authority, capability grants, permission changes, or Execution authorization;
- planning, goals, autonomy, agents, decision policy, or action selection;
- persistence/database-backed personality state;
- Android/UI/device integration;
- multi-user or multi-agent personality identity semantics.

## Freeze rule

These semantics are stable for Personality Foundation v0.1. Any later expansion must be an explicitly scoped revision through the normal feature branch → PR → exact-head Core CI → architecture/security audit → exact-head merge workflow.

Next allowed architecture stage after this freeze: `Reflection / Learning Foundation v0.1`.
