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
5. relevant section of `DEVELOPMENT_LOG.md` and `DECISIONS.md`.
6. relevant production files and executable contract tests.
7. verify current GitHub PR/CI state in `yaroshenkopavel/LiliyaCore-`.

## Hard engineering rules

- Work on feature branches; do not modify `main` directly.
- Merge only after the relevant exact-head Core CI gate is GREEN.
- Verify merge/main CI after risky or architectural slices.
- Prefer coherent PRs and clean history; rebuild a polluted branch rather than merge noise.
- Contracts before complexity.
- Explicit ownership for mutable state/resources.
- Prefer exact `(ID, generation)` ownership handles/instances over ID-only re-resolution.
- Stale/ABA ownership must not remove a replacement generation.
- Failures must not be silently swallowed.
- Significant subsystem actions must be observable through Logging/Diagnostics where semantically meaningful.
- Correlation context must be explicit; do not introduce hidden ThreadLocal/global acquisition as a shortcut.
- No hidden logger/global infrastructure acquisition inside subsystems.
- Runtime is the state authority; Lifecycle orchestrates it.
- Modules do not replace service lifecycle ownership.
- Capability is not permission; Authority is separate from Execution.
- Authority is fail-closed/default-deny and must be fresh at real side-effect boundaries.
- Future Android/device/shell execution must not bypass Authority.
- Planning is descriptive data; Reasoning is descriptive data; Decision is a recorded choice; Orchestration Intent is non-executing intent.
- Autonomy/Agents/Delegation/Coordination are governance/provenance layers and never implicit permission.
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
- Agent Coordination Foundation.

Controlled Agent Coordination v0.1 is still **in progress**.

## Current active stage

The verified controlled coordination path now reaches ordinary frozen Reasoning:

`exact live Coordination → participant ACTIVE preflight → coordination↔Autonomy work binding → compensated participant initiatives → transactional attempts → exact attempt binding → compensated deliberation → live deliberation preflight → ordinary Planning → ordinary Reasoning → post-write revalidation/compensation`

Current verified `main` checkpoint and exact CI evidence are recorded in `CURRENT_STATE.md`.

The next preferred architectural slice is:

`exact live coordinated Reasoning generation → ordinary frozen Decision data`

This next bridge must preserve:

- fresh governance preflight;
- exact Reasoning generation/provenance;
- ordinary frozen Decision installation only;
- post-write revalidation;
- exact compensation on stale governance;
- explicit failure if compensation cannot restore the invariant;
- private cognitive-content redaction;
- no Orchestration, Authority, scheduler or Execution semantics.

## New-session resume procedure

Before making any code change:

1. read `CURRENT_STATE.md`;
2. fetch current `main` SHA and compare it with the journal;
3. fetch the active PR, if any, and confirm its head SHA/state;
4. fetch the relevant CI result;
5. if CI failed, read the failed job/logs before editing;
6. inspect relevant production source and contract tests;
7. make the smallest correct change on the active feature branch;
8. update `CURRENT_STATE.md` when the verified checkpoint changes;
9. update `DEVELOPMENT_LOG.md`/`ARCHITECTURE.md`/`STRUCTURE.md` when durable project truth changes;
10. merge only with the expected exact head after gates are GREEN.

Source-of-truth order:

`current GitHub/main + CI → production source + executable contracts → CURRENT_STATE.md → DEVELOPMENT_LOG.md → chat history`.
