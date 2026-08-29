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

---

## 18. Clean branches are preferred over polluted microcommit history

When experimentation becomes too noisy to review safely, quarantine or reconstruct the work from a known-good baseline rather than merging confusing history.

---

## 19. Tests are executable architecture contracts

Contract tests encode many non-obvious decisions: concurrency, stale ownership, exact ordering, failure isolation, expiry boundaries, correlation continuity, and security restrictions.

Before modifying a subsystem, read its contracts and any cross-layer readiness tests. A class implementation alone may not reveal the full intended semantics.

---

## 20. Frozen does not mean immutable forever

Frozen baselines should not be casually redesigned while later layers are built.

A demonstrated correctness/security bug may justify a focused fix, but such a fix requires:
- reproduction/contract;
- minimal scope;
- CI;
- readiness reasoning;
- journal update.

---

## 21. Current project scope is LiliyaCore only

This journal intentionally does not import development history from predecessor repositories.

If older code is ever examined as a donor, that is a separate comparison activity. It does not automatically become part of the current architecture or current project history.

---

## 22. License is not Authority

A valid license or entitlement answers whether a protected product feature/model/package may be available under commercial/security policy. It does not grant permission to perform arbitrary actions.

Protected operations must still pass the normal `Authority` boundary. Never make `license == valid` equivalent to `Authority == granted`.

---

## 23. Device binding must not be derived from public hardware identifiers

IMEI, Android ID, serial-like identifiers, Build fields, or other readable device attributes are identifiers, not cryptographic secrets.

Do not derive model/data master keys directly from HWID-style values. Preferred Android design is a non-exportable key generated/imported into Android Keystore, hardware-backed when available, used to wrap/unwrap independent data-encryption keys.

---

## 24. Model-protection keys and user-data keys are separate domains

Model assets, application/runtime protected assets, user cognitive memory, backup/export archives, and update packages must not all share one master key.

Compromise, revocation, rotation, or license expiry in one domain must not automatically expose or destroy another domain.

In particular, commercial model entitlement must not be the sole cryptographic root for a user's Memory/Knowledge database.

---

## 25. License expiry must not destroy user cognitive data

License expiry/revocation may deny protected inference/model use according to policy, but must not intentionally make user-owned cognitive history unrecoverable.

User-data encryption requires a recovery/export/migration design independent enough to preserve ownership and portability under documented policy.

---

## 26. Anti-debug and obfuscation are delay layers, not trust roots

`PR_SET_DUMPABLE`, debugger/Frida detection, symbol stripping, native obfuscation, string encryption, integrity checks, and similar mechanisms can raise reverse-engineering cost.

They cannot guarantee that plaintext never exists while the device computes on it and must not be treated as the sole protection boundary. Rooted/compromised-device resistance is defense-in-depth, not an absolute promise.

---

## 27. License failure is fail-closed, not deliberately corrupted AI output

Do not intentionally make Attention/MLP produce garbage, NaN, or deceptive answers when entitlement validation fails.

A protected operation should stop with an explicit typed denial/error before exposing a successful inference result. Security failures must remain observable and diagnosable without leaking secret material.

---

## 28. Offline licensing needs explicit lease and trusted-time semantics

Offline-first does not mean timeless licenses. Any expiring entitlement needs explicit policy for issued-at/not-before/expires-at, cached lease duration, clock rollback, monotonic-time evidence where available, and what happens when online revalidation is unavailable.

Do not silently trust mutable wall-clock time as the only expiry authority for high-value entitlements.

---

## 29. Liliya Network and Update System are transports/orchestrators, not trust roots

A package or license message is not trusted because it arrived through Liliya Network. Update signatures, license signatures, compatibility, local policy, Authority, anti-rollback and key state are independently validated.

Network compromise must not become automatic code/model/license compromise.

---

## 30. Plaintext model files must not be intentionally materialized on disk

Protected model packages should use authenticated encryption and bounded chunk/tensor decryption into working buffers. Do not create a convenience temporary plaintext `.gguf`/`.bin` file as part of normal protected loading.

Memory plaintext exposure can only be minimized and zeroized when no longer needed; it cannot be honestly claimed to be impossible during computation.

---

## 31. A prepared mutation or authorization receipt is not permission

Controlled learning intentionally separates preparation/readiness from mutation-time permission.

A stored prepared mutation, preflight receipt, or earlier authorization receipt must never be treated as durable permission to write Memory or Knowledge. Fresh exact preflight and fresh target-scoped Authority are mandatory at the real side-effect boundary.

This rule is particularly important when grants may expire or be revoked after preparation.

---

## 32. Prepared target must match the fresh application target

The mutation plan target is not trusted merely because its payload type matches it.

Before downstream write, the mutation-time authorization gate requires the prepared target to equal the target resolved from the current exact Application reference. This prevents a confused-deputy path such as authorizing a Knowledge scope but applying a prepared Memory payload.

---

## 33. Claim ownership is not completion authority

An exact mutation claim is a serialization/ownership handle. Public code may release it, but must not be able to mark the mutation successfully completed without the controlled downstream write.

`complete(...)` is therefore internal to the controlled learning module. Future designs should preserve this separation: owning or reserving work is not automatically authority to declare its side effect committed.

---

## 34. Idempotency must bind semantic identity, not just a key string

A completed idempotency key cannot simply mean “return success for any future request using this string”.

Controlled Learning v0.1 stores the completed plan privately and replays the previous structural receipt only when the incoming plan is value-equal to the completed plan. Reusing the completed key for another plan rejects. Reusing the completed mutation ID with another key/plan also rejects.

This prevents idempotency-key aliasing and mutation-ID aliasing.

---

## 35. In-memory completed outcome is not crash-durable exactly-once

Controlled Learning v0.1 reserves completed mutation IDs/keys and replays structural outcomes only for the lifetime of the composition/process.

Do not describe this as exactly-once across application restart, process death, device reboot, restore, or migration. Those guarantees require a later persistent transaction/outcome store integrated with encrypted storage and recovery policy.

When persistence is added, it must preserve the same semantic identity and fail-closed replay rules rather than treating storage durability as permission to weaken them.

---

## 36. Controlled learning apply has one explicit correlation lineage

The real apply path is not a collection of unrelated root logs.

It uses one operation root and explicit child contexts through claim, Authority, Memory/Knowledge mutation, completion/release, compensation, and final result observation. Logging and Diagnostics for the same significant operation must carry the same `LogContext`.

Do not replace this with ThreadLocal/global hidden context merely for convenience.
