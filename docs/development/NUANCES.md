# LiliyaCore — Important Nuances, Pitfalls, and Audit Findings

This file records details that are easy to miss when reading only class names or happy-path tests.

## 1. GREEN CI is necessary but not sufficient for freeze

Several important defects were discovered after earlier PRs were already GREEN. Therefore the project uses two gates:

1. CI gate — code compiles/tests pass.
2. Readiness audit — ownership, lifecycle, observability, bypasses, concurrency, and security boundaries are reviewed before a subsystem is declared frozen.

Do not declare a new subsystem frozen solely because its first PR passes.

---

## 2. Exact instance/handle ownership is a core invariant

String IDs are identity labels, not sufficient ownership tokens.

Registries use exact registration handles so stale owners cannot remove replacements.

Whenever future systems register resources, subscribe listeners, acquire leases, open files, own jobs, or start executors, prefer exact ownership handles/instances over later re-resolution by ID.

---

## 3. Registry ownership and lifecycle ownership are different

A service can be registered and not started. A started service can outlive a registry mutation unless exact lifecycle ownership is retained.

Do not collapse structural registration and active lifecycle ownership.

---

## 4. Module structure and service execution are different

Modules group/depend on structural capabilities/services. Services have executable lifecycle.

Uninstall safety must consider both structural dependents and started service state.

---

## 5. Raw registries are intentionally private in FoundationComposition

Public raw registries would let callers mutate ownership without CoreObservability. Add explicit observable ownership APIs instead of re-exposing mutable stores.

---

## 6. Low-level primitives may remain logging-agnostic

Not every primitive should depend on CoreObservability. The anti-pattern is hidden logger creation, not low-level purity.

---

## 7. Hidden LoggerFactory defaults are dangerous

Composition distributes logging/diagnostic infrastructure explicitly. Do not add hidden global logger acquisition to subsystem constructors.

---

## 8. Logging and Diagnostics are complementary, not interchangeable

Logging is technical operational trace. Diagnostics records meaningful state/failure/contract information. Significant operations should use the shared observation path consistently.

---

## 9. Correlation continuity is a system invariant

A significant operation should remain traceable across subsystem boundaries. Do not silently generate unrelated correlation roots or introduce hidden ThreadLocal/global context as a shortcut.

---

## 10. Global sequence objects are not ownership singletons

Process-wide ordering infrastructure is not precedent for global mutable business registries/managers.

---

## 11. Event publication is synchronous and deterministic by design

Do not assume asynchronous behavior, retry, persistence, or queue semantics. Those would require a new explicit layer and contracts.

---

## 12. Recovery does not own semantic intelligence

Recovery owns reliability decisions/attempts, not planning, reasoning, autonomous intent or semantic policy.

---

## 13. Authority is fail-closed

No matching authority means denied. Capability existence does not imply permission. Scoped grants require exact principal/capability/scope.

---

## 14. Expiry boundary is strict

A scoped grant is valid only when `now < expiresAt`. At `now == expiresAt`, it is already expired.

---

## 15. Delegation uses type-level direct provenance

Delegation source provenance is represented by a stronger type boundary rather than a freely forgeable flag. Do not weaken this back to caller-constructible metadata checks.

---

## 16. Delegation cannot amplify authority

Current v0.1 delegation is intentionally bounded/non-amplifying. Future multi-hop delegation requires a separate security design.

---

## 17. Authority and Execution must remain separate

Authority decides permission. Execution performs side effects only after fresh Authority. No public path may bypass the mandatory Authority boundary.

---

## 18. Clean branches are preferred over polluted microcommit history

When experimentation becomes too noisy to review safely, reconstruct from a known-good baseline rather than merge confusing history.

---

## 19. Tests are executable architecture contracts

Contract tests encode non-obvious decisions: concurrency, stale ownership, exact ordering, failure isolation, expiry, correlation, security boundaries and readiness constraints.

---

## 20. Frozen does not mean immutable forever

A demonstrated correctness/security bug may justify a focused fix, but it requires reproduction/contract, minimal scope, CI, readiness reasoning and journal update.

---

## 21. Current project scope is LiliyaCore only

Predecessor repositories may be donors/history but do not automatically define current architecture.

---

## 22. License is not Authority

Entitlement to use a protected feature/model/package does not grant arbitrary operation permission. Protected side effects still pass the normal Authority boundary.

---

## 23. Device binding must not be derived from public hardware identifiers

Readable hardware identifiers are not secrets. Preferred Android design uses Keystore-backed non-exportable keys and separate wrapped data-encryption keys.

---

## 24. Model-protection keys and user-data keys are separate domains

Model assets, runtime assets, user cognitive data, backups and update packages must not share one master key.

---

## 25. License expiry must not destroy user cognitive data

Revocation/expiry may deny protected model use but must not intentionally make user-owned history unrecoverable.

---

## 26. Anti-debug and obfuscation are delay layers, not trust roots

They raise reverse-engineering cost but cannot guarantee plaintext never exists during computation.

---

## 27. License failure is fail-closed, not deliberately corrupted AI output

Denied entitlement should produce explicit typed denial/error, not garbage/NaN/deceptive inference output.

---

## 28. Offline licensing needs explicit lease and trusted-time semantics

Do not silently trust mutable wall-clock time as the sole expiry authority for high-value entitlements.

---

## 29. Liliya Network and Update System are transports/orchestrators, not trust roots

Transport origin does not replace signature, compatibility, Authority, anti-rollback or local-policy validation.

---

## 30. Plaintext model files must not be intentionally materialized on disk

Protected loading should use authenticated encryption and bounded working buffers, not convenience plaintext temp files.

---

## 31. A prepared mutation or authorization receipt is not permission

Fresh exact preflight and fresh target-scoped Authority are mandatory at the real side-effect boundary.

---

## 32. Prepared target must match the fresh application target

Do not authorize one target while applying a structurally different prepared payload to another.

---

## 33. Claim ownership is not completion authority

Owning/reserving work is not authority to declare side effects committed.

---

## 34. Idempotency must bind semantic identity, not just a key string

A completed key cannot mean success for arbitrary future requests that reuse the string.

---

## 35. In-memory completed outcome is not crash-durable exactly-once

Process-lifetime replay/ownership guarantees must not be described as durable across restart, reboot, restore or migration.

---

## 36. Controlled learning apply has one explicit correlation lineage

The real apply path uses explicit root/child context lineage. Do not replace this with hidden global context.

---

## 37. Structural provenance strings are evidence, not credentials

Coordination/Planning/Reasoning bridges use source IDs and source-reference strings to prove structural consistency between exact live records.

These strings are **not** cryptographic authenticity, capability tokens, permission receipts or Authority grants. A caller that can reproduce the same text does not gain permission or execution power. Trust comes from combining structural provenance with exact live generation checks and the downstream fresh Authority boundary.

---

## 38. Controlled cognition is TOCTOU-sensitive

For a compound bridge that performs a write, one preflight before the write is insufficient. Governance or provenance can change between validation and commit.

Frozen coordinated cognitive bridges therefore use this pattern:

`fresh preflight → exact source validation → write → fresh preflight → exact source revalidation → success OR exact compensation`.

Planning, Reasoning, Decision and Orchestration coordination bridges all follow this rule. The final coordinated execution guard similarly repeats fresh readiness and full-chain validation immediately before delegating into frozen Controlled Orchestration.

---

## 39. Exact compensation owns only the exact generation it created

A bridge may remove only the exact downstream generation returned by its own install operation.

If a stale ownership handle fails because another generation has replaced it, the replacement is outside the stale operation's ownership and must be preserved. Never convert a stale handle into an ID-only delete.

---

## 40. ABA-safe failed removal is not automatically a critical failure

`ownership.remove() == false` has two materially different meanings:

- the same exact created generation is still live and could not be removed — this is an invariant failure and should surface explicit `Failed` plus CRITICAL observability;
- the exact generation is already gone and a newer replacement is live — compensation has no right to remove that replacement, and this is not a fatal compensation failure.

Always inspect the currently live generation before escalating a failed exact removal.

---

## 41. Coordinated cognitive private text must remain outside operational observability

Coordination purpose, deliberation objective, Planning goal/steps, Reasoning premises/analysis/conclusion, Decision options/rationale and Orchestration description are private cognitive content.

Operational bridge observability may expose structural IDs, exact generations, counts, selected option IDs and provenance references when needed for diagnosis, but not the private text itself.

---

## 42. Coordination governance is not a new permission stack

Controlled Agent Coordination v0.1 terminates at the existing frozen execution path:

`coordination final guard → ControlledOrchestrationExecution → fresh Authority → frozen Execution`.

The coordination layer must not grow its own Capability grant, Authority model, executor, scheduler, implicit retry loop, voting/quorum/consensus, or hidden fan-out mechanism. Its job is exact live governance/provenance validation, not power amplification.