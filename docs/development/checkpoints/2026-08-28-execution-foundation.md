# Execution Foundation Checkpoint — 2026-08-28

Status: VERIFIED

Scope: current repository `Vikrot123/LiliyaCore` only.

## Merge

PR #20: `Execution v0.1: Authority-Gated Execution Foundation`

Final PR head before merge:

`ab085624c07ce527254369cb69e9cbf1d88a48d2`

Final pre-merge Core CI:

- run: #313
- conclusion: success

Merge commit on `main`:

`ce5205d9345678ef80089ef60a5b9b096790dcca`

## Purpose

Execution Foundation introduces the first side-effect boundary above frozen Authority without adding any real Android/device/shell adapter.

Its responsibility is to accept an explicit execution request, bind the requested action to a trusted required capability, ask `AuthorityManager` for authorization, invoke an executor adapter only after all gates pass, and convert the adapter result into an observable execution result.

Execution does not decide authority and Authority does not execute actions.

## Production location

Package:

`core/src/main/kotlin/pro/liliya/core/execution/`

Primary files:

- `ExecutionModels.kt` — action identity, execution request and result model.
- `ExecutionExecutor.kt` — adapter SPI for the actual side-effect implementation.
- `ExecutionManager.kt` — authority-gated execution orchestration and observability boundary.

Test location:

`core/src/test/kotlin/pro/liliya/core/execution/ExecutionFoundationContractTest.kt`

## Core model

`ExecutionActionId`

A nonblank stable action identity. It identifies the concrete operation to execute independently from a capability identity.

`ExecutionRequest`

Contains:

- `AuthorityPrincipal principal`
- `CapabilityId capability`
- `AuthorityScope scope`
- `ExecutionActionId actionId`
- nonblank `reason`

The request therefore carries both the requested operation and the authority claim that should permit that operation.

`ExecutionResult`

Explicit sealed result boundary:

- `Succeeded`
- `Rejected(reason)`
- `Failed(reason, throwable?)`

A rejected request is not the same as an execution failure. Rejection means the request was not allowed to reach successful execution, while failure means the execution path itself failed after passing earlier gates.

## ExecutionManager flow

Accepted flow:

`ExecutionRequest`
→ resolve trusted required capability for `actionId`
→ reject unknown action
→ reject action/capability mismatch
→ `AuthorityManager.authorize(...)`
→ reject denied authority
→ `ExecutionExecutor.execute(...)`
→ isolate adapter exception
→ record result through `CoreObservability`
→ return `ExecutionResult`

## Trusted action/capability binding

A major readiness finding occurred after the first GREEN CI.

Original design allowed an `ExecutionRequest` to carry both `capability` and `actionId`, but the two values were not structurally linked. Authority could therefore authorize the declared capability while an executor interpreted the action as a different or more privileged operation.

This was considered an authority bypass risk.

Hardening changed `ExecutionManager` so it owns an explicit trusted map:

`Map<ExecutionActionId, CapabilityId>`

The map is copied with `toMap()` at construction, so later mutation of the caller's map does not silently change execution authorization semantics.

Before calling Authority:

1. the action must be registered;
2. the request capability must equal the trusted capability required by that action.

If either condition fails, the request is rejected before both Authority and executor invocation.

## Fail-closed invariants

### Unknown action

No trusted action mapping → `ExecutionResult.Rejected`.

- Authority is not used to legitimize an unknown operation.
- Executor call count remains zero.
- Rejection is observable.

### Capability mismatch

Known action but request claims a different capability → `ExecutionResult.Rejected`.

- No privilege substitution through request-controlled capability.
- Executor call count remains zero.
- Rejection is observable.

### Authority denial

Exact action/capability binding passes but Authority denies principal/scope/capability → `ExecutionResult.Rejected`.

- Executor call count remains zero.
- Authority denial and execution rejection share correlation context.

## Executor boundary

`ExecutionExecutor` is a small functional SPI:

`execute(request, context): ExecutionResult`

It does not receive an authority decision token and does not independently decide permission. The caller-side security boundary is therefore `ExecutionManager`.

This is deliberate for the foundation slice, but creates the next readiness requirement: production composition must own the concrete executor instance and must not expose it as a raw bypass around `ExecutionManager`.

Until that composition ownership is proven, Execution v0.1 is not frozen.

## Exception semantics

`ExecutionManager` catches `Exception` from the executor adapter and converts it to `ExecutionResult.Failed`.

Failure reason:

- nonblank exception message when available;
- otherwise fallback text `executor threw an exception`.

The original exception is preserved in the returned `ExecutionResult.Failed` and passed to `CoreObservability`.

Logging/diagnostic event models do not retain a raw throwable object. They retain normalized throwable metadata:

- `throwableType`
- `throwableMessage`

This distinction caused the first CI failure for PR #20 and is now documented as an important test/API nuance.

## Observability

Execution result markers/codes:

- `EXECUTION_SUCCEEDED`
- `EXECUTION_REJECTED`
- `EXECUTION_FAILED`

Metadata includes:

- principal
- capabilityId
- scope
- actionId
- reason
- rejection/failure reason where applicable

The same incoming `LogContext` is passed into Authority, executor and Execution observability. Contracts verify correlation continuity across `AUTHORITY_*` and `EXECUTION_*` observations.

## CI history

### Core CI #304 — FAILURE

Failure stage: test compilation.

Cause: test code attempted to access `throwable` on `LogEvent` and `DiagnosticEvent`.

Actual event contract uses `throwableType` and `throwableMessage`.

Production Execution API was not changed for this fix.

### Core CI #309 — SUCCESS

The corrected exception-observability test passed along with the Core suite.

### Readiness audit after #309

Audit found the missing action→capability binding described above.

Hardening and regression contracts were added before merge.

### Core CI #313 — SUCCESS

Final exact head `ab085624c07ce527254369cb69e9cbf1d88a48d2` passed Core CI and was then merged.

## Out of scope at this checkpoint

Not implemented:

- Android intents or Accessibility actions
- shell/command execution
- browser/device adapters
- retries
- queues
- cancellation
- background execution
- scheduling
- execution persistence
- capability registry ownership

Those features must not be added in a way that bypasses the authority-gated manager boundary.

## Next mandatory readiness gate

Introduce production Execution composition ownership.

Required properties:

- concrete executor instance is owned inside composition;
- raw executor is not exposed to production callers;
- caller-facing API routes execution through `ExecutionManager`;
- action/capability configuration is composition-owned;
- observability/correlation remain explicit;
- no globals/singletons;
- contracts prove the composition cannot accidentally offer an authority bypass.

Only after that gate, GREEN Core CI and another readiness audit may Execution v0.1 be frozen.
