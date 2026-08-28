# LiliyaCore Development Journal

This directory is the durable technical history and handoff source for the current repository `Vikrot123/LiliyaCore` only.

Predecessor projects are intentionally outside the scope of this journal.

## Read order for a new session

1. `START_HERE.md` — project identity, frozen baselines, hard rules, and safe resume procedure.
2. `CURRENT_STATE.md` — exact current main/PR/CI checkpoint and next action.
3. `STRUCTURE.md` — current package/file layout, subsystem purpose, ownership, and boundaries.
4. `ARCHITECTURE.md` — frozen architecture baseline and dependency direction.
5. `NUANCES.md` — non-obvious pitfalls and audit findings that are easy to break accidentally.
6. `DEVELOPMENT_LOG.md` — detailed PR-by-PR history of this repository from its earliest verified baseline to now.
7. `DECISIONS.md` — durable architecture decisions and rationale.
8. `VERIFICATION_POLICY.md` — rules for when future work is promoted into permanent history.

## Journal roles

### `CURRENT_STATE.md`
Live operational checkpoint. Update immediately when main SHA, PR head/state, CI result, blocker, pause/resume marker, or exact next action changes.

### Durable history/reference files
`DEVELOPMENT_LOG.md`, `STRUCTURE.md`, `ARCHITECTURE.md`, `NUANCES.md`, and `DECISIONS.md` should contain checked, stable facts rather than every intermediate experiment.

Future detailed entries are promoted after Git/source/CI verification according to `VERIFICATION_POLICY.md`.

## Maintenance rule

After every merged architectural PR, failed/blocked gate that materially changes the next action, freeze milestone, or important architecture decision:

- update `CURRENT_STATE.md` first;
- verify Git/CI/source facts;
- update detailed history after the facts are stable;
- update `STRUCTURE.md` only when source layout/ownership changed;
- update `ARCHITECTURE.md` only when a boundary/invariant changed;
- update `NUANCES.md` when a new non-obvious audit finding/pitfall is learned;
- append to `DECISIONS.md` only for durable decisions future work must understand.

## Source-of-truth priority

1. Current GitHub repository state and CI.
2. Current production source and contract tests.
3. `CURRENT_STATE.md`.
4. Durable journal files.
5. Chat/session history.

If journal text conflicts with GitHub/source, verify and repair the journal before continuing development.
