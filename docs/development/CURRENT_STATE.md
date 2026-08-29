# CURRENT STATE

Last journal update: 2026-08-29

## Current verified baseline

Current `main`: `f594c00989cd79fd9ea8f4a4bf065a8703c8685e`.

This commit merged PR #88 `Controlled Learning Application v0.1: Downstream Mutation Apply` from exact head `e4749a43a78350cf0c2347f2dea8be87796e3e63` after Core CI #645 completed successfully and the final authority/ownership/idempotency/privacy/partial-failure readiness audit passed.

Immediately before it, PR #89 `Docs: Security & Licensing v0.1 architecture contract` merged as `5968c52af438d4005008dfc72677f423d5f674f9` after Core CI #643 completed successfully.

## Frozen subsystem status

- Core Foundation v0.1: FROZEN.
- Capability & Authority v0.1: FROZEN.
- Execution v0.1: FROZEN.
- Memory Foundation v0.1: FROZEN.
- Knowledge Foundation v0.1: FROZEN.
- Identity / Self Foundation v0.1: FROZEN.
- Trust / Security Foundation v0.1: FROZEN.
- Personality Foundation v0.1: FROZEN.
- Reflection Foundation v0.1: FROZEN.
- Learning Candidate Foundation v0.1: FROZEN.
- Learning Decision Foundation v0.1: FROZEN.
- Learning Policy Foundation v0.1: FROZEN.
- Learning Application Intent Foundation v0.1: FROZEN.

Controlled Learning Application / Consolidation remains **IN PROGRESS**, but the first real Memory/Knowledge mutation path is now merged and verified.

## Verified Controlled Learning chain

Current merged chain:

`candidate → decision → policy boundary → application intent → exact preflight → Authority → prepared mutation → exact claim → fresh preflight + fresh Authority → Memory/Knowledge write → exact completion/idempotency tombstone → structural result receipt`

Verified boundaries:

1. **Preflight**
   - exact Application, Decision, Candidate, and Policy generations must still be current;
   - Decision must be `APPROVE`;
   - readiness is not permission.

2. **Authorization** — PR #78
   - capability: `learning.application.apply`;
   - target scopes: `learning.application.memory`, `learning.application.knowledge`;
   - default deny through Capability/Authority;
   - authorization receipt is evidence only, never durable future permission.

3. **Prepared mutation store** — PR #81
   - exact Application reference, principal, target, target-specific payload, idempotency key, createdAt;
   - duplicate mutation IDs/active idempotency keys reject;
   - exact generation ownership and stale/ABA protection;
   - payload content is not exposed in lifecycle observability metadata.

4. **Composition ownership** — PR #83, merge `0525304e367c0e691dfb172571af541c1c3bf5f2`
   - prepared mutation store is private to `LearningApplicationMutationComposition`;
   - public API exposes controlled ownership only.

5. **Mutation-time authorization gate** — PR #84, merge `0d05ad9a342bb2683c67395a23a312bbdcd42635`
   - exact mutation checked before and after fresh authorization;
   - prepared target must equal the fresh Application target;
   - stale/missing application/preflight state and Authority denial fail closed.

6. **Exact mutation claim** — PR #85, merge `4ed793e76e1eadf34a8ef0c5010de508565826cc`
   - one active claim per exact mutation generation;
   - active claim blocks removal;
   - release is bound to a private exact claim token.

7. **Exact completion/idempotency tombstone** — PR #87, merge `d073257412f4b7e772cff3bc43e420e82864b53b`
   - only exact current claim token can complete;
   - successful completion removes prepared mutation;
   - completed idempotency key remains reserved for the composition lifetime;
   - repeated complete/release is stale and rejected;
   - current guarantee is in-memory/composition-local, not crash-durable exactly-once.

8. **Real downstream apply** — PR #88, merge `f594c00989cd79fd9ea8f4a4bf065a8703c8685e`
   - applier first acquires exact claim;
   - reruns fresh mutation authorization while claim is held;
   - MEMORY uses `MemoryComposition.remember()`;
   - KNOWLEDGE uses `KnowledgeComposition.create()`;
   - public success receipt exposes only downstream ID + generation, not mutable ownership;
   - Authority denial/target mismatch cause zero downstream writes;
   - downstream ID conflict releases claim and leaves mutation retryable;
   - successful completion prevents a second apply of the same exact mutation;
   - unexpected post-write completion failure attempts exact compensation using the returned downstream ownership; compensation failure is surfaced explicitly as partial failure.

## Update System architecture

PR #86 merged Update System v0.1 architecture contract as `1c9c87e81ba2bd847e9c450881f51e0593576f5a`.

Required future pipeline:

`Discovery → Signed Manifest → Compatibility → Authority → Download → Verify → Stage → Migrate → Activate → Health Check → Commit / Rollback`

Mandatory support:
- Android application/runtime updates;
- explicitly supported internal Liliya packages.

Network delivery is transport, not trust. Signature validity is not activation permission. Previous viable generations remain rollback points until commit/retention permits cleanup.

Durable contract: `UPDATE_SYSTEM_V0_1_CONTRACT.md`.

## Security & Licensing architecture

PR #89 merged Security & Licensing v0.1 architecture contract as `5968c52af438d4005008dfc72677f423d5f674f9`.

Hard decisions now recorded:
- License is not Authority.
- Device binding must use cryptographic enrollment/non-exportable Android Keystore keys when Android integration arrives; do not derive master secrets from IMEI/Android ID/HWID-like identifiers.
- Model/runtime protected-asset keys and user cognitive-data keys are separate domains.
- Commercial license expiry/revocation must not intentionally destroy user-owned Memory/Knowledge data.
- Protected model packages use authenticated encryption and bounded chunk/tensor decryption; normal protected loading must not materialize plaintext model files on disk.
- Anti-debug, anti-dump, obfuscation, symbol stripping, Frida/debugger detection, and similar controls are defense-in-depth, not absolute trust roots.
- License failure is explicit fail-closed denial/error, not deliberately corrupted AI output.
- Offline licensing requires signed/versioned entitlement, lease/expiry policy, clock-rollback considerations, revocation and recovery/device-transfer semantics.
- Update System, Liliya Network, licensing and Authority are separate boundaries and may not bypass one another.

Durable contract: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Current next action

Before declaring Controlled Learning Application / Consolidation frozen, perform a focused readiness pass on the now-real downstream mutation path, especially:

- operation-level observability/correlation continuity across claim → authorization → downstream write → completion;
- outcome/receipt lifecycle and whether a dedicated application-result store is required;
- concurrent apply behavior for the same exact mutation and for conflicting downstream IDs;
- compensation semantics and explicit ambiguous/partial states;
- privacy of result/rejection rendering and metadata;
- clear statement of in-memory idempotency vs future durable/crash-recovery semantics.

Do **not** broaden into Planning / Autonomy / Agents until Controlled Learning Application is separately audited and frozen.

Android Integration remains deferred; when it begins it must implement, not bypass, the recorded Update System and Security & Licensing contracts.

## Workflow

Durable workflow remains:

`feature branch → minimal coherent commits → PR → exact-head Core CI GREEN → architecture/security/privacy/readiness audit → exact-head merge with expected head SHA → journal checkpoint`

No intentional direct-to-main development.
