# START HERE — LiliyaCore

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Legacy `Vikrot123/LiliyaCore` is backup/migration history only.

Current phase: **Runtime Hardening v0.1 — ACTIVE, NOT FROZEN; Slice 3 complete, Slice 4 next.**

## Read this first

Before changing code, read in this order:

1. `HANDOFF.md` — canonical active checkpoint, exact implementation/CI evidence, next allowed slice and gates;
2. `CURRENT_STATE.md` — compact live state;
3. `RUNTIME_HARDENING_V0_1_CONTRACT.md` — current active architecture contract;
4. `PROTECTED_MODEL_PACKAGE_V0_1_CONTRACT.md` and `PROTECTED_MODEL_PACKAGE_V0_1_FREEZE.md` — immediate frozen dependency;
5. `ARCHITECTURE.md`, `STRUCTURE.md`, `NUANCES.md`, `DECISIONS.md`;
6. production source and executable contract tests for the touched subsystem;
7. current GitHub PR/CI state.

Source-of-truth priority:

`current GitHub/main + CI → production source + executable contracts → HANDOFF.md + CURRENT_STATE.md → canonical contract/freeze docs → other journal docs → chat history`

If documentation conflicts with GitHub/source, verify GitHub/source first and repair the documentation before implementation continues.

## Current verified checkpoint

Verified implementation `main`:

`7a3794bab338d90813a0a82067ad65db4ae52982`

Runtime Hardening Slice 3 / PR #69.

Verified merge/main CI:

`33443333795` / Core CI run #456 — GREEN for both:

- `Test LiliyaCore`;
- `Android Keystore Instrumentation`.

## Runtime Hardening v0.1

Direction:

`approved protected-model target → exact runtime session ownership → bounded model activation → supervised use → explicit fault classification → fail-closed isolation/retirement → controlled replacement/recovery`

Hard separations:

- Runtime Session is not Protected Model Package, Model DEK, License, Capability, Authority or Execution;
- loaded/active model is not permission;
- runtime ownership is not durable authorization;
- activation is not autonomous execution;
- recovery is not hidden replay/retry.

### Completed

- architecture gate — PR #64, merged and merge/main GREEN;
- Slice 1 exact session models/ownership — PR #65, merged and merge/main GREEN;
- Slice 2 activation/publication barrier — PR #66, exact-head audit CLEAN, merged, merge/main run #418 GREEN;
- Slice 3 operation supervision/resource bounds — PR #69, exact head `f083deaa...`, exact-head run #455 GREEN, focused audit CLEAN, merged as `7a3794ba...`, merge/main run #456 GREEN.

Slice 3 established atomic ACTIVE-session admission, bounded per-session in-flight operations, one supervisor per registry, identity-based exact operation tickets, exactly-one local terminal release, explicit failure/cancellation/timeout outcomes, stale-success publication rejection, reentrant publication barriers and bounded state with no hidden retry/replay history.

### Next

**Slice 4 — Failure Containment, Replacement and Recovery Readiness.**

Implement only the contract-required failure/replacement/recovery surface:

- explicit stop-new-admission / quiescing / exact retirement / fresh replacement sequence;
- explicit policy for in-flight work: await local cleanup or explicitly cancel it;
- structural failure classification and fail-closed retirement/recovery behavior;
- stale workers cannot publish into replacement state;
- recovery is a fresh explicit attempt and new generation, not resurrection;
- privacy-safe structural observability;
- deterministic concurrency tests for replacement/retirement while operations are in flight;
- no hidden retry/replay/reconciliation/exactly-once semantics.

Do not drift into License service, Update System, Authority changes or unnecessary Android coupling.

## Frozen baselines

Treat these as frozen dependencies:

- Persistent Cognitive Storage v0.1;
- Memory Persistence Integration v0.1;
- Knowledge Persistence Integration v0.1;
- Learning Persistence Integration v0.1;
- License Core v0.1;
- Android Device Key v0.1;
- Cognitive Storage Encryption v0.1;
- Protected Model Package / Loader v0.1.

Frozen Android Device Key v0.1 remains signing-only and exposes only `SIGN_CHALLENGE`. Do not retrofit cognitive/model DEK wrap/unwrap/decrypt into it.

## Hard engineering rules

- work on feature branches;
- keep each slice minimal and contract-scoped;
- exact `(ID, generation)` ownership beats ID-only ownership;
- stale/ABA ownership must not mutate/delete/authorize replacements;
- capability is not permission; Authority is separate from Execution;
- structural provenance/evidence is not a credential;
- persistence, encryption, License, key access, Authority and execution remain separate;
- fail closed on uncertain ownership, stale state, authentication or cleanup;
- frozen baselines are not casually redesigned;
- no hidden retry, replay, reconciliation or exactly-once claims.

## Mandatory workflow

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

CI GREEN does not replace the focused audit.

Never claim an audit that was not performed against the exact changed-file set/head.

Do not start a new implementation slice until the previous merge/main required CI is GREEN and the journal checkpoint is current.

## Privacy / observability

Use Foundation Logging/Diagnostics/CoreObservability. Do not introduce direct production console-output bypasses.

Normal diagnostics must not expose cognitive/model plaintext, raw DEKs, wrapped-key bytes, protected payloads, private proof material or secret-bearing exception messages.

Foundation can retain throwable messages when a throwable is explicitly forwarded, so secret-bearing throwables must not be passed blindly.

## Continuation procedure

1. verify current `main` and required CI still match or supersede this checkpoint;
2. read `HANDOFF.md`, `CURRENT_STATE.md` and `RUNTIME_HARDENING_V0_1_CONTRACT.md`;
3. inspect current Runtime Hardening production/tests plus the frozen Protected Model access boundary;
4. create a fresh Slice 4 feature branch from verified current `main` only after this journal checkpoint itself passes its required gate;
5. implement only failure containment/replacement/recovery readiness;
6. close exact-head CI + focused audit + expected-head merge + merge/main CI;
7. update the journal before starting Slice 5.
