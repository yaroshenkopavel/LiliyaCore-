# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `e5b3113d9342056e3167f9337ca87860fed85171`.

Recent persistence milestones:

- PR #12 `Persistent Cognitive Storage v0.1: Freeze Checkpoint` → merge `54b2896957212ff7564b35fad7e39ccbeb3a8e92`, exact-head CI `33315840062` GREEN, merge/main CI `33315974315` GREEN;
- PR #17 `Memory Persistence Integration v0.1: Freeze Checkpoint` → merge `c7a7866c199d42713c7047289db1e0f68559fcae`, exact-head CI `33317960415` GREEN, merge/main CI `33318203580` GREEN;
- PR #22 `Knowledge Persistence Integration v0.1: Freeze Checkpoint` → merge `45e9ff178207a0249dff11c20665b5b02ff8de78`, exact-head CI `33320651334` GREEN, merge/main CI `33320803828` GREEN;
- PR #23 `Learning Persistence Integration v0.1: Architecture Contract` → merge `0e8176d65eca1592fcd53434773f642db637f3bb`, exact-head CI `33321153413` GREEN, merge/main CI `33321283053` GREEN;
- PR #24 `Learning Persistence Integration v0.1: Codec and Restoration Boundary` → merge `db1af23965a747d6993711e07de52f0c20469d0a`, exact-head CI `33321940324` GREEN, merge/main CI `33322073125` GREEN;
- PR #25 `Learning Persistence Integration v0.1: Durable Prepare Remove and Reopen` → merge `c6404c5056370e26a07abc7c94e0e32eb794e147`, exact-head CI `33322377913` GREEN, merge/main CI `33322531446` GREEN;
- PR #26 `Learning Persistence Integration v0.1: Atomic Durable Completion` → merge `c89c383611a8f26194361eec6592e96506cd7760`, exact-head CI `33322846949` GREEN, merge/main CI `33322990978` GREEN;
- PR #27 `Learning Persistence Integration v0.1: Readiness Hardening` → merge `b04bbd6020ff9c9807e7db4f378d969534cee362`, exact-head CI `33323246383` GREEN, merge/main CI `33323408553` GREEN;
- PR #28 `Learning Persistence Integration v0.1: Freeze Checkpoint` → merge `e5b3113d9342056e3167f9337ca87860fed85171`, exact-head CI `33323689991` GREEN, merge/main CI `33323803034` GREEN.

## Frozen subsystem status

Persistent Cognitive Storage v0.1 generic primitive is fully **FROZEN**.

Memory Persistence Integration v0.1 is fully **FROZEN**.

Knowledge Persistence Integration v0.1 is fully **FROZEN**.

Learning Persistence Integration v0.1 is fully **FROZEN**.

Canonical persistence documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

## Learning Persistence Integration v0.1 frozen boundary

Frozen direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Durable prepare:

`validate plan → encode prepared → durable commit → exact committed local install → Prepared`

Durable remove:

`validate exact unclaimed ownership → durable exact-generation remove → exact local remove → success`

Durable completion:

`validate exact active claim + exact receipt → one durable exact prepared→completed transition → exact local completion/index publication → success`

Verified guarantees include exact mutation ID/generation restoration, persistent high-watermark monotonicity, exact idempotency semantics, canonical Memory/Knowledge payload fidelity, exact completed receipts, completed lookup by both indexes, fail-closed malformed/overlap/generation validation, zero claim resurrection, active-claim removal barrier, same-composition serialization, explicit shared-backend optimistic-CAS conflicts and privacy-safe failure rendering.

The generic persistence layer contains one narrow internal exact transition primitive used by Learning completion. It atomically replaces one exact source record with one replacement record in one backend CAS revision while preserving generation and high-watermark. It does not add retry, merge, scheduler, distributed locking or cross-domain transaction semantics.

Mandatory separation remains:

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

Persisted principal/application/decision/policy references remain structural recorded state only. Reopen never pre-authorizes a mutation; fresh controlled authorization remains required before downstream Memory/Knowledge mutation.

## Critical known limitation retained by Learning freeze

Learning persistence does **not** create a transaction spanning Learning and Memory/Knowledge.

The controlled application path still has:

`downstream Memory/Knowledge mutation → durable Learning completion`

A crash/failure can occur between those boundaries. There is no exactly-once cross-domain guarantee, no automatic replay authorization and no hidden retry/compensation/reconciliation semantics.

Any future cross-domain crash-atomic or exactly-once guarantee requires a separate reviewed architecture and executable proof.

## Logging and diagnostics status

Foundation Logging/Diagnostics/CoreObservability remain mandatory cross-cutting infrastructure.

Operational observability may expose approved structural IDs, generations, schema/version, timestamps and typed decision/rejection categories. Private cognitive content, raw persistent payloads, bearer tokens, cryptographic keys and secret-bearing exception messages remain excluded from normal logs, diagnostics, `toString` and public failure rendering.

No new production path may bypass Foundation observability through direct console output or hidden global logging.

## Current active architecture stage

The active stage is **License Core v0.1** — Phase A of the accepted Security & Licensing roadmap.

Canonical umbrella security contract:

`SECURITY_LICENSING_V0_1_CONTRACT.md`

New focused stage contract:

`LICENSE_CORE_V0_1_CONTRACT.md`

Selected direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit policy decision → optional scoped Authority request → controlled protected use`

This is still core-only Kotlin/JVM work. It must establish immutable license models, exact-generation ownership, canonical verification/trust-root abstraction, explicit time/replay/revocation policy, LicenseDecision semantics, Authority separation and privacy-safe observability before any Android Keystore or encrypted-storage adapter work.

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License expiry != cognitive-data destruction`

Do not broaden this stage into Android Keystore/StrongBox, attestation, Play Integrity, SQLite/SQLCipher, cognitive encryption, protected model loading, online billing, scheduler/retry/reconciliation or Update System activation.

## Accepted security roadmap after License Core

The existing `SECURITY_LICENSING_V0_1_CONTRACT.md` remains authoritative for later phases:

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline leases → Update System integration → red-team/readiness`

Each later phase requires its own reviewed boundary and executable proof.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Persistence != Encryption != License != Authority != Cognitive Permission`

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

Persisted cognitive/control/license state remains state/evidence, not permission. Fresh Authority remains mandatory at real side-effect boundaries.

## Known cross-cutting debt

1. Structural provenance/source references remain evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. Authenticated encryption and platform key management remain future adapters after License Core and Android device-key boundaries are proven.
4. Shared-backend independently opened persisted compositions use explicit optimistic CAS conflict rather than hidden refresh/retry/reconciliation.
5. Learning application retains the downstream-mutation → Learning-completion crash window; no exactly-once cross-domain guarantee exists.
6. Physical crash durability still depends on a future concrete persistent backend; the in-memory backend is contract/test infrastructure only.
7. No hardware-backed device binding or trusted monotonic time exists in current core-only main.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/logging-diagnostics/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
