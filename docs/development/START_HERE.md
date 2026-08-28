# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `Vikrot123/LiliyaCore`

Default branch: `main`

Scope of this journal: only the current LiliyaCore repository and its own development history.

Current project type: core-only Kotlin/JVM foundation. Android/device adapters are not part of current `main`.

## Product direction

LiliyaCore is the foundation for a personal AI assistant with one continuous identity/persona, offline-first operation, memory/knowledge, controlled autonomy, text/voice interaction, and later Android no-root device capabilities.

Target conceptual chain:

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

## Read before changing code

1. `CURRENT_STATE.md` — exact live checkpoint.
2. `STRUCTURE.md` — package/file ownership map.
3. `ARCHITECTURE.md` — frozen boundaries.
4. `NUANCES.md` — known traps/readiness findings.
5. relevant section of `DEVELOPMENT_LOG.md` and `DECISIONS.md`.
6. relevant production files and contract tests.
7. verify current GitHub PR/CI state.

## Hard engineering rules

- Work on feature branches; do not modify `main` directly.
- Merge only after the relevant Core CI gate is GREEN.
- Prefer coherent PRs and clean history; rebuild a polluted branch rather than merge noise.
- Contracts before complexity.
- Explicit ownership for mutable state/resources.
- Prefer exact ownership handles/instances over ID-only later re-resolution.
- Failures must not be silently swallowed.
- Important subsystem actions must be observable through Logging/Diagnostics where semantically significant.
- Correlation context must survive subsystem boundaries.
- No hidden logger/global infrastructure acquisition inside subsystems.
- Runtime is the state authority; Lifecycle orchestrates it.
- Modules do not replace service lifecycle ownership.
- Capability/Authority is separate from Execution.
- Authority is fail-closed/default-deny.
- Future Android/device/shell execution must not bypass Authority.
- Do not casually redesign frozen baselines; fix demonstrated correctness/security defects with focused contracts and CI.

## Frozen baselines

### Core Foundation v0.1 — FROZEN

Frozen chain:

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Main freeze milestone after PR #14:
`15c0727d5a22eb731e802d3b59105bf517d24807`

Key guarantees:
- structured Logging and semantic Diagnostics remain distinct;
- significant operations can flow through `CoreObservability` with shared context/correlation;
- Runtime owns current runtime state;
- Recovery has explicit active-target ownership;
- EventBus is synchronous/deterministic with explicit subscriptions and failure isolation;
- registries use exact ownership handles and stale-handle protection;
- ServiceManager retains exact started service instances;
- ModuleServiceInstaller is transactional and lifecycle/dependency safe;
- raw service/module registries are private in `FoundationComposition` production wiring.

### Authority v0.1 — FROZEN

Authority freeze main:
`638bbfdc51b9446f637a11c922a050b5289e63d7`

Boundary:

`AuthorityRequest(principal, capability, scope, reason) → AuthorityPolicy → AuthorityDecision`

Key guarantees:
- default deny;
- legacy explicit grants are GLOBAL-only;
- scoped grants require exact principal + capability + scope;
- expiry is strict: valid only while `now < expiresAt`;
- bounded one-level delegation;
- only `DirectAuthorityGrant` is accepted as delegation source;
- delegated grants can authorize but cannot become delegation sources;
- authority decisions/delegation decisions are observable;
- Authority never executes actions.

## Current open work

Execution v0.1 is open in PR #20 and is not part of `main`.

Read `CURRENT_STATE.md` for exact PR head, CI failure, and resume action.

Do not call Execution frozen or production-ready until it is fixed, GREEN, audited, and merged.

## New-session resume procedure

Before making any code change:

1. read `CURRENT_STATE.md`;
2. fetch current `main` SHA and compare it with the journal;
3. fetch the active PR and confirm its head SHA/state;
4. fetch its latest CI result;
5. if CI failed, read the failed job/logs before editing;
6. inspect relevant source/tests;
7. make the smallest correct change on the active feature branch;
8. update `CURRENT_STATE.md` when the checkpoint changes;
9. promote a detailed history entry only after verification according to `VERIFICATION_POLICY.md`.
