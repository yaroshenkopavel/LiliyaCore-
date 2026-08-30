# LiliyaCore — Current Repository Structure and Subsystem Guide

Scope: verified code `main` at `51c19d07710a0606cb619f9164e0bd6ab8f4414f`.

This file is a concise map of the current core-only repository. Detailed invariants live in `ARCHITECTURE.md`, `NUANCES.md`, canonical freeze documents, production source, and executable contract tests.

## Top-level layout

- `.github/workflows/core-ci.yml` — Core CI.
- `core/` — Kotlin/JVM core module.
- `core/src/main/kotlin/pro/liliya/core/` — production packages.
- `core/src/test/kotlin/pro/liliya/core/` — executable architecture contracts.
- `docs/development/` — durable journals, freeze documents and architecture contracts.

Android application/device adapters remain deferred; the repository is core-only.

## Foundational production areas

Top-level production areas include:

- `logging` — structured trace/correlation/bootstrap buffering/writer isolation;
- `diagnostics` — semantic failures/conditions and sinks;
- `observability` — shared Logging + Diagnostics path;
- `runtime`, `lifecycle`, `recovery`, `events`, `services`, `modules`, `foundation` — frozen Core Foundation chain;
- `capability`, `authority`, `execution` — fail-closed permission and side-effect boundary;
- `memory`, `knowledge`, `identity`, `trust`, `personality`, `reflection`, `learning` — cognitive/support foundations;
- `planning`, `reasoning`, `decision`, `orchestration` — frozen descriptive cognition through Authority-gated execution;
- `autonomy` — bounded proposal/attempt/deliberation governance;
- `agent` — exact Agent identity/lifecycle plus controlled initiative, delegation and coordination governance.

Foundation direction:

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Security/action direction:

`Capability → Authority → Execution`

Conceptual cognitive chain:

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy, Agent, Delegation and Coordination layers govern provenance/readiness around this chain; they do not replace or bypass Authority.

## Planning / Reasoning / Decision / Orchestration

Planning and Reasoning are descriptive exact-generation owned data. Decision records a choice from exact inputs. Orchestration Intent records a non-executing exact Decision reference.

Important common properties:

- exact generation ownership;
- stale/ABA-safe removal;
- private cognitive text redaction;
- structural provenance is evidence, not permission;
- controlled bridges never turn data installation into Authority.

## Agent Coordination

Location:
`core/src/main/kotlin/pro/liliya/core/agent/`

Both **Agent Coordination Foundation v0.1** and **Controlled Agent Coordination v0.1** are frozen.

Frozen controlled path:

`exact live Coordination → exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated participant initiatives → transactional bounded attempts → exact coordination↔attempt binding → compensated deliberation → exact live deliberation preflight → ordinary Planning → ordinary Reasoning → ordinary Decision → ordinary Orchestration Intent → final coordinated execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution`

Key controlled-coordination production files include:

- `ControlledAgentCoordinationPreflight.kt`;
- work/attempt binding compositions and controlled initiative/attempt/deliberation gates;
- `ControlledAgentCoordinationDeliberationPreflight.kt`;
- `ControlledAgentCoordinationPlanningBridge.kt`;
- `ControlledAgentCoordinationReasoningBridge.kt`;
- `ControlledAgentCoordinationDecisionBridge.kt`;
- `ControlledAgentCoordinationOrchestrationBridge.kt`;
- `ControlledAgentCoordinationExecution.kt`.

Executable contracts include normal and race/TOCTOU coverage for coordinated cognitive bridges plus `ControlledAgentCoordinationReadinessContractTest.kt` for the final freeze boundary.

Hard guarantees:

- exact coordination/participant/attempt/deliberation generations;
- fresh ACTIVE lifecycle validation where readiness requires it;
- exact-generation stale/ABA-safe ownership;
- pre/post validation around coordinated cognitive writes;
- exact-generation compensation only for the generation created by the current operation;
- newer replacement generations are preserved;
- final execution revalidates the full exact chain immediately before downstream delegation;
- stale governance produces zero downstream Authority/executor calls;
- downstream permission/side effects are owned only by frozen Controlled Orchestration → fresh Authority → frozen Execution;
- no coordination-specific Capability/Authority/executor/scheduler/retry/voting/consensus/fan-out semantics;
- private cognitive text remains outside coordination bridge observability.

Canonical freeze documents:

- `AGENT_COORDINATION_V0_1_FREEZE.md`;
- `CONTROLLED_AGENT_COORDINATION_V0_1_FREEZE.md`.

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

Controlled Agent Coordination v0.1 is frozen. Do not extend it ad hoc.

The next architecture stage should be chosen from the deferred roadmap using a fresh architecture audit. Leading candidates are persistent encrypted cognitive storage/crash recovery, Security & Licensing runtime foundations, Update System runtime foundations, and later Android/device integration behind frozen Authority/Execution.