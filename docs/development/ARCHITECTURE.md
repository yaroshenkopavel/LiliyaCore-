# ARCHITECTURE BASELINE

## Core Foundation v0.1

Frozen chain:

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

### Logging / Diagnostics / Observability

- Logging is technical operational trace/telemetry.
- Diagnostics records meaningful state, errors, contract violations, and health failures.
- `CoreObservability` emits significant observations to both using the same `LogContext`.
- Correlation continuity is a cross-layer requirement.
- Subsystems do not silently create hidden global loggers.

### Runtime / Lifecycle / Recovery

- Runtime state has one authority.
- Lifecycle delegates state transitions rather than shadowing state.
- Recovery owns active targets explicitly and rejects duplicate concurrent ownership.
- Rejected/applied/failure states must be observable.

### Events

- Synchronous deterministic in-process delivery.
- Explicit subscription ownership.
- Listener failures isolated and observable.
- No global event bus ownership.

### Services / Modules

- Registries use exact registration handles and compare/remove semantics to prevent stale-handle/ABA removal.
- `ServiceManager` owns exact started service instances, not just IDs.
- Registry replacement/unregister cannot cause the wrong instance to be stopped.
- Module service install is transactional and rolls back only attempt-owned registrations.
- Module uninstall cannot orphan started services or violate dependent-module relationships.
- Raw registries are encapsulated by `FoundationComposition`; mutation follows observable ownership paths.

## Authority v0.1

Frozen boundary:

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

### Invariants

- Default deny.
- Identity/scope/reason values must be explicit/nonblank.
- Legacy explicit grants are GLOBAL-only.
- Scoped grants match principal + capability + scope exactly.
- Expiring grant is valid only while `now < expiresAt`.
- Authority decisions are observable through Logging + Diagnostics.

### Delegation

- One level only.
- Delegator and delegate must differ.
- Delegation requires an exact active direct grant for the same capability and scope.
- Child expiry cannot exceed source expiry.
- Bounded source cannot create an unbounded child.
- `AuthorityDelegationPolicy` source type is `DirectAuthorityGrant`.
- `DelegatedAuthorityGrant` may convert to `ScopedAuthorityGrant` for authorization, retaining `DELEGATED` provenance.
- Delegated authorization does not become a delegation source.

## Execution direction (not frozen)

Required boundary:

`ExecutionRequest → AuthorityManager → Granted? → ExecutionExecutor → ExecutionResult`

Hard rule: a denied authority decision must result in zero executor invocations.

Execution must not decide authority. Real Android/device/shell adapters come later and must sit behind this boundary.

## Update System direction (architecture contract, not implemented)

Mandatory future boundary:

`Update Discovery → Signed Manifest → Compatibility Check → Authority → Download → Integrity/Signature Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

The Update System must support both:

- Android application/runtime updates; and
- independently deployable internal Liliya packages explicitly designed for dynamic update.

Hard invariants:

- network origin is not trust;
- signature validity is not installation permission;
- staging is not activation;
- activation is provisional until health checks pass;
- replacement/rollback use exact version/generation ownership and stale/ABA-safe handles;
- previous viable generations remain rollback points until commit/retention policy permits cleanup;
- update installation/activation cannot bypass Authority or Android platform security;
- arbitrary remote executable code is not accepted merely because it arrived through the update channel;
- failures, migration, health checks, commit, and rollback are observable through Logging/Diagnostics;
- prior authorization receipts are evidence, not durable future permission.

Detailed durable contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing direction (architecture contract, not implemented)

Mandatory long-term protected-use chain:

`Signed Entitlement → Exact Device Enrollment → Keystore-backed Key Boundary → Fresh License Policy → Authority → Protected Asset/Store Access → Controlled Operation → Observable Result`

For online-assisted integrity/licensing:

`App/Device Integrity Signal → Backend Verification → Signed License/Lease → Local Verification → Authority → Protected Use`

Hard invariants:

- Android Keystore/StrongBox non-exportable keys are the preferred device cryptographic root; raw HWID/IMEI/Android ID is not a master-key source;
- license entitlement and Authority remain separate boundaries;
- valid signature is evidence, not general permission;
- protected model assets may use authenticated chunk/tensor encryption and must not intentionally create plaintext temporary model files;
- cognitive/user data uses an independent encrypted-storage/key-recovery domain and must not become irrecoverable merely because a commercial license expires;
- anti-debugging, anti-dump checks and obfuscation are defense-in-depth only, never cryptographic trust anchors;
- license/security failure is explicit and fail-closed; it must not intentionally corrupt model mathematics into plausible-but-wrong output;
- long-lived DEKs/private signing keys are never hard-coded in application/native binaries;
- license, update and asset signing keys must support rotation/revocation;
- protected update activation must satisfy both Update System trust and Security/Licensing policy without bypassing Authority;
- security operations remain privacy-safe and observable.

Detailed durable contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Later roadmap

After foundation/authority/execution readiness:

- Memory
- Knowledge
- Identity/Self
- Trust & Security hardening
- Personality Core
- Reflection & Learning
- Planning/Autonomy/Agents
- Security & Licensing Core contracts
- Android Keystore/StrongBox device-key and enrollment boundary
- encrypted cognitive persistence, key rotation, backup/export/recovery
- protected model package + authenticated streaming loader
- runtime/native hardening and optional obfuscation
- offline licensing/lease, revocation and device-transfer infrastructure
- Update System Core contracts and staging/migration/rollback foundation
- Android Integration and application/runtime updater
- Liliya Network update/license delivery and automation
- security/readiness/red-team verification before protected distribution

Memory/Knowledge and later cognitive/update/security layers must preserve provenance, observability, exact ownership, rollback/safety, privacy, key recovery, and authority boundaries.
