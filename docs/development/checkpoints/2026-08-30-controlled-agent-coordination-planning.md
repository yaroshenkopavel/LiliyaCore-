# Controlled Agent Coordination v0.1 — Planning Progress Checkpoint

Date: 2026-08-30

## Verified baseline

- `main`: `824306e18990c0cd37fcc95d1c69a1bbeb99f914`
- PR #177: `Controlled Agent Coordination v0.1: Planning Bridge`
- exact PR head: `6d2e707f280a8704e96c2d25698b64edc75e12a8`
- PR exact-head Core CI run #1116: GREEN
- merge/main Core CI run #1117: GREEN
- local Termux targeted Planning Bridge contract: GREEN
- local Termux full `gradle :core:test --console=plain`: GREEN

## CI incident classification

Several attempts of PR run #1116 failed before runner assignment. The exact GitHub check-run annotation stated:

`The job was not started because recent account payments have failed or your spending limit needs to be increased. Please check the 'Billing & plans' section in your settings`

The failed attempts had no runner and no workflow steps. A payment method and a $1 GitHub Actions budget were configured. The same exact-head run then received a hosted runner and completed GREEN. This incident is classified as external billing/runner gating, not a source or test failure.

## Controlled Agent Coordination progression

Implemented and merged slices through this checkpoint:

1. #167 Exact Live Preflight
2. #168 Exact Coordination Work Binding
3. #169 Work Binding Ownership
4. #170 Compensated Coordination Initiative
5. #171 Transactional Attempt Gate
6. #172 Exact Attempt Binding Foundation
7. #173 Attempt Binding Ownership
8. #174 Commit Attempt Transaction Binding
9. #175 Compensated Deliberation Transaction
10. #176 Deliberation Live Preflight
11. #177 Planning Bridge

The current governed chain is:

`exact live Coordination → exact ACTIVE participants → exact work binding → compensated Autonomy initiatives → transactional bounded attempts → exact attempt binding → compensated deliberation requests → live deliberation preflight → ordinary frozen Planning → post-write revalidation/compensation`

## Planning Bridge boundary

`Coordinated Planning != Reasoning != Decision != Permission != Authority != Execution`

The bridge:

- accepts one exact coordinated deliberation request;
- performs fresh coordinated deliberation preflight before writing;
- builds ordinary frozen `PlanningProposal` data with structural source provenance;
- installs through frozen `PlanningComposition`;
- performs a second fresh coordinated preflight after the write;
- compares the exact readiness evidence;
- removes the exact newly-created Planning generation if governance changed;
- returns explicit `Failed` and emits CRITICAL observability if exact compensation cannot restore the invariant;
- keeps private goal and steps outside bridge observability;
- performs no Reasoning, Decision, scheduling, Authority or Execution.

## Security/provenance nuance

Planning source IDs/references are structural provenance/evidence markers. They are not cryptographic capability tokens and must not be described as unforgeable permission. A syntactically matching provenance string alone cannot authorize execution; downstream trusted layers must combine provenance with fresh exact live evidence and the existing Authority boundary.

This is an existing architectural nuance, not a reason to redesign the Planning Bridge.

## Correlation nuance

The compound coordination cognitive path does not yet maintain one shared correlation root through every frozen subsystem composition. Some frozen compositions create their own operation roots. This is cross-cutting observability debt rather than a Planning Bridge correctness blocker.

Do not solve it by adding hidden global/ThreadLocal context. Address it later as an explicit cross-subsystem correlation design with contracts.

## Next stage

Preferred next slice: **Controlled Agent Coordination Reasoning Bridge**.

Target boundary:

`Coordinated Reasoning != Decision != Permission != Authority != Execution`

Expected transaction shape:

`exact live coordinated deliberation + exact live coordinated Planning → fresh preflight → ordinary frozen Reasoning install → fresh post-write preflight/provenance check → exact compensation on stale governance`

Required guarantees:

- exact Planning generation, not ID-only lookup;
- Planning must belong to the same coordinated deliberation provenance;
- fresh coordination/deliberation governance immediately before write;
- ordinary frozen Reasoning data only;
- post-write revalidation before returning success;
- exact Reasoning-generation compensation on race;
- explicit failure if compensation cannot restore the invariant;
- private reasoning/cognitive content excluded from bridge observability;
- no Decision, Orchestration, permission, Authority, scheduler or Execution.

## Reopen rule

Reopen already-merged Planning behavior only for a demonstrated correctness, security, privacy or ownership defect with a focused reproduction/contract. Do not widen the next Reasoning slice into provenance-token or global-correlation redesign without such evidence.
