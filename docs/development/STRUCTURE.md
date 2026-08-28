# LiliyaCore — Current Repository Structure and Subsystem Guide

Scope: current `main` at `638bbfdc51b9446f637a11c922a050b5289e63d7` unless explicitly marked otherwise.

This file explains what each current package is for, where it lives, what it owns, and the important constraints future development must preserve.

## Top-level repository layout

- `.github/workflows/core-ci.yml` — GitHub Actions Core CI workflow. Current merge policy requires relevant Core CI to be GREEN before architectural PRs are merged.
- `README.md` — minimal repository-level readme.
- `build.gradle.kts` — root Gradle configuration.
- `settings.gradle.kts` — project/module inclusion.
- `core/build.gradle.kts` — Kotlin/JVM configuration for the `core` module.
- `core/src/main/kotlin/pro/liliya/core/` — production source root.
- `core/src/test/kotlin/pro/liliya/core/` — contract/unit test root, organized largely to mirror production packages.
- `docs/development/` — durable project handoff/development journal once PR #21 is merged.

The active repository is intentionally core-only. Android app/device adapters are not part of current `main`.

---

# Production package map

Current production packages under `core/src/main/kotlin/pro/liliya/core/`:

- `logging`
- `diagnostics`
- `observability`
- `runtime`
- `lifecycle`
- `recovery`
- `events`
- `services`
- `modules`
- `foundation`
- `authority`

`execution` does not exist in `main` yet; it exists only in open PR #20.

---

## `pro.liliya.core.logging`

Location:
`core/src/main/kotlin/pro/liliya/core/logging/`

Purpose: low-level structured technical telemetry/tracing infrastructure.

Important files/components include:

- `LogLevel.kt` — logging severity/level model.
- `LogContext.kt` — context carried with observations: module/component/operation, correlation/parent correlation, metadata.
- `LogEvent.kt` — immutable structured log event representation.
- `LogWriter.kt` — output abstraction.
- `Logger.kt` / `StructuredLogger.kt` — structured logger API/implementation.
- `LoggerFactory.kt` — logging factory infrastructure. Subsystems must not secretly acquire ownership through hidden factory defaults.
- `CorrelationIdGenerator.kt` — correlation ID abstraction/default generator.
- `LogContextPropagation.kt` — root/child context creation and metadata propagation.
- `GlobalLogSequence.kt` — global monotonically increasing log sequence infrastructure.
- `BootstrapLogWriter.kt` — early logging buffering/replay before final writer plumbing is ready.
- `FilteringLogWriter.kt` — writer-level filtering.
- `CompositeLogWriter.kt` — fan-out to multiple writers.
- `SafeLogWriter.kt` — isolates writer failure from caller path.
- `InMemoryLogWriter.kt` — deterministic test/in-memory writer.
- log writer failure model/observer helpers including in-memory failure observer.

Ownership/nuances:

- Logging is not semantic diagnostics.
- `GlobalLogSequence` is accepted as global sequence infrastructure; it is not mutable subsystem ownership state.
- Metadata is treated as snapshots so later external map mutation cannot rewrite history.
- Writer failures must not disappear silently.
- A previous integration bug showed that hidden `LoggerFactory.create(...)` defaults can leak bootstrap/global writer state into isolated components/tests. New subsystem wiring should use explicit composition-owned observability/logger providers.

---

## `pro.liliya.core.diagnostics`

Location:
`core/src/main/kotlin/pro/liliya/core/diagnostics/`

Files:

- `DiagnosticEvent.kt`
- `DiagnosticFailure.kt`
- `DiagnosticRecorder.kt`
- `DiagnosticSeverity.kt`
- `DiagnosticSink.kt`
- `GlobalDiagnosticSequence.kt`
- `InMemoryDiagnosticFailureObserver.kt`
- `InMemoryDiagnosticSink.kt`
- `SafeDiagnosticSink.kt`

Purpose: meaningful system condition/error/contract reporting.

Responsibilities:

- record semantic failures, rejected transitions, invalid ownership/lifecycle operations, recovery decisions, authority decisions, etc.;
- preserve `LogContext` correlation information;
- isolate sink failures safely;
- expose deterministic in-memory sinks for contract tests.

Nuances:

- Diagnostics should not replace Logging.
- `GlobalDiagnosticSequence` is accepted sequence infrastructure, not subsystem ownership singleton.
- Significant operations often need both a technical log and diagnostic event; that dual-channel behavior belongs in `CoreObservability` rather than duplicated ad hoc in every subsystem.

---

## `pro.liliya.core.observability`

Location:
`core/src/main/kotlin/pro/liliya/core/observability/`

Current production file:

- `CoreObservability.kt`

Purpose: bridge significant operations into both structured Logging and Diagnostics with one context/correlation lineage.

Core design:

`CoreObservability.record(...)` receives severity, code, message, `LogContext`, metadata, and optional throwable; it enriches context once, emits through a logger supplied by `LoggerProvider`, and records the corresponding diagnostic.

Nuances:

- It does not make Logging and Diagnostics the same system.
- Composition owns infrastructure distribution.
- Important subsystem actions should not bypass this path merely because a low-level logger is available.
- Technical-only tracing may still be logging-only when it is not a semantic system observation.

---

## `pro.liliya.core.runtime`

Location:
`core/src/main/kotlin/pro/liliya/core/runtime/`

Files:

- `RuntimeState.kt`
- `RuntimeStateController.kt`
- `RuntimeStateHolder.kt`
- `RuntimeTransition.kt`
- `RuntimeTransitionPolicy.kt`
- `RuntimeTransitionResult.kt`
- `RuntimeTransitionRule.kt`

Purpose: authoritative runtime state and legal transition control.

Responsibilities:

- model runtime states;
- define legal transition rules/policy;
- own atomic current state through `RuntimeStateHolder`;
- apply or reject transitions explicitly;
- observe applied/rejected behavior.

Nuances:

- Runtime is the single state authority.
- Lifecycle and later systems must not create parallel runtime state.
- Rejected transitions are first-class results, not exceptional hidden behavior.
- Concurrency contracts protect state transition invariants.

---

## `pro.liliya.core.lifecycle`

Location:
`core/src/main/kotlin/pro/liliya/core/lifecycle/`

Files:

- `LifecycleCommand.kt`
- `LifecycleController.kt`
- `LifecyclePhase.kt`
- `LifecycleResult.kt`

Purpose: express lifecycle intent in domain-friendly commands while delegating actual state authority to Runtime.

Responsibilities:

- map lifecycle commands to runtime transitions;
- report lifecycle `Applied`/`Rejected` results;
- preserve runtime/lifecycle diagnostic consistency.

Nuances:

- Lifecycle orchestrates Runtime; it does not shadow state.
- Repeated/invalid lifecycle commands must reject deterministically.

---

## `pro.liliya.core.recovery`

Location:
`core/src/main/kotlin/pro/liliya/core/recovery/`

Files:

- `RecoveryAction.kt`
- `RecoveryCoordinator.kt`
- `RecoveryDecision.kt`
- `RecoveryPolicy.kt`
- `RecoveryRequest.kt`

Purpose: reliability recovery ownership and policy.

Responsibilities:

- validate recovery requests;
- choose retry/restart/fail recovery action according to policy;
- own active recovery targets;
- reject duplicate simultaneous recovery ownership;
- complete/release recovery ownership;
- observe accepted/rejected/ignored outcomes.

Nuances:

- Recovery is reliability infrastructure, not planning/reasoning.
- Ownership must be explicit and reusable after legitimate completion/release.
- Duplicate recovery suppression must not permanently poison a target.

---

## `pro.liliya.core.events`

Location:
`core/src/main/kotlin/pro/liliya/core/events/`

Files:

- `CoreEvent.kt`
- `EventBus.kt`
- `EventDeliveryReport.kt`
- `EventEnvelope.kt`
- `EventListener.kt`
- `EventSubscription.kt`
- `GlobalEventSequence.kt`

Purpose: deterministic synchronous in-process event delivery.

Responsibilities:

- subscribe with explicit subscription ownership;
- publish event envelope snapshots;
- isolate listener failures so remaining listeners are still called;
- return delivery report;
- preserve event sequence/correlation/metadata.

Nuances:

- Subscription order is deterministic.
- Publication uses listener snapshots.
- Listener callbacks are not held under mutable ownership locks.
- Event bus is not a global singleton ownership boundary.
- No persistent event store/retry/async queue exists in this foundation.

---

## `pro.liliya.core.services`

Location:
`core/src/main/kotlin/pro/liliya/core/services/`

Files:

- `CoreService.kt`
- `ServiceDescriptor.kt`
- `ServiceRegistry.kt`
- `ServiceDependencyResolver.kt`
- `ServiceManager.kt`

Purpose: infrastructure service identity, dependency ordering, registration, and lifecycle ownership.

### `CoreService.kt`
Defines the executable service contract.

### `ServiceDescriptor.kt`
Defines stable service identity/dependency metadata and validates descriptor invariants.

### `ServiceRegistry.kt`
Owns currently registered service identity entries.

Important ownership behavior:
- one current owner per service ID;
- exact `ServiceRegistration` handles;
- idempotent unregister;
- compare/remove prevents stale handle from unregistering a replacement owner;
- snapshots are immutable views.

### `ServiceDependencyResolver.kt`
Computes deterministic dependency order and rejects:
- duplicates;
- missing dependencies;
- cycles.

### `ServiceManager.kt`
Owns service start/stop lifecycle state.

Critical readiness change: it now stores exact started `CoreService` instances, not just IDs. Therefore if a registered service is removed/replaced after start, `stopAll()` still stops the original object that was actually started.

Nuances:

- Registry ownership and started lifecycle ownership are different concerns.
- Registry lookup must not be used later as a substitute for retaining the exact started instance.
- Start rollback/stop order must use exact owned instances.
- ServiceManager observability is explicitly injected in production composition; isolated tests may use diagnostic fallback depending on constructor contract.

---

## `pro.liliya.core.modules`

Location:
`core/src/main/kotlin/pro/liliya/core/modules/`

Files:

- `CoreModule.kt`
- `ModuleDescriptor.kt`
- `ModuleRegistry.kt`
- `ModuleDependencyResolver.kt`
- `ModuleServiceInstaller.kt`

Purpose: structural composition and transactional ownership of module-declared services.

### `CoreModule.kt` / `ModuleDescriptor.kt`
Define module identity, dependencies, and service declarations.

### `ModuleRegistry.kt`
Owns module registrations with exact `ModuleRegistration` handles and stale-handle protection analogous to services.

### `ModuleDependencyResolver.kt`
Deterministically validates module dependency graph and rejects duplicate/missing/cycle cases.

### `ModuleServiceInstaller.kt`
Composition boundary connecting module registration and service registration transactionally.

Install behavior:
- validate dependency graph before ownership mutation;
- register module;
- register owned services;
- preserve exact registration handles;
- if service conflict occurs, roll back only registrations owned by the current attempt;
- rollback service registrations in reverse order, then module;
- preserve external pre-existing owners;
- reject repeated install.

Uninstall behavior:
- reject if an owned service is still started;
- reject if another registered module depends on target module;
- unregister owned services in reverse order;
- unregister module last.

Nuances:

- Modules are structural units; `ServiceManager` still owns executable lifecycle state.
- The installer cannot bypass `ServiceManager.isStarted(...)` safety when uninstalling.
- Install/reject/rollback/uninstall ownership changes are observable.

---

## `pro.liliya.core.foundation`

Location:
`core/src/main/kotlin/pro/liliya/core/foundation/`

Current file:

- `FoundationComposition.kt`

Purpose: production composition/ownership root for Foundation subsystems.

Owns/wires shared infrastructure including:

- `DiagnosticRecorder`;
- `CoreObservability`;
- Runtime state holder/policy/controller;
- Lifecycle controller;
- Recovery coordinator/policy;
- Event bus;
- private ServiceRegistry + service dependency resolver/manager;
- private ModuleRegistry + module dependency resolver/installer;
- correlation ID generator;
- root/child `LogContext` creation.

Critical nuance from final Foundation audit:

Raw `ServiceRegistry` and `ModuleRegistry` are private inside composition. This prevents production ownership changes through an unobservable path. Read-only lookup is exposed through methods such as `findService(...)` and `findModule(...)`.

Low-level registry classes remain reusable structural primitives outside composition tests, but the production composition path owns mutation boundaries.

---

## `pro.liliya.core.authority`

Location:
`core/src/main/kotlin/pro/liliya/core/authority/`

Current files:

- `AuthorityModels.kt`
- `AuthorityPolicy.kt`
- `AuthorityManager.kt`
- `AuthorityDelegationModels.kt`
- `AuthorityDelegationPolicy.kt`
- `AuthorityDelegationManager.kt`
- `DirectAuthorityGrant.kt`

Purpose: security boundary deciding whether a principal may use a capability in a specific scope.

### Core authorization model

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Properties:
- default deny;
- explicit nonblank identities/reasons/scopes;
- legacy explicit grants restricted to GLOBAL scope;
- scoped grant exact match on principal + capability + scope;
- expiry valid only while `now < expiresAt`;
- grant/deny observable through `AuthorityManager`.

### Delegation

Delegation model includes delegator, delegate, capability, scope, reason, optional expiry, decision, and delegated grant.

Security properties:
- delegator != delegate;
- exact capability/scope;
- source active at decision time;
- child cannot outlive bounded source;
- bounded source cannot create unbounded child;
- only `DirectAuthorityGrant` can be passed to `AuthorityDelegationPolicy` as source;
- delegated grants can convert to `ScopedAuthorityGrant` for authorization while retaining `DELEGATED` provenance;
- delegated grant cannot become a source merely by conversion.

Critical nuance: provenance flags alone were found insufficient because a caller could reconstruct a direct-looking scoped grant. Type separation via `DirectAuthorityGrant` became the final security boundary.

Authority never executes actions.

---

# Open, unmerged Execution package (PR #20)

Branch:
`foundation/execution-v0.1`

Head:
`8117df9a6476e9826674e0e2dbbdffeb279bfcb8`

Not present in `main`.

Intended location:
`core/src/main/kotlin/pro/liliya/core/execution/`

Intended boundary:
`ExecutionRequest → AuthorityManager → Granted? → ExecutionExecutor → ExecutionResult`

Hard rule: if authority denies, executor invocation count must remain zero.

Execution may report succeeded/rejected/failed, but it does not make policy decisions itself.

Current status: PR #20 CI fails during test compilation due unresolved `throwable` references in `ExecutionFoundationContractTest.kt` lines 153–154. Implementation is paused and must not be described as production architecture until merged/frozen.

---

# Test layout and role

Location:
`core/src/test/kotlin/pro/liliya/core/`

Current test package directories include:

- `logging`
- `diagnostics`
- `runtime`
- `lifecycle`
- `recovery`
- `events`
- `services`
- `modules`
- `foundation`
- `observability`
- `authority`
- `ownership`

Tests are treated as executable architecture contracts. Important categories include:

- invariants/validation;
- deterministic order;
- concurrency/serialization;
- failure isolation;
- exact ownership and stale-handle protection;
- rollback behavior;
- lifecycle safety;
- observability/correlation continuity;
- security default-deny/scope/expiry/delegation boundaries;
- composition-path bypass prevention.

A future chat should inspect relevant contract tests before changing a subsystem, because many non-obvious rules were intentionally encoded there after real readiness defects were discovered.

---

# Dependency direction summary

Conceptually:

`logging`
→ `diagnostics`
→ `observability`
→ reliability/runtime layers
→ service/module composition
→ `foundation` composition root
→ `authority`
→ future `execution`

This is conceptual, not a claim that every package has a direct source-code import edge to the previous item.

Important boundaries:

- Runtime owns state.
- Lifecycle requests runtime changes.
- Recovery owns recovery attempts, not cognition.
- Events deliver; they do not own business state.
- Registries own registrations.
- ServiceManager owns started service instances.
- Modules own composition declarations, not service lifecycle.
- FoundationComposition owns production infrastructure wiring.
- Authority owns permission decisions.
- Execution must own side-effect invocation but cannot bypass Authority.
