# START HERE — Session Handoff

## Active project

Repository: `Vikrot123/LiliyaCore`
Default branch: `main`
Project: core-only LiliyaPro/Liliya assistant foundation. Old `LiliyaPro` repositories/tags are donor/history only unless explicitly requested.

## Product direction

Liliya is intended to become a personal AI assistant with one continuous identity/persona, offline-first operation, memory/knowledge, controlled autonomy, text/voice interaction, and later Android no-root device capabilities.

Target conceptual chain:

`Interaction/Input → Context → Meaning → Goal → Planning → Reasoning → Decision → Capability/Authority → Execution → Result → Reflection → Memory/Knowledge → Learning`

## Hard engineering rules

- Work on feature branches; do not modify `main` directly.
- Merge only after the relevant Core CI gate is GREEN.
- Prefer one coherent commit per small architectural PR; avoid polluted microcommit branches.
- Contracts before complexity.
- Explicit ownership for mutable state/resources.
- Failures must not be silently swallowed.
- New subsystems must be observable through Logging/Diagnostics where significant.
- Correlation context must survive subsystem boundaries.
- No hidden logger/global ownership dependencies.
- Capability/Authority is separate from Execution.
- Authority is fail-closed/default-deny.
- Android/device/shell integration must not bypass Authority.
- Do not reopen frozen foundation layers casually; only fix demonstrated contract/security defects.

## Frozen baselines

### Core Foundation v0.1 — frozen

Contains:

`Logging → Diagnostics → CoreObservability → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition`

Important properties include exact service/module registration ownership, exact started-service instance ownership, lifecycle-safe module uninstall, no raw registry mutation through FoundationComposition, and shared correlation-aware observability.

### Authority v0.1 — frozen

Contains capability/principal/scope models, default deny, scoped/expiring grants, one-level bounded delegation, provenance, and direct-only delegation sources.

Important invariants:

- legacy explicit grants are GLOBAL-only;
- scoped grants require exact principal + capability + scope;
- grant is expired at `now == expiresAt`;
- delegated grant cannot outlive its direct source;
- `AuthorityDelegationPolicy` accepts only `DirectAuthorityGrant` as source;
- delegated grants can authorize but cannot become delegation sources;
- authority decisions are observable;
- authority never executes actions.

## Resume procedure

Before making any code change in a new session:

1. Read `CURRENT_STATE.md`.
2. Verify the recorded `main` SHA and any open PR head SHA on GitHub.
3. Check CI for the open PR before changing it.
4. If CI failed, read the failed job/logs before editing.
5. Update this journal whenever the checkpoint materially changes.

User convention: a standalone `+` means continue the already-planned next step autonomously; do not re-ask for confirmation.
