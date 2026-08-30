# CURRENT STATE

Last journal update: 2026-08-30

## Current verified baseline

Current verified code `main`: `b04bbd6020ff9c9807e7db4f378d969534cee362`.

Recent persistence milestones:

- PR #12 `Persistent Cognitive Storage v0.1: Freeze Checkpoint` → merge `54b2896957212ff7564b35fad7e39ccbeb3a8e92`, exact-head CI `33315840062` GREEN, merge/main CI `33315974315` GREEN;
- PR #17 `Memory Persistence Integration v0.1: Freeze Checkpoint` → merge `c7a7866c199d42713c7047289db1e0f68559fcae`, exact-head CI `33317960415` GREEN, merge/main CI `33318203580` GREEN;
- PR #22 `Knowledge Persistence Integration v0.1: Freeze Checkpoint` → merge `45e9ff178207a0249dff11c20665b5b02ff8de78`, exact-head CI `33320651334` GREEN, merge/main CI `33320803828` GREEN;
- PR #23 `Learning Persistence Integration v0.1: Architecture Contract` → merge `0e8176d65eca1592fcd53434773f642db637f3bb`, exact-head CI `33321153413` GREEN, merge/main CI `33321283053` GREEN;
- PR #24 `Learning Persistence Integration v0.1: Codec and Restoration Boundary` → merge `db1af23965a747d6993711e07de52f0c20469d0a`, exact-head CI `33321940324` GREEN, merge/main CI `33322073125` GREEN;
- PR #25 `Learning Persistence Integration v0.1: Durable Prepare Remove and Reopen` → merge `c6404c5056370e26a07abc7c94e0e32eb794e147`, exact-head CI `33322377913` GREEN, merge/main CI `33322531446` GREEN;
- PR #26 `Learning Persistence Integration v0.1: Atomic Durable Completion` → merge `c89c383611a8f26194361eec6592e96506cd7760`, exact-head CI `33322846949` GREEN, merge/main CI `33322990978` GREEN;
- PR #27 `Learning Persistence Integration v0.1: Readiness Hardening` → merge `b04bbd6020ff9c9807e7db4f378d969534cee362`, exact-head CI `33323246383` GREEN, merge/main CI `33323408553` GREEN.

## Frozen subsystem status

Persistent Cognitive Storage v0.1 generic primitive is fully **FROZEN**.

Memory Persistence Integration v0.1 is fully **FROZEN**.

Knowledge Persistence Integration v0.1 is fully **FROZEN**.

Learning Persistence Integration v0.1 implementation is **FROZEN pending documentation-checkpoint merge**.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

## Learning Persistence Integration v0.1 verified boundary

Frozen direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Durable prepare:

`validate plan → encode prepared → durable commit → exact committed local install → Prepared`

Durable remove:

`validate exact unclaimed ownership → durable exact-generation remove → exact local remove → success`

Durable completion:

`validate exact active claim + exact receipt → one durable exact prepared→completed transition → exact local completion/index publication → success`

Verified guarantees include exact mutation ID/generation restoration, persistent high-watermark monotonicity, exact idempotency semantics, canonical Memory/Knowledge payload fidelity, exact completed receipts, completed lookup by both indexes, fail-closed malformed/overlap/generation validation, zero claim resurrection, active-claim removal barrier, same-composition serialization, explicit shared-backend optimistic-CAS conflicts and privacy-safe failure rendering.

The generic persistence layer now contains one narrow internal exact transition primitive used by Learning completion. It atomically replaces one exact source record with one replacement record in one backend CAS revision while preserving generation and high-watermark. It does not add retry, merge, scheduler, distributed locking or cross-domain transaction semantics.

Mandatory separation remains:

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

Persisted principal/application/decision/policy references remain structural recorded state only. Reopen never pre-authorizes a mutation; fresh controlled authorization remains required before downstream Memory/Knowledge mutation.

## Logging and diagnostics status

Foundation logging/diagnostics remain mandatory cross-cutting infrastructure for persistence and Learning.

Operational observability may include approved structural IDs, generations, target, idempotency key, schema/version, timestamps and payload structural IDs. Private Memory/Knowledge content, raw persistent bytes and backend exception messages remain excluded from normal logs, diagnostics, `toString` and public integration failure rendering.

No new persistence/Learning path is allowed to bypass Foundation observability through direct console output or private payload dumping.

## Current active architecture stage

The active stage is **Learning Persistence Integration v0.1 freeze checkpoint**.

Canonical freeze document:

`LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

No further Learning Persistence v0.1 production semantics should be added unless the freeze audit/CI exposes a correctness defect.

After the documentation checkpoint is merged and merge/main Core CI is GREEN, Learning Persistence Integration v0.1 becomes fully frozen. Only then select the next controlled stage from current repository architecture/roadmap; do not invent a new subsystem from chat context alone.

## Critical known limitation retained by freeze

Learning persistence does **not** create a transaction spanning Learning and Memory/Knowledge.

The controlled application path still has this ordering:

`downstream Memory/Knowledge mutation → durable Learning completion`

A crash/failure can occur between those boundaries. Therefore there is no exactly-once cross-domain guarantee, no automatic replay authorization, and no hidden retry/compensation/reconciliation semantics.

Any future cross-domain crash-atomic or exactly-once guarantee requires a separate reviewed architecture and executable proof.

## Governed control-path invariants

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

`Persistence != Encryption != License != Authority != Cognitive Permission`

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

Persisted cognitive/control state remains state, not permission. Fresh Authority remains mandatory at real side-effect boundaries.

## Known cross-cutting debt

1. Structural provenance/source references remain evidence and consistency markers, not cryptographic authenticity tokens.
2. Compound controlled-cognition operations do not yet share one correlation root across every frozen subsystem boundary.
3. Authenticated encryption and platform key management remain future adapters after storage-neutral domain integration boundaries are proven.
4. Shared-backend independently opened persisted compositions use explicit optimistic CAS conflict rather than hidden refresh/retry/reconciliation.
5. Learning application retains the downstream-mutation → Learning-completion crash window; no exactly-once cross-domain guarantee exists.
6. Physical crash durability still depends on the concrete future persistent backend; the in-memory backend is contract/test infrastructure only.

## Repository continuity

Primary development repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` remains migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs + CURRENT_STATE.md → chat history`.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/logging-diagnostics/readiness audit → exact-head merge → merge/main Core CI GREEN → journal/freeze checkpoint`
