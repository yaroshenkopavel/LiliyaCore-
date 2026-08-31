# CURRENT STATE

Last journal update: 2026-08-31.

## Repository continuity

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Default branch: `main`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only.

## Exact verified implementation checkpoint

Current verified implementation `main`:

`076b0c4dfa18dbdde178f741edd7f63237ceaf28`

This is Runtime Hardening v0.1 Slice 2, merged from PR #66.

Verified merge/main CI:

`33435578143` / Core CI run #418 — GREEN for both required jobs:

- `Test LiliyaCore` — success;
- `Android Keystore Instrumentation` — success.

## Frozen baselines

Treat these as frozen dependencies unless a concrete correctness/security blocker proves a new version is required:

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

Frozen Android Device Key v0.1 remains signing-only (`SIGN_CHALLENGE`). Cognitive-storage and protected-model key protection remain separate purpose-specific domains.

## Runtime Hardening v0.1

Status: **ACTIVE, NOT FROZEN, SLICE 2 COMPLETE**.

Canonical contract:

`RUNTIME_HARDENING_V0_1_CONTRACT.md`

Direction:

`approved protected-model target → exact runtime session ownership → bounded model activation → supervised use → explicit fault classification → fail-closed isolation/retirement → controlled replacement/recovery`

Mandatory separation:

`Runtime Session != Protected Model Package != Model DEK != License != Capability != Authority != Execution`

`Loaded model != authorized action`

`Runtime ownership != durable permission`

`Model activation != autonomous execution`

### Architecture gate — complete

PR #64:

- exact head `bb53c7e18aa020f57a7b1d59b19b9899b72dd47c`;
- exact-head run `33424400826` — Core + Android GREEN;
- focused architecture/security/privacy/logging/readiness audit — CLEAN;
- merge `4a64b753d37d7bad53b49096c52e193102fb87ba`;
- merge/main run `33425537207` — Core + Android GREEN.

### Slice 1 — exact session models and ownership — complete

PR #65:

- exact head `f2567296189142b7f51b76006728457121eee6ad`;
- exact-head run `33426885570` — Core + Android GREEN;
- focused audit — CLEAN;
- merge `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`;
- merge/main run `33427756131` — Core + Android GREEN.

Established positive monotonic generation, exact session/model reference binding, one-live-session v0.1 ownership, duplicate/overflow fail-closed behavior, stale/ABA-safe retirement, deterministic snapshot and explicit structural limits.

### Slice 2 — activation and publication barrier — complete

PR #66 exact verified head:

`b07f18601d6183beb35883f3796b66f92ecb5a6a`

Exact-head run:

`33431660506` / Core CI run #417 — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

Focused architecture/security/privacy/logging/readiness audit of all three changed files: **CLEAN; no merge blocker**.

Merge:

`076b0c4dfa18dbdde178f741edd7f63237ceaf28`

Merge/main run:

`33435578143` / Core CI run #418 — GREEN for both required jobs.

Slice 2 guarantees:

- activation consumes only an already-opened `ProtectedModelAccessResult.Opened<T>`;
- one exact protected-model generation is bound to one exact runtime-session generation;
- publication occurs behind the same ownership barrier used by retirement/registration;
- same-thread reentrant registration/retirement cannot bypass publication;
- competing retirement remains behind the publication barrier;
- publication failure fails closed and removes the live current session;
- failure rendering exposes exception class, not secret-bearing exception message;
- activation does not grant License, Capability, Authority or Execution permission;
- no operation supervision, retry, replay, recovery or reconciliation is introduced.

Audit note: the concurrency contract contains a short bounded wait to prove a competing retirement has not completed while publication owns the barrier. This is test-quality debt, not the sole correctness mechanism; production serialization is enforced by the registry monitor and reentrant mutation guards.

## Next implementation slice

**Runtime Hardening Slice 3 — Operation Supervision and Resource Bounds.**

Required direction:

- exact operation tickets bound to one exact ACTIVE runtime session generation;
- no admission for missing, PREPARED, QUIESCING, FAILED or RETIRED sessions;
- enforce `maxInFlightOperationsPerSession` before admission;
- exactly one local terminal release for each admitted operation;
- stale completion may perform local cleanup but cannot publish success/state into a newer session;
- cancellation/timeout seams must be explicit and must not become hidden wall-clock policy;
- operation completion is not Authority and cannot authorize external side effects;
- no hidden retry/replay/reconciliation or exactly-once inference claim.

Do not begin Slice 4 until Slice 3 exact-head CI, focused audit, expected-head merge and merge/main Core + Android CI are GREEN.

## Remaining Runtime Hardening plan

- Slice 3 — operation supervision and resource bounds;
- Slice 4 — failure containment, replacement and recovery readiness;
- Slice 5 — platform/runtime integration evidence only if required, otherwise explicit no-op checkpoint;
- Slice 6 — formal freeze checkpoint.

After Runtime Hardening freeze:

`licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

## Privacy / observability

Foundation Logging/Diagnostics/CoreObservability remains mandatory.

Normal diagnostics must not expose model/cognitive plaintext, raw DEKs, wrapped-key bytes, protected payloads, private proof material or secret-bearing exception messages.

Direct production `println`, `print`, `System.out`, `System.err` and `printStackTrace` bypasses remain forbidden in reviewed paths.

Foundation is not a universal throwable-message sanitizer; secret-bearing throwables must not be forwarded blindly.

## Mandatory workflow

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

CI GREEN is not a substitute for the focused audit.

Do not start the next implementation slice until the previous merge/main required CI is GREEN.
