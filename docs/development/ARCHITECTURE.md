# ARCHITECTURE BASELINE

## Frozen foundation chain

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Core Foundation v0.1 is frozen.

Key invariants:

- one runtime state authority;
- explicit lifecycle/recovery ownership;
- synchronous deterministic in-process events;
- listener failures isolated and observable;
- exact registration/ownership handles and stale/ABA-safe removal;
- transactional module/service installation;
- raw registries encapsulated by composition boundaries;
- important operations observable through Logging + Diagnostics with explicit `LogContext` correlation.

## Capability & Authority v0.1 — FROZEN

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Hard invariants:

- default deny;
- exact principal + capability + scope matching;
- strict expiry (`now < expiresAt`);
- bounded one-level delegation only;
- delegation cannot amplify authority;
- Authority decisions are observable;
- authorization evidence is not durable future permission.

## Execution v0.1 — FROZEN

`ExecutionRequest → trusted action/capability resolution → fresh Authority → executor → ExecutionResult`

Hard invariants:

- unknown/mismatched actions reject before executor;
- denied Authority means zero executor calls;
- executor failures are isolated and observable;
- Execution never decides permission;
- future Android/device/shell adapters must stay behind this boundary.

## Frozen cognitive foundations

The following v0.1 foundations are frozen:

- Memory;
- Knowledge;
- Identity / Self;
- Trust / Security;
- Personality;
- Reflection;
- Learning Candidate;
- Learning Decision;
- Learning Policy;
- Learning Application Intent;
- Controlled Learning Application;
- Learning Consolidation.

### Controlled Learning Application

Frozen chain:

`Candidate → Decision → Policy → Application Intent → exact preflight → fresh Authority → prepared mutation → exact claim → Memory/Knowledge write → exact completion → completed structural outcome`

Important rules:

- APPROVE is not Authority and is not execution;
- target consistency is checked again at mutation time;
- fresh Authority is adjacent to the controlled downstream write;
- exact claims serialize mutation ownership;
- completed mutation ID/idempotency key remain reserved for composition lifetime;
- exact replay returns the retained structural receipt without another downstream write;
- compensation uses exact returned downstream ownership;
- apply correlation survives claim → Authority → downstream → completion;
- payload content is excluded from lifecycle metadata;
- v0.1 is in-memory/composition-local and does not claim crash-durable exactly-once.

### Learning Consolidation v0.1

Frozen chain:

`completed controlled outcomes → consolidation proposal → exact consolidation ownership → controlled Candidate bridge → Learning Candidate → Decision → Policy → Application → fresh Authority → controlled apply`

Hard invariants:

- consolidation sources must exactly match retained completed learning outcomes;
- source lists are non-empty, unique, defensive and deterministic;
- proposal content is caller-declared and privacy-redacted in observability;
- exact generation ownership and stale-safe removal;
- active conversion claim blocks source removal;
- conversion completion/idempotency belongs to consolidation composition/store, not a bridge instance;
- one exact consolidation can produce at most one exact Candidate reference in composition lifetime;
- exact replay returns the same Candidate reference;
- candidate-ID conflict remains retryable;
- `LearningOrigin.Consolidation` is controlled typed provenance, not publicly forgeable evidence;
- public install/transplant of consolidation-origin Candidate is fail-closed;
- bridge creates Candidate only and cannot bypass Decision, Policy, Application, Authority or controlled apply;
- no Planning/Autonomy/Agents/model-weight mutation exists in consolidation.

Canonical freeze document: `LEARNING_CONSOLIDATION_V0_1_FREEZE.md`.

## Planning Foundation v0.1 — NEXT

Planning begins only after Learning Consolidation freeze.

Mandatory invariant:

`Plan != Decision != Authority != Execution`

Planning v0.1 is a structural proposal boundary for possible future actions. It must not:

- grant capability or authority;
- execute an action;
- mutate Memory/Knowledge merely because a plan exists;
- approve learning;
- become an autonomous agent/controller.

Initial planning contracts must define exact plan identity/generation, goal/input provenance, ordered steps/dependencies, structural action/capability references, deterministic snapshots, stale-safe ownership, composition isolation, privacy-safe observability and explicit bridge boundaries to future decision/authority/execution layers.

## Update System v0.1 — ARCHITECTURE CONTRACT

Mandatory future pipeline:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Supports both Android application/runtime updates and explicitly designed internal package updates.

Hard invariants:

- network origin is transport, not trust;
- signature validity is not activation permission;
- staging is not activation;
- activation is provisional until health checks pass;
- version/generation ownership and rollback are exact/stale-safe;
- Android platform security and Authority cannot be bypassed;
- arbitrary remote executable code is not accepted merely because it came through the update channel;
- prior authorization receipts are evidence only.

Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing v0.1 — ARCHITECTURE CONTRACT

Protected-use direction:

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

Hard invariants:

- license != Authority;
- device binding uses cryptographic enrollment/non-exportable Android Keystore or StrongBox keys, not HWID-derived master secrets;
- protected model/runtime keys and user cognitive-data keys are separate domains;
- commercial license expiry/revocation must not intentionally destroy user Memory/Knowledge;
- protected model assets may use authenticated chunk/tensor encryption without plaintext temporary model files;
- anti-debug/anti-dump/obfuscation are defense-in-depth, not trust roots;
- security/license failure is explicit fail-closed, never deliberately corrupted AI output;
- long-lived DEKs/private signing keys are not hard-coded into binaries;
- update/license/asset signing keys support rotation and revocation;
- Update, Licensing, Authority and Execution remain separate mandatory boundaries.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Deferred roadmap

After Planning Foundation v0.1:

- Planning readiness/freeze;
- later deliberation/decision orchestration boundaries;
- Autonomy only after explicit controlled-governance design;
- Agents only after Autonomy boundaries are frozen;
- persistent encrypted cognitive storage and crash recovery;
- Android Keystore/StrongBox device enrollment;
- protected model package/streaming loader;
- offline licensing/revocation/device transfer;
- Update System runtime/staging/migration/rollback;
- Android integration/updater;
- Liliya Network delivery/automation;
- security/readiness/red-team verification before protected distribution.

All future layers must preserve provenance, observability, exact ownership, fail-closed Authority, privacy, rollback/safety, key recovery, and composition isolation.
