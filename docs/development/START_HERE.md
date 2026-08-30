# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Current project type: core-only Kotlin/JVM foundation. Android/device adapters are not part of current `main`.

Legacy repository `Vikrot123/LiliyaCore` is migration history/backup only. Do not split active development across both repositories.

## Product direction

LiliyaCore is the foundation for a personal AI assistant with one continuous identity/persona, offline-first operation, memory/knowledge, controlled autonomy, text/voice interaction, and later Android no-root device capabilities.

Target conceptual chain:

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Orchestration Intent → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

Autonomy, Agent, Delegation and Coordination layers add governance/provenance around this chain; they never create an Authority or Execution bypass.

## Read before changing code

1. `CURRENT_STATE.md` — exact live checkpoint.
2. `ARCHITECTURE.md` — frozen boundaries and current controlled path.
3. `STRUCTURE.md` — package/file ownership map.
4. `NUANCES.md` — known traps/readiness findings.
5. canonical freeze contract for the subsystem being touched.
6. relevant production source and executable contract tests.
7. current GitHub PR/CI state in `yaroshenkopavel/LiliyaCore-`.

## Hard engineering rules

- Work on feature branches; do not modify `main` directly.
- Merge only after the relevant exact-head Core CI gate is GREEN.
- Verify merge/main CI after risky or architectural slices.
- Prefer coherent PRs and clean history; rebuild polluted work rather than merge noise.
- Contracts before complexity.
- Explicit ownership for mutable state/resources.
- Prefer exact `(ID, generation)` ownership handles/instances over ID-only re-resolution.
- Stale/ABA ownership must not remove a replacement generation.
- A failed stale-owner removal is not automatically fatal when a newer replacement generation is live; exact compensation owns only the generation created by the current operation.
- Significant subsystem actions must be observable through Logging/Diagnostics where semantically meaningful.
- Correlation context must be explicit; do not introduce hidden ThreadLocal/global acquisition as a shortcut.
- Runtime is the state authority; Lifecycle orchestrates it.
- Capability is not permission; Authority is separate from Execution.
- Authority is fail-closed/default-deny and must be fresh at real side-effect boundaries.
- Planning is descriptive data; Reasoning is descriptive data; Decision is a recorded choice; Orchestration Intent is non-executing intent.
- Autonomy/Agents/Delegation/Coordination are governance/provenance layers and never implicit permission.
- Structural provenance strings/source references are evidence/consistency markers, not cryptographic credentials, capability tokens, permission receipts or Authority grants.
- Compound controlled cognition is TOCTOU-sensitive: a successful initial preflight is insufficient; write-capable bridges revalidate after the write and compensate their exact generation when governance/provenance changes.
- Private cognitive content must stay out of operational bridge observability unless a dedicated privacy-reviewed contract explicitly says otherwise.
- Do not casually redesign frozen baselines; fix demonstrated correctness/security defects with focused contracts, CI and journal updates.

## Frozen baselines

Frozen v0.1 boundaries currently include:

- Core Foundation;
- Capability & Authority;
- Execution;
- Memory;
- Knowledge;
- Identity / Self;
- Trust / Security;
- Personality;
- Reflection;
- Learning foundations;
- Planning;
- Reasoning;
- Decision;
- Orchestration Intent;
- Controlled Orchestration;
- Autonomy Foundation;
- Controlled Autonomy Deliberation;
- Agents Foundation;
- Controlled Agent Initiative;
- Controlled Agent Lifecycle;
- Agent Delegation Foundation;
- Controlled Agent Delegation;
- Agent Coordination Foundation;
- **Controlled Agent Coordination v0.1**.

Canonical controlled-coordination freeze contract: `CONTROLLED_AGENT_COORDINATION_V0_1_FREEZE.md`.

## Frozen Controlled Agent Coordination path

`exact live Coordination → exact participant ACTIVE preflight → exact coordination↔Autonomy work binding → compensated multi-participant initiative → transactional bounded attempts → exact coordination↔attempt binding → compensated exact deliberation → exact live deliberation preflight → ordinary Planning → ordinary Reasoning → ordinary Decision → ordinary Orchestration Intent → final coordinated execution guard → frozen Controlled Orchestration → fresh Authority → frozen Execution`

The final coordination guard performs no Authority or Execution itself. It revalidates fresh coordinated readiness and the complete exact cognitive/orchestration chain, then delegates only to the existing frozen `ControlledOrchestrationExecution` path. Stale coordination/governance means zero downstream Authority/executor calls.

## Current active stage

Controlled Agent Coordination v0.1 is complete/frozen once its documentation checkpoint merge/main CI is GREEN.

Do not extend coordination with ad-hoc scheduler, voting, consensus, fan-out, retry or permission semantics. Select the next architecture stage from the deferred roadmap using current source truth and a fresh architecture audit.

Current candidates include:

- persistent encrypted cognitive storage and crash recovery;
- Security & Licensing runtime foundations;
- Update System runtime foundations;
- later Android/device integration behind frozen Authority/Execution.

## New-session resume procedure

Before making any code change:

1. read `CURRENT_STATE.md`;
2. fetch current `main` SHA and compare it with the journal;
3. fetch the active PR, if any, and confirm head SHA/state;
4. fetch the relevant CI result;
5. if CI failed, read the failed job/logs before editing;
6. inspect production source, executable contracts and canonical freeze docs for affected boundaries;
7. make the smallest correct change on a feature branch;
8. update durable documentation when verified project truth changes;
9. merge only with the expected exact head after gates are GREEN.

Source-of-truth order:

`current GitHub/main + CI → production source + executable contracts → canonical freeze docs/CURRENT_STATE.md → DEVELOPMENT_LOG.md → chat history`.