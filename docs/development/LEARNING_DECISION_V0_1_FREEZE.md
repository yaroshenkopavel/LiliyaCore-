# Learning Decision Foundation v0.1 Freeze

Freeze date: 2026-08-29

## Verified implementation sequence

### PR #65 — Explicit Decision Store Foundation

Final exact head: `31a6a40de9f7262e5b3c35515d08f10faa817770`.
Core CI #529: GREEN.
Merge commit: `c842817645f7359dbdb4926b16c7acf896a81162`.

Introduced explicit `LearningDecision` records that reference an exact structural Learning Candidate lifecycle identity `(LearningCandidateId, LearningGeneration)`. `LearningDecisionDisposition.APPROVE` and `REJECT` are decision-record semantics only. The store provides exact positive `LearningDecisionGeneration`, duplicate rejection, stale/ABA-safe removal, deterministic snapshots, concurrent same-ID one-winner behavior, lifecycle observability, and rationale redaction.

### PR #66 — Composition Ownership

Final exact head: `af9b2445af5cc1c224c5c565b69023ca8b45151c`.
Core CI #534: GREEN.
Merge commit: `de40f2471b34adb98d4061c08be4b5788c65a9e1`.

Introduced `LearningDecisionComposition` as the production ownership boundary around the internal decision store. Raw mutable store/registration primitives remain internal. Callers receive controlled install/read/inspect/snapshot/remove APIs through exact `LearningDecisionOwnership`. Install/remove use fresh Foundation root contexts, same decision IDs are isolated across independent compositions, candidate references remain structural-only, and rationale stays out of lifecycle metadata.

### PR #67 — Readiness Contract Hardening

Final exact head: `817e7e990128330625794dcc6192fa08aca722f3`.
Core CI #538: GREEN.
Merge commit: `a460216beb315ef2b0b2dc772ccbd72c443cb692`.

Test-only readiness hardening locked the final Decision v0.1 boundaries:

- `LearningDecision.createdAt` is caller-supplied and preserved unchanged;
- independent `LearningDecisionComposition` instances isolate the same decision ID;
- equal numeric `LearningDecisionGeneration` values across compositions remain local lifecycle identities and do not create shared ownership;
- candidate references remain exact structural `(LearningCandidateId, LearningGeneration)` references without hidden candidate lookup;
- `APPROVE` means only that an explicit decision record has disposition `APPROVE`;
- `APPROVE` does not imply policy evaluation, authorization, application, consolidation, execution, downstream mutation, truth, confidence, trust, authority, or capability semantics;
- decision rationale remains redacted from object rendering and lifecycle observability metadata.

## Frozen guarantees

- `LearningDecisionId` is a non-blank structural identifier.
- `LearningDecisionGeneration` is a positive opaque store/composition-local lifecycle identity, not a global sequence, timestamp, score, priority, trust value, or durable identity.
- `LearningCandidateReference` contains an exact structural candidate ID and generation pair only.
- Installing a decision does not perform hidden candidate existence checks, source lookup, validation, truth verification, trust evaluation, or policy evaluation.
- `LearningDecisionDisposition.APPROVE` and `REJECT` describe the caller-supplied recorded decision state only.
- Decision presence does not itself authorize, apply, execute, consolidate, promote, or mutate anything.
- Duplicate decision IDs are rejected without replacement.
- Removal is exact-generation ownership based; stale ownership cannot remove a replacement.
- A replacement decision receives a distinct generation within the same store lifecycle.
- Same decision IDs in independent compositions do not share state.
- Equal numeric generations across compositions are not shared/global ownership identities.
- Concurrent same-ID registration has exactly one winner.
- Snapshot ordering is deterministic by caller-supplied `createdAt` then decision ID and is not trusted chronology, priority, utility, confidence, truth, or execution order.
- `createdAt` is caller-supplied data and is not a trusted clock assertion.
- Decision rationale is explicit stored data but is excluded from lifecycle observability metadata.
- `LearningDecision.toString()` redacts rationale.
- `LearningDecisionComposition` privately owns mutable decision state; raw store and registration primitives are not production public API.

## Explicit exclusions

Learning Decision Foundation v0.1 does **not** include or authorize:

- a `LearningPolicy` engine, policy registry, policy evaluator, or policy-generated decisions;
- automatic acceptance, rejection, approval, or deferral of candidates;
- application state or application workers;
- consolidation, adaptation, self-improvement, or autonomous learning;
- automatic mutation of Memory, Knowledge, Self, Personality, Trust, Authority, Capability, Execution, planning, goals, or agents;
- promotion of a candidate or approved decision into fact, knowledge, belief, memory, personality trait, or self-definition;
- truth verification, confidence scoring, ranking, utility scoring, causality, adjudication, reputation, or trust inference;
- hidden candidate lookup or hidden validation of candidate lifecycle references;
- authority grants, capability grants, execution permission, or execution dispatch;
- prompt/style/model control;
- planning, autonomy, agents, cognitive-cycle orchestration, background workers, or recursive learning loops;
- persistence or durable generation identity;
- Android integration or multi-agent/multi-user learning identity.

## Freeze rule

Any expansion of Learning Decision semantics must go through the normal feature-branch → PR → exact-head Core CI → architecture/security audit → exact-head merge workflow.

The next allowed Learning architecture stage is a separate `Learning Policy v0.1` design. Policy must not silently collapse the distinctions:

`candidate → decision → authorization → application → downstream mutation`.

This freeze does not authorize candidate application, consolidation, execution, or downstream mutation merely because a decision is `APPROVE`.
