# LiliyaCore Development Journal

This directory is the durable handoff source for continuing LiliyaCore development across chat/session boundaries.

## Read order for a new session

1. `START_HERE.md` — project identity, hard rules, and how to resume safely.
2. `CURRENT_STATE.md` — exact current checkpoint, open PRs, CI state, and next action.
3. `ARCHITECTURE.md` — frozen architecture boundaries and non-negotiable invariants.
4. `DEVELOPMENT_LOG.md` — chronological milestone history.
5. `DECISIONS.md` — important architectural decisions and why they were made.

## Maintenance rule

After every merged architectural PR, failed/blocked gate that changes the next action, freeze milestone, or important architecture decision:

- update `CURRENT_STATE.md` first;
- append one concise entry to `DEVELOPMENT_LOG.md`;
- update `ARCHITECTURE.md` only if a boundary/invariant changed;
- append to `DECISIONS.md` only for decisions future work must understand.

Do not rewrite old history to make it look cleaner. Correct mistakes with a later dated entry.

## Source of truth priority

1. GitHub repository state and CI results.
2. Files in this journal directory.
3. Chat memory/history.

If journal text conflicts with GitHub, verify GitHub and repair the journal.
