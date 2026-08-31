# LiliyaCore — Canonical Project Handoff

Status: **ACTIVE DEVELOPMENT — Runtime Hardening v0.1 Slice 3 complete; Slice 4 is next.**

Checkpoint date: 2026-09-01.

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

`7a3794bab338d90813a0a82067ad65db4ae52982`

This is Runtime Hardening v0.1 Slice 3, PR #69.

Verified merge/main CI:

`33443333795` / Core CI run #456 — GREEN for both required jobs:

- `Test LiliyaCore` — success;
- `Android Keystore Instrumentation` — success.

Do not start work from an older SHA without first reconciling it with current `main`.

## Current phase

**Runtime Hardening v0.1 — ACTIVE, NOT FROZEN, SLICE 3 COMPLETE.**

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

PR #65 exact head `f2567296189142b7f51b76006728457121eee6ad`.

- exact-head CI `33426885570` — Core + Android GREEN;
- focused pre-merge audit — CLEAN;
- merge `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`;
- merge/main CI `33427756131` — Core + Android GREEN.

Established positive monotonic runtime-session generation, exact session/model reference binding, one-live-session v0.1 ownership, duplicate/overflow fail-closed behavior, stale/ABA-safe retirement, deterministic snapshot and explicit structural limits.

## Slice 2 — completed

PR #66: `Runtime Hardening v0.1: Slice 2 Activation Barrier`.

- exact head `b07f18601d6183beb35883f3796b66f92ecb5a6a`;
- exact-head CI `33431660506` / run #417 — Core + Android GREEN;
- focused architecture/security/privacy/logging/readiness audit — CLEAN;
- merge `076b0c4dfa18dbdde178f741edd7f63237ceaf28`;
- merge/main CI `33435578143` / run #418 — Core + Android GREEN.

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

## Slice 3 — completed

PR #69: `Runtime Hardening v0.1: Slice 3 Operation Supervision`.

Exact verified head:

`f083deaa9e5a9352a06cdedf1629bfaa3108e3bd`

Exact-head CI:

`33442898637` / Core CI run #455 — GREEN for both required jobs.

Focused architecture/security/privacy/logging/readiness audit of the final four-file diff: **CLEAN; no merge blocker**.

Merge:

`7a3794bab338d90813a0a82067ad65db4ae52982`

Merge/main CI:

`33443333795` / Core CI run #456 — GREEN for both required jobs.

Slice 3 guarantees:

- admission is atomic with exact current ownership and requires an `ACTIVE` session;
- `maxInFlightOperationsPerSession` is enforced before admission;
- exactly one operation supervisor may belong to one registry in v0.1, preventing duplicate-supervisor limit bypass;
- tickets bind to one exact runtime-session generation and use instance identity for terminal ownership;
- reconstructing identical ticket values cannot release the actual operation;
- exactly one terminal local release is allowed per admitted ticket;
- `FAILED`, `CANCELLED` and `TIMED_OUT` are explicit structural outcomes;
- stale success after retirement/replacement releases its own local capacity but cannot publish into the replacement session;
- success publication is serialized behind the same session publication/ownership barrier;
- same-thread reentrant retirement and admission cannot bypass that barrier;
- failure rendering exposes structural reason + exception class rather than exception message;
- operation tracking remains bounded and retains no hidden terminal-history/retry/replay buffer;
- operation completion remains structural runtime state, not Authority and not permission for external side effects;
- no hidden retry, replay, reconciliation or exactly-once inference is introduced.

A temporary GitHub-hosted runner allocation incident occurred during Slice 3 verification. It was independently reproduced on an already-known GREEN SHA and therefore separated from Slice 3 code. Runner availability recovered before final acceptance; exact-head run #455 and merge/main run #456 both executed normally and passed Core + Android.

## Next implementation — Slice 4

**Runtime Hardening Slice 4 — Failure Containment, Replacement and Recovery Readiness.**

Required behavior from the canonical contract:

1. make replacement explicit: stop new admission → establish quiescing barrier → retire/invalidate exact current ownership → publish a fresh exact session;
2. make the in-flight policy explicit: await local cleanup or explicitly cancel it; do not let incidental lock scheduling define semantics;
3. structurally classify session/provider/retirement/recovery failures and fail closed;
4. prevent stale workers from publishing or mutating state in a replacement session;
5. preserve exact local cleanup ownership for stale work;
6. make recovery a fresh explicit attempt/new generation rather than resurrection of a failed session;
7. introduce no hidden retry, replay, reconciliation or exactly-once claim;
8. preserve privacy-safe structural observability;
9. cover retirement/replacement races with deterministic concurrency tests.

Do not widen Slice 4 into License service, update transport, Authority changes or platform coupling unless a concrete contract requirement proves it necessary.

## Remaining Runtime Hardening plan

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

Do not start the next implementation slice until the previous merge/main required CI is GREEN and the journal checkpoint is current.

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
