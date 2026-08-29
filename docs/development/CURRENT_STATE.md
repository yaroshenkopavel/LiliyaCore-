# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Current `main`: `d74c0a16f9f92a3f4979f23c7bd3f40482df1477`.

This commit merged PR #101 `Learning Consolidation v0.1: Final Readiness Contracts` after exact-head Core CI and final ownership/provenance/pipeline-bypass audit.

Immediately preceding Learning Consolidation milestones:

- PR #96 `Proposal Foundation` → exact completed controlled-learning outcomes become validated consolidation sources;
- PR #99 `Candidate Bridge` → exact consolidation re-enters the normal Learning Candidate pipeline;
- PR #100 `Provenance Hardening` → consolidation-origin provenance is not publicly forgeable and public transplant is fail-closed;
- PR #101 `Final Readiness Contracts` → cross-bridge idempotency and composition/provenance boundaries verified.

PR #98 `Candidate Projection Boundary` was closed unmerged because #99–#101 superseded it with a stronger accepted design.

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
- bridge creates only a normal Candidate and cannot bypass Decision, Policy, Application, Authority or controlled apply;
- no Planning/Autonomy/Agents/model-weight modification exists in this boundary.

Canonical freeze contract: `LEARNING_CONSOLIDATION_V0_1_FREEZE.md`.

## Update System architecture contract

Required future pipeline:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Network delivery is transport, not trust. Signature validity is not activation permission. Android application/runtime updates and explicitly supported internal packages are separate update modes behind the same trust/rollback discipline.

## Security & Licensing architecture contract

Required protected-use direction:

`Signed Entitlement → Device Enrollment → Keystore-backed Key Boundary → License Policy → Authority → Protected Asset/Store Access → Controlled Operation`

Hard rules include: license != Authority; Keystore/StrongBox device key instead of HWID-derived secrets; model keys separated from user cognitive-data keys; license expiry must not intentionally destroy user Memory/Knowledge; anti-debug/obfuscation are defense-in-depth only; protected failures are explicit fail-closed.

## Current next action

The next cognitive architecture stage is **Planning Foundation v0.1**.

Planning must begin as a proposal/structure boundary only.

Mandatory invariant:

`Plan != Decision != Authority != Execution`

Planning v0.1 must not automatically execute actions, grant permissions, mutate Memory/Knowledge, approve learning, or become Autonomy/Agents.

Before implementation, define:

- exact plan identity/generation ownership;
- explicit goal/input provenance;
- deterministic ordered steps and dependency semantics;
- structural capability/action references without execution permission;
- immutable/defensive snapshots;
- stale-safe replacement/removal;
- privacy-safe observability and correlation;
- explicit status semantics that do not imply execution;
- composition isolation;
- clear bridge boundary to future Decision/Authority/Execution layers.

Autonomy / Agents remain deferred until Planning is separately implemented, audited and frozen.

Persistent encrypted storage, Android integration, Update runtime, and Security/Licensing runtime remain separate future stages.

## Workflow

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge with expected head SHA → journal checkpoint`

No intentional direct-to-main development.
