# Update System v0.1 — Architecture Contract

Status: **architecture contract only; implementation not started**.

This document defines the mandatory future update boundary for Liliya/LiliyaCore. It covers both application/runtime updates and independently deployable internal system packages. Future implementation may refine APIs and Android integration details, but must preserve the safety and ownership invariants below.

## Purpose

Liliya must be able to evolve after deployment without requiring a full manual rebuild for every new system, skill, model, configuration, or runtime improvement.

The Update System must support two distinct update classes:

1. **Application / runtime update** — replacement or upgrade of the Android application package and tightly coupled runtime/native components.
2. **Internal package update** — controlled installation, replacement, activation, deactivation, and rollback of updateable Liliya-owned packages such as skills, policies, configuration bundles, model assets, dictionaries, capability providers, schemas, or other components explicitly designed for dynamic deployment.

These two classes share verification, trust, observability, staging, compatibility, and rollback principles, but they must not be treated as the same installation mechanism.

## Mandatory pipeline

All update paths must conceptually follow:

`Update Discovery → Signed Manifest → Compatibility Check → Authority → Download → Integrity/Signature Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

No future adapter may bypass mandatory stages merely because an update originates from a Liliya-operated server, local network, trusted repository, or previously used source.

## Core contracts to introduce

The future subsystem must define explicit models equivalent in responsibility to:

- `UpdateId` — stable identity of one published update.
- `UpdateVersion` — ordered/versioned release identity.
- `UpdateChannel` — at minimum `STABLE`, `BETA`, and `DEV`, or equivalent controlled channels.
- `UpdateKind` — application/runtime vs internal package, with internal package subtypes where necessary.
- `UpdateManifest` — signed structural description of the update.
- `UpdatePackage` / package reference — immutable artifact identity, size, digest, signature metadata, source, and compatibility requirements.
- `UpdatePolicy` — rules for channels, automatic/manual activation, battery/network constraints, maintenance windows, and downgrade policy.
- `UpdateState` — explicit lifecycle state; never inferred only from files on disk.
- `UpdateOwnership` / exact generation handle — exact ownership of a staged or active update generation.
- `UpdateReceipt` — immutable structural evidence of discovery, verification, staging, activation, health result, commit, or rollback outcome.
- `UpdateFailure` / rejection reason — structured and observable failure classification.

Sensitive package payload, secrets, credentials, signing private material, and user content must not be rendered by default `toString()`, logs, diagnostics, or receipts.

## Signed manifest and trust chain

Every installable update must be described by a manifest authenticated by a trusted update-signing chain.

The manifest must bind at least:

- update ID and version;
- update kind/package type;
- artifact digest and expected size;
- signer/key identity or key reference;
- minimum/maximum compatible core/application version where relevant;
- target platform/ABI requirements where relevant;
- dependency and schema/migration requirements;
- rollback compatibility information;
- channel;
- publication/build identity;
- optional expiry/revocation metadata where the signing design supports it.

A network source is **not** a trust source. TLS protects transport; it does not replace artifact signature verification.

Private signing keys must never live in the distributed Liliya application. The device stores only the minimum public trust material or verifiable trust-anchor chain required for validation.

Key rotation and revocation must be designed explicitly before production use. A compromised signing key must not require accepting arbitrary old/new packages forever.

## Authority boundary

Update verification and update permission are separate concerns.

A correctly signed package is not automatically authorized to install or activate.

Future Update System operations that can change installed/active state must pass through Authority using trusted, implementation-owned capability/scope mapping rather than request-controlled capability strings.

Expected capability families may include equivalents of:

- `update.discover`;
- `update.download`;
- `update.stage`;
- `update.activate`;
- `update.rollback`;
- `update.application.install`;
- `update.internal.install`.

Exact names may change during implementation, but there must be no path from network/download code directly to installation or activation that bypasses Authority.

Prior authorization is not a durable permission token. If activation occurs materially later than download/staging, authorization must be evaluated again at the side-effect boundary according to the then-current Authority state.

## Compatibility boundary

Compatibility must be checked before installation and revalidated before activation if mutable state could have changed.

Compatibility checks must include, as applicable:

- current LiliyaCore/application version;
- update package version and downgrade policy;
- Android API level;
- device ABI/architecture;
- required runtime/native library versions;
- required package dependencies;
- schema/data format compatibility;
- required storage space;
- model/runtime compatibility;
- capability/provider contracts;
- migration availability;
- rollback feasibility.

Compatibility results must be explicit and observable. "Downloaded successfully" must never imply "safe to activate".

## Download boundary

The downloader owns transport only. It must not own trust, Authority, activation, or mutation policy.

Requirements:

- download to non-active staging storage;
- bounded size and storage checks;
- resumable/retry semantics may be added, but retries must be idempotent;
- partial files must never become active artifacts;
- artifact digest/signature verification occurs before a package becomes verified/staged;
- network origin alone must not confer trust;
- corrupted or mismatched downloads are rejected and observable.

## Staging

No update should overwrite the active version in place as the first installation step.

Preferred model:

`active generation A + staged generation B`

Only after verification, compatibility checks, required migration preparation, Authority, and activation checks may B become active.

Staging ownership must use exact generation/handle semantics so stale operations cannot remove or activate a replacement package with the same logical ID.

Power loss, process death, or interruption during staging must leave the currently committed active generation recoverable.

## Migration

Any update that changes durable data/schema/config format must declare and execute an explicit migration protocol.

Migration requirements:

- versioned source and target schema identity;
- preconditions;
- deterministic result where possible;
- idempotency or explicit resume marker;
- failure observability;
- backup/snapshot or compensating strategy when rollback requires it;
- no silent destructive migration;
- migration completion receipt/checkpoint before activation is committed.

An update that cannot safely migrate or roll back must surface that fact before activation. Irreversible migrations require an explicit higher-level policy decision rather than being hidden inside package installation.

## Activation and exact ownership

Activation is a state transition, not a file-copy event.

For dynamically updateable internal packages:

- retain exact ownership of the previously active generation;
- activate the new exact staged generation;
- do not re-resolve by logical ID when rollback/removal requires the exact old/new instance;
- prevent stale/ABA handles from deactivating a replacement;
- define dependency ordering for packages that depend on each other;
- prevent partially activated dependency graphs from being treated as healthy.

For application/runtime updates, Android installation constraints apply. The Update System must use a supported Android installation/update flow and must not intentionally bypass platform security/installer requirements.

## Health check

Activation is provisional until health verification succeeds.

Health checks may include:

- process/runtime startup readiness;
- Core health state;
- required service/module availability;
- native library/model load checks;
- package self-check contract;
- required dependency availability;
- migration/schema verification;
- crash/restart threshold within a bounded validation window where appropriate.

Health check failure must produce an observable failure and enter rollback/recovery rather than silently committing the new generation.

## Commit and rollback

Successful health verification allows an explicit **commit** of the new active generation.

Before commit, the previous viable generation and required rollback metadata must remain available unless policy explicitly establishes that rollback is impossible/forbidden.

Rollback is an explicit controlled operation:

`detect activation/health failure → policy/Authority gate → deactivate failed exact generation → restore exact prior generation → restore/compensate migrated state if required → health check → rollback receipt`

Rollback must itself be observable and stale-safe.

Old artifacts must not be cleaned up until retention policy confirms they are no longer required as rollback points.

## Application/runtime update contract

Application/runtime updates include the Android APK/application and tightly coupled native/runtime pieces that cannot safely be hot-swapped as internal packages.

Mandatory requirements:

- signed release provenance;
- manifest/digest/signature verification before installer handoff;
- version/channel compatibility policy;
- Android-supported installer/update mechanism;
- no root requirement in the normal product design;
- no silent platform-security bypass;
- preserve user data and Liliya state across compatible application upgrades;
- startup health marker after installation;
- detect failed/incompatible startup where feasible and provide a recovery path;
- application-level migration follows the same explicit migration principles described above.

The exact amount of unattended installation possible depends on Android distribution/install privileges and must be designed during Android integration rather than assumed at Core level.

## Internal package update contract

Internal package updating is intended for components deliberately designed to be independently deployable.

Potential package classes include:

- skills/tools;
- configuration/policy bundles;
- model assets and model metadata;
- dictionaries/resources;
- capability providers/adapters explicitly designed for dynamic loading;
- schemas/migration bundles;
- safe data/knowledge packs;
- other future extension packages approved by architecture.

An arbitrary downloaded executable file must **not** automatically become an internal package. Executable/plugin loading requires a separately reviewed code-loading/sandbox/trust contract before it is permitted.

Internal packages must support exact package identity + version/generation, compatibility, staging, activation ownership, dependency validation, health check, receipts, and rollback/disable semantics.

## Update network boundary

The future Liliya Network may provide update discovery and artifact transport, but network membership does not grant installation authority.

The Update System must remain safe if:

- the update endpoint is compromised;
- DNS/routing is redirected;
- an old valid package is replayed;
- a package is truncated/corrupted;
- an attacker provides a correctly structured but unsigned manifest;
- an update is validly signed but incompatible with the device/current state;
- the current Authority grant was revoked after download but before activation.

Therefore trust, compatibility, Authority, and exact lifecycle ownership remain local mandatory gates.

## Idempotency, retries, conflicts, and concurrency

Future implementation must define:

- one active install/activation claim per exact update/package generation;
- idempotent repeated discovery/download handling;
- exact ownership for staged and active generations;
- conflict behavior for simultaneous updates to the same package;
- dependency ordering or transactional group semantics where multiple packages must move together;
- safe retry after process death;
- no duplicate activation from replayed receipts/messages;
- receipts are evidence, not reusable permission tokens.

## Observability and privacy

Significant update lifecycle operations must be observable through Logging + Diagnostics with correlation continuity.

At minimum, observable events should cover:

- update discovered;
- manifest rejected/verified;
- compatibility accepted/rejected;
- download started/completed/failed;
- digest/signature verification result;
- staged;
- migration started/completed/failed;
- activation attempted/succeeded/failed;
- health check result;
- committed;
- rollback started/completed/failed;
- cleanup/recovery anomalies.

Logs/diagnostics must prefer structural metadata such as update ID, version, package ID, generation, signer ID, digest reference, state, and failure code. They must not leak signing secrets, credentials, private user data, model prompts, arbitrary package payload, or full sensitive manifests.

## Recovery after interruption

The Update System must be crash-/power-loss-aware.

Durable state must distinguish at least conceptual states equivalent to:

`DISCOVERED → VERIFIED → STAGED → MIGRATING → READY_TO_ACTIVATE → ACTIVATING → HEALTH_CHECK → COMMITTED`

with explicit failure/rollback states.

On restart, the system must inspect durable update state and choose a deterministic recovery action. It must not infer successful activation solely because files exist.

## Rollout/channel policy

The architecture must support controlled release channels, at minimum conceptually:

- `STABLE` — normal trusted production releases;
- `BETA` — opt-in pre-release validation;
- `DEV` — development/testing only.

Channel selection does not weaken signature, compatibility, Authority, staging, or rollback rules.

Future rollout may add percentage/ring/device-cohort deployment, but cohort selection is distribution policy, not trust or authorization.

## Implementation phases

### Phase 0 — now: architecture contract

- keep this document as a durable design constraint;
- preserve exact generation/ownership patterns in new subsystems;
- avoid irreversible state-format designs without version/migration strategy;
- do not expose future update bypasses around Authority/Execution/platform security.

### Phase 1 — Core Update models and policy

Introduce explicit Core contracts for:

- `UpdateManifest`;
- `UpdatePackage`;
- `UpdatePolicy`;
- `UpdateState`;
- `UpdateReceipt`;
- update IDs/versions/channels/kinds;
- compatibility and verification result models.

No networking or Android installation is required in this phase.

### Phase 2 — durable staging, migration, rollback

After the project has an appropriate persistent storage foundation:

- staged package storage;
- durable lifecycle checkpoints;
- schema/data migration protocol;
- exact active/staged ownership;
- rollback checkpoints and cleanup policy;
- interruption recovery.

### Phase 3 — internal package deployment

Implement controlled internal package lifecycle for package classes explicitly approved for dynamic deployment:

- dependency checks;
- activation/deactivation;
- exact generation replacement;
- health checks;
- rollback;
- package result receipts.

Executable plugin/code loading remains excluded until separately designed and audited.

### Phase 4 — Android application/runtime updater

During Android integration:

- update discovery/download transport;
- Android installer handoff/allowed installation workflow;
- application migration/startup health marker;
- recovery UX/path after failed upgrade;
- native/runtime compatibility checks.

### Phase 5 — Liliya Network delivery and automation

When Liliya Network exists:

- signed manifest discovery;
- authenticated/secure transport;
- stable/beta/dev channel delivery;
- automatic periodic update checks according to policy;
- battery/network/maintenance-window constraints;
- staged background download where permitted;
- user/policy-controlled activation;
- rollout/ring support if needed.

Network automation must not weaken local verification, compatibility, Authority, or rollback requirements.

## Hard invariants

Future implementation must preserve all of the following:

1. **Network source is not trust.**
2. **Signature validity is not Authority.**
3. **Download success is not compatibility.**
4. **Staging is not activation.**
5. **Authorization receipt is not durable future permission.**
6. **Activation is provisional until health check succeeds.**
7. **Exact generations/handles are used for replacement and rollback; no ID-only stale ownership.**
8. **Active state is never overwritten blindly as the first update step.**
9. **Failures, rollback, and rejected updates are observable.**
10. **Sensitive update/package contents are redacted from observability by default.**
11. **Android/platform security is not intentionally bypassed.**
12. **Arbitrary remote code is not accepted merely because it arrived through the update channel.**
13. **Irreversible migrations require explicit policy and cannot be hidden.**
14. **The current viable generation remains a rollback point until commit/retention policy permits cleanup.**
15. **Liliya Network may transport updates but cannot bypass local trust, Authority, compatibility, staging, health, or rollback gates.**

## Relationship to current architecture

The future Update System must reuse existing LiliyaCore principles rather than invent parallel unsafe infrastructure:

- Logging/Diagnostics/CoreObservability for lifecycle visibility;
- exact ownership/generation and stale/ABA protection;
- Authority as mandatory permission boundary;
- explicit composition ownership, no hidden global updater;
- Recovery concepts for reliability, while keeping Update lifecycle ownership distinct;
- Execution boundary for side effects where appropriate;
- contract tests as executable architecture specification.

This contract deliberately does **not** claim the Update System is implemented or frozen. It defines the constraints under which future implementation may begin.