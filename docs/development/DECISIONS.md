# LiliyaCore — Architecture Decisions

This file records durable decisions for the current `Vikrot123/LiliyaCore` project.

## ADR-001 — Repository journals are the durable handoff source

Decision: keep development continuity in version-controlled Markdown under `docs/development/`.

Reason: chat/session history may be truncated or unavailable. Git-backed journals survive session boundaries and are reviewable beside code.

Consequence: every material checkpoint change updates `CURRENT_STATE.md`; detailed history is promoted only after verification.

## ADR-002 — Foundation layers freeze after readiness, not first GREEN

Decision: a subsystem is not considered frozen merely because its first PR passes CI. Perform a readiness audit for ownership, lifecycle, observability, concurrency, bypasses, and hidden globals first.

Reason: multiple real defects were found only during readiness passes, especially service lifecycle ownership and authority delegation provenance.

## ADR-003 — Observability is part of correctness

Decision: significant lifecycle, ownership, recovery, authority, and future execution decisions must be observable through Logging/Diagnostics with correlation continuity.

Reason: invisible failures/ownership changes are unsafe to extend and difficult to diagnose.

## ADR-004 — Composition root owns infrastructure

Decision: logging/diagnostic distribution and mutable production infrastructure ownership are wired explicitly by composition roots. Low-level primitives should not secretly create global infrastructure.

Reason: hidden `LoggerFactory.create(...)` defaults previously leaked bootstrap/global writer state into isolated tests and blurred ownership.

## ADR-005 — Exact ownership beats identifier-only ownership

Decision: registrations and started services retain exact handles/instances, not only string IDs.

Reason: IDs can be reused after unregister/replacement. Stale owners must never remove or stop a replacement instance, and lifecycle must still stop the exact object originally started.

## ADR-006 — Runtime is the state authority

Decision: `RuntimeStateController`/holder own runtime state. Lifecycle and future orchestration layers request state changes rather than maintaining competing state.

Reason: dual state authorities create contradictory lifecycle behavior and make recovery/diagnostics unreliable.

## ADR-007 — Modules are structural; services own executable lifecycle

Decision: modules group structure/dependencies/services, while `ServiceManager` owns service start/stop state.

Reason: combining the concerns would make module uninstall and service replacement ownership ambiguous.

## ADR-008 — Raw registries stay private in FoundationComposition

Decision: production `ServiceRegistry` and `ModuleRegistry` mutation is encapsulated inside `FoundationComposition` paths rather than publicly exposed.

Reason: public raw registries allowed ownership changes to bypass `CoreObservability`.

## ADR-009 — Authority is separate from capability and execution

Decision: identifying/possessing a capability is not permission to use it, and an authority decision does not execute anything.

Reason: safe autonomy requires an explicit mandatory policy boundary before side effects.

Consequence: future execution/device adapters must not offer a bypass around the Authority gate.

## ADR-010 — Authority defaults to deny

Decision: no matching grant means denied. Scoped grants require exact scope. Legacy explicit grants apply only to GLOBAL scope.

Reason: implicit or ambiguous authority is unsafe.

## ADR-011 — Grant expiry is strict

Decision: an expiring grant is valid only while `now < expiresAt`; at equality it is expired.

Reason: exact boundary semantics prevent inconsistent authorization across call sites/tests.

## ADR-012 — Delegation cannot amplify authority

Decision: current delegation is one-level, exact capability/scope, cannot outlive the source, and only `DirectAuthorityGrant` is accepted as a delegation source type.

Reason: provenance flags alone can be reconstructed/forged by callers; type-level separation creates a stronger boundary.

## ADR-013 — Event Foundation remains synchronous/deterministic

Decision: current EventBus semantics stay synchronous, ordered, snapshot-based, and in-process.

Reason: queues, persistence, retries, and asynchronous delivery have different failure/ownership semantics and should be introduced explicitly rather than silently changing the foundation contract.

## ADR-014 — Recovery remains reliability infrastructure

Decision: Recovery owns retry/restart/fail policy and active recovery attempts, not cognition/planning/semantic intelligence.

Reason: reliability and intelligence have different ownership and safety responsibilities.

## ADR-015 — Feature branches and GREEN merge gates

Decision: do not modify `main` directly. Prefer clean coherent PRs; merge only after relevant Core CI is GREEN.

Reason: clean history, rollback, and trustworthy checkpoints are more valuable than rapid microcommit accumulation.

## ADR-016 — Tests are executable architecture contracts

Decision: contract tests are part of the architecture specification and must be read before changing subsystem semantics.

Reason: many important rules (concurrency, stale ownership, ordering, expiry, delegation, observability) are easier to preserve through executable contracts than comments alone.

## ADR-017 — Current journal scope is this repository only

Decision: `docs/development/` records only the development, structure, decisions, and state of `Vikrot123/LiliyaCore`.

Reason: mixing histories from separate repositories would make the handoff source ambiguous and could cause a future session to treat unrelated architecture as current code.

## ADR-018 — Update System supports both application and internal-package evolution

Decision: future Liliya update architecture must support both Android application/runtime updates and independently deployable internal Liliya packages. Both use a mandatory staged pipeline with signed manifests, compatibility checks, Authority, integrity verification, explicit migration, provisional activation, health checks, commit, and rollback.

Reason: Liliya must be maintainable and extensible after deployment without turning network delivery into an unrestricted code-install path or forcing every future skill/model/configuration change into a full application rebuild.

Hard consequences:

- network origin is transport, not trust;
- a valid signature is not installation permission;
- update activation must not bypass Authority;
- exact version/generation ownership is required for activation, replacement, and rollback;
- previous viable generations remain rollback points until commit/retention policy permits cleanup;
- Android application updates use supported platform installation/update mechanisms and do not intentionally bypass platform security;
- internal packages are limited to package classes explicitly designed for dynamic deployment;
- arbitrary downloaded executable code requires a separate code-loading/sandbox/trust design and is not implicitly allowed by the Update System;
- prior authorization receipts are evidence, not durable future permission;
- update lifecycle failures, migrations, health checks, commit, and rollback are observable.

Detailed contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## ADR-019 — Security & Licensing uses cryptographic device enrollment, not HWID-derived secrets

Decision: future protected deployment uses Android Keystore/StrongBox non-exportable keys as the preferred device cryptographic anchor, signed/versioned entitlements, independent DEK envelopes, fresh License Policy plus Authority, authenticated encrypted model/storage formats, and explicit recovery/rotation semantics. Raw hardware identifiers are not master-key derivation material.

Reason: IMEI/Android ID/serial/HWID-style identifiers are identifiers rather than protected cryptographic secrets. Binding protected assets or user memory directly to them creates weak key material, poor rotation and dangerous recovery behavior.

Hard consequences:

- device binding uses cryptographic enrollment to a device-held key, not equality with a raw HWID;
- model asset keys, cognitive-store keys and signing trust roots are separate/rotatable responsibilities;
- commercial entitlement and Authority remain separate boundaries;
- user cognitive data is not intentionally made irrecoverable by license expiry/replacement;
- protected model files may use authenticated chunk/tensor encryption and are not intentionally decrypted into plaintext temporary files;
- security/license denial is explicit and fail-closed, not hidden as intentionally corrupted AI math/output;
- anti-debugging, anti-dump detection and native obfuscation are defense-in-depth only and are never claimed as perfect protection on a fully compromised/rooted device;
- long-lived secret/decryption/private signing keys are never hard-coded in shipped Kotlin/native binaries;
- license/update/signing keys and device enrollment support rotation/revocation/recovery;
- Security & Licensing integrates with Update System without making network delivery a trust root.

Detailed contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.
