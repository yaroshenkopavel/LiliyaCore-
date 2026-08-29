# LiliyaCore — Current Repository Structure and Subsystem Guide

Scope: current `main` at `f594c00989cd79fd9ea8f4a4bf065a8703c8685e`.

This file is a concise map of the current core-only repository. Detailed invariants live in `ARCHITECTURE.md`, `NUANCES.md`, subsystem contract tests, and the dedicated Update/Security contracts.

## Top-level layout

- `.github/workflows/core-ci.yml` — Core CI.
- `core/` — Kotlin/JVM core module.
- `core/src/main/kotlin/pro/liliya/core/` — production packages.
- `core/src/test/kotlin/pro/liliya/core/` — executable architecture contracts.
- `docs/development/` — durable project journals/contracts.

Android application/device adapters are still deferred; current repository remains core-only.

## Current production packages

Current top-level production areas include:

- `logging` — structured operational trace, correlation, writers, bootstrap buffering and failure isolation.
- `diagnostics` — semantic failures/conditions and diagnostic sinks.
- `observability` — `CoreObservability`, shared Logging + Diagnostics observation path.
- `runtime` — authoritative runtime state and transitions.
- `lifecycle` — lifecycle orchestration over Runtime state authority.
- `recovery` — retry/restart/fail reliability policy and active recovery ownership.
- `events` — synchronous deterministic in-process event delivery.
- `services` — service descriptors, registry, dependency resolution and exact started-instance lifecycle ownership.
- `modules` — module structure/dependencies and transactional module-service installation.
- `foundation` — composition root for foundational infrastructure and observable ownership paths.
- `capability` — capability identity/descriptor foundation.
- `authority` — fail-closed authorization, scoped grants and bounded delegation.
- `execution` — Authority-gated action execution foundation; no Android/shell/browser adapter is implied by its existence.
- `memory` — exact generation-owned Memory records and controlled composition.
- `knowledge` — exact generation-owned Knowledge items and controlled composition.
- `identity` — Self/identity foundation and exact generation ownership.
- `trust` — explicit trust anchors; trust is structural and is not Authority.
- `personality` — stored personality profile data bound structurally to exact Self.
- `reflection` — explicit reflection records; reflection does not autonomously mutate learned state.
- `learning` — candidate, decision, policy, application intent, prepared mutation, authorization/claim/completion, and controlled Memory/Knowledge application.

## Foundational dependency direction

Conceptually:

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Security/action direction:

`Capability → Authority → Execution`

Cognitive direction currently implemented:

`Memory / Knowledge → Identity/Self → Trust/Security → Personality → Reflection → Learning Candidate → Learning Decision → Learning Policy → Learning Application`

These are architectural directions, not a claim that each package imports the previous package directly.

## Controlled Learning package

Location:
`core/src/main/kotlin/pro/liliya/core/learning/`

The important current layers are:

- candidate models/store/composition — proposals only;
- decision models/store/composition — recorded APPROVE/REJECT decisions only;
- policy models/store/composition — caller-supplied structural policy data;
- application intent models/store/composition — binds exact Decision + Policy + target;
- `LearningApplicationPreflight.kt` — exact structural readiness validation;
- `LearningApplicationAuthorization.kt` — target-specific Authority check using capability `learning.application.apply`;
- `LearningApplicationMutationModels.kt` — prepared target-specific mutation plans and idempotency IDs;
- `LearningApplicationMutationStore.kt` — private exact-generation mutation storage, claim token state, completion and in-memory completed-idempotency tombstones;
- `LearningApplicationMutationComposition.kt` — controlled public mutation ownership/claim API;
- `LearningApplicationMutationAuthorizationGate.kt` — mutation-time fresh preflight + Authority + target consistency check;
- `LearningApplicationMutationApplier.kt` — first real controlled downstream Memory/Knowledge mutation path.

Current apply path:

`exact mutation ref → exact claim → fresh authorization gate → target-specific downstream write → exact completion → structural receipt`

Important properties:

- only one claim can own an exact mutation generation at a time;
- an active claim blocks removal;
- prepared target must match current Application target;
- Authority denial or target mismatch produces zero downstream writes;
- Memory/Knowledge write returns exact ownership internally;
- public success receipt exposes downstream ID + generation, not mutable removal ownership;
- downstream conflict leaves the prepared mutation retryable;
- successful completion removes the prepared mutation and reserves the idempotency key for the composition lifetime;
- repeated apply of the completed exact mutation cannot write again;
- post-write completion failure attempts exact compensation; failed compensation becomes explicit partial failure;
- current idempotency/completion state is process-memory/composition-local, not crash-durable exactly-once.

## Memory

Location:
`core/src/main/kotlin/pro/liliya/core/memory/`

Core boundary:

`MemoryComposition.remember(record) → MemoryRememberResult`

Successful registration returns exact `MemoryOwnership(record, generation)` with stale-safe removal. Reads/snapshots are controlled through composition. Content must not leak through lifecycle observability metadata.

## Knowledge

Location:
`core/src/main/kotlin/pro/liliya/core/knowledge/`

Core boundary:

`KnowledgeComposition.create(item) → KnowledgeCreateResult`

Successful creation returns exact `KnowledgeOwnership(item, generation)`. Knowledge origin can structurally reference exact Memory ID + generation or an explicit declared source. Origin is provenance, not truth/trust/authority.

## Identity / Trust / Personality / Reflection

- Identity/Self: single current Self per composition/store with exact generation ownership; Self references are structural identity, not Authority credentials.
- Trust: explicit trust anchors, isolated compositions and exact generations; trust does not transitively grant authority.
- Personality: stored profile attributes only; it does not automatically alter prompts/actions/authority.
- Reflection: caller-declared reflection content and provenance; reflection is not autonomous learning/application.

## Authority and Execution

Authority boundary:

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Hard invariants:
- default deny;
- exact principal/capability/scope;
- strict expiry (`now < expiresAt`);
- bounded one-level delegation from `DirectAuthorityGrant` only;
- Authority decides permission but performs no side effect.

Execution consumes trusted action→capability mapping, rejects unknown/mismatched capabilities before executor invocation, invokes Authority, and calls the executor only when granted. Android/device/shell/browser adapters must later sit behind this boundary.

## Update System architecture (contract only)

Durable contract:
`docs/development/UPDATE_SYSTEM_V0_1_CONTRACT.md`

Required future pipeline:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

It must support Android application/runtime updates and explicitly supported internal packages. Network transport does not imply trust, and activation must remain rollback-safe and Authority-gated.

No Update System production package is implemented yet.

## Security & Licensing architecture (contract only)

Durable contract:
`docs/development/SECURITY_LICENSING_V0_1_CONTRACT.md`

Future implementation areas include:

- signed/versioned entitlements and offline lease policy;
- device cryptographic enrollment using Android Keystore/StrongBox when available;
- separate key domains for model/runtime assets and user cognitive data;
- authenticated encrypted model packages with bounded chunk/tensor decryption;
- encrypted cognitive persistence, rotation, backup/export and device transfer/recovery;
- anti-tamper/anti-debug/obfuscation as defense-in-depth;
- licensing integration with Authority and Update System without bypasses.

No licensing/DRM/Keystore/model-encryption production package is implemented yet.

## Tests

Tests under `core/src/test/kotlin/pro/liliya/core/` are executable architecture contracts, not merely regression checks.

Before changing a subsystem, inspect its tests for:

- exact generation/ownership semantics;
- stale/ABA protection;
- concurrency/serialization;
- deterministic ordering;
- failure isolation;
- rollback/compensation;
- Authority denial zero-side-effect behavior;
- privacy-safe rendering/metadata;
- correlation/observability expectations.

## Deferred areas

Still deferred until current Controlled Learning Application readiness/freeze is complete:

- Planning;
- Autonomy;
- Agents;
- Android integration;
- Liliya Network runtime;
- persistent/crash-durable controlled-learning result/idempotency storage;
- real Update System implementation;
- real Security & Licensing implementation.
