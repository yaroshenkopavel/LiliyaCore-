# CURRENT STATE

Last journal update: 2026-09-01.

## Repository continuity

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Default branch: `main`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only.

## Exact verified implementation checkpoint

Current verified implementation `main`:

`c09b37d14f4cbd367bba9165ccb09dc4fd37116f`

This is Runtime Hardening v0.1 Slice 4, merged from PR #71.

Verified merge/main CI:

`33448183290` / Core CI run #480 — GREEN for both required jobs:

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

Frozen Android Device Key v0.1 remains signing-only (`SIGN_CHALLENGE`). Cognitive-storage and protected-model key protection remain separate purpose-specific domains.

Learning retains the explicit cross-domain crash window `downstream Memory/Knowledge mutation → durable Learning completion`; no exactly-once downstream guarantee, automatic replay, hidden retry or reconciliation is claimed.

## Runtime Hardening v0.1

Status: **ACTIVE, NOT FROZEN, SLICE 4 COMPLETE**.

Canonical contract: `RUNTIME_HARDENING_V0_1_CONTRACT.md`.

Direction:

`approved protected-model target → exact runtime session ownership → bounded model activation → supervised use → explicit fault classification → fail-closed isolation/retirement → controlled replacement/recovery`

Mandatory separations remain:

`Runtime Session != Protected Model Package != Model DEK != License != Capability != Authority != Execution`

`Loaded model != authorized action`

`Recovered process state != replay permission`

`Crash recovery != retry authorization`

`Runtime ownership != durable permission`

`Model activation != autonomous execution`

### Architecture gate — complete

PR #64: exact head `bb53c7e18aa020f57a7b1d59b19b9899b72dd47c`; exact-head Core + Android GREEN; focused audit CLEAN; merge `4a64b753d37d7bad53b49096c52e193102fb87ba`; merge/main GREEN.

### Slice 1 — exact session models and ownership — complete

PR #65: exact head `f2567296189142b7f51b76006728457121eee6ad`; exact-head Core + Android GREEN; focused audit CLEAN; merge `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`; merge/main GREEN.

### Slice 2 — activation and publication barrier — complete

PR #66: exact head `b07f18601d6183beb35883f3796b66f92ecb5a6a`; exact-head run `33431660506` / #417 Core + Android GREEN; focused audit CLEAN; merge `076b0c4dfa18dbdde178f741edd7f63237ceaf28`; merge/main run `33435578143` / #418 GREEN.

### Slice 3 — operation supervision and resource bounds — complete

PR #69: exact head `f083deaa9e5a9352a06cdedf1629bfaa3108e3bd`; exact-head run `33442898637` / #455 Core + Android GREEN; focused audit CLEAN; merge `7a3794bab338d90813a0a82067ad65db4ae52982`; merge/main run `33443333795` / #456 GREEN.

Slice 3 established atomic ACTIVE-session admission, bounded per-session in-flight operations, one supervisor per registry, identity-based exact tickets, exactly-one local terminal release, explicit failure/cancellation/timeout outcomes, stale-success publication rejection, reentrant publication barriers and bounded state with no hidden retry/replay history.

### Slice 4 — failure containment, replacement and recovery readiness — complete

PR #71 exact verified head:

`459be1834156a5d4cc1220d6a611c918c4c11f26`

Exact-head evidence:

- push run `33447325465` / Core CI #478 — Core + Android GREEN;
- PR run `33447713754` / Core CI #479 — Core + Android GREEN.

Focused architecture/security/privacy/logging-diagnostics/readiness audit inspected the exact seven-file PR changed set and found **no merge blocker**.

Merge:

`c09b37d14f4cbd367bba9165ccb09dc4fd37116f`

Merge/main run:

`33448183290` / Core CI #480 — Core + Android GREEN.

Slice 4 guarantees:

- `ACTIVE → QUIESCING` atomically closes new operation admission;
- normal replacement uses explicit drain-before-retire; retirement neither waits nor cancels work implicitly;
- direct ownership retirement cannot bypass supervised quiescing after supervisor claim;
- stale successful operations may release locally but cannot publish after quiescing or into a replacement;
- `SESSION_FAILED` and `PROVIDER_FAILED` are exact-session structural failures and close admission immediately;
- repeated session failure preserves the first retained structural reason;
- failed-session retirement does not implicitly cancel old local tickets;
- stale failed owners cannot fail or retire a replacement;
- retirement cleanup executes once behind the exact transition barrier;
- cleanup failure becomes `RETIREMENT_FAILED`, retains exact current ownership and blocks replacement;
- ordinary `retireFailed()` cannot discard a `RETIREMENT_FAILED` session;
- uncertain retirement cleanup can be cleared only by an explicit `recoverRetirementFailure` cleanup attempt;
- failed recovery returns `RECOVERY_REJECTED`, remains fail-closed and is not retried implicitly;
- successful recovery retires the failed ownership only; a later fresh activation/new generation is still required;
- failure result rendering exposes structural reason + throwable class, not secret-bearing throwable messages;
- deterministic concurrency contracts cover admission vs quiescing and final release vs retirement;
- no hidden retry, replay, reconciliation, resurrection or exactly-once semantics were introduced.

## Next implementation slice

**Runtime Hardening Slice 5 — Platform/runtime integration evidence if required.**

First determine whether any Runtime Hardening v0.1 guarantee actually depends on Android/process-specific behavior not already represented by the platform-neutral Core contracts.

- If a real platform property is required, add only the minimum integration plus real Android instrumentation proving that property.
- If no platform-specific behavior is required, make Slice 5 an explicit no-op evidence checkpoint; do not invent Android coupling merely to fill a slice.
- Do not broaden Slice 5 into License service, Update System, Authority changes, model download transport or a general Android application shell.
- Preserve all current exact ownership, fail-closed cleanup, privacy and no-hidden-retry guarantees.

Do not begin Slice 6 until Slice 5 has passed its required evidence/gate and documentation checkpoint.

## Remaining Runtime Hardening plan

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

CI GREEN is not a substitute for the focused audit. Do not start the next implementation slice until the previous merge/main required CI is GREEN and the journal checkpoint is current.
