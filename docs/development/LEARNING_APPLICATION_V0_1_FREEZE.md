# Learning Application Foundation v0.1 Freeze

Freeze date: 2026-08-29

## Verified implementation sequence

### PR #73 — Explicit Application Intent Store

Final exact head: `b7b11f326bf7068e612ec8140ab842e35f040b3d`.
Core CI #563: GREEN.
Merge commit: `cb8f30267be4ebe6c4cfb22329f6f2392df85734`.

Introduced explicit caller-supplied `LearningApplicationIntent` records as structural application intent only. Each intent binds an exact structural `LearningDecisionReference` and exact structural `LearningPolicyReference` to a structural `LearningApplicationTarget` of `MEMORY` or `KNOWLEDGE`.

The store provides positive local `LearningApplicationGeneration`, duplicate-ID rejection, stale/ABA-safe exact removal, deterministic snapshots, concurrent same-ID one-winner behavior, and lifecycle observability.

Intent presence does not validate referenced Decision or Policy state, does not require `APPROVE`, does not authorize or execute anything, and does not mutate Memory, Knowledge, or other downstream state.

### PR #74 — Composition Ownership

Final exact head: `535463678007690fc226e6f9f81e9e0c54c3bb26`.
Core CI #568: GREEN.
Merge commit: `97bb6f90c0e605642b43cfc448e2dc07a028db09`.

Introduced `LearningApplicationComposition` as the production ownership boundary around the internal application-intent store. Raw mutable store/registration primitives remain internal. Callers receive controlled install/read/inspect/snapshot/remove APIs through exact `LearningApplicationOwnership`.

Install/remove use fresh Foundation root contexts. Independent compositions isolate application-intent state. Structural Decision/Policy references and target metadata are observable without hidden lookup or downstream store access.

### PR #75 — Readiness Contract Hardening

Final exact head: `f2929d8a241883a4a9e7cdc62af1ca65118145fb`.
Core CI #572: GREEN.
Merge commit: `a9806df993b973308ece61971b5bcdfef4b884f9`.

Test-only readiness hardening locked the final Learning Application v0.1 boundaries:

- `LearningApplicationIntent.createdAt` is caller-supplied and preserved unchanged;
- independent `LearningApplicationComposition` instances isolate the same application ID;
- equal numeric `LearningApplicationGeneration` values across compositions remain local lifecycle identities and do not create shared ownership;
- Decision and Policy references are structural-only and accept missing or otherwise unvalidated referenced state;
- application intent installation does not require or infer `APPROVE`;
- `MEMORY` and `KNOWLEDGE` targets are structural routing declarations only;
- target presence does not apply, consolidate, authorize, execute, or mutate downstream state;
- `LearningApplicationGeneration` is a positive local lifecycle identity, not time, score, priority, trust, confidence, authorization, or execution state.

## Frozen guarantees

- `LearningApplicationId` is a non-blank structural identifier.
- `LearningApplicationGeneration` is a positive opaque store/composition-local lifecycle identity, not a global sequence, timestamp, score, priority, authorization token, trust value, confidence value, or durable identity.
- `LearningDecisionReference` is an exact structural `(LearningDecisionId, LearningDecisionGeneration)` reference only.
- `LearningPolicyReference` is an exact structural `(LearningPolicyId, LearningPolicyGeneration)` reference only.
- Neither structural reference causes hidden lookup, existence validation, disposition validation, policy evaluation, truth verification, trust inference, or source verification.
- `LearningApplicationTarget.MEMORY` and `LearningApplicationTarget.KNOWLEDGE` identify intended downstream domains only; they do not expose or call downstream stores.
- Installing an application intent does not require the referenced Decision to exist or have disposition `APPROVE`.
- Installing an application intent does not require the referenced Policy to exist or evaluate successfully.
- Installing an application intent does not itself constitute authorization, approval, execution, application, consolidation, promotion, or learned state.
- Duplicate application IDs are rejected without replacement.
- Removal is exact-generation ownership based; stale ownership cannot remove a replacement.
- A replacement intent receives a distinct generation within the same store lifecycle.
- Same application IDs in independent compositions do not share state.
- Equal numeric generations across compositions are not shared/global ownership identities.
- Concurrent same-ID registration has exactly one winner.
- Snapshot ordering is deterministic by caller-supplied `createdAt` then application ID and is not trusted chronology, priority, utility, confidence, truth, authorization, or execution order.
- `createdAt` is caller-supplied data and is not a trusted clock assertion.
- Lifecycle metadata contains structural identifiers/generations, target, and createdAt only; it does not claim authorization, execution, application, consolidation, Memory/Knowledge mutation, truth, confidence, trust, or learned-state results.
- `LearningApplicationComposition` privately owns mutable application-intent state; raw store and registration primitives are not production public API.

## Explicit exclusions

Learning Application Foundation v0.1 does **not** include or authorize:

- a real application worker, consolidation worker, promotion engine, or learned-state mutation engine;
- automatic validation that a referenced Decision exists, is current, or has disposition `APPROVE`;
- automatic validation that a referenced Policy exists, is current, matches the candidate/decision, or permits application;
- automatic Decision or Policy lookup;
- Memory store writes, Knowledge store writes, or any downstream mutation;
- conversion of application intent into Memory, Knowledge, belief, fact, personality trait, self-definition, trust state, authority state, capability state, or execution state;
- Authority or Capability grants, permission checks, authorization tokens, or Execution dispatch;
- rollback, transactional mutation, compensation, idempotent downstream application, conflict resolution, deduplication across downstream stores, or durable application receipts;
- truth verification, confidence scoring, ranking, utility scoring, causality, adjudication, reputation, or trust inference;
- policy evaluation, candidate evaluation, automatic approval/rejection, or autonomous learning;
- adaptation, self-improvement, recursive learning loops, or background learning workers;
- prompt/style/model control;
- planning, autonomy, agents, cognitive-cycle orchestration, or resource-governor behavior;
- persistence or durable generation identity;
- Android integration or multi-agent/multi-user learning identity.

## Freeze rule

Any expansion of Learning Application semantics must go through the normal feature-branch → PR → exact-head Core CI → architecture/security audit → exact-head merge workflow.

The frozen sequence remains explicit:

`candidate → decision → policy boundary → application intent → controlled application/consolidation → downstream mutation`.

An application intent is not permission and is not application. A Decision marked `APPROVE`, a Policy record, and a `LearningApplicationIntent` may all exist without any downstream state change.

The next learning architecture work must separately design and review the **controlled application/consolidation mechanism** before Memory, Knowledge, Personality, Self, Trust, Authority, Capability, Execution, or other downstream state may be changed.

That future boundary must explicitly define validation inputs, authorization, idempotency, conflict handling, atomicity/rollback or compensation, observability/privacy, result receipts, and exact downstream ownership semantics before mutation is introduced.
