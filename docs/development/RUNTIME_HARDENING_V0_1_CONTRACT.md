# Runtime Hardening v0.1 — Architecture Contract

Status: **ACTIVE CONTRACT — implementation not yet frozen**.

## Purpose

Runtime Hardening v0.1 strengthens the already-frozen Foundation/Runtime/Lifecycle/Recovery/Execution surfaces for production use without redefining their authority model or weakening any previously frozen subsystem boundary.

This phase does not create a second runtime architecture. It hardens the existing one around bounded work, exact operation ownership, cancellation/shutdown races, protected-model runtime publication, failure containment, observability and restart behavior.

## Canonical direction

`fresh higher-layer intent/policy → exact runtime operation ownership → bounded start admission → execution/model-use boundary → observable completion/failure/cancellation → exact terminal commit → lifecycle/recovery continuation`

Mandatory separations:

`Runtime State != Lifecycle != Recovery != Operation Ownership != Capability != Authority != Execution != Model Access Policy != Protected Model Crypto`

`Admission != Authority`

`Started != completed`

`Cancellation requested != cancellation committed`

`Recovery attempt != successful recovery`

`Model decrypted/opened != model published != model execution permission`

`Shutdown != silent abandonment`

## Existing frozen boundaries preserved

Runtime Hardening v0.1 must preserve all existing frozen guarantees, including:

- Runtime remains the single runtime-state authority;
- Lifecycle orchestrates Runtime and does not shadow runtime state;
- Authority remains fresh, explicit and fail-closed at controlled execution boundaries;
- capability existence remains distinct from permission;
- mutable ownership is exact and stale/ABA-safe;
- listener failures remain isolated and observable;
- logging/diagnostics/correlation use the frozen Foundation infrastructure;
- protected-model package/loader, key protection and access-policy primitives remain frozen and are consumed rather than reinterpreted;
- Device Key remains signing-only;
- cognitive-storage encryption remains independent of protected-model key protection;
- no hidden retry, replay, reconciliation or durable permission token is introduced.

## v0.1 hardening targets

### 1. Exact runtime operation ownership

Longer-lived runtime work that can overlap stop/restart/recovery must have an explicit process-local operation identity and generation/epoch ownership.

Required properties:

- monotonic positive operation identity;
- stale operation completion cannot mutate or publish into a newer runtime generation;
- duplicate terminal commit is rejected/idempotently ignored according to the explicit contract;
- shutdown/restart invalidates older operation ownership before newer runtime work becomes publishable;
- no global mutable authorization registry is created.

### 2. Bounded admission and in-flight accounting

The hardened runtime must not accept unbounded concurrent work implicitly.

The contract must expose explicit admission outcome and in-flight accounting suitable for later policy/configuration, with fail-closed rejection when the configured bound is reached.

This is resource ownership only. Admission success is not Capability, Authority or execution permission.

### 3. Cancellation, timeout and shutdown races

Cancellation and shutdown must be explicit state transitions with deterministic stale-worker behavior.

Required direction:

`ACTIVE → cancellation requested / shutdown barrier → terminal completion or cancelled outcome`

Rules:

- cancellation does not fabricate success;
- work that finishes after its ownership became stale cannot publish a successful terminal result into the current runtime generation;
- shutdown establishes a barrier before runtime-owned publication is considered complete;
- repeated cancel/stop calls are safe and do not resurrect work;
- this phase does not claim thread preemption or forced interruption of arbitrary blocking platform calls.

Timeout policy, where introduced, must consume an explicit time source/evidence rather than a hidden wall clock inside domain primitives.

### 4. Failure containment

Provider, listener, consumer and publication failures must remain typed/observable and must not corrupt runtime ownership state.

One failed operation must not silently poison unrelated operation ownership. Terminal cleanup must run through reviewed `finally`/exact-release paths.

Secret-bearing exception messages, model plaintext, raw DEKs, wrapped-key bytes, license bearer evidence and private cognitive payloads remain outside normal observability.

### 5. Protected-model runtime binding

The frozen Protected Model Package / Loader v0.1 boundary is consumed as-is.

Runtime hardening may add a runtime binding/lease around a successfully published exact `ProtectedModelReference`, but it must not:

- bypass fresh protected-model access policy;
- cache policy approval as durable permission;
- reinterpret successful decrypt/unwrap as Authority;
- add a generic crypto/decrypt executor;
- extend Device Key with unwrap/decrypt capability.

A model runtime binding must become stale when its exact runtime/model ownership is replaced or retired.

### 6. Restart and recovery isolation

Restart/recovery behavior must preserve composition isolation and exact ownership.

Required properties:

- stale pre-restart operations cannot publish into the restarted runtime;
- recovery/startup failure remains observable;
- recovery success is committed only after the recovered runtime state is valid for the exact current generation;
- duplicate bridge/listener delivery must not be introduced by restart cycles;
- no automatic infinite recovery loop is introduced.

### 7. Observability and correlation

Every hardened operation must support explicit structured correlation through Foundation observability.

Normal events should identify structural operation/generation/category information needed to diagnose lifecycle, cancellation and stale-worker behavior without leaking protected payloads or secret exception messages.

Direct `println`, `System.out`, `System.err`, `printStackTrace` and hidden global logger/context acquisition remain forbidden in reviewed production paths.

## Initial implementation slicing

Runtime Hardening v0.1 is planned as narrowly reviewable slices:

1. **Operation ownership + bounded admission** — platform-neutral exact operation tickets, stale/duplicate terminal protection, explicit admission limits and contracts.
2. **Cancellation/shutdown barrier** — explicit cancellation/stop semantics, stale completion rejection and deterministic restart isolation contracts.
3. **Runtime protected-model binding** — exact model/runtime binding that consumes the frozen Protected Model access coordinator without minting Authority.
4. **Recovery/restart hardening** — stale recovery-worker barriers, restart-cycle isolation and terminal ownership cleanup.
5. **Observability/privacy/readiness** — structured operation events/correlation, production console audit, concurrency/privacy/failure contracts.
6. **Formal freeze checkpoint** — evidence audit and merge/main gate.

Slices may be further narrowed when audit findings require it. A later slice must not begin before the current exact head has passed required CI and its focused audit gate.

## Explicit non-goals

Runtime Hardening v0.1 does not claim:

- OS-level process isolation or sandboxing;
- preemptive cancellation of arbitrary native/blocking code;
- globally distributed exactly-once execution;
- durable cross-process operation recovery;
- hidden retry/replay/reconciliation;
- cloud scheduling;
- License issuance/refresh;
- Update System implementation;
- model inference engine implementation;
- protection from a compromised OS/runtime or privileged process debugger.

## Gate

This architecture contract is accepted only when this exact contract head passes all required Core and Android CI jobs, the contract patch receives focused architecture/security/privacy/readiness audit, the PR merges using the verified expected head, and the resulting merge/main commit passes all required jobs.

After that gate closes, begin Slice 1 only: exact runtime operation ownership plus bounded admission. Do not begin cancellation/shutdown, protected-model runtime binding or recovery hardening before Slice 1 is independently GREEN and audited.
