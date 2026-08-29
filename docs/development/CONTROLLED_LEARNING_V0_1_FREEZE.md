# Controlled Learning Application v0.1 — Frozen Contract

Status: **FROZEN FOUNDATION** after PR #94 and exact-head Core CI #675 GREEN.

Verified merge baseline: `8aaa6713a8fe0f8f1d9f1831a7c30f680c11c28f`.

This document defines the durable boundary of Controlled Learning Application v0.1. It is intentionally an in-process/composition-local foundation. Persistence, crash recovery, durable encrypted replay, Planning, Autonomy, Agents, Android adapters, Update delivery, and licensing implementation are outside this freeze unless explicitly stated.

## 1. Canonical chain

`candidate → decision → policy boundary → application intent → prepared mutation → exact claim → fresh preflight → fresh Authority → target-checked downstream Memory/Knowledge write → exact completion → completed structural outcome`

The chain is mandatory. No later subsystem may skip directly from candidate/decision/intent/prepared mutation to Memory or Knowledge mutation.

## 2. Freshness and Authority

A prepared mutation and any earlier authorization receipt are never durable permission.

Immediately before the downstream side effect, the controlled application path must:

- hold the exact mutation claim;
- rerun exact preflight against current Application/Decision/Candidate/Policy generations;
- require Decision disposition `APPROVE`;
- rerun target-specific Authority;
- require the fresh Application target to match the prepared mutation target.

Authority remains default-deny. Current capability is `learning.application.apply` with target-specific scopes `learning.application.memory` and `learning.application.knowledge`.

## 3. Exact mutation ownership and serialization

Prepared mutation ownership is generation-bound.

Only one active claim may exist for one exact mutation generation. An active claim blocks removal. Release is bound to a private exact claim token.

Public claim ownership is not completion authority. Public callers may hold/release a claim, but controlled `complete(...)` is internal to the learning module so callers cannot manufacture a false completed state without the controlled downstream path.

## 4. Downstream mutation

Controlled application currently supports exactly one downstream target per prepared mutation:

- `MEMORY` → `MemoryComposition.remember()`;
- `KNOWLEDGE` → `KnowledgeComposition.create()`.

The public success receipt exposes only structural downstream identity:

- target;
- downstream record/item ID;
- exact downstream generation;
- exact mutation reference.

Mutable downstream ownership/removal capability is not exposed through the application receipt.

## 5. Zero-write rejection invariants

The following must result in zero new downstream mutation:

- stale or missing mutation generation;
- claim rejection;
- stale/missing Application/Decision/Candidate/Policy state;
- Decision not approved;
- Authority denial;
- prepared target mismatch with fresh Application target;
- downstream ID conflict before registration succeeds.

A rejected downstream conflict releases the exact claim and leaves the prepared mutation available for controlled retry/resolution.

## 6. Partial failure and compensation

If downstream creation succeeds but exact completion unexpectedly fails, the applier attempts compensation using the exact ownership returned by that downstream creation.

If exact compensation succeeds, the result explicitly reports compensated completion failure.

If compensation fails, the result is explicit `PartialFailure` with structural downstream reference. The system must never silently report a generic failure while leaving an unknown applied state.

## 7. Idempotency and completed outcome

Successful exact completion atomically:

- removes the prepared mutation;
- records a completed structural receipt;
- reserves the completed mutation ID;
- reserves the completed idempotency key.

The completion store binds the retained outcome to the exact completed plan.

Re-preparing a value-equal completed plan returns `AlreadyCompleted(previousReceipt)` without creating another prepared mutation or another Memory/Knowledge write.

Reusing either a completed mutation ID or a completed idempotency key for a different plan fails closed.

This prevents both idempotency-key aliasing and mutation-ID aliasing within the composition lifetime.

## 8. Concurrency

Contracts require:

- concurrent apply of the same exact mutation has exactly one downstream winner;
- concurrent distinct mutations targeting the same downstream ID cannot overwrite one another;
- only one completed outcome is established for a successfully completed exact mutation;
- exact claim tokens prevent stale release/completion from affecting replacement/current state.

## 9. Observability and correlation

The real apply operation creates one root `LogContext` and explicit child lineage through:

`apply root → exact claim → fresh Authority → Memory/Knowledge write → exact completion`

Completion/release are children of the exact claim context. Compensation removal is a child of the exact downstream-write context.

Logging and Diagnostics for the same significant operation use the same `LogContext`.

No ThreadLocal/global hidden operation context is introduced.

## 10. Privacy

Learning proposal/content, Memory content, and Knowledge content must not be placed into lifecycle observability metadata or public application receipts.

Structural metadata such as IDs, generations, target, principal, idempotency key, and timestamps may be observed according to existing privacy rules.

Completed public outcomes contain no payload content.

The private in-memory completed entry currently retains the exact plan so value-equal replay can be distinguished from aliasing. This is not a public rendering or persistence format.

## 11. Explicit non-guarantees of v0.1

Controlled Learning Application v0.1 does **not** claim:

- exactly-once semantics across process death/device reboot;
- persistent completed-outcome storage;
- encrypted durable storage;
- distributed/remote idempotency;
- transactional Authority leases spanning external systems;
- multi-target transactions;
- autonomous selection of what to learn;
- Planning/Autonomy/Agent behavior;
- Android/device/network execution.

A future persistent layer must preserve the v0.1 exact ownership, target, Authority, idempotency, privacy, correlation, and fail-closed invariants rather than silently weakening them.

## 12. Frozen readiness evidence

The final readiness sequence included:

- real downstream Memory/Knowledge application;
- exact mutation claim and removal barrier;
- fresh mutation-time Authority;
- target-confusion protection;
- exact completion and compensation;
- concurrency/privacy contracts;
- operation-level correlation continuity;
- internal-only completion authority;
- completed structural outcome;
- exact replay without second write;
- mutation-ID and idempotency-key alias protection.

PR #94 final head `99ae4bc9002afea787659a854061ecbd68262c4e` passed Core CI #675 before merge.

## 13. Reopening rule

This frozen foundation may be reopened only for a demonstrated correctness/security/privacy defect or an explicitly designed higher-layer integration requirement.

Any reopening requires:

`reproduction/contract → minimal feature branch → exact-head Core CI GREEN → architecture/security/privacy audit → exact-head merge → journal update`.
