# LiliyaCore — Current Repository Structure and Subsystem Guide

Scope: verified `main` at `249ae23947c3a707d6d03dfb31503d1d858cd873`.

This file is a concise map of the current core-only repository. Detailed invariants live in `ARCHITECTURE.md`, `NUANCES.md`, canonical freeze documents, production source, and executable contract tests.

## Top-level layout

- `.github/workflows/core-ci.yml` — Core CI.
- `core/` — Kotlin/JVM core module.
- `core/src/main/kotlin/pro/liliya/core/` — production packages.
- `core/src/test/kotlin/pro/liliya/core/` — executable architecture contracts.
- `docs/development/` — durable journals, freeze documents and architecture contracts.

Android application/device adapters are still deferred; the current repository remains core-only.

## Foundational production areas

Current top-level production areas include:

- `logging` — structured operational trace, correlation, bootstrap buffering and writer failure isolation;
- `diagnostics` — semantic failures/conditions and diagnostic sinks;
- `observability` — shared Logging + Diagnostics observation path;
- `runtime` — authoritative runtime state and transitions;
- `lifecycle` — lifecycle orchestration over Runtime state authority;
- `recovery` — retry/restart/fail reliability policy and active recovery ownership;
- `events` — synchronous deterministic in-process event delivery;
- `services` — service descriptors, registry, dependency resolution and exact lifecycle ownership;
- `modules` — module structure/dependencies and transactional module-service installation;
- `foundation` — composition root for foundational infrastructure;
- `capability` — capability identity/descriptor foundation;
- `authority` — fail-closed authorization and bounded delegation;
- `execution` — Authority-gated action execution foundation.

Foundation direction:

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Security/action direction:

`Capability → Authority → Execution`

## Cognitive and control areas

Implemented/frozen cognitive and control packages include:

- `memory` — exact-generation owned Memory records;
- `knowledge` — exact-generation owned Knowledge items;
- `identity` — Self/identity foundation;
- `trust` — explicit trust anchors;
- `personality` — stored personality profile data;
- `reflection` — explicit reflection records;
- `learning` — controlled learning candidate/decision/policy/application foundation;
- `planning` — descriptive Planning proposals with exact generation ownership;
- `reasoning` — descriptive Reasoning artifacts with exact generation ownership;
- `decision` — recorded Decision data;
- orchestration/control code — non-executing Orchestration Intent plus controlled Authority-gated execution path;
- `autonomy` — bounded proposal/attempt/deliberation governance;
- `agent` — exact Agent identity/lifecycle, delegated and coordinated governance bridges.

Conceptual cognitive chain:

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy, Agent, Delegation and Coordination layers govern provenance/readiness around this chain; they do not replace or bypass Authority.

## Planning

Location:
`core/src/main/kotlin/pro/liliya/core/planning/`

Important properties:

- Planning is descriptive data only;
- installs return exact `PlanningOwnership` with generation-safe removal;
- stale ownership cannot remove a newer replacement generation;
- bridge provenance is structural evidence, not permission;
- private goal/step content is not meant to leak through coordination bridge observability.

## Reasoning

Location:
`core/src/main/kotlin/pro/liliya/core/reasoning/`

Important properties:

- Reasoning is descriptive data only;
- `ReasoningComposition.install(...)` returns exact `ReasoningOwnership`;
- exact generation ownership is stale/ABA-safe;
- premises are defensively copied;
- `ReasoningArtifact.toString()` redacts premise/analysis/conclusion content;
- operational metadata exposes structural IDs/counts/provenance, not private cognitive text.

## Agent Coordination

Location:
`core/src/main/kotlin/pro/liliya/core/agent/`

Agent Coordination Foundation v0.1 is frozen. Controlled Agent Coordination v0.1 is in progress.

Verified controlled path currently reaches Reasoning:

`exact live Coordination → exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated participant initiatives → transactional attempt claims → exact coordination↔attempt binding → compensated deliberation transaction → live deliberation preflight → ordinary Planning → ordinary Reasoning → post-write exact revalidation/compensation`

Current coordination production includes governed slices for:

- exact live coordination/participant preflight;
- work binding and ownership;
- compensated multi-participant initiative;
- transactional bounded attempt claiming;
- exact attempt binding and ownership;
- compensated deliberation transaction;
- live deliberation preflight;
- Planning bridge;
- Reasoning bridge.

The Reasoning bridge production file is:

`core/src/main/kotlin/pro/liliya/core/agent/ControlledAgentCoordinationReasoningBridge.kt`

Its executable contracts include:

- `ControlledAgentCoordinationReasoningBridgeContractTest.kt`;
- `ControlledAgentCoordinationReasoningBridgeRaceContractTest.kt`.

Hard Reasoning-bridge guarantees include exact Planning generation/provenance validation, fresh pre/post coordinated readiness, exact Reasoning compensation on stale governance, explicit CRITICAL failure when compensation cannot restore the same live generation, privacy-safe observability, and no Decision/Authority/Execution semantics.

## Authority and Execution

Authority boundary:

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Hard invariants:

- default deny;
- exact principal/capability/scope;
- strict expiry;
- bounded non-amplifying delegation;
- Authority decides permission but performs no side effect.

Execution consumes trusted action→capability mapping, rejects unknown/mismatched capabilities before executor invocation, obtains fresh Authority, and calls the executor only when granted.

Android/device/shell/browser adapters must later sit behind this boundary.

## Tests

Tests under `core/src/test/kotlin/pro/liliya/core/` are executable architecture contracts, not merely regression checks.

Before changing a subsystem, inspect contracts for:

- exact generation/ownership semantics;
- stale/ABA protection;
- concurrency/serialization;
- deterministic ordering;
- failure isolation;
- rollback/compensation;
- fail-closed zero-side-effect behavior;
- privacy-safe rendering/metadata;
- correlation/observability expectations;
- cross-layer non-amplification and bypass resistance.

## Architecture contracts not yet runtime subsystems

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts.

Persistent encrypted storage, Android integration, Liliya Network runtime, real Update System runtime and real Security/Licensing runtime remain deferred.

## Current next structural area

The preferred next Controlled Agent Coordination slice is:

`exact live coordinated Reasoning generation → ordinary frozen Decision data`

It must preserve the existing fail-closed pattern and must not introduce Orchestration, permission, Authority, scheduler or Execution semantics.
