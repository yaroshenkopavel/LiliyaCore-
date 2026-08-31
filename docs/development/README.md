# LiliyaCore Development Journal

This directory is the durable technical history and handoff source for the active repository `yaroshenkopavel/LiliyaCore-`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only.

Current status: **ACTIVE DEVELOPMENT — Runtime Hardening v0.1 Slice 2 complete; Slice 3 next.**

## Read order for a new session

1. `HANDOFF.md` — canonical transfer/current-work checkpoint, exact implementation and CI evidence, current phase, next allowed slice and strict gates.
2. `CURRENT_STATE.md` — compact live main/CI/phase checkpoint.
3. `START_HERE.md` — safe-entry procedure and hard engineering/security rules.
4. `RUNTIME_HARDENING_V0_1_CONTRACT.md` — active, not-yet-frozen Runtime Hardening architecture.
5. `PROTECTED_MODEL_PACKAGE_V0_1_CONTRACT.md` and `PROTECTED_MODEL_PACKAGE_V0_1_FREEZE.md` — immediate frozen dependency.
6. `COGNITIVE_STORAGE_ENCRYPTION_V0_1_CONTRACT.md` and `COGNITIVE_STORAGE_ENCRYPTION_V0_1_FREEZE.md` — frozen storage-encryption dependency.
7. `ANDROID_DEVICE_KEY_V0_1_CONTRACT.md` and `ANDROID_DEVICE_KEY_V0_1_FREEZE.md` — frozen signing-only device-key boundary.
8. `STRUCTURE.md` — package/file layout, subsystem purpose, ownership and boundaries.
9. `ARCHITECTURE.md` — broader frozen architecture baseline and dependency direction.
10. `NUANCES.md` — non-obvious pitfalls and audit findings.
11. `DECISIONS.md` — durable architecture decisions and rationale.
12. `DEVELOPMENT_LOG.md` — milestone history; older entries may lag newer checkpoint docs and must be interpreted using GitHub/source evidence.
13. `VERIFICATION_POLICY.md` — rules for promoting verified facts into permanent history.
14. `UPDATE_SYSTEM_V0_1_CONTRACT.md` and `SECURITY_LICENSING_V0_1_CONTRACT.md` — later roadmap contracts, not permission to skip Runtime Hardening.

## Journal roles

### `HANDOFF.md`

Canonical cross-chat/current-work transfer document. It records the exact verified implementation checkpoint, current phase, frozen dependencies, known audit caveats, next allowed slice and strict continuation procedure.

### `CURRENT_STATE.md`

Compact live operational checkpoint. Update when implementation main SHA, PR/CI state, blocker, pause/resume marker or exact next action changes materially.

### `START_HERE.md`

Safe entry point. It tells a fresh session what to read, which invariants must be preserved and what implementation stage is allowed next.

### Contract/freeze documents

Subsystem contracts define allowed architecture before implementation. Freeze documents record guarantees/evidence of formally closed phases. A frozen baseline is not casually redesigned; a real incompatibility requires an explicit new version/contract rather than silent reinterpretation.

### Durable history/reference files

`DEVELOPMENT_LOG.md`, `STRUCTURE.md`, `ARCHITECTURE.md`, `NUANCES.md`, `DECISIONS.md`, security/update contracts and subsystem freeze docs contain verified stable facts or explicit architecture contracts rather than every intermediate experiment.

## Current implementation checkpoint

Verified implementation `main`:

`076b0c4dfa18dbdde178f741edd7f63237ceaf28`

Runtime Hardening v0.1 Slice 2, PR #66.

Verified merge/main run:

`33435578143` / Core CI #418 — GREEN for both `Test LiliyaCore` and `Android Keystore Instrumentation`.

Runtime Hardening remains **ACTIVE, NOT FROZEN**. Slice 3 — Operation Supervision and Resource Bounds — is next.

See `HANDOFF.md` for complete evidence and continuation instructions.

## Maintenance rule

After every merged architecture/security PR, implementation slice, failed/blocked gate that changes the next action, freeze milestone, intentional pause/resume, or important architecture decision:

- update `CURRENT_STATE.md` immediately once facts are verified;
- keep `HANDOFF.md` synchronized when the cross-chat/current-work resume point changes;
- verify GitHub/source/CI facts before promoting them into durable history;
- update `DEVELOPMENT_LOG.md` with stable milestone checkpoints without rewriting uncertain historical evidence;
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
