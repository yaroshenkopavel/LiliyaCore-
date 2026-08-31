# LiliyaCore — Canonical Project Handoff

Status: **ACTIVE DEVELOPMENT — Runtime Hardening v0.1 Slice 2 complete; Slice 3 is next.**

Checkpoint date: 2026-08-31.

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Default branch: `main`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only and must not be modified as part of active development.

## Source-of-truth order

Use this order before changing the project:

1. current GitHub `main` and current CI state;
2. production source and executable contract tests;
3. this `HANDOFF.md` and `CURRENT_STATE.md`;
4. canonical subsystem contract/freeze documents;
5. `START_HERE.md`, `NUANCES.md`, `DECISIONS.md`, `ARCHITECTURE.md`, `STRUCTURE.md`;
6. chat/session history only as supplementary context.

If documentation conflicts with GitHub/source, verify GitHub/source first and repair the documentation before implementation continues.

## Exact implementation checkpoint

Current verified implementation `main`:

`076b0c4dfa18dbdde178f741edd7f63237ceaf28`

This is Runtime Hardening v0.1 Slice 2, PR #66.

Verified merge/main CI:

`33435578143` / Core CI run #418 — GREEN for both required jobs:

- `Test LiliyaCore` — success;
- `Android Keystore Instrumentation` — success.

Do not start work from an older SHA without first reconciling it with current `main`.

## Current phase

**Runtime Hardening v0.1 — ACTIVE, NOT FROZEN, SLICE 2 COMPLETE.**

Canonical architecture contract:

`docs/development/RUNTIME_HARDENING_V0_1_CONTRACT.md`

Direction:

`approved protected-model target → exact runtime session ownership → bounded model activation → supervised use → explicit fault classification → fail-closed isolation/retirement → controlled replacement/recovery`

Mandatory separations:

`Runtime Session != Protected Model Package != Model DEK != License != Capability != Authority != Execution`

`Loaded model != authorized action`

`Healthy runtime != valid License entitlement`

`Recovered process state != replay permission`

`Crash recovery != retry authorization`

`Runtime ownership != durable permission`

`Model activation != autonomous execution`

## Runtime Hardening architecture gate

PR #64:

- exact head `bb53c7e18aa020f57a7b1d59b19b9899b72dd47c`;
- exact-head run `33424400826` — Core + Android GREEN;
- focused architecture/security/privacy/logging/readiness audit — CLEAN;
- merge `4a64b753d37d7bad53b49096c52e193102fb87ba`;
- merge/main run `33425537207` — Core + Android GREEN.

## Slice 1 — completed

PR #65 exact head:

`f2567296189142b7f51b76006728457121eee6ad`

Exact-head CI:

`33426885570` — Core + Android GREEN.

Focused pre-merge audit: CLEAN.

Merge:

`ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`

Merge/main CI:

`33427756131` — Core + Android GREEN.

Established positive monotonic runtime-session generation, exact session/model reference binding, one-live-session v0.1 ownership, duplicate/overflow fail-closed behavior, stale/ABA-safe retirement, deterministic snapshot and explicit structural limits.

## Slice 2 — completed

PR #66: `Runtime Hardening v0.1: Slice 2 Activation Barrier`.

Exact verified head:

`b07f18601d6183beb35883f3796b66f92ecb5a6a`

Exact-head CI:

`33431660506` / Core CI run #417 — GREEN for both required jobs.

Focused architecture/security/privacy/logging/readiness audit inspected all three changed files and found **no merge blocker**.

Merge:

`076b0c4dfa18dbdde178f741edd7f63237ceaf28`

Merge/main CI:

`33435578143` / Core CI run #418 — GREEN for both required jobs.

Slice 2 guarantees:

- activation consumes only an already-opened `ProtectedModelAccessResult.Opened<T>` from the frozen protected-model path;
- one exact `ProtectedModelReference` is bound to one exact runtime-session generation;
- `PREPARED → ACTIVE` publication occurs behind the registry ownership barrier;
- retirement/registration cannot bypass an in-progress publication;
- same-thread reentrant ownership mutation is rejected;
- publication failure fails closed and does not leave a current live session;
- retained failure rendering exposes exception class rather than the exception message;
- activation does not grant License, Capability, Authority or Execution permission;
- no operation supervision, retry, replay, recovery or reconciliation is introduced by Slice 2.

Audit note: one deterministic-concurrency contract uses a short bounded wait only to prove that a competing retirement has not completed while publication holds the monitor. Correctness itself is enforced by the production monitor barrier and reentrant mutation guards; the wait is test-quality debt, not the ownership mechanism.

## Next implementation — Slice 3

**Runtime Hardening Slice 3 — Operation Supervision and Resource Bounds.**

Required behavior from the canonical contract:

1. issue exact operation tickets bound to one exact ACTIVE runtime-session generation;
2. issue no ticket for a missing, PREPARED, QUIESCING, FAILED or RETIRED session;
3. enforce `maxInFlightOperationsPerSession` before admission;
4. give each admitted operation exactly one terminal local release;
5. prevent replacement/retirement from allowing stale operation success/state to publish into a newer session;
6. allow stale operations to finish their own local cleanup without mutating replacement state;
7. keep timeout/cancellation policy explicit at the supervisor boundary rather than hiding wall-clock behavior inside model primitives;
8. preserve the rule that operation completion is structural runtime state, not Authority and not permission for external side effects;
9. introduce no hidden retry, replay, reconciliation or exactly-once inference claim.

Do not widen Slice 3 into replacement/recovery logic that belongs to Slice 4 unless a concrete correctness blocker proves the contract needs an explicit amendment.

## Remaining Runtime Hardening plan

- Slice 3 — operation supervision and resource bounds;
- Slice 4 — failure containment, replacement and recovery readiness;
- Slice 5 — platform/runtime integration evidence only if actually required; otherwise explicit no-op evidence checkpoint;
- Slice 6 — formal freeze checkpoint.

Runtime Hardening v0.1 is **not FROZEN** until all required slices and final evidence close.

## Frozen dependencies

Treat these as frozen baselines rather than redesign targets:

- Persistent Cognitive Storage v0.1;
- Memory Persistence Integration v0.1;
- Knowledge Persistence Integration v0.1;
- Learning Persistence Integration v0.1;
- License Core v0.1;
- Android Device Key v0.1;
- Cognitive Storage Encryption v0.1;
- Protected Model Package / Loader v0.1.

Learning retains the explicit cross-domain crash window:

`downstream Memory/Knowledge mutation → durable Learning completion`

There is no exactly-once downstream guarantee, automatic replay, hidden retry or reconciliation.

Frozen Android Device Key v0.1 exposes only `SIGN_CHALLENGE`; do not retrofit cognitive/model DEK unwrap/decrypt into that signing surface. Cognitive-storage and protected-model key protection remain separate purpose-specific domains.

Protected Model Package / Loader v0.1 formal freeze merge:

`aeccc0713ad1466a9ed371ff028e48406ed945e4`

Verified merge/main run:

`33416794458` — Core + Android GREEN.

## Strict workflow — mandatory

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

Never claim a focused audit unless the exact changed-file set and relevant patches/source were actually inspected.

Never merge merely because CI is GREEN.

Do not start the next implementation slice until the previous merge/main required CI is GREEN.

Fail closed on uncertain ownership, stale state, authentication or cleanup.

No hidden retry, replay, reconciliation or exactly-once semantics may be claimed unless explicitly implemented and tested.

## Privacy / observability invariants

Foundation Logging/Diagnostics/CoreObservability remains the approved path.

Do not introduce direct production `println`, `print`, `System.out`, `System.err` or `printStackTrace` bypasses.

Normal diagnostics must not expose model/cognitive plaintext, raw DEK material, wrapped-key bytes, protected payloads, private proof material or secret-bearing exception messages.

Foundation is not a universal throwable-message sanitizer; do not blindly forward secret-bearing throwables into observability.

## Historical audit caveat

Protected Model Slice 1 PR #56 lacked retained proof of the originally required pre-merge focused audit. Before Protected Model freeze, a corrective post-merge focused audit re-inspected the exact changed files/patches and found no freeze blocker. The freeze document records that evidence accurately.

Do not manufacture missing historical evidence. Where historical proof matters, verify repository/PR history and state uncertainty explicitly.

## Roadmap after Runtime Hardening freeze

`licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Do not jump ahead while Runtime Hardening remains unfrozen.
