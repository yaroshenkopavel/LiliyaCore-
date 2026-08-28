# CURRENT STATE

Last journal update: 2026-08-28

## Main baseline

`main` SHA: `ce5205d9345678ef80089ef60a5b9b096790dcca`

This commit merged PR #20 `Execution v0.1: Authority-Gated Execution Foundation`.

Status:

- Core Foundation v0.1: FROZEN.
- Authority v0.1: FROZEN.
- Execution Foundation: MERGED.
- Execution v0.1: NOT FROZEN — composition ownership remains the next readiness gate.

## Execution Foundation checkpoint

PR #20 merged after Core CI #313 succeeded for exact head `ab085624c07ce527254369cb69e9cbf1d88a48d2`.

Merged execution boundary:

`ExecutionRequest → action/capability binding → AuthorityManager → ExecutionExecutor → ExecutionResult`

Confirmed invariants:

- unknown action IDs are rejected before authority and before executor invocation;
- action/capability mismatch is rejected before authority and before executor invocation;
- denied authority means zero executor calls;
- successful authorization invokes the executor through `ExecutionManager`;
- executor `Exception` is isolated into `ExecutionResult.Failed`;
- success/rejection/failure is recorded through `CoreObservability`;
- authority and execution observations preserve the same correlation context;
- action-to-capability configuration is copied into an immutable map snapshot inside `ExecutionManager`.

## Readiness finding still open

`ExecutionExecutor` is intentionally a public adapter SPI. The foundation itself is safe when called through `ExecutionManager`, but production wiring must ensure callers do not receive the concrete executor instance and bypass the manager.

Therefore Execution is not frozen yet.

Next required architectural gate:

1. introduce an execution composition/ownership boundary above the executor;
2. composition owns the concrete `ExecutionExecutor` instance;
3. production callers receive only the authority-gated execution entry point;
4. prove by contracts that production composition cannot expose a raw executor bypass;
5. preserve observability and correlation through that composition boundary;
6. run Core CI and a final Execution readiness audit before declaring Execution v0.1 frozen.

## Historical CI note

The first PR #20 run, Core CI #304, failed only at test compilation because the test incorrectly referenced a nonexistent `throwable` property on `LogEvent` and `DiagnosticEvent`. Those event models intentionally store `throwableType` and `throwableMessage` instead. The test was corrected without changing the production Execution result contract.

Core CI #309 then passed.

A subsequent readiness audit found the action/capability binding bypass. That bypass was closed and regression-tested; Core CI #313 passed on the final head before merge.

## Current development direction

Do not add Android/device/shell adapters yet.

The immediate next code work is Execution composition ownership/readiness hardening. Only after that gate should Execution v0.1 be considered for freeze and the roadmap advance further.
