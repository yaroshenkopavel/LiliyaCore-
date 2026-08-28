# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Learning Decision implementation baseline before the freeze documentation merge: `a460216beb315ef2b0b2dc772ccbd72c443cb692`.

This commit merged PR #67 `Learning Decision v0.1: Readiness Contract Hardening` after Core CI #538 succeeded for exact head `817e7e990128330625794dcc6192fa08aca722f3` and the final test-only readiness/source audit passed.

Learning Foundation v0.1 freeze record: `docs/development/LEARNING_V0_1_FREEZE.md`.
Learning Decision Foundation v0.1 freeze record: `docs/development/LEARNING_DECISION_V0_1_FREEZE.md`.

Status:

- Core Foundation v0.1: FROZEN.
- Capability & Authority v0.1: FROZEN.
- Execution v0.1: FROZEN.
- Memory Foundation v0.1: FROZEN.
- Knowledge Foundation v0.1: FROZEN.
- Identity / Self Foundation v0.1: FROZEN.
- Trust / Security Foundation v0.1: FROZEN.
- Personality Foundation v0.1: FROZEN.
- Reflection Foundation v0.1: FROZEN.
- Learning Foundation v0.1: FROZEN.
- Learning Decision Foundation v0.1: FROZEN by this documentation checkpoint.
- Learning Policy stage: NOT STARTED.
- Planning / Autonomy / Agents stage: NOT STARTED.
- Android Integration stage: NOT STARTED.

## Learning v0.1 verified implementation

### PR #61 — Explicit Candidate Store Foundation

Final exact head: `a00fbed4714c5f6bbbf2aaa0fc3da6992bbe1c68`.
Core CI #512: GREEN.
Merge commit: `5a367762e3c09750a1233b5a9e99b13730f91206`.

Introduced explicit structural `LearningCandidate` models as proposals for possible future learning rather than accepted/applied learning. Origins are exact structural Reflection `(ReflectionRecordId, ReflectionGeneration)` references or caller-declared source attribution. The store provides exact positive `LearningGeneration`, duplicate rejection, stale/ABA-safe removal, deterministic snapshots, concurrent same-ID one-winner behavior, lifecycle observability, and proposal redaction.

### PR #62 — Composition Ownership

Final exact head: `86dd070a9095430c3e2bfbadd26066013d729b79`.
Core CI #517: GREEN.
Merge commit: `a849aaf52afe763376dcb569270214c74af2c53b`.

Introduced `LearningComposition` as the production ownership boundary. Raw mutable store/registration primitives remain internal; callers receive controlled install/read/inspect/snapshot/remove ownership APIs bound to exact `LearningGeneration`. Install/remove use fresh Foundation root contexts, and proposal text stays out of lifecycle metadata.

### PR #63 — Readiness Contract Hardening

Final exact head: `3cb3d0e091d180c0a3fcebb4c6673946c8dbde51`.
Core CI #521: GREEN.
Merge commit: `7e6a18d95189aa1e5295663bb626ca07248bc909`.

Test-only hardening locked caller-supplied `createdAt`, composition isolation, composition-local generation identity, structural-only Reflection origin, proposal privacy, and absence of implicit acceptance/application/downstream semantics.

## Learning Decision v0.1 verified implementation

### PR #65 — Explicit Decision Store Foundation

Final exact head: `31a6a40de9f7262e5b3c35515d08f10faa817770`.
Core CI #529: GREEN.
Merge commit: `c842817645f7359dbdb4926b16c7acf896a81162`.

Introduced explicit `LearningDecision` records with exact structural candidate lifecycle references, `APPROVE` / `REJECT` dispositions, positive local `LearningDecisionGeneration`, duplicate rejection, stale/ABA-safe exact ownership, deterministic snapshots, concurrent same-ID one-winner behavior, and rationale redaction. `APPROVE` is decision state only and creates no application or authorization semantics.

### PR #66 — Composition Ownership

Final exact head: `af9b2445af5cc1c224c5c565b69023ca8b45151c`.
Core CI #534: GREEN.
Merge commit: `de40f2471b34adb98d4061c08be4b5788c65a9e1`.

Introduced `LearningDecisionComposition` as the production ownership boundary. The mutable store remains private; controlled install/read/inspect/snapshot/remove APIs use exact `LearningDecisionOwnership`, fresh Foundation contexts, isolated composition state, structural-only candidate references, and rationale-safe metadata.

### PR #67 — Readiness Contract Hardening

Final exact head: `817e7e990128330625794dcc6192fa08aca722f3`.
Core CI #538: GREEN.
Merge commit: `a460216beb315ef2b0b2dc772ccbd72c443cb692`.

Test-only hardening locked:

- caller-supplied `LearningDecision.createdAt` preservation;
- independent composition isolation for the same decision ID;
- composition-local `LearningDecisionGeneration` identity even when numeric values match;
- structural-only exact candidate lifecycle references without hidden lookup;
- `APPROVE` as recorded decision state only, without implicit Policy, application, consolidation, authorization, downstream mutation, truth/confidence, trust/authority, Capability, or Execution semantics;
- rationale redaction from rendering and lifecycle metadata.

## Learning Decision v0.1 frozen boundaries

- candidate and decision remain separate structural stages;
- a decision references exact `(LearningCandidateId, LearningGeneration)` structurally and performs no hidden candidate lookup or validation;
- `APPROVE` and `REJECT` are explicit recorded dispositions only;
- decision presence, including `APPROVE`, does not authorize or apply anything;
- exact positive `LearningDecisionGeneration` protects stale/ABA ownership within a store lifecycle;
- generation identity is composition-local, not global or durable;
- same decision IDs in independent compositions do not share state;
- `createdAt` is caller-supplied and snapshot ordering is not trusted chronology, priority, truth, utility, confidence, or execution order;
- decision rationale remains sensitive stored content and is redacted from `toString()` and lifecycle observability metadata;
- `LearningDecisionComposition` privately owns mutable decision state and raw store/registration primitives are not production public surface;
- there is no `LearningPolicy` engine, policy evaluator, automatic decision generation, application state, consolidation engine, autonomous adaptation, downstream mutation, verification/truth engine, authority/capability grant, Execution coupling, planning/agents, persistence, cognitive-cycle orchestration, or Android integration.

## Current next action

Next allowed architecture stage: `Learning Policy v0.1`.

Policy work must build on frozen Learning Candidate and Learning Decision boundaries. It must keep these distinctions explicit:

`candidate → decision → authorization → application → downstream mutation`.

A policy may evaluate or propose decision semantics only through an explicit reviewed boundary; it must not silently turn `APPROVE` into application, authority, execution, consolidation, or learned state.

Controlled consolidation or mutation of Memory/Knowledge/Personality/Self remains out of scope until application/consolidation boundaries are separately designed, reviewed, and frozen.

## Deferred future architecture note

A separate docs-only future architecture note exists on PR #57 for later Cognitive Cycle / Cognitive Governor / Context Assembler / fast-vs-deliberative paths / Resource Governor ideas. It is non-binding and does not authorize premature orchestration, autonomous learning, or self-modifying behavior.

## Frozen predecessor references

Detailed verified freeze history remains in repository docs and Git history:

- Core Foundation v0.1 — frozen.
- Capability & Authority v0.1 — frozen.
- Execution v0.1 — frozen.
- Memory Foundation v0.1 — frozen.
- Knowledge Foundation v0.1 — frozen.
- Identity / Self Foundation v0.1 — `IDENTITY_SELF_V0_1_FREEZE.md`.
- Trust / Security Foundation v0.1 — `TRUST_SECURITY_V0_1_FREEZE.md`.
- Personality Foundation v0.1 — `PERSONALITY_V0_1_FREEZE.md`.
- Reflection Foundation v0.1 — `REFLECTION_V0_1_FREEZE.md`.
- Learning Foundation v0.1 — `LEARNING_V0_1_FREEZE.md`.
- Learning Decision Foundation v0.1 — `LEARNING_DECISION_V0_1_FREEZE.md`.

## Workflow notes

Durable workflow remains:

feature branch → minimal commits → PR → exact-head Core CI GREEN → architecture/security audit → exact-head merge with expected head SHA.

Important prior incidents that must not recur:

- PR #25 handling briefly moved `main` directly to a PR head; the ref was restored and reconciled.
- PR #29 initially used a stale/mixed tree and was rebuilt before merge.
- PR #33 initially had a concurrency-test harness starvation defect; production ownership logic was unchanged and the harness was corrected before GREEN.
- During Memory readiness work, a temporary `noop` file was accidentally created directly on `main` and immediately removed by a corrective commit. No source behavior changed, but all future writes must explicitly target a feature/docs branch.

No intentional direct-to-main development.
