# LiliyaCore — Verified Development History

Scope: this history covers only repository `Vikrot123/LiliyaCore`. Predecessor projects are intentionally excluded.

Status convention:
- **VERIFIED** — merged/current state confirmed from repository/PR/CI history.
- **CONTRACT** — durable architecture contract exists, runtime implementation is still future work.
- **FROZEN** — verified baseline should not be casually redesigned; correctness/security fixes still require focused contracts, CI, audit, and journal update.

This file is deliberately milestone-oriented. Contract tests remain the executable source for fine-grained behavior.

---

# Foundation build-out

The repository was developed foundation-first rather than by immediately wiring an Android application.

Core build order:

`Logging → Diagnostics → Runtime → Lifecycle → Recovery → Events → Services → Modules → Foundation Composition → Capability/Authority → Execution`

Durable rules established during this phase:

- explicit mutable ownership;
- exact handles/instances instead of ID-only later re-resolution;
- Runtime is the state authority;
- Lifecycle orchestrates Runtime rather than shadowing state;
- failures must be observable;
- Logging and Diagnostics are distinct but correlated;
- no hidden global logger acquisition;
- tests are executable architecture contracts;
- feature branch → PR → exact-head GREEN CI → readiness audit → exact-head merge.

## PR #1 — Structured Logging Core — VERIFIED

Established structured log events/contexts, correlation propagation, sequencing, bootstrap buffering/replay, filtering/composite/safe writers, failure observation, metadata snapshots, and concurrency contracts.

Logging was defined as technical operational trace, not semantic system diagnostics.

## PR #2 — Diagnostics Core — VERIFIED

Added diagnostic events/severity, recorder/sink model, safe sink isolation, failure observation, metadata snapshots, correlation preservation, and deterministic in-memory diagnostics.

Durable distinction:

- Logging = operational trace.
- Diagnostics = semantic condition/failure/contract reporting.

## PR #3 — Runtime Core — VERIFIED

Established authoritative runtime state, transitions, rules/policy, atomic holder/controller, explicit applied/rejected results, failure transitions, observability, and concurrency invariants.

## PR #4 — Lifecycle Core — VERIFIED

Added lifecycle commands/phases/results while retaining Runtime as the only state authority.

## PR #5 — Recovery Core — VERIFIED

Added explicit recovery policy and active-target ownership, duplicate-attempt rejection, completion/reuse semantics, and observable decisions.

Recovery was explicitly restricted to reliability behavior, not cognition/planning.

## PR #6 — Event Core — VERIFIED

Established synchronous deterministic in-process delivery, explicit subscription ownership, listener failure isolation, event sequencing/correlation, and delivery reports.

## Services / Modules / Foundation Composition — VERIFIED / FROZEN

Subsequent Foundation work established:

- exact `ServiceRegistry` registration handles;
- exact started `CoreService` instance ownership in `ServiceManager`;
- deterministic dependency resolution;
- transactional module/service installation and exact rollback;
- structural module ownership separated from executable service lifecycle;
- private raw registries in `FoundationComposition` to prevent unobservable production mutation paths;
- `CoreObservability` as shared Logging + Diagnostics bridge with explicit correlation lineage.

Core Foundation v0.1 was frozen after final ownership/observability readiness audit.

---

# Capability & Authority v0.1 — VERIFIED / FROZEN

Authority development introduced:

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Verified guarantees:

- default deny;
- capability existence does not imply permission;
- exact principal/capability/scope grants;
- strict expiry (`now < expiresAt`);
- legacy explicit grants restricted to GLOBAL scope;
- bounded one-level delegation;
- delegated authority cannot amplify capability, scope, or lifetime;
- only `DirectAuthorityGrant` may be a delegation source;
- delegated provenance remains type-distinct;
- authorization/delegation decisions observable;
- Authority never executes actions.

Important readiness history: provenance flags alone were judged forgeable as a policy boundary, leading to the type-level `DirectAuthorityGrant` source restriction.

Authority v0.1 final merge baseline: `638bbfdc51b9446f637a11c922a050b5289e63d7`.

---

# Execution v0.1 — VERIFIED / FROZEN

Execution was eventually completed after its early open/failed-test stage.

Frozen boundary:

`ExecutionRequest → resolve trusted action capability → reject unknown/mismatch → Authority → executor → result`

Guarantees:

- unknown/mismatched action-capability mapping rejects before Authority/executor;
- Authority denial causes zero executor invocations;
- executor exceptions are isolated as explicit failure results;
- throwable metadata is normalized;
- Execution performs side effects behind Authority but does not decide permission;
- no Android/shell/browser implementation is implied by the foundation itself.

The historical “PR #20 OPEN / test compile failure” checkpoint is superseded by the later merged/frozen Execution baseline.

---

# Cognitive foundations — VERIFIED / FROZEN

## Memory Foundation

- composition-private store;
- exact record generation ownership;
- stale-safe exact removal;
- deterministic snapshots;
- provenance/reference metadata separated from content;
- production lifecycle observability excludes Memory content.

## Knowledge Foundation

- exact item generation ownership;
- stale-safe removal;
- deterministic snapshots;
- origins include exact Memory `(recordId,generation)` or Declared source;
- lifecycle observability avoids Knowledge content.

## Identity / Self Foundation

- single current Self per composition/store;
- exact positive generation ownership;
- stale-safe replacement/removal;
- Knowledge origins are structural references only;
- Self name is structural designation, not Authority/trust/credential.

## Trust / Security Foundation

- explicit trust anchors;
- exact trust generations and stale-safe ownership;
- composition isolation and deterministic snapshots;
- trust is non-transitive;
- trust anchor is not Authority grant/principal/auth credential/truth/confidence.

## Personality Foundation

- exact Self target reference;
- explicit stored profile attributes;
- defensive copy/redacted rendering;
- personality storage does not automatically control behavior, prompts, Authority, or decisions.

## Reflection Foundation

- caller-declared reflection content;
- exact generation ownership;
- redacted content in observability/rendering;
- origin references are structural only;
- reflection itself does not autonomously mutate Memory/Knowledge or declare truth.

---

# Learning foundations — VERIFIED / FROZEN

## Learning Candidate

Proposal-only boundary. Candidate is not accepted/applied learned state. Reflection origin is structural only; proposal content is protected from lifecycle rendering.

## Learning Decision

Exact candidate ID+generation. APPROVE/REJECT records a decision only; APPROVE is not Authority, downstream mutation, truth, or learned state.

## Learning Policy

Caller-supplied structural policy data. Policy foundation is not a hidden evaluator/authorizer/executor.

## Learning Application Intent — PR #75

Merged as `a9806df993b973308ece61971b5bcdfef4b884f9` after exact-head GREEN CI.

Application intent binds:

- exact Decision reference;
- exact Policy reference;
- target MEMORY or KNOWLEDGE.

Intent construction performs no hidden lookup and does not itself require Decision APPROVE. It is intent, not permission/execution/application.

Accepted conceptual sequence:

`candidate → decision → policy → application intent → controlled application`

---

# Controlled Learning Application v0.1

This stage turned structural learning intent into the first real, Authority-gated Memory/Knowledge mutation path.

## PR #78 — Authorization Boundary — VERIFIED

Merge: `18c3c030c9026576dbaf930c2981ddeda73e561d`.

Added exact preflight and target-scoped authorization:

- exact Application/Decision/Candidate/Policy generations;
- Decision must be APPROVE;
- capability `learning.application.apply`;
- scopes `learning.application.memory` / `learning.application.knowledge`;
- authorization receipt is evidence only, not durable permission.

## PR #79 / #80 — exploratory alternatives — CLOSED UNMERGED

Earlier mutation-plan/idempotency designs were rejected because they risked turning a past authorization receipt into apparent durable permission.

The accepted architecture requires fresh permission at the side-effect boundary.

## PR #81 — Prepared Mutation Store — VERIFIED

Merge: `ecd406e3365605f5a315c875b6a3afdf1b9f8256`.

Prepared mutations bind exact Application reference, principal, target, target-specific payload, idempotency key, and createdAt.

Guarantees:

- duplicate IDs/active keys reject;
- generation-based exact ownership;
- same-key concurrent preparation has one winner;
- deterministic snapshots;
- payload content excluded from lifecycle metadata/rendering;
- preparation performs no preflight/Authority/downstream write.

## PR #83 — Mutation Composition Ownership — VERIFIED

Merge: `0525304e367c0e691dfb172571af541c1c3bf5f2`.

Private mutation store became composition-owned and callers received controlled ownership handles instead of raw store access.

## PR #84 — Mutation Authorization Gate — VERIFIED

Merge: `0d05ad9a342bb2683c67395a23a312bbdcd42635`.

Key security fix: prepared target must match the target from fresh exact Application preflight. This blocks a confused-deputy path where Authority could authorize one scope while the prepared payload targeted another subsystem.

The gate checks exact mutation before and after fresh authorization.

## PR #85 — Exact Mutation Claim — VERIFIED

Merge: `4ed793e76e1eadf34a8ef0c5010de508565826cc`.

Added exact claim serialization:

- one active claim per exact generation;
- stale generation reject;
- active claim blocks removal;
- exact private claim token controls release/completion lifecycle.

## PR #87 — Completion / Idempotency Tombstone — VERIFIED

Merge: `d073257412f4b7e772cff3bc43e420e82864b53b`.

Initial completion boundary reserved completed idempotency keys for composition lifetime and removed the prepared entry after exact claim completion.

Later readiness audit found that boolean-only tombstones were insufficient for safe replay and did not reserve completed mutation IDs; this was superseded by PR #94.

## PR #88 — Real Downstream Mutation Apply — VERIFIED

Merge: `f594c00989cd79fd9ea8f4a4bf065a8703c8685e`.

First real controlled write path:

- exact claim acquired first;
- fresh preflight + fresh target-specific Authority while claim held;
- MEMORY through `MemoryComposition.remember()`;
- KNOWLEDGE through `KnowledgeComposition.create()`;
- public success receipt exposes only downstream ID+generation;
- denial/mismatch causes zero writes;
- downstream conflict releases claim and remains retryable;
- post-write completion failure uses exact downstream ownership for compensation;
- compensation failure surfaces explicit partial failure.

Core CI #645: success on final exact head.

## PR #91 — Apply Readiness Contracts — VERIFIED

Merge: `c6bd1f7308d0bf2d0cd35679c23464f9ffe336c6`.

Additional contracts proved:

- concurrent apply of the same exact mutation has one downstream winner;
- distinct mutations targeting the same Memory ID do not overwrite one another;
- sensitive payload does not render in controlled apply observability/results.

## PR #92 — Apply Correlation Continuity — VERIFIED

Merge: `c8b45bd27f2d7f1717e587acd9350f35a7bea7d0`.

Established one explicit operation lineage:

`apply root → claim child → Authority child → Memory/Knowledge child → completion/release child → final apply observation`

Logging and Diagnostics for a significant operation share the same `LogContext`.

No ThreadLocal/global hidden context was introduced. Public Memory/Knowledge/applier APIs remained compatible; context-aware plumbing is internal.

Core CI #658: success.

## PR #93 — Internal Completion Authority — VERIFIED

Merge: `89410f810d7c1fc636d1892d12c115c69c5380f4`.

Readiness audit found that a public claim exposing `complete()` could create a false completion tombstone without a downstream write.

Fix:

- public claim remains an ownership/serialization handle with release;
- completion becomes internal controlled-learning capability;
- exact private store token remains mandatory.

Core CI #662: success.

## PR #94 — Completed Outcome Boundary — VERIFIED

Exact final head: `99ae4bc9002afea787659a854061ecbd68262c4e`.

Core CI #675: success.

Merge: `8aaa6713a8fe0f8f1d9f1831a7c30f680c11c28f`.

Replaced boolean-only completion with an atomic structural outcome:

- completion validates exact mutation reference/generation;
- completion validates target and downstream reference type;
- completed mutation ID and idempotency key are both reserved;
- completed outcome stores structural receipt by both indexes;
- exact value-equal replay plan returns `AlreadyCompleted(previousReceipt)` without a second downstream write;
- same key + different plan rejects;
- same completed mutation ID + different key/plan rejects;
- real applier receipt equals retained completed outcome;
- payload is not exposed through completed receipt or lifecycle observability.

The first CI attempt (#671) failed only because two new tests incorrectly used `.copy()` on a non-data-class plan. Tests were corrected to construct distinct value-equal plans; production semantics were unchanged. Final exact-head CI #675 passed.

### Controlled Learning Application v0.1 — FROZEN

Final verified boundary:

`candidate → decision → policy → application intent → exact preflight → Authority → prepared mutation → exact claim → fresh preflight + fresh Authority → Memory/Knowledge write → exact completion → completed structural outcome`

Frozen guarantees include exact ownership, fail-closed target-scoped Authority, one-winner claim concurrency, target consistency, downstream conflict safety, exact compensation/partial failure visibility, privacy-safe observability, explicit correlation continuity, internal completion authority, semantic idempotency identity, and structural replay outcome.

Explicit limitation: completed outcome/idempotency state is in-memory and composition-local. This foundation does not claim exactly-once across process death/restart/device reboot. Durable/encrypted persistence is a separate future architecture stage.

---

# Update System v0.1 — CONTRACT

PR #86 merge: `1c9c87e81ba2bd847e9c450881f51e0593576f5a`.

Durable pipeline contract:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network transport is not trust. Signature validity is not activation permission. Rollback viability is retained until commit/retention policy permits cleanup.

Runtime implementation remains future work.

---

# Security & Licensing v0.1 — CONTRACT

PR #89 merge: `5968c52af438d4005008dfc72677f423d5f674f9`.

Durable decisions include:

- License is not Authority;
- future Android device binding uses cryptographic enrollment/non-exportable Keystore keys rather than IMEI/Android-ID/HWID-derived secrets;
- protected model/runtime keys and user cognitive-data keys are separate domains;
- license expiry must not intentionally destroy user Memory/Knowledge;
- protected model packages use authenticated encryption and bounded decryption without normal plaintext temp model files;
- anti-debug/anti-dump/obfuscation are defense-in-depth only;
- entitlement failure is explicit fail-closed denial, not deliberately corrupted AI output;
- offline entitlement requires signed/versioned lease, trusted-time/rollback, revocation, recovery, and transfer semantics;
- Update System, network transport, Licensing, Authority, and Execution remain separate boundaries.

Runtime implementation remains future work.

---

# Learning Consolidation v0.1 — VERIFIED / FROZEN

Learning Consolidation consumes exact retained completed controlled-learning outcomes and may bridge one exact consolidation proposal into one ordinary Learning Candidate without bypassing any later learning gate.

Frozen chain:

`completed controlled outcomes → consolidation proposal → exact ownership → controlled Candidate bridge → Candidate → Decision → Policy → Application → fresh Authority → controlled apply`

Key guarantees include exact source verification, defensive/deterministic source lists, exact generation ownership, active conversion claims, source-removal protection, bridge-instance-independent idempotency, retryable Candidate ID conflicts, exact replay of the same Candidate reference, controlled typed consolidation provenance, fail-closed public transplant/install, privacy-safe observability and no Decision/Policy/Application/Authority bypass.

Canonical contract: `LEARNING_CONSOLIDATION_V0_1_FREEZE.md`.

---

# Planning Foundation v0.1 — VERIFIED / FROZEN

PR #103 established the structural proposal/store foundation. PR #104 added controlled composition ownership. PR #105 added readiness contracts. PR #106 froze the verified baseline.

Frozen boundary:

`PlanningOrigin + caller-declared goal + ordered PlanningStep list → PlanningProposal → exact PlanningGeneration ownership`

Mandatory invariant:

`Plan != Decision != Authority != Execution`

Planning is descriptive structural data only. It has exact/stale-safe ownership, deterministic detached snapshots, composition isolation, redacted goal/step payloads, explicit install→remove correlation, one-winner same-ID concurrency, and no Authority/Execution/downstream side effects.

Canonical contract: `PLANNING_V0_1_FREEZE.md`.

---

# Reasoning Foundation v0.1 — VERIFIED / FROZEN

PR #107 established structural reasoning artifacts/store ownership. PR #108 added controlled composition ownership. PR #109 added readiness contracts. The subsequent freeze checkpoint established the durable Reasoning baseline.

Frozen boundary:

`ReasoningOrigin + ordered premises + caller-declared analysis + conclusion → ReasoningArtifact → exact ReasoningGeneration ownership`

Mandatory invariant:

`Reasoning != Decision != Authority != Execution`

Reasoning conclusions remain deliberative data only and do not imply Decision, truth, confidence, trust, Authority or Execution. Exact ownership, composition isolation, deterministic detached snapshots, privacy-safe rendering/metadata and explicit correlation are frozen.

Canonical contract: `REASONING_V0_1_FREEZE.md`.

---

# Decision Foundation v0.1 — VERIFIED / FROZEN

## PR #112 — Structural Decision Record Foundation — VERIFIED

Exact head: `68312f7a166e031ab6c6b84b3cc9fa767bf7a624`.

Core CI #785: success.

Merged baseline after PR #112: `ac2225e874339047edb1a4812b26718b9474b805`.

Established general Decision records with:

- explicit `DecisionId`, `DecisionOptionId`, `DecisionGeneration`;
- exact structural Planning and Reasoning input references by ID+generation;
- non-empty unique structural inputs;
- non-empty unique options;
- selected option required to exist in the option list;
- defensive input/option copies;
- exact generation ownership and stale-safe removal;
- deterministic snapshots;
- option/rationale redaction;
- no hidden Planning/Reasoning lookup;
- no Authority or Execution behavior.

## PR #113 — Composition Ownership — VERIFIED

Exact head: `da917c73768d36a5e30ce0e27fef1c6355a409f7`.

Core CI #790: success.

Merge/new main: `69fa6f0a35b463b279813c2711bde0ed0dd62fdc`.

Added controlled `DecisionComposition`, exact public `DecisionOwnership`, composition isolation, duplicate rejection without replacement, stale-ownership safety, lifecycle privacy contracts and install→remove parent/child `LogContext` correlation.

## PR #114 — Readiness Contracts — VERIFIED

Exact head: `24f3cdfe9e21ee5acc5ff1c1644139fd0da96ece`.

Core CI #794: success.

Exact-head merge/new main: `770b4da45ad71a7bbeab47b2ddfada32d3bdc44c`.

Readiness contracts proved:

- ownership remove is one-shot and repeated remove fails closed;
- the same exact Decision ID is independently owned across compositions;
- snapshot list views remain detached from later store changes;
- lifecycle metadata contains no Authority/Capability/permission/Execution/scheduling/truth-confidence/trust semantics;
- option descriptions and rationale remain absent from lifecycle metadata.

### Decision Foundation v0.1 — FROZEN

Final boundary:

`structural Planning/Reasoning references + caller-declared alternatives + selected option + rationale → DecisionRecord → exact DecisionGeneration ownership`

Mandatory invariant:

`Decision != Authority != Execution`

A selected option is only a recorded choice/outcome. It is not permission, a capability grant, an execution request, scheduling instruction, truth/confidence claim or real-world effect.

Decision v0.1 intentionally contains no downstream execution bridge. Any future bridge must be separately designed and must preserve Capability/Authority/Execution.

Canonical contract: `DECISION_V0_1_FREEZE.md`.

---

# Current continuation

The next cognitive architecture stage is the **explicit deliberation/orchestration bridge foundation**.

Required direction:

`Decision → explicit orchestration intent → Capability/Authority → Execution`

Mandatory invariant:

`Decision != Orchestration Intent != Authority != Execution`

The first orchestration slice must be structural and non-executing: exact identity/generation ownership, exact Decision provenance, deterministic detached snapshots, composition isolation, privacy-safe observability/correlation, and explicit absence of permission, Authority grants, execution requests, executor calls, scheduling or downstream mutation.

Autonomy remains deferred until this orchestration boundary and controlled-governance integration are separately implemented, audited and frozen. Agents remain deferred until Autonomy boundaries are explicit and frozen.

Persistent encrypted storage, Android integration, Update runtime implementation, and Security/Licensing runtime implementation remain separate future stages.
