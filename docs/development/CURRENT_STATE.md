# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Current `main`: `8aaa6713a8fe0f8f1d9f1831a7c30f680c11c28f`.

This commit merged PR #94 `Controlled Learning Application v0.1: Completed Outcome Boundary` from exact head `99ae4bc9002afea787659a854061ecbd68262c4e` after Core CI #675 completed successfully and the final idempotency/privacy/outcome audit passed.

Immediately preceding controlled-learning hardening milestones:

- PR #91 `Apply Readiness Contracts` → merge `c6bd1f7308d0bf2d0cd35679c23464f9ffe336c6`;
- PR #92 `Apply Correlation Continuity` → merge `c8b45bd27f2d7f1717e587acd9350f35a7bea7d0`;
- PR #93 `Internal Completion Authority` → merge `89410f810d7c1fc636d1892d12c115c69c5380f4`;
- PR #94 `Completed Outcome Boundary` → merge `8aaa6713a8fe0f8f1d9f1831a7c30f680c11c28f`.

## Frozen subsystem status

- Core Foundation v0.1: **FROZEN**.
- Capability & Authority v0.1: **FROZEN**.
- Execution v0.1: **FROZEN**.
- Memory Foundation v0.1: **FROZEN**.
- Knowledge Foundation v0.1: **FROZEN**.
- Identity / Self Foundation v0.1: **FROZEN**.
- Trust / Security Foundation v0.1: **FROZEN**.
- Personality Foundation v0.1: **FROZEN**.
- Reflection Foundation v0.1: **FROZEN**.
- Learning Candidate Foundation v0.1: **FROZEN**.
- Learning Decision Foundation v0.1: **FROZEN**.
- Learning Policy Foundation v0.1: **FROZEN**.
- Learning Application Intent Foundation v0.1: **FROZEN**.
- Controlled Learning Application v0.1: **FROZEN**.

Update System v0.1 and Security & Licensing v0.1 are currently **architecture contracts**, not implemented runtime subsystems.

## Controlled Learning Application v0.1 — frozen boundary

Frozen chain:

`candidate → decision → policy → application intent → exact preflight → Authority → prepared mutation → exact claim → fresh preflight + fresh Authority → Memory/Knowledge write → exact completion → completed structural outcome`

### Structural readiness

- Application, Decision, Candidate, and Policy references are generation-bound.
- Decision must still be `APPROVE` at mutation-time preflight.
- Prepared target must equal the current Application target.
- A preflight or authorization receipt is evidence only, never durable future permission.

### Mandatory fresh Authority

- capability: `learning.application.apply`;
- scopes: `learning.application.memory`, `learning.application.knowledge`;
- authorization is fail-closed;
- fresh Authority is executed while the exact mutation is claimed and immediately before downstream mutation;
- Authority and Execution/downstream mutation remain separate responsibilities.

### Exact claim and completion ownership

- one active claim per exact mutation generation;
- stale generation cannot claim;
- an active claim blocks removal;
- release is exact-token bound;
- public claim ownership does **not** expose completion authority;
- `complete(...)` is internal to the controlled learning path;
- completion validates the exact mutation reference, target, and downstream reference type.

### Real Memory / Knowledge mutation

- MEMORY uses `MemoryComposition.remember()`;
- KNOWLEDGE uses `KnowledgeComposition.create()`;
- Authority denial, stale preflight, or target mismatch causes zero downstream writes;
- downstream ID conflict releases the claim and leaves the prepared mutation retryable;
- public success receipts expose structural downstream ID + generation only, never mutable downstream ownership;
- if downstream write succeeds but completion unexpectedly fails, exact returned ownership is used for compensation;
- compensation failure is surfaced explicitly as partial failure rather than hidden.

### Concurrency and idempotency

- concurrent apply of the same exact mutation has one downstream winner;
- conflicting distinct mutations cannot overwrite the same Memory ID;
- completed mutation ID and completed idempotency key remain reserved for the composition lifetime;
- completion atomically records a structural outcome under the mutation-store lock;
- an exact value-equal replay plan returns `AlreadyCompleted(previousReceipt)` without another downstream write;
- same completed key with a different plan rejects;
- same completed mutation ID with a different key/plan rejects.

### Observability and privacy

One controlled apply operation has explicit correlation lineage:

`apply root → claim child → Authority child → Memory/Knowledge child → completion child → final apply observation`

Logging and Diagnostics for the same significant operation use the same `LogContext`.

Sensitive candidate proposals, Memory content, Knowledge content, and mutation payload content are not placed into controlled-learning lifecycle metadata or public result rendering.

### Explicit v0.1 limitation

Completed outcomes are **in-memory and composition-local**.

Controlled Learning Application v0.1 does **not** claim:

- process-restart persistence;
- crash-durable exactly-once semantics;
- encrypted persistent mutation/outcome storage;
- cross-device replay;
- distributed transaction semantics.

Those properties belong to a later persistent/encrypted storage boundary and must integrate with the Security & Licensing contract rather than being retrofitted implicitly into this in-memory foundation.

## Update System architecture contract

PR #86 merged `UPDATE_SYSTEM_V0_1_CONTRACT.md` as `1c9c87e81ba2bd847e9c450881f51e0593576f5a`.

Required future pipeline:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network delivery is transport, not trust. Signature validity is not activation permission. Previous viable generations remain rollback points until commit/retention permits cleanup.

## Security & Licensing architecture contract

PR #89 merged `SECURITY_LICENSING_V0_1_CONTRACT.md` as `5968c52af438d4005008dfc72677f423d5f674f9`.

Hard decisions:

- License is not Authority.
- Device binding uses cryptographic enrollment / non-exportable Android Keystore keys when Android integration arrives; no HWID/IMEI/Android-ID-derived master key.
- protected model/runtime keys and user cognitive-data keys are independent domains;
- commercial license expiry/revocation must not intentionally destroy user Memory/Knowledge;
- protected model packages use authenticated encryption and bounded decryption; normal loading must not create plaintext model files on disk;
- anti-debug/anti-dump/obfuscation are defense-in-depth, not trust roots;
- license failure is explicit fail-closed denial/error, never deliberately corrupted AI output;
- offline licensing requires signed/versioned entitlement, lease/expiry, clock-rollback, revocation, recovery and device-transfer semantics;
- Update System, Liliya Network, Licensing, Authority, and Execution remain separate mandatory boundaries.

## Current next action

Controlled Learning Application v0.1 is now frozen. The next cognitive architecture stage is **Learning Consolidation v0.1**.

It must be designed as an explicit controlled boundary, not automatic hidden learning. Before any implementation it must define:

- what exact completed learning outcomes are eligible inputs;
- ownership/reference generation rules;
- deterministic consolidation proposal/result models;
- deduplication/idempotency semantics;
- privacy-safe observability and correlation;
- whether consolidation creates a new candidate/proposal or a controlled Memory/Knowledge transformation;
- Authority requirements for any downstream mutation;
- rollback/conflict behavior;
- explicit separation from Planning, Autonomy, Agents, model weight updates, and arbitrary self-modification.

Planning / Autonomy / Agents remains deferred until Learning Consolidation is separately implemented, audited, and frozen.

Persistent encrypted storage, Android integration, Update runtime implementation, and Security/Licensing runtime implementation also remain separate future stages.

## Workflow

Durable workflow remains:

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge with expected head SHA → journal checkpoint`

No intentional direct-to-main development.
