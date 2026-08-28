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

## Later roadmap

After foundation/authority/execution readiness:

- Memory
- Knowledge
- Identity/Self
- Trust & Security hardening
- Personality Core
- Reflection & Learning
- Planning/Autonomy/Agents
- Android Integration
- Liliya Network

Memory/Knowledge and later cognitive layers must preserve provenance, observability, rollback/safety, and authority boundaries.
