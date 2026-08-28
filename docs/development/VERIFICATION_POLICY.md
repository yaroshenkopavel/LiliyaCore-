# LiliyaCore — Journal Verification and Maintenance Policy

Purpose: keep the development journal detailed without turning temporary assumptions into permanent project history.

## Two-speed journal model

### 1. Operational checkpoint — update immediately

`CURRENT_STATE.md` is the live handoff checkpoint.

Update it as soon as any of these changes:
- active main SHA;
- open feature branch/head;
- PR state;
- CI result;
- known blocking compiler/test failure;
- explicit pause/resume state;
- exact next action.

Operational checkpoint entries may describe unresolved work, but must label it clearly as open/failed/proposed.

### 2. Durable detailed history — promote only after verification

`DEVELOPMENT_LOG.md`, `STRUCTURE.md`, `NUANCES.md`, `ARCHITECTURE.md`, and `DECISIONS.md` are durable records.

A new detailed section should be treated as stable only after relevant facts are checked.

## Verification levels

### Git fact
Verify from repository/PR/commit metadata:
- branch/head/base SHA;
- merged/unmerged state;
- changed files/commit count when needed;
- location/name of production/test files.

### CI fact
Verify from GitHub Actions:
- workflow run status/conclusion;
- failing job/step;
- exact compiler/test error when it matters to the next action.

Do not describe a PR as GREEN from memory.

### Architecture fact
Verify against:
- production source;
- contract tests;
- composition wiring;
- relevant PR diff/history;
- readiness audit findings.

A PR description is useful context but is not by itself proof that final code has exactly the described semantics.

### Freeze fact
A subsystem may be documented as FROZEN only when:
- implementation is merged to main;
- relevant CI is GREEN;
- readiness audit has no known blocker;
- current-state journal points to the exact baseline.

## Promotion timing

Do not append a permanent historical conclusion during the middle of an experiment merely because code was written.

Promote into durable detailed history when one of these stable points is reached:
- PR merged after GREEN CI;
- PR definitively rejected/abandoned and reason is verified;
- failed gate changes the development direction and exact failure is confirmed;
- readiness audit discovers a real architecture/security issue that changes the implementation;
- subsystem is formally frozen;
- project structure/ownership boundary changes on main.

This implements the user's requested rule: later development should be recorded in detail after the section is considered reliable, rather than filling the permanent journal with unchecked intermediate assumptions.

## Corrections

If an old journal entry is wrong:

1. verify the correct Git/source/CI fact;
2. fix the factual statement;
3. if the correction changes an architectural conclusion, add a dated correction note to the development log/decision record;
4. never preserve a known false statement merely for chronological aesthetics.

## Source-of-truth priority

1. Current GitHub repository state and CI.
2. Current production source and tests.
3. `CURRENT_STATE.md`.
4. Durable development journal files.
5. Chat/session history.

If sources conflict, investigate before continuing implementation.

## Required update set after future work

After a normal merged architectural PR:

1. `CURRENT_STATE.md` — exact new checkpoint.
2. `DEVELOPMENT_LOG.md` — detailed verified PR/history entry.
3. `STRUCTURE.md` — only if files/packages/ownership layout changed.
4. `ARCHITECTURE.md` — only if a system boundary/invariant changed.
5. `NUANCES.md` — if a non-obvious pitfall/audit finding was learned.
6. `DECISIONS.md` — if a durable architecture decision was made.

After a failed CI gate:

- update `CURRENT_STATE.md` immediately;
- add durable history only when exact failure is confirmed and materially changes the next action.

## New-chat recovery procedure

A new session should:

1. read `START_HERE.md`;
2. read `CURRENT_STATE.md`;
3. read `STRUCTURE.md` for package/file map;
4. read `ARCHITECTURE.md` and `NUANCES.md` for boundaries;
5. read the relevant history/decision section;
6. verify current GitHub PR/main/CI state before modifying code.
