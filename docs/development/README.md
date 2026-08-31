# LiliyaCore Development Journal

This directory is the durable technical history and handoff source for the active repository `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only.

Current status: **PROJECT PAUSED FOR CHAT HANDOFF**. Do not resume implementation without an explicit user request.

## Read order for a new session

1. `HANDOFF.md` — canonical transfer checkpoint, exact implementation pause, current phase, next allowed work and strict gates.
2. `START_HERE.md` — short safe-entry procedure and hard rules.
3. `CURRENT_STATE.md` — compact current main/CI/phase checkpoint.
4. `RUNTIME_HARDENING_V0_1_CONTRACT.md` — active, not-yet-frozen Runtime Hardening architecture.
5. `PROTECTED_MODEL_PACKAGE_V0_1_CONTRACT.md` and `PROTECTED_MODEL_PACKAGE_V0_1_FREEZE.md` — immediate frozen dependency.
6. `COGNITIVE_STORAGE_ENCRYPTION_V0_1_CONTRACT.md` and `COGNITIVE_STORAGE_ENCRYPTION_V0_1_FREEZE.md` — frozen storage-encryption dependency.
7. `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md` and `ANDROID_DEVICE_KEY_V0_1_FREEZE.md` — frozen signing-only device-key boundary.
8. `STRUCTURE.md` — package/file layout, subsystem purpose, ownership and boundaries.
9. `ARCHITECTURE.md` — broader frozen architecture baseline and dependency direction.
10. `NUANCES.md` — non-obvious pitfalls and audit findings.
11. `DECISIONS.md` — durable architecture decisions and rationale.
12. `DEVELOPMENT_LOG.md` — historical development journal; verify newer checkpoints against GitHub because older entries can lag the live handoff state.
13. `VERIFICATION_POLICY.md` — rules for promoting verified facts into permanent history.
14. `UPDATE_SYSTEM_V0_1_CONTRACT.md` and `SECURITY_LICENSING_V0_1_CONTRACT.md` — later roadmap contracts, not permission to skip the current Runtime Hardening phase.

## Journal roles

### `HANDOFF.md`

Canonical cross-chat transfer document. It records the intentional pause, last implementation merge/CI, current active phase, frozen dependencies, known audit caveats, next allowed slice and strict resume procedure.

### `CURRENT_STATE.md`

Compact live operational checkpoint. Update when implementation main SHA, PR/CI state, blocker, pause/resume marker or exact next action changes materially.

### `START_HERE.md`

Safe entry point. It tells a fresh session what to read and which stop/resume conditions are mandatory.

### Contract/freeze documents

Subsystem contracts define allowed architecture before implementation. Freeze documents record the guarantees/evidence of formally closed phases. A frozen baseline is not casually redesigned; a real incompatibility requires an explicit new version/contract rather than silent reinterpretation.

### Durable history/reference files

`DEVELOPMENT_LOG.md`, `STRUCTURE.md`, `ARCHITECTURE.md`, `NUANCES.md`, `DECISIONS.md`, security/update contracts and subsystem freeze docs should contain verified stable facts or explicit architecture contracts rather than every intermediate experiment.

## Current implementation checkpoint

Last code implementation merge before this documentation-only handoff:

`ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`

Runtime Hardening v0.1 Slice 1, PR #65.

Merge/main run:

`33427756131` — GREEN for Core and Android Keystore Instrumentation.

Runtime Hardening remains **ACTIVE, NOT FROZEN, PAUSED AFTER SLICE 1**. Slice 2 has not started.

See `HANDOFF.md` for complete evidence and resume instructions.

## Maintenance rule

After every merged architecture/security PR, implementation slice, failed/blocked gate that changes the next action, freeze milestone, intentional pause/resume, or important architecture decision:

- update `CURRENT_STATE.md` immediately once the facts are verified;
- keep `HANDOFF.md` synchronized when the cross-chat resume point changes;
- verify GitHub/source/CI facts before promoting them into durable history;
- update `STRUCTURE.md` only when source layout/ownership materially changes;
- update `ARCHITECTURE.md` only when a durable boundary/invariant changes;
- update `NUANCES.md` for new non-obvious audit findings/pitfalls;
- update `DECISIONS.md` only for durable decisions future work must preserve;
- never invent missing historical audit evidence.

## Mandatory development workflow

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

CI GREEN is not a substitute for the focused audit.

Do not start the next implementation slice until the previous merge/main required CI is GREEN.

## Source-of-truth priority

1. Current GitHub repository state and CI.
2. Current production source and executable contract tests.
3. `HANDOFF.md` and `CURRENT_STATE.md`.
4. Canonical subsystem contract/freeze documents.
5. Other durable journal files.
6. Chat/session history.

If journal text conflicts with GitHub/source, verify GitHub/source and repair the journal before continuing development.
