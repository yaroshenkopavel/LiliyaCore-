# Runtime Hardening v0.1 — Architecture Contract

Status: **ACCEPTED — implementation active, Slice 4 complete, not yet frozen**.

Architecture gate and Slices 1–4 are implemented and verified. Slice 5 is the next allowed phase and is an evidence decision: add platform/runtime integration only where a v0.1 guarantee genuinely requires platform behavior; otherwise record an explicit no-op checkpoint. Runtime Hardening v0.1 becomes formally frozen only after Slice 5 and the final Slice 6 freeze checkpoint satisfy the required gates.

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
- explicit `PREPARED`, `ACTIVE`, `QUIESCING`, `FAILED` and `RETIRED` lifecycle states;
- fail-closed behavior when a current model/session becomes stale, invalid or unavailable;
- supervised operation admission with exact in-flight ownership and terminal release;
- bounded concurrency and resource limits independent of model semantics;
- structural fault classification and privacy-safe failure rendering;
- deterministic quiescing/replacement/retirement barriers;
- explicit cleanup failure handling and recovery readiness without hidden retry/replay/reconciliation;
- Android/process integration evidence only where a claimed property genuinely depends on platform behavior.

This phase does not grant Authority, decide License entitlement, issue offline leases, download/update model packages or execute external capabilities.

## Exact runtime ownership

Every active runtime model instance is owned by an exact `(RuntimeModelSessionId, RuntimeModelSessionGeneration)` pair and is bound to one exact `ProtectedModelReference`.

Requirements:

- generations are positive and monotonic within the owning registry/composition;
- duplicate live ownership fails closed;
- generation overflow fails closed;
- retirement is exact-owner only;
- stale/ABA ownership cannot mutate or delete a replacement;
- operation tickets bind to one exact runtime-session generation;
- session identifiers/references/tickets are structural ownership, not permission tokens;
- no global mutable authorization registry is introduced for runtime ownership.

## Activation boundary

Activation accepts only a value already produced through the frozen protected-model access path. Runtime Hardening does not bypass or duplicate package verification, policy evaluation or authenticated decryption.

Activation must:

1. bind one exact protected-model reference to one exact runtime-session generation;
2. enforce reviewed structural/resource bounds before publication;
3. create no durable plaintext model copy as part of this contract;
4. publish behind the same exact ownership barrier used by session transitions;
5. fail closed on stale ownership, activation failure or publication race.

Successful activation means only that the runtime may host the model. It does not imply Authority or capability execution permission.

## Operation supervision

Runtime use is represented by exact identity-based operation tickets bound to one active runtime-session generation.

Required and implemented behavior:

- no ticket is issued for missing, prepared, failed, quiescing or retired sessions;
- configured maximum in-flight operations is enforced atomically before admission;
- v0.1 permits exactly one operation supervisor per runtime-session registry so the bound cannot be bypassed by duplicate supervisors;
- each admitted operation has exactly one terminal local release;
- reconstructing the same ticket values does not grant release ownership;
- cancellation and timeout are explicit caller-selected terminal outcomes, not hidden wall-clock policy;
- stale operations may finish local cleanup but cannot publish success/state into a newer session;
- operation completion is not an Authority decision and cannot authorize a side effect;
- no terminal-history/retry/replay queue is retained.

## Fault containment

Minimum structural categories remain:

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

Slice 4 further establishes:

- `SESSION_FAILED` and `PROVIDER_FAILED` transition only the exact current `ACTIVE`/`QUIESCING` session to `FAILED`;
- repeated failure preserves the first retained structural reason;
- a fault cannot silently mutate replacement ownership;
- failed-session retirement does not imply cancellation of outstanding local operation tickets;
- retirement cleanup failure becomes `RETIREMENT_FAILED` and retains exact ownership fail-closed;
- ordinary failed-session retirement cannot discard a `RETIREMENT_FAILED` entry;
- failed explicit cleanup recovery reports `RECOVERY_REJECTED` while the underlying retained failure remains `RETIREMENT_FAILED`;
- exception messages, model plaintext, model-DEK material and protected payload bytes are not normal diagnostic payloads.

Reviewed result rendering exposes structural category and throwable class only where a throwable is retained.

## Resource bounds

Runtime Hardening v0.1 exposes explicit immutable configuration for reviewed process-local limits, including:

- exactly one live runtime session per owning registry/composition in v0.1;
- maximum in-flight operations per session;
- bounded diagnostic/snapshot metadata;
- no unbounded hidden retry queue, replay queue, terminal-history queue or failure buffer.

Resource rejection is structural and fail-closed. It is never a signal to bypass policy or downgrade a security guarantee.

## Replacement and quiescence

Normal replacement is explicit:

`current exact ACTIVE session → stop new admission via QUIESCING → drain exact local in-flight work → exact retirement cleanup → clear current ownership → later fresh activation/new generation`

V0.1 guarantees:

- entering `QUIESCING` atomically closes new admission;
- retirement never waits for or cancels in-flight work implicitly;
- while exact operations remain, `retireIfDrained` returns structural drain-required evidence;
- stale successful operations after quiescing release locally but cannot publish state;
- direct ownership retirement cannot bypass the supervised quiescing policy after an operation supervisor is claimed;
- old and new sessions are never simultaneously current in the same registry;
- stale workers cannot publish runtime state after replacement;
- same-thread reentrant ownership mutation cannot bypass publication/transition barriers;
- retirement does not destroy protected-model package or License evidence;
- replacement does not imply replay of failed or in-flight operations.

## Retirement cleanup failure

Retirement cleanup is an explicit caller-provided transition callback executed once behind the exact registry transition barrier.

If that cleanup fails:

- lifecycle becomes `FAILED`;
- retained structural reason becomes `RETIREMENT_FAILED`;
- current exact ownership remains present;
- replacement/activation remains blocked;
- ordinary `retireFailed()` cannot discard the uncertain cleanup state;
- the cleanup attempt is not retried automatically.

Uncertain cleanup can be cleared only through an explicit `recoverRetirementFailure` cleanup attempt. A failed recovery returns `RECOVERY_REJECTED`, leaves the exact session `FAILED/RETIREMENT_FAILED`, and performs no implicit retry. A successful recovery retires the exact failed ownership; it does not reactivate that session.

## Recovery semantics

Recovery is classification plus a new explicit attempt, never invisible continuation.

Allowed direction:

`observed runtime failure → exact failure/retirement handling → external decision to recover → fresh protected-model access/policy path where required → new exact runtime session generation`

No failed session may become active again merely because a provider or cleanup path becomes available later. A new activation attempt is required after ownership is safely retired.

V0.1 does not claim automatic crash-safe replay, exactly-once model operation execution, transparent retry/reconciliation or cross-process durable session resurrection.

## Concurrency requirements

Contracts cover:

- activation/publication versus retirement barriers;
- concurrent admission at configured limits;
- admission versus quiescing with only linearizable outcomes;
- final operation release versus drain retirement with only linearizable outcomes;
- stale operation completion after quiescing/replacement;
- same-thread reentrant mutation attempts from publication/transition callbacks;
- failure during retirement cleanup;
- stale failure/retirement attempts against replacement ownership;
- deterministic current-session visibility.

Tests must avoid timing-only correctness where deterministic synchronization or allowed-outcome reasoning can prove behavior.

## Android / platform boundary

Core runtime-hardening contracts remain Android-framework-free.

Platform-specific integration belongs outside Core and is required only for behavior that cannot be represented by the completed platform-neutral session/supervision contracts. Runtime Hardening must not add decryption permission to the frozen Device Key surface or merge dedicated cognitive/protected-model key domains into runtime ownership.

Slice 5 must begin by determining whether any remaining v0.1 guarantee actually needs Android/process/lifecycle integration. If yes, add only that minimum surface with real instrumentation. If no, record an explicit no-op evidence checkpoint rather than inventing platform coupling.

## Observability and privacy

Normal runtime-hardening observability is structural:

- exact generations may be logged where non-secret and useful;
- protected-model package ids, model-DEK ids, key-protector ids/platform references and payload bytes remain redacted;
- exception messages are not normal diagnostic fields;
- no `println`, `System.out`, `System.err`, `printStackTrace` or equivalent production bypass is introduced;
- failure/transition evidence must be understandable without raw model data.

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

### Slice 1 — Core session models and exact ownership — COMPLETE

Introduced runtime session/reference/configuration/failure models plus exact process-local ownership with generation and stale/ABA contracts.

### Slice 2 — Activation and publication barrier — COMPLETE

Composed already-approved protected-model output into exact runtime activation with bounded publication and stale/reentrant protection.

### Slice 3 — Operation supervision and resource bounds — COMPLETE

PR #69 exact head `f083deaa9e5a9352a06cdedf1629bfaa3108e3bd`; exact-head run `33442898637` / #455 Core + Android GREEN; focused audit CLEAN; merge `7a3794bab338d90813a0a82067ad65db4ae52982`; merge/main run `33443333795` / #456 GREEN.

### Slice 4 — Failure containment, replacement and recovery readiness — COMPLETE

Implemented explicit quiescing, drain-before-retire, exact structural failure containment, stale-worker publication protection, deterministic replacement/retirement concurrency contracts, fail-closed retirement cleanup, explicit recovery cleanup and fresh-generation-only replacement.

Verified implementation evidence:

- PR #71 exact head `459be1834156a5d4cc1220d6a611c918c4c11f26`;
- push exact-head Core CI `33447325465` / #478 — Core + Android GREEN;
- PR exact-head Core CI `33447713754` / #479 — Core + Android GREEN;
- focused architecture/security/privacy/logging-diagnostics/readiness audit of the exact seven-file changed set — CLEAN;
- merge `c09b37d14f4cbd367bba9165ccb09dc4fd37116f`;
- merge/main Core CI `33448183290` / #480 — Core + Android GREEN.

### Slice 5 — Platform/runtime integration evidence if required — NEXT

Determine whether any remaining Runtime Hardening v0.1 guarantee genuinely depends on Android/process-specific behavior. Add only the required platform surface plus real instrumentation, or record an explicit no-op evidence checkpoint if Core already expresses all claimed semantics.

### Slice 6 — Formal freeze checkpoint

Record exact-head/merge-main evidence, focused audits, explicit limitations and freeze Runtime Hardening v0.1 before License service/offline-lease issuance + refresh begins.

## Gate

Architecture gate and Slices 1–4 are complete. Each completed implementation slice passed exact-head required CI, focused audit, expected-head merge and merge/main required CI before progression.

Every remaining slice remains subject to:

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

Runtime Hardening v0.1 becomes formally **FROZEN** only after Slice 5 and Slice 6 satisfy that discipline.
