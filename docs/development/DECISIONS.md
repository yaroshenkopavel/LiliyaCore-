# ARCHITECTURE DECISIONS

This file records decisions future sessions must understand before changing architecture.

## ADR-001 — Repository journals are the durable handoff source

Decision: keep development continuity in version-controlled Markdown under `docs/development/`.

Reason: chat/session history may be truncated or unavailable. Git-backed journals survive session boundaries and are reviewable alongside code.

Consequence: every material checkpoint change must update `CURRENT_STATE.md`.

## ADR-002 — Foundation layers are frozen after readiness, not after first GREEN

Decision: a subsystem is not considered frozen merely because its first PR passes CI. Perform a readiness audit for ownership, observability, bypasses, and hidden globals first.

Reason: multiple real defects were discovered only during readiness passes, especially service lifecycle ownership and authority delegation provenance.

## ADR-003 — Observability is part of correctness

Decision: significant lifecycle, ownership, recovery, authority, and execution decisions must be observable through Logging/Diagnostics with correlation continuity.

Reason: invisible failures and hidden ownership changes made the old architecture difficult to diagnose and unsafe to extend.

## ADR-004 — Composition root owns infrastructure

Decision: logging/diagnostics distribution and mutable infrastructure ownership are wired explicitly by composition roots. Low-level primitives should not secretly create global infrastructure.

Reason: hidden `LoggerFactory.create` defaults previously leaked global writer state into isolated tests and blurred ownership.

## ADR-005 — Exact ownership beats identifier ownership

Decision: registrations and started services retain exact handles/instances, not only string IDs.

Reason: IDs can be reused after unregister/replacement. Stale owners must never remove or stop a replacement instance.

## ADR-006 — Authority is separate from capability and execution

Decision: possessing/identifying a capability is not permission to use it, and an authority decision does not itself execute anything.

Reason: safe autonomy requires a mandatory policy boundary before side effects.

Consequence: future execution/device adapters must not offer a bypass around `AuthorityManager`.

## ADR-007 — Authority defaults to deny

Decision: no matching grant means denied. Scoped grants require exact scope. Legacy explicit grants apply only to GLOBAL scope.

Reason: ambiguous or implicit authority is unsafe.

## ADR-008 — Delegation cannot amplify authority

Decision: delegation is one-level, exact capability/scope, cannot outlive the source, and only `DirectAuthorityGrant` is a source type.

Reason: provenance flags alone are forgeable/reconstructable; type-level separation makes the security boundary harder to bypass accidentally.

## ADR-009 — Feature branches and GREEN merge gates

Decision: do not modify `main` directly. Prefer small coherent PRs and one coherent commit where practical. Merge only after relevant Core CI is GREEN.

Reason: clean history, easy rollback, and trustworthy checkpoints are more valuable than rapid microcommits.

## ADR-010 — Old LiliyaPro code is donor/history, not active architecture

Decision: the active project is `Vikrot123/LiliyaCore`. Old LiliyaPro repositories/tags are reference material only unless explicitly selected for a targeted donor comparison.

Reason: bulk-porting old architecture risks reintroducing hidden globals, lifecycle coupling, and earlier design debt.
