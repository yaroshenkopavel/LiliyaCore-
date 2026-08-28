# LiliyaCore — Important Nuances, Pitfalls, and Audit Findings

This file records details that are easy to miss when reading only class names or happy-path tests.

## 1. GREEN CI is necessary but not sufficient for freeze

Several important defects were discovered after earlier PRs were already GREEN. Therefore the project uses two gates:

1. CI gate — code compiles/tests pass.
2. Readiness audit — ownership, lifecycle, observability, bypasses, concurrency, and security boundaries are reviewed before a subsystem is declared frozen.

Examples:
- ServiceManager ID-only started ownership survived earlier tests but failed readiness reasoning.
- Authority delegation provenance needed two additional hardening passes after initially GREEN delegation code.

Do not declare a new subsystem frozen solely because its first PR passes.

---

## 2. Exact instance/handle ownership is a core invariant

String IDs are identity labels, not sufficient ownership tokens.

Registries use exact registration handles so stale owners cannot remove replacements.

Service lifecycle stores exact started `CoreService` instances so a later registry replacement cannot make stop target a different object.

Whenever future systems register resources, subscribe listeners, acquire leases, open files, own jobs, or start executors, prefer exact ownership handles/instances over later re-resolution by ID.

---

## 3. Registry ownership and lifecycle ownership are different

A service can be registered and not started. A started service can outlive a registry mutation unless exact lifecycle ownership is retained.

Therefore:
- `ServiceRegistry` answers registration identity.
- `ServiceManager` answers started lifecycle ownership.

Do not collapse these responsibilities.

---

## 4. Module structure and service execution are different

Modules group/depend on structural capabilities/services. Services have executable lifecycle.

`ModuleServiceInstaller` coordinates registration transactionally but does not replace `ServiceManager`.

Uninstall safety must consider both:
- structural dependents;
- started service state.

---

## 5. Raw registries are intentionally private in FoundationComposition

This was a deliberate final Foundation freeze fix.

Why: public raw registries allowed callers to mutate service/module ownership without `CoreObservability`.

Do not re-expose them simply for convenience. If a new production operation needs mutation, add an explicit observable ownership API instead.

---

## 6. Low-level primitives may remain logging-agnostic

Not every primitive should depend on `CoreObservability`.

Registry classes are intentionally structural. Production observability can be enforced at the composition/manager/installer boundary.

The anti-pattern is hidden logger creation, not low-level purity.

---

## 7. Hidden LoggerFactory defaults are dangerous

A prior integration default effectively created subsystem observability by calling `LoggerFactory.create(...)` internally. This leaked bootstrap/global writer state into tests and blurred ownership.

Rule: composition distributes logging/diagnostic infrastructure explicitly. Do not add hidden global logger acquisition to new subsystem constructors.

---

## 8. Logging and Diagnostics are complementary, not interchangeable

Logging is technical operational trace.

Diagnostics records meaningful state/failure/contract information.

`CoreObservability` is the bridge for significant operations that belong in both.

Avoid:
- using Diagnostics as a full logger;
- logging meaningful rejected ownership/security decisions without corresponding diagnostic observability;
- emitting two independently constructed contexts for one operation.

---

## 9. Correlation continuity is a system invariant

A significant operation should remain traceable across subsystem boundaries.

Root and child contexts have explicit correlation lineage. Some contracts use exactly one correlation ID through an operation; child context creation may use a new correlation ID with `parentCorrelationId` pointing to the root.

When adding a subsystem, decide intentionally whether it continues the same context or creates a child. Do not silently generate unrelated IDs.

---

## 10. Global sequence objects are not treated as ownership singletons

`GlobalLogSequence`, `GlobalDiagnosticSequence`, and `GlobalEventSequence` are deliberate process-wide ordering infrastructure.

They should not accumulate business ownership/state beyond sequence generation.

Do not use their existence as precedent for adding global mutable registries/managers.

---

## 11. Event publication is synchronous and deterministic by design

The current Event Foundation is deliberately small.

Do not assume asynchronous behavior, retry, persistence, or queue semantics.

If later event infrastructure needs those properties, it should be a new explicit layer with new contracts rather than silently changing `EventBus` semantics.

---

## 12. Recovery does not own semantic intelligence

Recovery decides reliability actions from failure/recovery policy and owns active recovery attempts.

It must not become the place for planning, reasoning, long-term memory decisions, or autonomous intent.

---

## 13. Authority is fail-closed

No matching authority means denied.

Capability existence does not imply permission.

Legacy explicit grants apply only to GLOBAL scope.

Scoped grants require exact principal/capability/scope.

Do not introduce implicit wildcard behavior unless a future reviewed policy model explicitly defines it.

---

## 14. Expiry boundary is strict

A scoped grant is valid only when:

`now < expiresAt`

At exactly `now == expiresAt`, it is already expired.

This boundary has a contract and must remain consistent in future grant/delegation models.

---

## 15. Delegation uses type-level direct provenance

An earlier provenance-only model used `AuthorityGrantOrigin.DIRECT/DELEGATED`. Audit found callers could reconstruct a `ScopedAuthorityGrant(origin = DIRECT)`.

Final model introduces `DirectAuthorityGrant` as the source type required by `AuthorityDelegationPolicy`.

Do not weaken this back to a freely forgeable flag check.

---

## 16. Delegation cannot amplify authority

Current v0.1 delegation is intentionally one-level and exact-scope.

Child grant cannot:
- change capability;
- change scope;
- outlive bounded source;
- become unbounded if source is bounded;
- become a transitive delegation source.

Any future multi-hop/delegation-chain design must be a separate security design, not a small convenience change.

---

## 17. Authority and Execution must remain separate

Authority decides permission.

Execution performs side effects only after authority grants.

A caller must not be able to reach a real device/shell/Android executor through a public path that bypasses `AuthorityManager` or an equivalent mandatory gate.

The open PR #20 is specifically trying to establish this boundary before real adapters exist.

---

## 18. PR #20 is not production architecture yet

Execution files exist only on `foundation/execution-v0.1`.

CI #304 currently fails at test compilation due unresolved `throwable` references. This is a known checkpoint, not something to silently repair in documentation work.

Until PR #20 is fixed, GREEN, audited, and merged, `execution` must be described as proposed/open work rather than frozen `main` architecture.

---

## 19. Clean branches are preferred over polluted microcommit history

The services phase demonstrated that an experimental branch can become too noisy to trust/review. The accepted solution was to quarantine it and rebuild a clean branch.

When experimentation becomes messy, prefer reconstructing a coherent branch from a known baseline instead of merging historical noise.

---

## 20. Tests are executable architecture contracts

Contract tests encode many non-obvious decisions: concurrency, stale ownership, exact ordering, failure isolation, expiry boundaries, correlation continuity, and security restrictions.

Before modifying a subsystem, read its contracts and any cross-layer readiness tests. A class implementation alone may not reveal the full intended semantics.

---

## 21. Frozen does not mean immutable forever

Foundation v0.1 and Authority v0.1 are frozen baselines, meaning they should not be casually redesigned while later layers are built.

A demonstrated correctness/security bug may justify a focused fix, but such a fix requires:
- reproduction/contract;
- minimal scope;
- CI;
- readiness reasoning;
- journal update.

---

## 22. Current project scope is LiliyaCore only

This journal intentionally does not import development history from predecessor repositories.

If older code is ever examined as a donor, that is a separate comparison activity. It does not automatically become part of the current architecture or current project history.
