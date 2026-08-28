# LiliyaCore — Detailed Development History

Scope: this history covers only the current repository `Vikrot123/LiliyaCore`. Predecessor projects are intentionally excluded.

Status labels:
- **VERIFIED** — confirmed from GitHub PR/commit/CI metadata or current repository state.
- **OPEN** — work exists but is not merged/frozen.

## Repository bootstrap — VERIFIED

The earliest verified baseline referenced by the repository's PR history is main commit `73f8e9ce0bb1b4dd2391fc6024fd8fe448168f33`, the base of PR #1.

From the beginning of this repository, development was deliberately foundation-first and core-only. The accepted build order became:

`Logging → Diagnostics → Runtime → Lifecycle → Recovery → Events → Services → Modules → Observability → Composition/Ownership → Authority → Execution`

The core engineering rules established during this phase were: explicit ownership, contracts before complexity, failures must be observable, correlation must cross subsystem boundaries, no hidden global logger ownership, feature branches instead of direct `main` modification, and CI-green merge gates.

---

## PR #1 — Structured Logging Core — VERIFIED

Title: `Foundation v0.1: Structured Logging Core`

- base: `73f8e9ce0bb1b4dd2391fc6024fd8fe448168f33`
- head: `88e1dbda688ddad186290e463c37e43ad84f6c50`
- PR commits: 35
- resulting main used by PR #2: `68e46d45b22e3308ebdd023b8e49ca2d9cde2dac`

Purpose: make technical behavior observable before adding runtime/lifecycle complexity.

Introduced structured logging primitives and contracts: `LogEvent`, `LogContext`, levels, logger/writer abstractions, correlation propagation, global sequencing, bootstrap buffering/replay, filtering, composite writers, safe writer isolation, writer failure observation, immutable metadata snapshots, and concurrency coverage.

Nuance: logging was explicitly defined as operational/technical telemetry, not semantic diagnostics. Later layers were required to preserve this distinction.

---

## PR #2 — Diagnostics Core — VERIFIED

Title: `Foundation v0.1: Diagnostics Core`

- base: `68e46d45b22e3308ebdd023b8e49ca2d9cde2dac`
- head: `6be34d04a735510c9b28030745cf36c3cd2c3c56`
- commits: 12
- resulting main used by PR #3: `d9e57b5788242041c10f8cdac9434e8a234fe03e`

Purpose: add semantic/health observability on top of structured technical logging.

Added diagnostic events, severity, global diagnostic sequencing, recorder, sink abstraction, in-memory sink, safe sink failure isolation, failure observer, metadata snapshots, correlation preservation, and concurrency contracts.

Durable distinction:
- Logging = operational trace/telemetry.
- Diagnostics = meaningful failure/state/contract information.

---

## PR #3 — Runtime Core — VERIFIED

Title: `Foundation v0.1: Runtime Core`

- base: `d9e57b5788242041c10f8cdac9434e8a234fe03e`
- head: `a12b606b0f333ddafe517b5a41f5223bd3efb66b`
- commits: 11
- resulting main used by PR #4: `4d09c2e5e6717e70ecd8cf53f8a4ed81fb06b128`

Purpose: create the authoritative runtime state machine.

Added runtime state, transition model, transition rules/policy, atomic state holder, controller, explicit `Applied`/`Rejected` results, failure transitions, diagnostic observability, nominal transition contracts, and concurrency invariants.

Nuance: Runtime owns state authority. Future Lifecycle and higher layers request transitions; they do not maintain competing shadow state.

---

## PR #4 — Lifecycle Core — VERIFIED

Title: `Foundation v0.1: Lifecycle Core`

- base: `4d09c2e5e6717e70ecd8cf53f8a4ed81fb06b128`
- head: `7ed2969a59bd176454b995b1a51520dc8579ed7c`
- commits: 5
- resulting main used by PR #5: `baefaa4ff96d4feb48b1960676cd2fc2dca4cace`

Purpose: introduce lifecycle commands/phases while keeping Runtime as the only state authority.

Added lifecycle commands, phases, result model, controller, PREPARE → START → STOP contracts, repeated/invalid command rejection, and lifecycle/runtime diagnostic consistency.

Nuance: Lifecycle maps commands onto `RuntimeStateController`; it must not become a second state machine.

---

## PR #5 — Recovery Core — VERIFIED

Title: `Foundation v0.1: Recovery Core`

- base: `baefaa4ff96d4feb48b1960676cd2fc2dca4cace`
- head: `880dc6074547657dd119acc802c10db7a6a4d483`
- commits: 12
- Core CI #75: success
- resulting main: `affeb1bd2260629e351e214bc548213e346d7c83`

Purpose: define reliability recovery policy and ownership independently from semantic intelligence.

Added recovery request/action/decision/policy/coordinator, explicit active-target ownership, duplicate active recovery rejection, completion handling, ignored completion observability, policy boundaries, reuse contracts, and correlation-aware diagnostics.

Nuance: Recovery is reliability infrastructure only. It must not absorb planning/reasoning/semantic decision responsibilities.

---

## PR #6 — Event Core — VERIFIED

Title: `Foundation v0.1: Event Core`

- base: `affeb1bd2260629e351e214bc548213e346d7c83`
- head: `bb9815284106687f4a47f5ccfe03a4cceb54035a`
- commits: 8
- Core CI #86: success
- resulting main: `27237133683cf7184db930ec496953cc7e83b90d`

Purpose: provide a small deterministic in-process event foundation.

Semantics established: synchronous publication, deterministic subscription order, global event sequence, immutable envelope metadata, explicit cancellable subscription ownership, snapshot publication semantics, listener failure isolation, delivery reports, and correlation-aware diagnostics.

Nuances: callbacks run outside ownership lock; no persistent event store, retries, queue, asynchronous dispatcher, or global bus ownership was introduced.

---

## PR #7 — Service Core — VERIFIED

Title: `Foundation v0.1: Service Core`

- base: `27237133683cf7184db930ec496953cc7e83b90d`
- accepted branch: `foundation/services-v0.1-clean`
- accepted head: `cf57b11edcc3b80aaf87358619aa08979955b688`
- clean PR commits: 1
- Core CI #217: success
- resulting main: `2c69ddbaa697e2cea60f694c43bf14a1fc971f92`

Purpose: define lifecycle-manageable infrastructure services.

Added `CoreService`, `ServiceDescriptor`, thread-safe `ServiceRegistry`, deterministic dependency resolution with duplicate/missing/cycle rejection, and `ServiceManager` start/stop/rollback orchestration.

Development nuance: an earlier services branch became polluted with many incremental commits and was quarantined. It was not used as the merge source. A clean branch was rebuilt and merged.

Known limitation at this point: `ServiceManager` tracked started service IDs and re-resolved the registry later. Readiness audit subsequently identified that this could stop the wrong replacement instance or orphan the originally started service.

---

## PR #8 — Module Core — VERIFIED

Title: `Foundation v0.1: Module Core`

- base: `2c69ddbaa697e2cea60f694c43bf14a1fc971f92`
- branch: `foundation/modules-v0.1-clean`
- head: `b07779404004d7e880d469e0bf2c8b78ae09b9f2`
- commits: 1
- Core CI #231: success
- resulting main: `38e1d9cba1aa9a1102b2cb8d929b5976c67314ed`

Purpose: define structural modules without duplicating Service lifecycle ownership.

Added `CoreModule`, `ModuleDescriptor`, thread-safe `ModuleRegistry`, deterministic dependency resolver, duplicate/missing-dependency/cycle rejection, snapshots, ordering, and concurrency contracts.

Nuance: Modules are structural composition units. Services remain the executable lifecycle units.

---

## PR #9 — Observability Integration — VERIFIED

Title: `Foundation v0.1: Observability Integration`

- base: `38e1d9cba1aa9a1102b2cb8d929b5976c67314ed`
- head: `e300440e847d66df6f2b2e1195e162ea97101314`
- commits: 12
- resulting main: `7a1e42b3e82de81724bd4ca90814eb1b26743b43`

Purpose: integrate Logging and Diagnostics without collapsing them into one system.

Introduced `CoreObservability`, which records one significant operation to both channels while preserving `LogContext`, correlation ID, metadata, and throwable information.

Integrated into Runtime, Lifecycle, Recovery, Events, and Service lifecycle.

Important defect discovered during this work: hidden subsystem defaults using `LoggerFactory.create(...)` could leak bootstrap/global writer state into isolated tests and obscure ownership. Accepted architecture changed to explicit observability injection from composition.

Nuance: a subsystem is not considered properly integrated if its important state/ownership/failure transitions are invisible.

---

## PR #10 — Composition Root — VERIFIED

Title: `Foundation v0.1: Composition Root`

- base: `7a1e42b3e82de81724bd4ca90814eb1b26743b43`
- head: `221e7b09078101b1196e566d3d93c3952759b399`
- commits: 2
- resulting main: `9d4ac704d40a5c8dc799b71358dbd26fe06a9783`

Purpose: make infrastructure ownership explicit.

`FoundationComposition` became the composition root for Diagnostics, `CoreObservability`, Runtime, Lifecycle, Recovery, Events, Services, Modules, and correlation context creation.

Nuance: module→service transactional composition was intentionally deferred because registries did not yet have exact ownership handles needed for safe rollback.

---

## PR #11 — Registry Ownership — VERIFIED

Title: `Foundation v0.1: Registry Ownership`

- base: `9d4ac704d40a5c8dc799b71358dbd26fe06a9783`
- head: `77e0e168a9436ef1d70a5d88bedb794d99cddfe0`
- commits: 3
- Core CI #262: success
- resulting main: `e78ad2f8b7f117b03eeebc4d86164e39d85a4b78`

Purpose: make registry ownership exact rather than ID-only.

Added exact `ServiceRegistration`/`ModuleRegistration` handles with unique tokens, idempotent unregister, immutable snapshots, and compare/remove semantics.

Nuance: this closes stale-handle / ABA removal. After an ID is unregistered and replaced, an old handle cannot remove the replacement owner.

---

## PR #12 — Module Service Composition — VERIFIED

Title: `Foundation v0.1: Module Service Composition`

- base: `e78ad2f8b7f117b03eeebc4d86164e39d85a4b78`
- head: `f7c92555bfa5e409f6335d76bdd7350ac6a6b9c4`
- commits: 2
- Core CI #267: success
- resulting main: `2aa86a45fb7785991aa1744b025334ca53a246ac`

Purpose: connect module ownership to its declared services transactionally.

Added `ModuleServiceInstaller`:
- module registration first;
- owned service registration through exact handles;
- conflict rollback only for registrations created by the current attempt;
- external owners preserved;
- reverse-order service release on uninstall;
- module released last;
- repeated install rejected;
- install/reject/rollback/uninstall observable.

At this stage dependency/lifecycle hardening was still incomplete and was caught in the next readiness audit.

---

## PR #13 — Foundation Readiness Hardening — VERIFIED

Title: `Foundation v0.1: Readiness Hardening`

- base: `2aa86a45fb7785991aa1744b025334ca53a246ac`
- head: `6557f6cd3533e0fa97846cbf49ac44e213e2fa69`
- commits: 4
- Core CI #274: success
- resulting main: `f4abe49e50783ff2a62a8dee329fa00e347cda68`

This audit was explicitly not a feature expansion. It fixed four readiness blockers:

1. `ModuleServiceInstaller` existed outside `FoundationComposition` ownership.
2. `ServiceManager` stored only service IDs, so unregister/replacement could make stop target the wrong instance.
3. module installation did not enforce `ModuleDependencyResolver` before ownership mutation.
4. module uninstall could remove structure while an owned service was still started or while another module depended on it.

Hardening results:
- `ServiceManager` now retains exact started `CoreService` instances;
- stop/rollback target the exact started instances rather than current registry lookup;
- module dependency graph is validated before registration;
- uninstall is rejected while owned service is started;
- uninstall is rejected while another registered module depends on target;
- installer is wired into `FoundationComposition`;
- new rejection paths remain observable.

---

## PR #14 — Registry Observability Encapsulation — VERIFIED

Title: `Foundation v0.1: Registry Observability Encapsulation`

- base: `f4abe49e50783ff2a62a8dee329fa00e347cda68`
- head: `5ce47aa87c6b7399cf733780226e80b361c94e21`
- commits: 2
- Core CI #279: success
- resulting main/foundation freeze merge: `15c0727d5a22eb731e802d3b59105bf517d24807`

Final Foundation audit found that `FoundationComposition` publicly exposed raw `ServiceRegistry` and `ModuleRegistry`. That allowed ownership mutation to bypass `CoreObservability`.

Fix:
- raw registries became private inside composition;
- low-level registry classes remain structural/logging-agnostic primitives;
- composition exposes read-only lookup (`findService`, `findModule`);
- production ownership mutation flows through observable composition paths, especially `ModuleServiceInstaller`;
- composition contract proves module/service ownership produces Logging + Diagnostics with correlation.

After this gate, **Core Foundation v0.1 was frozen**.

Frozen chain:
`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

---

# Authority phase

## PR #15 — Explicit Capability Grants — VERIFIED

Title: `Authority v0.1: Explicit Capability Grants`

- base: `15c0727d5a22eb731e802d3b59105bf517d24807`
- head: `1d9db451835ea1fc60af32539748fa9db70c4896`
- commits: 1
- Core CI #283: success
- resulting main: `55781d1f8dda48467dc1206e5d6c9507a33b8762`

Introduced capability/authority identity and explicit authorization:
- `CapabilityId`;
- `AuthorityPrincipal`;
- `AuthorityRequest` with explicit reason;
- `AuthorityDecision`;
- `AuthorityPolicy`;
- default-deny `ExplicitGrantAuthorityPolicy`;
- observable `AuthorityManager`.

Boundary: capability existence is not authority, and authority does not execute actions.

---

## PR #16 — Scoped Expiring Grants — VERIFIED

Title: `Authority v0.1: Scoped Expiring Grants`

- base: `55781d1f8dda48467dc1206e5d6c9507a33b8762`
- head: `08b67ab280176f1c01c3424111ee430ca827e3a2`
- commits: 1
- Core CI #287: success
- resulting main: `72fbb80cb5f15c41ad8bf7a57bec6ca069d790d4`

Added `AuthorityScope`, `ScopedAuthorityGrant`, and `ScopedGrantAuthorityPolicy`.

Security semantics:
- exact principal + capability + scope match;
- no wildcard scopes;
- optional expiry;
- valid only while `now < expiresAt`;
- at `now == expiresAt`, grant is already expired;
- scope included in observability metadata.

---

## PR #17 — Bounded One-Level Delegation — VERIFIED

Title: `Authority v0.1: Bounded One-Level Delegation`

- base: `72fbb80cb5f15c41ad8bf7a57bec6ca069d790d4`
- head: `f9bcdca894d0943ffd2d76ddef8b2988985a70f0`
- commits: 1
- Core CI #291: success
- resulting main: `8bacb0bf0c8efc2add9a9031402f7b58cdf665fc`

Introduced delegation request/decision/grant, policy, and observable delegation manager.

Initial intended invariants:
- delegator and delegate differ;
- exact capability/scope source ownership;
- source must be active;
- bounded source cannot create unbounded child;
- child expiry cannot exceed source expiry;
- one-level delegation only.

Readiness audit then discovered that converting `DelegatedAuthorityGrant` to `ScopedAuthorityGrant` erased origin and could permit re-delegation.

---

## PR #18 — Preserve Delegation Provenance — VERIFIED

Title: `Authority v0.1: Preserve Delegation Provenance`

- base: `8bacb0bf0c8efc2add9a9031402f7b58cdf665fc`
- head: `8ef4c49663b1a8d408ae951f8089c8c8d71c7807`
- commits: 1
- Core CI #295: success
- resulting main: `823e21e19900aa6fb381852baf0e13ae6faca37a`

Added `AuthorityGrantOrigin` (`DIRECT`/`DELEGATED`) and preserved delegated origin when converting for authorization use. Delegation policy accepted only grants marked DIRECT.

A second readiness audit found two remaining weaknesses:
1. legacy `ExplicitGrantAuthorityPolicy` ignored scope, so a legacy grant could authorize a non-global scoped request;
2. public provenance fields could be reconstructed as `DIRECT`, so origin flags alone were not a strong enough type boundary.

---

## PR #19 — Final Authority Readiness Hardening — VERIFIED

Title: `Authority v0.1: Final Readiness Hardening`

- base: `823e21e19900aa6fb381852baf0e13ae6faca37a`
- head: `0aa61da142fecd5952f8e0ab6068f886f7fd6a01`
- commits: 1
- Core CI #300: success
- resulting main: `638bbfdc51b9446f637a11c922a050b5289e63d7`

Fixes:
- legacy explicit grants restricted to `AuthorityScope.GLOBAL`;
- new `DirectAuthorityGrant` type introduced;
- `AuthorityDelegationPolicy` source collection changed to `DirectAuthorityGrant`;
- scoped grants remain authorization representation/provenance, not delegation-source authority;
- delegated grant conversion preserves `DELEGATED` origin;
- tests updated to enforce the type-level boundary.

After this audit, **Authority v0.1 was frozen**.

Frozen Authority guarantees:
- default deny;
- explicit principal/capability/scope/reason;
- exact scoped grants;
- strict expiry boundary;
- bounded one-level delegation;
- direct-only delegation source type;
- observable grant/deny and delegation decisions;
- no action execution inside Authority.

---

# Execution phase

## PR #20 — Authority-Gated Execution Foundation — OPEN / NOT MERGED

Title: `Execution v0.1: Authority-Gated Execution Foundation`

- base: `638bbfdc51b9446f637a11c922a050b5289e63d7`
- branch: `foundation/execution-v0.1`
- head: `8117df9a6476e9826674e0e2dbbdffeb279bfcb8`
- commits: 1
- changed files: 4

Intended design:
`ExecutionRequest → AuthorityManager → Granted? → ExecutionExecutor → ExecutionResult`

Proposed types/components:
- `ExecutionActionId`;
- `ExecutionRequest`;
- `ExecutionExecutor` adapter boundary;
- `ExecutionResult.Succeeded`;
- `ExecutionResult.Rejected`;
- `ExecutionResult.Failed`;
- `ExecutionManager`.

Hard invariant: denied authority must result in zero executor calls.

Executor exceptions are intended to be isolated as explicit failed execution results. Authority and execution observations are intended to retain one correlation chain.

Deliberately excluded from this PR: Android adapters, shell, device control, retries, queues, cancellation, background scheduling.

### CI #304 failure — VERIFIED

Core CI run #304 (`33192528038`) failed at `:core:compileTestKotlin`.

Compiler errors:
- `ExecutionFoundationContractTest.kt:153:56` — unresolved reference `throwable`;
- `ExecutionFoundationContractTest.kt:154:63` — unresolved reference `throwable`.

This failure is still unresolved because implementation work was explicitly paused. PR #20 remains open and unmerged.

---

# PR #21 — Durable Development Journal — OPEN DOCUMENTATION WORK

Title: `Docs: Durable Development Journal`

- branch: `docs/development-journal-v1`
- initial docs commit: `256b6c0fd7e7dde90044cba449070621482af2b7`
- base: current frozen main `638bbfdc51b9446f637a11c922a050b5289e63d7`

Purpose: make Git-backed project continuity independent of chat-history retention.

The journal is being expanded into a detailed technical record containing current state, history, architecture, source layout, subsystem responsibilities, known nuances, decisions, verification rules, and exact resume instructions.

Execution implementation remains paused while this documentation work is performed.
