# Runtime Hardening v0.1 — Architecture Contract

Status: **PROPOSED — architecture gate required before implementation**.

## Direction

`approved protected-model target → exact runtime session ownership → bounded model activation → supervised use → explicit fault classification → fail-closed isolation/retirement → controlled replacement/recovery`

Runtime Hardening v0.1 consumes the frozen Protected Model Package / Loader v0.1 boundary. It must not reinterpret package authenticity, License policy, Authority or execution permission.

## Mandatory separations

`Runtime Session != Protected Model Package != Model DEK != License != Capability != Authority != Execution`

`Loaded model != authorized action`

`Healthy runtime != valid License entitlement`

`Recovered process state != replay permission`

`Crash recovery != retry authorization`

`Runtime ownership != durable permission`

`Model activation != autonomous execution`

## Scope

Runtime Hardening v0.1 defines process-local safeguards around activation and use of one exact protected-model generation. The phase covers:

- exact runtime session identity/generation and stale/ABA-safe ownership;
- bounded activation and publication of one already-approved protected-model target;
- explicit lifecycle states for prepared, active, quiescing, failed and retired sessions;
- fail-closed behavior when the active model/session becomes stale, invalid or unavailable;
- supervised operation admission with explicit in-flight ownership and completion/release;
- bounded concurrency and resource-limit contracts independent of model semantics;
- explicit fault categories and structural observability without secret/model-payload leakage;
- deterministic replacement/retirement barriers so stale workers cannot publish or complete into a newer session;
- controlled restart/recovery classification without hidden retry, replay or reconciliation;
- Android/process integration evidence only where platform behavior is actually required.

This phase does not grant Authority, decide License entitlement, issue offline leases, download/update model packages or execute external capabilities.

## Exact runtime ownership

Every active runtime model instance is owned by an exact `(RuntimeModelSessionId, RuntimeModelSessionGeneration)` pair and is bound to one exact `ProtectedModelReference`.

Requirements:

- generations are positive and monotonic within the owning registry/composition;
- duplicate live ownership fails closed;
- generation overflow fails closed;
- retirement is exact-owner only;
- replacement invalidates all prior activation/use tickets immediately;
- stale or ABA-reused tickets cannot publish, complete or release state into the replacement session;
- session identifiers and references are not permission tokens.

No global mutable authorization registry may be introduced to implement runtime ownership.

## Activation boundary

Activation accepts only a value already produced through the frozen protected-model access path. Runtime Hardening does not bypass or duplicate package verification, policy evaluation or authenticated decryption.

Activation must:

1. bind one exact protected-model reference to one exact runtime session generation;
2. enforce configured structural/resource bounds before publication;
3. create no durable plaintext model copy as part of the hardening contract;
4. publish the active session only behind the same exact ownership barrier used by replacement/retirement;
5. fail closed on stale ownership, activation failure or publication race.

Successful activation means only that the runtime may host the model. It does not imply Authority or capability execution permission.

## Operation supervision

Runtime use is represented by exact operation tickets bound to one active runtime session generation.

Required behavior:

- no ticket is issued for a missing, failed, quiescing or retired session;
- configurable maximum in-flight operations is enforced before admission;
- each admitted operation has exact ownership and exactly one terminal local release;
- replacement/retirement prevents new admission immediately;
- stale operations may finish local cleanup but cannot publish success/state into a newer session;
- timeout/cancellation policy is explicit at the supervisor boundary and is not inferred from wall-clock globals hidden inside the model primitive;
- operation completion is not an Authority decision and cannot itself authorize a side effect.

## Fault containment

Faults are classified structurally. Minimum v0.1 categories:

- `ACTIVATION_REJECTED`
- `ACTIVATION_FAILED`
- `SESSION_STALE`
- `SESSION_UNAVAILABLE`
- `SESSION_FAILED`
- `RESOURCE_LIMIT_REJECTED`
- `OPERATION_REJECTED`
- `OPERATION_FAILED`
- `OPERATION_CANCELLED`
- `OPERATION_TIMEOUT`
- `RETIREMENT_FAILED`
- `RECOVERY_REJECTED`
- `PROVIDER_FAILED`

A fault in one model operation must not silently mutate ownership of another session or reinterpret a protected-model policy decision.

Secret exception messages, model plaintext, model-DEK material and protected payload bytes are not normal observability payloads. Reviewed failure rendering exposes structural category and exception class only where a throwable is retained.

## Resource bounds

Runtime Hardening v0.1 must expose explicit immutable configuration for reviewed process-local limits. At minimum:

- maximum live runtime sessions for the owning composition (v0.1 target: one active model session unless a later explicit version changes this);
- maximum in-flight operations per session;
- maximum activation payload/handle metadata accepted by the hardening boundary where applicable;
- bounded diagnostic/snapshot metadata;
- no unbounded hidden retry queue, replay queue or failure buffer.

Resource rejection is structural and fail-closed. It is not a signal to bypass policy or silently downgrade a security guarantee.

## Replacement and quiescence

Replacement is explicit:

`current exact session → stop new admission → establish quiescing barrier → retire/invalidate current ownership → publish new exact session`

V0.1 guarantees:

- old and new sessions are never simultaneously current in the same ownership registry;
- stale workers cannot publish runtime state after replacement;
- same-thread reentrant ownership mutation must not bypass the publication/replacement barrier;
- retirement does not destroy protected-model package or License evidence;
- replacement does not imply hidden replay of failed or in-flight operations.

Whether the owner waits for in-flight cleanup or cancels outstanding operations must be explicit in the operation supervisor contract and covered by tests; it must not emerge accidentally from lock scheduling.

## Recovery semantics

Recovery is classification plus a new explicit attempt, not invisible continuation.

V0.1 does not claim automatic crash-safe replay, exactly-once model operation execution or cross-process durable session resurrection.

Allowed recovery direction:

`observed runtime failure → exact failed-session retirement/invalidation → external decision to recover → fresh protected-model access/policy path where required → new exact runtime session generation`

No failed session may become active again merely because a provider becomes available later. A new activation attempt is required.

## Concurrency requirements

Contracts must cover at least:

- competing activation/replacement attempts;
- stale operation completion after replacement;
- retirement while operations are in flight;
- concurrent admission at the configured limit;
- same-thread reentrant mutation attempts from activation/publication callbacks;
- failure during publication/retirement cleanup;
- deterministic snapshot/current-session visibility.

Tests must avoid timing-only correctness where a deterministic latch/barrier can prove ordering.

## Android / platform boundary

Core runtime-hardening contracts remain Android-framework-free.

Platform-specific integration belongs outside Core and is required only for behavior that cannot be represented by the platform-neutral session/supervision contracts. This phase must not add decryption permission to the frozen Device Key surface or merge the dedicated protected-model Android key domain into runtime ownership.

If Android process/lifecycle integration is added, exact-head Android instrumentation is required before merge and must demonstrate only the platform behavior claimed by that slice.

## Observability and privacy

Normal runtime-hardening observability is structural:

- exact generations may be logged where non-secret and useful;
- protected-model package ids, model-DEK ids, key-protector ids/platform references and payload bytes remain redacted;
- exception messages are not normal diagnostic fields;
- no `println`, `System.out`, `System.err`, `printStackTrace` or equivalent production bypass is introduced;
- failure/transition events must be observable without requiring raw model data.

## Non-goals / explicit limitations

Runtime Hardening v0.1 does not claim:

- License issuance, refresh or offline-lease service integration;
- Authority grant or capability execution permission;
- model package download/update transport;
- sandboxing against a compromised OS or native process;
- anti-debugging or memory-forensics resistance;
- cross-process distributed locks;
- exactly-once model inference;
- transparent retry/replay/reconciliation;
- multi-model scheduling or load balancing;
- hardware rollback counters;
- process resurrection with durable in-flight operation restoration.

## Implementation slices

### Slice 1 — Core session models and exact ownership

Introduce runtime session/reference/configuration/failure models plus exact process-local ownership with generation and stale/ABA contracts.

### Slice 2 — Activation and publication barrier

Compose already-approved protected-model output into exact runtime activation with bounded publication and stale/reentrant protection.

### Slice 3 — Operation supervision and resource bounds

Add exact operation tickets, bounded admission, terminal release, stale-completion protection and explicit cancellation/timeout seams.

### Slice 4 — Failure containment, replacement and recovery readiness

Add quiescence/replacement/retirement contracts, failure classification, recovery-as-new-attempt semantics, concurrency and privacy/readiness tests.

### Slice 5 — Platform/runtime integration evidence if required

Add only the Android/process integration actually required by the Core contract, with real instrumentation for each claimed platform property. If no platform-specific behavior is required, this slice becomes an explicit no-op evidence checkpoint rather than inventing Android coupling.

### Slice 6 — Formal freeze checkpoint

Record exact-head/merge-main evidence, focused audits, explicit limitations and freeze the phase before License service/offline-lease issuance + refresh begins.

## Gate

This architecture contract may merge only after its exact head passes both required Core and Android CI jobs and a focused architecture/security/privacy/logging/readiness audit finds no blocker. The merge must use the verified expected head SHA, followed by merge/main Core + Android GREEN before Slice 1 begins.

Runtime Hardening v0.1 becomes formally **FROZEN** only after all implementation slices and the final freeze checkpoint satisfy the same gate discipline.