# START HERE — LiliyaCore Session Handoff

## Stop condition

**The project is intentionally PAUSED for transfer to another chat/session. Do not resume implementation until the user explicitly asks to resume.**

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Default branch: `main`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only and is not the active development target.

## Read this first

A new session must read in this order before changing anything:

1. `HANDOFF.md` — canonical transfer checkpoint, exact pause state, next allowed slice, gates and caveats;
2. `CURRENT_STATE.md` — compact live state;
3. `RUNTIME_HARDENING_V0_1_CONTRACT.md` — current active architecture contract;
4. `PROTECTED_MODEL_PACKAGE_V0_1_FREEZE.md` and its contract — immediate frozen dependency;
5. `ARCHITECTURE.md`, `STRUCTURE.md`, `NUANCES.md`, `DECISIONS.md`;
6. production source and executable contracts for the touched subsystem;
7. current GitHub PR/CI state.

Source-of-truth priority:

`current GitHub/main + CI → production source + executable contracts → HANDOFF.md + CURRENT_STATE.md → canonical contract/freeze docs → other journal docs → chat history`

If documentation conflicts with GitHub/source, verify GitHub/source first and repair the documentation before implementation continues.

## Exact implementation pause checkpoint

Last implementation merge before the documentation-only handoff:

`ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`

PR #65 — Runtime Hardening v0.1 Slice 1.

Merge/main CI:

`33427756131` — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

The handoff documentation PR may advance `main` beyond that SHA. On resume, use current verified `main`, while treating `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6` as the last code implementation checkpoint unless GitHub proves later reviewed code changes.

## Current phase

**Runtime Hardening v0.1 — ACTIVE, NOT FROZEN, PAUSED AFTER SLICE 1.**

Architecture PR #64 is merged and merge/main GREEN.

Slice 1 is merged and merge/main GREEN.

Slice 2 has **not** started.

Next implementation after explicit resume:

**Slice 2 — Activation and Publication Barrier.**

Do not begin Slice 3, later Runtime Hardening work, licensing-service work or Update System integration before the required Slice 2 gates close.

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

Protected Model Package / Loader final freeze merge:

`aeccc0713ad1466a9ed371ff028e48406ed945e4`

Merge/main CI `33416794458` — Core + Android GREEN.

## Hard security boundaries

- Android Device Key v0.1 remains signing-only and exposes only `SIGN_CHALLENGE`;
- do not retrofit DEK wrap/unwrap/decrypt into Device Key;
- cognitive and protected-model key protectors remain separate purpose-specific domains;
- Persistence, Encryption, License, Capability, Authority and Execution remain distinct;
- successful decrypt/unwrap is not License entitlement or Authority;
- runtime/model ownership is not durable permission;
- model activation is not autonomous execution;
- no hidden retry, replay, reconciliation or exactly-once semantics may be invented.

## Mandatory workflow

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

CI GREEN does not replace the focused audit.

Never claim an audit that was not actually performed against the exact changed-file set/head.

Do not start a new implementation slice until the previous merge/main required CI is GREEN.

## Privacy / observability

Use Foundation Logging/Diagnostics/CoreObservability. Do not introduce direct production console-output bypasses.

Normal observability must not expose cognitive/model plaintext, raw DEKs, wrapped-key bytes, protected payloads, private proof material or secret-bearing exception messages.

Foundation can retain throwable messages when a throwable is explicitly forwarded, so secret-bearing throwables must not be passed blindly.

## Resume procedure

Only after an explicit user resume request:

1. verify current `main` SHA and current CI;
2. verify the handoff docs are the only changes after the recorded implementation checkpoint unless later code work is explicitly proven;
3. read `HANDOFF.md` and the Runtime Hardening/Protected Model contracts;
4. inspect Slice 1 source/tests;
5. create a fresh Runtime Hardening Slice 2 branch from verified current `main`;
6. implement only Activation and Publication Barrier scope;
7. close exact-head CI + focused audit + expected-head merge + merge/main CI before any Slice 3 work.

For complete transfer detail, `HANDOFF.md` is authoritative.
