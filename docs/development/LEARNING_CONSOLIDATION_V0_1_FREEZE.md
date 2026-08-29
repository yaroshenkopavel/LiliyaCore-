# Learning Consolidation v0.1 — Freeze Baseline

Freeze date: 2026-08-29

Verified production baseline: `d74c0a16f9f92a3f4979f23c7bd3f40482df1477`.

Learning Consolidation v0.1 is **FROZEN** as a controlled proposal-and-reentry foundation.

## Purpose

Consolidation takes exact structural evidence of already-completed Controlled Learning applications and allows a caller to record a consolidation proposal. A validated proposal may then be converted exactly once into an ordinary `LearningCandidate` that re-enters the existing Decision → Policy → Application → fresh Authority pipeline.

Consolidation is not automatic truth formation, hidden Memory mutation, model-weight learning, planning, autonomy, or arbitrary self-modification.

## Frozen chain

`Completed Controlled-Learning Outcomes → Consolidation Proposal → Exact Conversion Claim → Learning Candidate → Decision → Policy → Application Intent → fresh Authority → Controlled Apply`

## Proposal source integrity

A `LearningConsolidationProposal`:

- has an exact nonblank consolidation ID;
- receives one or more completed `LearningApplicationMutationApplicationReceipt` sources;
- requires unique exact mutation references;
- canonicalizes source order by mutation ID then generation;
- exposes defensive source snapshots;
- stores caller-declared proposal text with redacted rendering.

`LearningConsolidationComposition.install(...)` verifies every source receipt against the actual completed outcome retained by its exact `LearningApplicationMutationComposition`.

Therefore:

- missing source → reject;
- forged/changed receipt → reject;
- source evidence from another composition without a matching local completed outcome → reject.

The proposal store owns exact positive generations and stale-safe registration removal.

## Conversion ownership and one-winner semantics

Proposal → Candidate conversion is owned by the consolidation store, not by a bridge-local map.

For an exact `LearningConsolidationReference(id, generation)`:

- stale/missing generation cannot convert;
- one active conversion claim exists at a time;
- active conversion blocks proposal removal;
- release is exact-token bound;
- successful conversion records the exact `LearningCandidateReference` in the source entry;
- repeated conversion returns that already-created candidate instead of creating a second candidate;
- separate bridge instances share the same completion state;
- candidate-ID installation conflict releases the conversion claim and permits a later retry with another candidate ID.

If candidate installation succeeds but conversion completion unexpectedly fails, the bridge uses exact returned candidate ownership for compensation. Compensation failure is surfaced explicitly as partial failure.

## Typed provenance and anti-forgery boundary

Converted candidates use:

`LearningOrigin.Consolidation(consolidationId, generation)`

The constructor for this origin is internal to the learning module.

Additionally, public `LearningComposition.install(candidate)` rejects any consolidation-origin candidate. The validated bridge uses a dedicated internal `installFromConsolidation(...)` path.

This dual boundary matters because constructor restriction alone would not prevent a legitimate consolidation candidate obtained through read APIs from being transplanted into another composition.

Consequences:

- external callers cannot normally construct typed consolidation provenance;
- public install cannot accept forged or transplanted consolidation provenance;
- only the validated bridge path creates and installs that origin;
- the created candidate then behaves as an ordinary candidate for all later Decision/Policy/Application stages.

This is an API/provenance integrity boundary, not a sandbox against arbitrary hostile code already executing inside the same trusted core module/process.

## Pipeline safety

`LearningConsolidationCandidateBridge` has no direct dependency on:

- `LearningDecisionComposition`;
- `LearningPolicyComposition`;
- `LearningApplicationComposition`;
- Authority;
- Memory;
- Knowledge;
- Execution.

Its downstream capability is limited to installing an ordinary `LearningCandidate` through the trusted consolidation path.

Therefore a consolidation proposal cannot silently become accepted learned state. It must proceed through the frozen learning pipeline.

## Observability and privacy

Significant lifecycle events are observable through Logging + Diagnostics.

Bridge correlation lineage is explicit:

`conversion root → exact conversion claim child → candidate install child → conversion completion/release child → final result`

Logging and Diagnostics preserve matching `LogContext` for the same significant operation.

Consolidation proposal content is not placed into lifecycle metadata or bridge result rendering. Structural IDs/generations/source counts are allowed metadata.

## Composition isolation

- proposal stores are composition-owned;
- exact completed outcomes are validated against the supplied local mutation composition;
- candidate conversion state lives in the local consolidation store;
- public candidate install rejects consolidation-origin transplantation into another composition.

## Explicit v0.1 limits

Learning Consolidation v0.1 does not:

- automatically generate consolidation proposals;
- judge whether a proposal is true or useful;
- approve candidates;
- bypass Decision/Policy/Application/Authority;
- mutate Memory/Knowledge directly;
- perform semantic clustering/summarization/model inference;
- persist proposals/conversion state across process death;
- update model weights;
- create plans/goals/agents;
- grant Authority.

Future semantic consolidation engines may propose content, but their output must enter this controlled boundary rather than gaining direct mutation authority.

## Freeze rationale

Readiness contracts cover:

- exact completed-outcome source validation;
- forged/missing source rejection;
- canonical/defensive source snapshots;
- exact generation ownership and stale-safe removal;
- composition isolation;
- deterministic snapshots;
- proposal privacy;
- exact conversion claim and source-removal barrier;
- stale source rejection;
- candidate-ID conflict retry;
- one-winner concurrent conversion;
- repeated conversion replay;
- shared completion across separate bridge instances;
- typed exact candidate provenance;
- public provenance-forgery rejection;
- cross-composition transplant rejection;
- correlation continuity;
- compensation/partial-failure boundary.

## Next architecture stage

The next cognitive foundation is **Context v0.1**, followed by Meaning v0.1 and Goal v0.1 before Planning.

Corrected cognitive direction:

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning → Consolidation`

This ordering prevents Planning from becoming an implicit owner of context construction, semantic interpretation, or goal creation.
