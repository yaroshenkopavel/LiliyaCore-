# LiliyaCore — Canonical Project Handoff

Status: **ACTIVE DEVELOPMENT — Runtime Hardening v0.1 Slice 4 complete; Slice 5 is next.**

Checkpoint date: 2026-09-01.

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Default branch: `main`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only and must not be modified as part of active development.

## Source-of-truth order

1. current GitHub `main` and current CI state;
2. production source and executable contract tests;
3. this `HANDOFF.md` and `CURRENT_STATE.md`;
4. canonical subsystem contract/freeze documents;
5. `START_HERE.md`, `NUANCES.md`, `DECISIONS.md`, `ARCHITECTURE.md`, `STRUCTURE.md`;
6. chat/session history only as supplementary context.

If documentation conflicts with GitHub/source, verify GitHub/source first and repair documentation before implementation continues.

## Exact implementation checkpoint

Current verified implementation `main`:

`c09b37d14f4cbd367bba9165ccb09dc4fd37116f`

This is Runtime Hardening v0.1 Slice 4, PR #71.

Verified merge/main CI:

`33448183290` / Core CI run #480 — GREEN for both required jobs:

- `Test LiliyaCore` — success;
- `Android Keystore Instrumentation` — success.

Do not start work from an older SHA without reconciling it with current `main`.

## Current phase

**Runtime Hardening v0.1 — ACTIVE, NOT FROZEN, SLICE 4 COMPLETE.**

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

## Completed Runtime Hardening evidence

### Architecture gate

PR #64: exact head `bb53c7e18aa020f57a7b1d59b19b9899b72dd47c`; exact-head Core + Android GREEN; focused audit CLEAN; merge `4a64b753d37d7bad53b49096c52e193102fb87ba`; merge/main GREEN.

### Slice 1

PR #65: exact head `f2567296189142b7f51b76006728457121eee6ad`; exact-head Core + Android GREEN; audit CLEAN; merge `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`; merge/main GREEN.

### Slice 2

PR #66: exact head `b07f18601d6183beb35883f3796b66f92ecb5a6a`; run `33431660506` / #417 Core + Android GREEN; audit CLEAN; merge `076b0c4dfa18dbdde178f741edd7f63237ceaf28`; merge/main `33435578143` / #418 GREEN.

### Slice 3

PR #69: exact head `f083deaa9e5a9352a06cdedf1629bfaa3108e3bd`; run `33442898637` / #455 Core + Android GREEN; audit CLEAN; merge `7a3794bab338d90813a0a82067ad65db4ae52982`; merge/main `33443333795` / #456 GREEN.

Established atomic ACTIVE-session operation admission, per-session in-flight bounds, one supervisor per registry, identity-based exact tickets, exactly-one local terminal release, explicit terminal classifications, stale publication protection and no hidden retry/replay state.

### Slice 4

PR #71: `Runtime Hardening v0.1: Slice 4 Failure Containment and Recovery Readiness`.

Exact verified head:

`459be1834156a5d4cc1220d6a611c918c4c11f26`

Exact-head CI:

- push run `33447325465` / #478 — Core + Android GREEN;
- PR run `33447713754` / #479 — Core + Android GREEN.

Focused architecture/security/privacy/logging-diagnostics/readiness audit inspected the exact seven-file changed set and found **no merge blocker**.

Merge:

`c09b37d14f4cbd367bba9165ccb09dc4fd37116f`

Merge/main CI:

`33448183290` / #480 — Core + Android GREEN.

Slice 4 guarantees:

- normal replacement is explicit `ACTIVE → QUIESCING → drain → exact retirement → later fresh activation`;
- quiescing atomically closes admission;
- retirement does not implicitly wait or cancel in-flight work;
- direct ownership retirement cannot bypass the supervised policy after supervisor claim;
- stale success can clean up locally but cannot publish after quiescing or into replacement state;
- `SESSION_FAILED` / `PROVIDER_FAILED` are exact-session structural failures;
- failed-session retirement does not implicitly cancel old tickets;
- stale failed owners cannot mutate replacement ownership;
- retirement cleanup is one explicit attempt behind the transition barrier;
- cleanup failure becomes `RETIREMENT_FAILED` and keeps exact ownership fail-closed;
- `RETIREMENT_FAILED` cannot be discarded through ordinary `retireFailed()`;
- uncertain cleanup requires explicit `recoverRetirementFailure` cleanup before ownership can be retired;
- failed recovery returns `RECOVERY_REJECTED` and remains blocked; no implicit retry occurs;
- successful recovery only retires the failed ownership; recovery never reactivates the old session;
- any later replacement is a fresh activation with a higher session generation;
- failure rendering retains structural reason and exception class, not secret-bearing exception message;
- deterministic concurrency tests cover admission/quiescing and release/retirement races;
- no hidden retry, replay, reconciliation, resurrection or exactly-once claim was introduced.

## Next implementation — Slice 5

**Runtime Hardening Slice 5 — Platform/runtime integration evidence if required.**

The first task is an evidence review, not implementation by default:

1. inspect the completed Runtime Hardening Core guarantees and immediate Protected Model/runtime boundary;
2. identify whether any claimed v0.1 property actually depends on Android process/lifecycle/platform behavior;
3. if such a property exists, implement only the smallest platform integration needed and prove it with real instrumentation;
4. if no such property exists, record Slice 5 as an explicit no-op evidence checkpoint rather than inventing Android coupling;
5. preserve all exact ownership, fail-closed cleanup, privacy and no-hidden-retry invariants.

Do not widen Slice 5 into License service, offline lease issuance, Update System, Authority changes, model package transport, local-LLM integration or a general Android application shell.

## Remaining Runtime Hardening plan

- Slice 5 — platform/runtime integration evidence if required, otherwise explicit no-op evidence checkpoint;
- Slice 6 — formal freeze checkpoint.

Runtime Hardening v0.1 is **not FROZEN** until these gates close.

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

Frozen Android Device Key v0.1 exposes only `SIGN_CHALLENGE`; do not retrofit cognitive/model DEK unwrap/decrypt into that signing surface.

Learning retains its explicit cross-domain crash window and no exactly-once/replay/reconciliation guarantee.

## Strict workflow — mandatory

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

Never merge merely because CI is GREEN. Never claim a focused audit unless the exact changed-file set and relevant patches/source were inspected. Do not start the next slice until merge/main required CI and the journal checkpoint are current.

Fail closed on uncertain ownership, stale state, authentication or cleanup. Do not invent hidden retry, replay, reconciliation or exactly-once semantics.

## Privacy / observability invariants

Foundation Logging/Diagnostics/CoreObservability remains the approved path.

Do not introduce direct production `println`, `print`, `System.out`, `System.err` or `printStackTrace` bypasses.

Normal diagnostics must not expose model/cognitive plaintext, raw DEK material, wrapped-key bytes, protected payloads, private proof material or secret-bearing exception messages.

Foundation is not a universal throwable-message sanitizer; do not blindly forward secret-bearing throwables into observability.

## Roadmap after Runtime Hardening freeze

`licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Do not jump ahead while Runtime Hardening remains unfrozen.
