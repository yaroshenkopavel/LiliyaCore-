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

Hard invariants:
- default deny;
- exact principal + capability + scope;
- strict expiry `now < expiresAt`;
- one-level bounded delegation;
- direct-source provenance is type-enforced with `DirectAuthorityGrant`;
- Authority decides permission and never performs the action itself.

## Execution v0.1

Frozen boundary:

`ExecutionRequest → trusted action/capability resolution → Authority → ExecutionExecutor → ExecutionResult`

Hard invariants:
- unknown action or action/capability mismatch rejects before executor invocation;
- denied Authority means zero executor invocations;
- executor failures are isolated and represented explicitly;
- Execution does not decide Authority;
- future Android/device/shell/browser adapters must sit behind this boundary.

## Cognitive foundations

The following foundations are frozen independently and remain composition-owned:

- Memory v0.1;
- Knowledge v0.1;
- Identity / Self v0.1;
- Trust / Security v0.1;
- Personality v0.1;
- Reflection v0.1;
- Learning Candidate v0.1;
- Learning Decision v0.1;
- Learning Policy v0.1;
- Learning Application Intent v0.1;
- Controlled Learning Application v0.1.

Structural references to upstream cognitive state do not silently create truth, trust, authority, execution, or autonomous behavior.

## Controlled Learning Application v0.1

Frozen chain:

`candidate → decision → policy boundary → application intent → prepared mutation → exact claim → fresh preflight → fresh Authority → target-checked Memory/Knowledge write → exact completion → completed structural outcome`

Hard invariants:

- prepared mutation and prior authorization receipts are not durable permission;
- fresh preflight and fresh target-specific Authority occur while the exact mutation claim is held;
- prepared target must equal the fresh Application target;
- one exact mutation generation has one active claim;
- public claim ownership is not public completion authority;
- rejected Authority/preflight/target checks cause zero downstream writes;
- downstream writes retain exact ownership for compensation;
- successful completion records a structural result and reserves both mutation ID and idempotency key;
- exact value-equal replay returns the previous structural receipt without a second write;
- reuse of a completed mutation ID or idempotency key for a different plan fails closed;
- real apply observability preserves explicit root/child correlation across claim, Authority, downstream mutation and completion;
- payload content is excluded from application lifecycle observability and public completed receipts.

Current idempotency/outcome guarantees are in-process/composition-local, not crash-durable exactly-once.

Detailed freeze contract: `CONTROLLED_LEARNING_V0_1_FREEZE.md`.

## Update System direction — contract recorded, implementation deferred

Mandatory future boundary:

`Update Discovery → Signed Manifest → Compatibility Check → Authority → Download → Integrity/Signature Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

The Update System must support both Android application/runtime updates and independently deployable internal Liliya packages explicitly designed for dynamic update.

Hard invariants include network-is-not-trust, signature-is-not-permission, staging-is-not-activation, exact generation ownership, rollback, anti-rollback, observable migration/health/commit/rollback, and no bypass of Authority or Android platform security.

Detailed durable contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing direction — contract recorded, implementation deferred

Mandatory long-term protected-use chain:

`Signed Entitlement → Exact Device Enrollment → Keystore-backed Key Boundary → Fresh License Policy → Authority → Protected Asset/Store Access → Controlled Operation → Observable Result`

Hard invariants:

- non-exportable Android Keystore/StrongBox keys are preferred device cryptographic roots;
- raw HWID/IMEI/Android ID is not a master-secret source;
- license and Authority are distinct boundaries;
- model/runtime protected-asset keys and user cognitive-data keys are separate domains;
- user-owned cognitive data must not intentionally become unrecoverable because a commercial license expires;
- protected model assets use authenticated encryption and normal protected loading must not materialize plaintext temporary model files;
- anti-debug/anti-dump/obfuscation are defense-in-depth, not trust anchors;
- failures are explicit and fail-closed rather than intentionally corrupting AI mathematics;
- signing/encryption key rotation, revocation, recovery and device transfer are explicit requirements;
- Update System, licensing, Liliya Network and Authority cannot bypass one another.

Detailed durable contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Next cognitive architecture stage: Planning Foundation v0.1

Planning is the next allowed cognitive foundation.

Required boundary direction:

`Goal/Planning Input → explicit Plan → ordered/dependent Plan Steps → validation/snapshot → later Decision/Authority/Execution boundary`

Planning v0.1 must remain structural and non-autonomous.

Hard rules:

- `Plan != Decision != Authority != Execution`;
- plan creation does not grant a capability;
- a plan cannot directly execute actions;
- a plan cannot directly mutate Memory/Knowledge;
- no hidden autonomous scheduler/agent loop;
- exact plan identity/generation and stale-safe ownership are required;
- dependency/order validation is deterministic;
- public snapshots are immutable/defensive;
- observability is privacy-safe and composition-scoped;
- future execution of a plan step must still pass current Authority/Execution boundaries.

Autonomy and Agents remain deferred until Planning is implemented, audited and frozen separately.

## Later roadmap

After Planning v0.1:

1. Planning readiness/freeze.
2. Explicit Goal/Decision orchestration boundary as needed by Planning contracts.
3. Reasoning/Decision integration without granting execution authority.
4. Autonomy foundation only after planning/decision safety boundaries are frozen.
5. Agent orchestration only after autonomy ownership/cancellation/authority semantics are explicit.
6. Persistent encrypted cognitive storage and crash-durable controlled-learning outcomes.
7. Security & Licensing Core implementation, including entitlement/device/key abstractions.
8. Update System Core implementation and staged rollback foundation.
9. Android Keystore/StrongBox integration and encrypted persistence adapters.
10. Protected model package/streaming loader and runtime/native hardening.
11. Android Integration/application updater.
12. Liliya Network update/license delivery.
13. Final protected-distribution security/readiness/red-team verification.

All later layers must preserve frozen provenance, exact ownership, observability, privacy, Authority, Execution, rollback and recovery boundaries.
