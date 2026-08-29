# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Current `main`: `ed4ec56a3382f0f656a6e65e974270227648ceb8`.

This commit merged PR #109 `Reasoning v0.1: Readiness Contracts` after exact-head Core CI #771 and final ownership/privacy/boundary audit.

Immediately preceding Reasoning milestones:

- PR #107 `Reasoning v0.1: Structural Artifact Store Foundation` → structural reasoning models, exact generations, defensive premises, stale-safe store ownership, privacy and deterministic snapshots;
- PR #108 `Reasoning v0.1: Composition Ownership` → controlled public composition API, exact `ReasoningOwnership`, composition isolation and install→remove correlation lineage;
- PR #109 `Reasoning v0.1: Readiness Contracts` → repeated-remove fail-closed, detached snapshots, cross-composition same-ID isolation and explicit absence of Decision/Authority/Capability/Execution/truth-confidence semantics.

## Frozen subsystem status

- Core Foundation v0.1: **FROZEN**.
- Capability & Authority v0.1: **FROZEN**.
- Execution v0.1: **FROZEN**.
- Memory Foundation v0.1: **FROZEN**.
- Knowledge Foundation v0.1: **FROZEN**.
- Identity / Self Foundation v0.1: **FROZEN**.
- Trust / Security Foundation v0.1: **FROZEN**.
- Personality Foundation v0.1: **FROZEN**.
- Reflection Foundation v0.1: **FROZEN**.
- Learning Candidate Foundation v0.1: **FROZEN**.
- Learning Decision Foundation v0.1: **FROZEN**.
- Learning Policy Foundation v0.1: **FROZEN**.
- Learning Application Intent Foundation v0.1: **FROZEN**.
- Controlled Learning Application v0.1: **FROZEN**.
- Learning Consolidation v0.1: **FROZEN**.
- Planning Foundation v0.1: **FROZEN**.
- Reasoning Foundation v0.1: **FROZEN**.

Update System v0.1 and Security & Licensing v0.1 remain **architecture contracts**, not implemented runtime subsystems.

## Controlled Learning Application v0.1

Frozen chain:

`candidate → decision → policy → application intent → exact preflight → Authority → prepared mutation → exact claim → fresh preflight + fresh Authority → Memory/Knowledge write → exact completion → completed structural outcome`

Key guarantees:

- fresh Authority adjacent to downstream mutation;
- target/preflight consistency;
- exact claim/completion ownership;
- single-winner concurrency;
- exact structural completed outcome and semantic replay;
- explicit compensation/partial failure;
- correlation continuity across apply → claim → Authority → Memory/Knowledge → completion;
- payload/privacy-safe observability;
- no crash-durable exactly-once claim in v0.1.

## Learning Consolidation v0.1

Frozen chain:

`completed controlled outcomes → consolidation proposal → exact consolidation ownership → controlled Candidate bridge → Learning Candidate → Decision → Policy → Application → fresh Authority → controlled apply`

Key guarantees:

- only exact retained completed outcomes are eligible consolidation sources;
- missing/forged/changed sources reject;
- source lists are non-empty, unique, defensive and deterministic;
- proposal content is redacted from lifecycle observability;
- exact generation ownership and stale-safe removal;
- active conversion claim blocks source removal;
- conversion completion/idempotency is source/composition-owned, not bridge-local;
- multiple bridge instances cannot create multiple Candidates from one exact consolidation;
- exact replay returns the same Candidate reference;
- candidate-ID conflict stays retryable;
- `LearningOrigin.Consolidation` is controlled provenance, not public evidence;
- public installation/transplant of consolidation-origin Candidates is fail-closed;
- bridge creates only a normal Candidate and cannot bypass Decision, Policy, Application, Authority or controlled apply.

Canonical freeze contract: `LEARNING_CONSOLIDATION_V0_1_FREEZE.md`.

## Planning Foundation v0.1

Frozen boundary:

`caller-declared planning origin + goal + ordered descriptive steps → PlanningProposal → exact PlanningGeneration ownership`

Mandatory invariant:

`Plan != Decision != Authority != Execution`

Key guarantees:

- proposal/step IDs and textual fields are validated;
- at least one ordered step is required;
- step IDs are unique;
- caller step lists are defensively copied;
- duplicate proposal IDs reject without replacement;
- exact positive generation ownership and stale/ABA-safe removal;
- repeated removal fails closed;
- same-ID concurrent registration has one winner;
- compositions are isolated;
- snapshots are deterministic and detached from mutable store state;
- goal/step payload is redacted from rendering and lifecycle metadata;
- install→remove uses explicit parent/child `LogContext` correlation;
- Planning exposes no Decision/Authority/Capability/Execution semantics and performs no side effect.

Canonical freeze contract: `PLANNING_V0_1_FREEZE.md`.

## Reasoning Foundation v0.1

Frozen boundary:

`caller-declared reasoning origin + ordered premises + analysis + conclusion → ReasoningArtifact → exact ReasoningGeneration ownership`

Mandatory invariant:

`Reasoning != Decision != Authority != Execution`

Key guarantees:

- artifact/premise IDs and textual fields are validated;
- at least one premise is required and premise IDs are unique;
- caller premise lists are defensively copied;
- duplicate artifact IDs reject without replacement;
- exact positive generation ownership and stale/ABA-safe removal;
- repeated removal fails closed;
- same-ID concurrent registration has one winner;
- compositions are isolated and the same ID may exist independently across compositions;
- snapshots are deterministic and detached from mutable store state;
- premise/analysis/conclusion payload is redacted from rendering and lifecycle metadata;
- install→remove uses explicit parent/child `LogContext` correlation;
- conclusion does not imply Decision, truth, confidence, trust, Authority or Execution;
- Reasoning performs no downstream side effect.

Canonical freeze contract: `REASONING_V0_1_FREEZE.md`.

## Update System architecture contract

Required future pipeline:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network delivery is transport, not trust. Signature validity is not activation permission. Android application/runtime updates and explicitly supported internal packages are separate update modes behind the same trust/rollback discipline.

## Security & Licensing architecture contract

Required protected-use direction:

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

Hard rules include: license != Authority; Keystore/StrongBox device key instead of HWID-derived secrets; model keys separated from user cognitive-data keys; license expiry must not intentionally destroy user Memory/Knowledge; anti-debug/obfuscation are defense-in-depth only; protected failures are explicit fail-closed.

## Current next action

The next cognitive architecture stage is **Decision Foundation v0.1**.

This is a future general decision layer and is distinct from the already frozen domain-specific Learning Decision foundation.

Mandatory invariant:

`Decision != Authority != Execution`

Decision v0.1 must begin as a recorded choice/outcome boundary only. It must not grant permission, call executors, mutate Memory/Knowledge merely because a choice exists, approve unrelated learning, or become Autonomy/Agents.

Before implementation, define:

- exact general decision identity/generation ownership;
- explicit structural references to Reasoning/Planning inputs without hidden lookup;
- caller-declared alternatives/options and selected outcome semantics;
- deterministic defensive snapshots;
- stale-safe ownership and composition isolation;
- privacy-safe observability/correlation;
- an explicit downstream bridge that cannot bypass Capability/Authority/Execution.

Autonomy / Agents remain deferred until general Decision/orchestration and controlled-governance boundaries are separately implemented, audited and frozen.

Persistent encrypted storage, Android integration, Update runtime, and Security/Licensing runtime remain separate future stages.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge with expected head SHA → journal checkpoint`

No intentional direct-to-main development.
