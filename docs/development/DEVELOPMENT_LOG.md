# LiliyaCore — Verified Development History

Scope: repository `Vikrot123/LiliyaCore` only.

This log is milestone-oriented. Fine-grained behavior is defined by contract tests and canonical freeze documents.

## Frozen foundation chain

`Logging → Diagnostics → Runtime → Lifecycle → Recovery → Events → Services → Modules → FoundationComposition → Capability/Authority → Execution`

Core Foundation, Capability & Authority, and Execution v0.1 are frozen.

## Frozen cognitive foundations

Memory, Knowledge, Identity/Self, Trust/Security, Personality, Reflection, Learning Candidate, Learning Decision, Learning Policy, Learning Application Intent, Controlled Learning Application, Learning Consolidation, Planning, Reasoning, Decision, Orchestration Intent and Controlled Orchestration v0.1 are frozen.

## Decision Foundation v0.1

- PR #112 — structural Decision foundation; Core CI #785 GREEN.
- PR #113 — composition ownership; Core CI #790 GREEN.
- PR #114 — readiness contracts; Core CI #794 GREEN; merge/new main `770b4da45ad71a7bbeab47b2ddfada32d3bdc44c`.
- PR #115 — freeze/journal; Core CI #796 GREEN; merge/new main `d3853a7ec59e22632766f23d614b7ba18b0acc58`.

Invariant: `Decision != Authority != Execution`.

Canonical contract: `DECISION_V0_1_FREEZE.md`.

## Orchestration Intent Foundation v0.1

- PR #116 — structural intent; exact head `b93a78dcc5f698e5e7a017705f528c093b5966a0`; Core CI #800 GREEN; merge `862e24c0378ee2780e4850685802b48c3d5c0197`.
- PR #117 — composition ownership; exact head `c8e5a24e10211c31e2d515496e24d612ac4a43f8`; Core CI #804 GREEN; merge `f97f46a7d87faefcfcd7834723f119a885f4eca3`.
- PR #118 — readiness; exact head `6aa49bace987c502d046baf8a050424b9efadc70`; Core CI #808 GREEN; merge `ec8037c1a918b7673d82dc9fae539fef2f9d6c96`.
- PR #119 — freeze/journal; Core CI #810 GREEN; merge `42ac72f8c3fbc35617bc965d488d1253994f86ed`.

Invariant: `Decision != Orchestration Intent != Authority != Execution`.

Canonical contract: `ORCHESTRATION_V0_1_FREEZE.md`.

## Controlled Orchestration v0.1

### PR #120 — Exact Execution Preflight

Exact head `cd2f41ea9015056a8fdb7d87e092b7371aca5a78`; Core CI #815 GREEN; merge/new main `c9025ced195e168302b798d9b80a7f94f333ed85`.

Added evidence-only live validation of exact Orchestration generation, exact Decision generation + selected option, and trusted action policy. No Authority or executor call in this slice.

### PR #121 — Fresh Authorization Boundary

Initial CI #820 exposed a test-only mistake: `LogEvent.code` was used although diagnostic code is stored in `LogEvent.marker`. Production authorization code compiled. Final exact head `f824679309338e0d05da2be1492bef229b1750c5`; Core CI #822 GREEN; merge/new main `fdd2c953b0b742d4e7f1f3d9d85e1e5f0c65ac50`.

Established fresh Authority after live preflight and execution-mapping consistency. Authorization evidence remains non-executable and non-durable.

### PR #122 — Controlled Execution Boundary

Exact head `716668b44c5735e75b8c153928b55b4691ebf801`; Core CI #827 GREEN; merge/new main `ff6cabe02c860ce75cefa9d328ed4c8fa9ccfb1c`.

Connected orchestration to frozen Execution. Success reaches executor exactly once. Stale provenance, denied Authority, authorization mapping mismatch, or execution mapping drift produce zero executor calls.

### PR #123 — Readiness Contracts

Exact head `e7e226d76bed3ad25c692d14d1fe6053af3c27c6`; Core CI #831 GREEN; merge/new main `1ba105b74c9fedff45fc6ab70cf5dc6a84172c71`.

Proved prior authorization evidence cannot be reused after grant revocation or exact Decision replacement, and private cognitive payload remains absent from full-path observability.

Frozen direction:

`exact OrchestrationIntent → exact live provenance preflight → trusted action/capability mapping → fresh Authority → frozen Execution → fresh Authority → executor`

Invariant: `Decision != Orchestration Intent != Authorization != Execution`.

Canonical contract: `ORCHESTRATION_CONTROL_V0_1_FREEZE.md`.

## Architecture contracts not yet runtime subsystems

Update System v0.1 and Security & Licensing v0.1 remain architecture contracts.

## Current continuation

Next stage: **Autonomy Foundation v0.1**.

Required direction:

`Goals / Context / Reflection → explicit Autonomy proposal/intent → Decision → Orchestration → Capability/Authority → Execution`

Invariant: `Autonomy != Decision != Authority != Execution`.

The first Autonomy slice must remain structural and non-executing. Agents remain deferred until Autonomy lifecycle, budgets, cancellation, scheduling and governance are separately implemented, audited and frozen.
