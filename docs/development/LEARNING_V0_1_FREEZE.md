# Learning Foundation v0.1 Freeze

Frozen on 2026-08-29 after the verified PR #61, #62, and #63 implementation sequence.

## Verified implementation

### PR #61 — Explicit Candidate Store Foundation

- Final exact head: `a00fbed4714c5f6bbbf2aaa0fc3da6992bbe1c68`.
- Core CI #512: GREEN.
- Merge commit: `5a367762e3c09750a1233b5a9e99b13730f91206`.

Introduced explicit structural `LearningCandidate` models as proposals for possible future learning, not accepted or applied learning decisions. Origins are either exact structural Reflection lifecycle references `(ReflectionRecordId, ReflectionGeneration)` or caller-declared attribution. The store provides exact positive `LearningGeneration`, duplicate candidate-ID rejection, stale/ABA-safe removal, deterministic snapshots, concurrent same-ID one-winner behavior, lifecycle observability, and redacted `LearningCandidate.toString()`.

Candidate proposal text is explicit caller-declared data. It is not written into lifecycle observability metadata and its object string representation is redacted.

### PR #62 — Composition Ownership

- Final exact head: `86dd070a9095430c3e2bfbadd26066013d729b79`.
- Core CI #517: GREEN.
- Merge commit: `a849aaf52afe763376dcb569270214c74af2c53b`.

Introduced `LearningComposition` as the production ownership boundary around the internal `LearningCandidateStore`. Public callers receive controlled install/read/inspect/snapshot APIs and exact `LearningOwnership`; raw store/registration primitives remain outside the public production surface. Install/remove operations use fresh Foundation root contexts, and lifecycle metadata excludes candidate proposal text.

### PR #63 — Readiness Contract Hardening

- Final exact head: `3cb3d0e091d180c0a3fcebb4c6673946c8dbde51`.
- Core CI #521: GREEN.
- Merge commit: `7e6a18d95189aa1e5295663bb626ca07248bc909`.

Test-only hardening locked caller-supplied `createdAt`, independent composition isolation, composition-local generation semantics, structural-only Reflection origin handling without hidden lookup, absence of implicit acceptance/approval/application/consolidation/downstream mutation/truth/confidence/trust/authority/execution semantics, and redacted candidate proposal rendering. No production API or runtime behavior changed.

## Frozen guarantees

- `LearningCandidateId`, `LearningSourceId`, and optional `LearningSourceReference` are explicit nonblank structural values.
- A `LearningCandidate` contains explicit nonblank caller-declared `proposal`; Learning Foundation v0.1 does not infer, verify, approve, accept, apply, rank, score, or consolidate that proposal.
- `LearningOrigin.Reflection(recordId, generation)` is an exact structural reference to a Reflection record lifecycle identity only. Learning performs no hidden Reflection lookup and does not prove Reflection existence, correctness, current availability, truth, trust, acceptance, or applicability.
- `LearningOrigin.Declared(sourceId, sourceReference)` is caller-declared attribution only; it does not prove source identity, authenticity, authority, trust, truth, confidence, or correctness.
- `LearningCandidate.createdAt` is caller-supplied and preserved unchanged; it is not a trusted runtime/source clock or proof of chronology.
- `LearningGeneration` is a positive opaque in-memory lifecycle identity owned by one learning store/composition lifecycle.
- Duplicate candidate IDs are rejected without replacing the current candidate.
- Successful installation owns one exact generation; stale ownership cannot remove a later replacement.
- Same-ID replacement receives a distinct generation within the same store lifecycle.
- Equal numeric generation values across different `LearningComposition` instances do not imply shared ownership, global identity, or shared state.
- `LearningComposition` instances are isolated; the same candidate ID may exist independently in different compositions.
- Concurrent same-ID registration has exactly one winner per store.
- Deterministic snapshots order by caller-supplied `createdAt`, then candidate ID; this ordering is not truth, importance, confidence, utility, acceptance priority, consolidation priority, or trusted chronology.
- Lifecycle observability uses structural metadata such as candidate ID, generation, origin identifiers/generations, caller-declared source attribution, and caller-supplied `createdAt`; candidate proposal text is not written into lifecycle metadata.
- `LearningCandidate.toString()` is redacted and does not render proposal text.
- `LearningComposition` privately owns mutable candidate storage and uses fresh Foundation root contexts for install/remove operations.
- Raw `LearningCandidateStore` and `LearningCandidateRegistration` are not production public surface.
- Candidate presence means only that a candidate is structurally installed. It does not mean accepted, approved, applied, consolidated, learned, trusted, true, useful, authorized, or executable.

## Explicit exclusions

Learning Foundation v0.1 does **not** provide:

- `LearningDecision`, `LearningPolicy`, acceptance/rejection workflow, approval state, or application state;
- a consolidation engine, adaptation engine, autonomous self-improvement loop, or recursive learning loop;
- automatic mutation or creation of Memory, Knowledge, Self, Personality, Trust, Authority, Capability, Execution, planning, or agent state;
- automatic promotion of candidate proposals into facts, knowledge, beliefs, memories, skills, preferences, goals, identity, policy, or behavior;
- truth verification, confidence estimation, scoring, ranking, importance, utility, causal inference, contradiction resolution, or adjudication;
- hidden lookup or validation of referenced Reflection lifecycle identities;
- trust inference, authentication, authority, capability grants, permission changes, or Execution authorization;
- prompt construction, prompt injection, behavior/tone/style application, or direct model-control semantics;
- planning, goals, autonomy, agents, cognitive-cycle orchestration, background workers, or recursive self-modification;
- persistence/database-backed learning state;
- Android/UI/device integration;
- multi-user or multi-agent learning identity semantics.

## Freeze rule

These semantics are stable for Learning Foundation v0.1. Any later expansion must be an explicitly scoped revision through the normal feature branch → PR → exact-head Core CI → architecture/security audit → exact-head merge workflow.

The next learning work must be a separate architectural boundary for explicit `LearningDecision / LearningPolicy` semantics. This freeze does not authorize candidate application, consolidation, or downstream mutation.