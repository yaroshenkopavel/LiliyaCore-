# ARCHITECTURE BASELINE

## Foundation chain — FROZEN

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Hard invariants:

- Runtime is the single runtime-state authority;
- Lifecycle orchestrates Runtime rather than shadowing it;
- mutable ownership is explicit and stale/ABA-safe;
- listener failures are isolated and observable;
- raw mutable registries stay behind composition boundaries;
- important operations use explicit structured correlation;
- no hidden global logger/context acquisition.

## Capability / Authority / Execution — FROZEN

`AuthorityRequest → AuthorityPolicy → AuthorityDecision`

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants: default deny; capability existence is not permission; exact principal+capability+scope matching; strict expiry; bounded delegation; denied Authority means zero executor calls; old authorization evidence is not durable permission.

## Frozen cognitive/control foundations

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning, Planning, Reasoning, Decision, Orchestration, Autonomy, Agents, Delegation and Coordination foundations are frozen through **Controlled Agent Coordination v0.1**.

Canonical subsystem freeze documents remain the detailed source of truth.

## Cognitive/control chain

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy, Agents, Delegation and Coordination govern initiative/provenance around this chain. None of those layers propagates implicit permission.

Mandatory invariants remain:

`Decision != Orchestration Intent != Authorization != Execution`

`Autonomy != Deliberation != Planning != Reasoning != Decision != Orchestration Intent != Authority != Execution`

`Agent Identity != Agent Lifecycle != Delegation != Coordination != Autonomy != Authority != Execution`

`Structural provenance != credential != capability != permission != Authority`

## Persistence foundation — FROZEN

Persistent Cognitive Storage v0.1 plus Memory, Knowledge and Learning persistence integrations are frozen.

Generic persistence provides exact entity/generation ownership, deterministic snapshots, explicit backend CAS conflicts and a narrow internal exact-transition primitive used by Learning completion.

Learning durable path:

`prepared record → exact durable transition → completed record`

Known retained limitation:

`downstream Memory/Knowledge mutation → durable Learning completion`

There is no cross-domain exactly-once guarantee, hidden retry, automatic replay or reconciliation.

The delayed Learning/Persistence post-freeze observability audit is closed **CLEAN**. Audited durable paths use Foundation observability; private cognitive payload and secret-bearing backend exception messages stay out of normal public/durable rendering; corruption/incompatibility/reopen mismatch remains fail closed.

## License Core v0.1 — FREEZE-READY / FROZEN AFTER CHECKPOINT CI

Canonical direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit LicensePolicy → LicenseDecision → optional fresh scoped Authority request`

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

### Verification boundary

`canonical envelope → schema/algorithm gate → exact trusted key-id lookup → exact trusted key identity/algorithm match → signature verification → canonical payload decode → signing-key consistency → Verified evidence`

The envelope cannot select its own trust root. Unknown key, unsupported algorithm/schema, trusted-key substitution, invalid signature and malformed canonical payload fail closed.

### Exact License ownership

Live state uses exact `(LicenseId, LicenseGeneration)` ownership.

Duplicate live ID is rejected; stale/ABA ownership cannot remove a newer generation; removal is one-shot; snapshots are deterministic detached views; composition isolation is default.

### License policy/time/replay semantics

Policy reads no hidden system clock. Time/revocation/replay evidence is explicit input.

Frozen rules:

- `notBefore` inclusive;
- `expiresAt` exclusive;
- **offline-lease semantics are mandatory in License Core v0.1**;
- `offlineLeaseUntil`, when present, is exclusive;
- stale revocation epoch denies;
- replay sequence missing/stale when required denies;
- suspicious time/replay state denies;
- exact product/feature and optional subject matching;
- no hidden refresh/retry/reconciliation.

### License → Authority boundary

`fresh Verified evidence → fresh LicensePolicy evaluation → fresh AuthorityRequest → AuthorityDecision`

License denial means zero Authority calls. Authority denial cannot become an authorized License result. Old License receipts are historical evidence only and cannot bypass fresh policy evaluation.

Execution/protected asset use remains outside License Core v0.1.

### License privacy / observability

License transitions use Foundation Logging/Diagnostics/CoreObservability.

Normal observability excludes private subject text, raw canonical payload/envelope bytes, signature bytes, verification/private key material, bearer evidence, cognitive/model plaintext and secret-bearing exception messages.

Targeted License production-path audit found no `println`, `System.out` or `printStackTrace` bypass.

## Security & Licensing dependency direction

Current accepted security sequence:

`License Core → Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

This replaces the older deferred-roadmap ordering that placed persistent encrypted cognitive storage before Android Keystore/device-key work.

The Android device-key boundary must come first so later cognitive/model encryption can depend on an explicit non-exportable device-root abstraction rather than inventing raw HWID-derived trust.

## Android device-key boundary — NEXT CONTRACT, NOT IMPLEMENTED

Next phase must be separately reviewed before code.

Expected architecture direction:

`Android Keystore/StrongBox capability → non-exportable device wrapping key → explicit enrollment/binding evidence → controlled key-use boundary`

Hard rules:

- IMEI, Android ID, serial, advertising ID or hashes of them are not cryptographic device roots;
- hardware-backed guarantees must not be claimed without platform evidence;
- software fallback policy must be explicit;
- key invalidation/recovery/migration semantics must be reviewed;
- no cognitive/model data encryption implementation is smuggled into the device-key contract.

## Update System v0.1 — ARCHITECTURE CONTRACT

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network origin is transport, not trust. Signature validity is not activation permission.

## Cross-cutting debt that does not weaken frozen boundaries

- structural provenance strings are not cryptographic authenticity;
- some compound cognitive operations do not share one correlation root across every frozen subsystem boundary;
- physical persistence durability still depends on a future concrete backend;
- Learning retains the downstream-mutation → completion crash window;
- current core-only code has no hardware-backed device binding or trusted monotonic time;
- authenticated cognitive encryption and protected model loading remain future phases.

## Repository continuity

Active repository: `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` is migration history/backup only.

Source-of-truth precedence:

`current GitHub/main + CI → production source + executable contracts → canonical architecture/freeze docs → CURRENT_STATE.md → chat history`

All future layers must preserve exact provenance, explicit ownership, fail-closed Authority, privacy, observability, rollback/recovery and composition isolation.