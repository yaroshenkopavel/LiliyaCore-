# Controlled Orchestration v0.1 — FREEZE CONTRACT

Freeze date: 2026-08-29
Verified baseline before documentation freeze: `1ba105b74c9fedff45fc6ab70cf5dc6a84172c71`.

## Frozen boundary

`exact Decision → exact OrchestrationIntent → exact live preflight → trusted action/capability mapping → fresh Authority → frozen Execution → executor`

Mandatory invariant:

`Decision != Orchestration Intent != Authorization != Execution`

A Decision or OrchestrationIntent is cognitive/structural provenance. It is not permission. Preflight evidence and Authority evidence are historical evidence only and are never durable permission.

## Verified milestone chain

### PR #120 — Exact Execution Preflight

Exact head: `cd2f41ea9015056a8fdb7d87e092b7371aca5a78`.
Core CI #815: GREEN.
Merge/new main: `c9025ced195e168302b798d9b80a7f94f333ed85`.

Established evidence-only live preflight:

- exact OrchestrationIntent ID+generation validation;
- exact retained Decision ID+generation validation;
- exact selected Decision option consistency;
- capability+scope derived only from trusted action policy;
- stale/missing/mismatched provenance rejects fail-closed;
- unknown action rejects before Authority/Execution;
- private Decision/orchestration payload stays out of observability;
- no Authority call, `ExecutionRequest`, executor or side effect in this slice.

### PR #121 — Fresh Authorization Boundary

Initial head `e01efe6518d18c55311f1edcfb5f07a9f437efa5` reached CI #820 RED because new tests referenced nonexistent `LogEvent.code`; production authorization code compiled. Tests were corrected to use the real `LogEvent.marker` diagnostic-code field.

Final exact head: `f824679309338e0d05da2be1492bef229b1750c5`.
Core CI #822: GREEN.
Merge/new main: `fdd2c953b0b742d4e7f1f3d9d85e1e5f0c65ac50`.

Established controlled fresh Authority handoff:

- preflight is rerun for every authorization attempt;
- orchestration action policy capability must match the execution action→capability mapping before Authority;
- missing/mismatched mapping rejects before Authority;
- Authority request uses structural principal/capability/scope/reason only;
- private cognitive payload is excluded from Authority reason/observability;
- denied Authority rejects fail-closed;
- returned authorization evidence cannot execute anything itself.

### PR #122 — Controlled Execution Boundary

Exact head: `716668b44c5735e75b8c153928b55b4691ebf801`.
Core CI #827: GREEN.
Merge/new main: `ff6cabe02c860ce75cefa9d328ed4c8fa9ccfb1c`.

Established the real side-effect bridge:

- every execution attempt first performs fresh orchestration preflight + Authority;
- `ExecutionRequest` is constructed only from fresh structural authorization evidence;
- frozen `ExecutionComposition` independently revalidates action→capability mapping;
- frozen Execution performs another fresh Authority decision immediately before executor;
- successful path reaches executor exactly once;
- stale provenance, denied Authority or authorization mapping mismatch reach executor zero times;
- execution mapping drift after orchestration authorization still reaches executor zero times;
- executor failures are isolated as controlled failures.

### PR #123 — Readiness Contracts

Exact head: `e7e226d76bed3ad25c692d14d1fe6053af3c27c6`.
Core CI #831: GREEN.
Merge/new main: `1ba105b74c9fedff45fc6ab70cf5dc6a84172c71`.

Readiness proved:

- previously granted authorization evidence cannot be reused after Authority grant revocation;
- previously granted authorization evidence cannot be reused after exact Decision replacement;
- revoked/stale paths cause zero executor calls;
- private Decision rationale, option text and orchestration description remain absent from full-path observability.

## Frozen security and correctness guarantees

1. Exact provenance is live-validated at use time.
2. Orchestration generation is stale/ABA-sensitive.
3. Decision generation and selected option are revalidated.
4. Caller cannot supply capability or scope through the orchestration execution request.
5. Trusted action policy and Execution action→capability mapping must agree.
6. Unknown/mismatched mappings fail before downstream power.
7. Authority is fresh, scope-correct and not implied by prior records.
8. Execution independently repeats mapping validation and Authority at its own boundary.
9. Denial/staleness/mismatch means zero executor calls.
10. Successful controlled execution calls executor exactly once.
11. Executor exception/failure is isolated and observable.
12. Private cognitive payload is not copied into Authority reason or lifecycle/execution metadata.
13. No authorization/preflight evidence is durable permission.
14. No hidden scheduler, Autonomy or Agent behavior is part of v0.1.

## Explicit non-goals

Controlled Orchestration v0.1 does not provide:

- autonomous goal selection;
- autonomous scheduling or retries;
- long-lived permissions;
- background agents;
- Android/device adapters beyond the generic frozen Execution boundary;
- persistence/crash-durable orchestration claims;
- network-distributed execution;
- bypass of Capability/Authority/Execution.

## Next architecture stage

The next cognitive-control stage is **Autonomy Foundation v0.1**.

Autonomy must be designed as policy-governed selection of *whether/when to propose or attempt controlled work*, not as permission and not as execution.

Mandatory direction:

`Goals/Context/Reflection → Autonomy proposal/intent → Decision/Orchestration → fresh Capability/Authority → Execution`

Mandatory invariant:

`Autonomy != Decision != Authority != Execution`.

Agents remain deferred until Autonomy identity, ownership, lifecycle, budget/limits, scheduling semantics, cancellation, governance and fail-closed boundaries are separately implemented, audited and frozen.
