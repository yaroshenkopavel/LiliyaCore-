# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Current `main`: `ecd406e3365605f5a315c875b6a3afdf1b9f8256`.

This commit merged PR #81 `Controlled Learning Application v0.1: Prepared Mutation Store` from exact head `5c7430977be56cb35ca348997c905819a0117fb5` after Core CI #601 completed successfully and the final architecture/security/privacy readiness audit passed.

The immediately preceding controlled-learning baseline is PR #78 `Controlled Learning Application v0.1: Authorization Boundary`, merged as `18c3c030c9026576dbaf930c2981ddeda73e561d`.

## Frozen subsystem status

- Core Foundation v0.1: FROZEN.
- Capability & Authority v0.1: FROZEN.
- Execution v0.1: FROZEN.
- Memory Foundation v0.1: FROZEN.
- Knowledge Foundation v0.1: FROZEN.
- Identity / Self Foundation v0.1: FROZEN.
- Trust / Security Foundation v0.1: FROZEN.
- Personality Foundation v0.1: FROZEN.
- Reflection Foundation v0.1: FROZEN.
- Learning Foundation v0.1: FROZEN.
- Learning Decision Foundation v0.1: FROZEN.
- Learning Policy Foundation v0.1: FROZEN.
- Learning Application Foundation v0.1: FROZEN.

## Controlled Application / Consolidation status

Controlled Application is now **IN PROGRESS**.

Verified merged boundaries:

1. **Preflight validation**
   - exact Application `(id, generation)` must still exist;
   - exact Decision reference must still exist and match generation;
   - Decision must be `APPROVE`;
   - exact Candidate reference must still exist and match generation;
   - exact Policy reference must still exist and match generation;
   - `ReadyForAuthorization` is structural readiness only, not permission to mutate.

2. **Authorization boundary** — PR #78
   - controlled learning uses capability `learning.application.apply`;
   - target-specific scopes are `learning.application.memory` and `learning.application.knowledge`;
   - authorization remains fail-closed through Capability/Authority;
   - an authorization receipt records a successful check but is **not durable future permission**.

3. **Prepared mutation boundary** — PR #81
   - a prepared mutation binds an exact `LearningApplicationIntentReference`, `AuthorityPrincipal`, target, target-specific payload, caller-supplied `createdAt`, and idempotency key;
   - Memory payload is valid only for `MEMORY`; Knowledge payload only for `KNOWLEDGE`;
   - duplicate mutation IDs and duplicate idempotency keys are rejected;
   - exact registration identity prevents stale/ABA removal;
   - same idempotency key has one concurrent winner;
   - snapshots are deterministic by `createdAt`, then mutation ID;
   - Memory/Knowledge content is redacted from rendering and lifecycle observability metadata;
   - preparation performs no preflight, no Authority call, no Memory/Knowledge write, no Execution dispatch, and no learned-state mutation.

PR #79 and PR #80 were earlier exploratory alternatives and were closed unmerged after #81 became the accepted continuation.

## Critical current invariant

A stored prepared mutation or a previously returned authorization receipt is **not permission to mutate downstream state**.

Immediately before any future Memory/Knowledge mutation, the controlled application path must re-run the exact preflight and Authority authorization against current state. Grant revocation, stale generations, changed Decision/Policy/Candidate/Application state, or target mismatch must fail closed before downstream mutation.

The intended chain is now:

`candidate → decision → policy boundary → application intent → exact preflight → Authority → prepared mutation → fresh preflight + fresh Authority → controlled downstream mutation → result/receipt`

The two authorization checks have different purposes: an earlier check may validate/prep control-plane work, while the mutation-time check is the mandatory permission gate for the actual side effect.

## Current next action

Next allowed architecture work: design the **mutation execution / controlled downstream application boundary** without yet broadening into Planning, Autonomy, Agents, Android, or generic cognitive orchestration.

Before the first real Memory/Knowledge write, that boundary must explicitly define and contract:

- exact prepared-mutation ownership/reference validation;
- fresh preflight immediately before mutation;
- fresh target-specific Authority authorization immediately before mutation;
- idempotency semantics across retry and completed results;
- downstream conflict behavior when target IDs already exist;
- exact ownership transfer into Memory or Knowledge;
- failure result/receipt semantics;
- atomicity expectations and rollback/compensation behavior;
- privacy-safe observability;
- behavior if authorization or structural state changes between preparation and execution.

No real downstream write should be introduced until those semantics are explicit in contracts.

Planning / Autonomy / Agents remains deferred until Controlled Application / Consolidation is separately completed, audited, and frozen.

Android Integration remains deferred.

## Workflow

Durable workflow remains:

`feature branch → minimal commits → PR → exact-head Core CI GREEN → architecture/security/privacy audit → exact-head merge with expected head SHA → journal checkpoint`

No intentional direct-to-main development.
