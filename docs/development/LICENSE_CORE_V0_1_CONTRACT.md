# License Core v0.1 Architecture Contract

Status: **FROZEN after freeze-checkpoint merge/main CI GREEN**

## Purpose

License Core v0.1 is the storage/platform-neutral licensing boundary that precedes Android Keystore, device enrollment, encrypted cognitive storage and protected model loading.

Frozen direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit policy decision → optional scoped Authority request → controlled protected use`

## Mandatory separation

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

No License model, verification result, decision or receipt is a general execution permission.

## Frozen implementation surface

License Core v0.1 contains:

- immutable structural License models;
- exact positive `LicenseGeneration` ownership;
- composition-owned exact License store with stale/ABA-safe removal;
- deterministic detached snapshots;
- canonical entitlement payload/envelope representation;
- exact trusted key-ID lookup and allowed-algorithm verification boundary;
- typed verification rejection;
- explicit `LicensePolicyRequest` and `LicensePolicyContext`;
- typed `LicenseDecision` / structural receipt evidence;
- explicit offline-lease, revocation-epoch and replay-sequence semantics;
- controlled License→Authority composition with fresh policy evaluation;
- Foundation Logging/Diagnostics/CoreObservability integration;
- readiness contracts for malformed evidence, concurrency/isolation, replay/revocation/time boundaries and privacy-safe observability.

## Canonical verification boundary

The trusted path is:

`canonical envelope → schema/algorithm gate → exact trusted key-id lookup → exact key identity/algorithm match → signature verification → canonical payload decode → signing-key consistency → Verified evidence`

The envelope cannot select its own trust root.

Forbidden behavior remains:

- `alg=none` or unsigned fallback;
- algorithm confusion;
- embedded/caller-selected arbitrary trust roots;
- unknown algorithm fallback;
- treating parse success as signature success;
- treating signature success as entitlement or Authority success.

Verification failures fail closed and cannot produce policy entitlement.

## Exact ownership

A live License record is owned by exact `(LicenseId, LicenseGeneration)`.

Frozen invariants:

- generation is positive;
- duplicate live License ID is rejected;
- stale ownership cannot remove a newer generation;
- removal is exact and one-shot;
- snapshots are deterministic detached views;
- independent compositions are isolated unless an explicit shared dependency is supplied;
- generation is ownership evidence, not permission.

## Time semantics

Policy time is always an explicit caller-supplied input; License Core does not read a hidden global/system clock.

Frozen boundary rules:

- `notBefore` is inclusive: evidence is denied strictly before that instant;
- `expiresAt` is an exclusive deadline: entitlement is denied at and after it;
- **offline lease semantics are mandatory in License Core v0.1**;
- when an entitlement carries `offlineLeaseUntil`, it is an exclusive deadline and entitlement is denied at and after it;
- suspicious time/replay state explicitly fails closed;
- wall-clock comparison alone is not claimed to solve rollback attacks;
- trusted monotonic/device/server time remains a later adapter concern.

This mandatory offline-lease policy semantics satisfies Phase A of `SECURITY_LICENSING_V0_1_CONTRACT.md`. Phase F later adds enrollment/service-driven issuance and refresh; it does not redefine the already-frozen local policy semantics.

## Replay, revocation and version semantics

License policy accepts explicit minimum revocation/replay evidence from its caller.

Frozen rules:

- unsupported schema/version fails explicitly;
- stale revocation epoch denies;
- required replay sequence missing denies;
- replay sequence below the required minimum denies;
- suspicious replay/time evidence denies;
- no hidden refresh/retry/reconciliation occurs;
- an old decision receipt is historical evidence only and cannot bypass a fresh policy evaluation.

## License policy

Conceptual path:

`Verified entitlement + exact requested product/feature/optional subject + explicit time/revocation/replay evidence → LicenseDecision`

Default posture is fail closed.

A positive decision requires exact product and feature agreement and exact subject agreement when a subject is explicitly requested.

Typed denial covers product/feature/subject mismatch, not-yet-valid, expiry, offline-lease expiry, stale revocation, replay missing/stale and suspicious time/replay state.

## Authority boundary

Licensing does not replace frozen Authority.

Frozen integration direction:

`fresh Verified evidence → fresh LicensePolicy evaluation → fresh exact AuthorityRequest → AuthorityDecision`

Hard rules:

- License denial performs zero Authority calls;
- a positive License decision is not an Authority grant;
- an Authority denial never becomes an authorized License result;
- every integration authorization call re-evaluates License policy, so an earlier entitled result cannot bypass later expiry/revocation/replay changes;
- execution/protected asset access remains outside License Core v0.1.

## Privacy and observability

All License transitions use Foundation Logging/Diagnostics/CoreObservability.

Approved operational metadata is structural, for example License ID/reference, product/feature ID, generation, schema/version, signing key ID, revocation/replay fields and typed decision/rejection category.

Normal logs/diagnostics/failure rendering must not expose:

- private/signing/verification key material;
- DEKs/wrapping keys;
- raw bearer tokens;
- full canonical payload/envelope bytes;
- signature bytes;
- private subject data;
- Memory/Knowledge/model plaintext;
- attestation tokens;
- secret-bearing exception messages.

Readiness contracts exercise emitted logs and diagnostics and verify that private subject, key material, raw payload and signature content are absent.

No License production path may bypass Foundation observability with `println`, `System.out`, `printStackTrace` or hidden global logging.

## Cognitive-data separation

Commercial entitlement and user cognitive-data recovery are separate domains.

License denial/expiry must not delete Memory/Knowledge or intentionally make legitimate cognitive state unrecoverable. Cognitive encryption keys must not be derived directly from a License signature/token.

## Explicit non-goals

License Core v0.1 does not implement:

- Android Keystore/StrongBox;
- cryptographic device enrollment/binding;
- attestation/Play Integrity;
- SQLite/SQLCipher or filesystem storage;
- cognitive-store encryption;
- protected model package/decryption/streaming;
- online enrollment/billing;
- background refresh/retry/reconciliation;
- Update System activation;
- universal anti-tamper/anti-dump guarantees.

## Executable readiness gates

The frozen implementation proves:

1. malformed/incompatible signed evidence cannot become Verified/Entitled;
2. unknown key ID, unsupported algorithm/schema and invalid signature fail closed;
3. envelope content cannot promote its own trust root;
4. exact License ID/generation ownership is stale/ABA-safe;
5. duplicate/live behavior and deterministic snapshots are explicit;
6. not-before, expiry and **mandatory offline-lease** boundaries are exact;
7. product/feature/subject mismatch denies;
8. stale revocation/replay and suspicious state deny;
9. old decisions/receipts are not durable permission;
10. License entitlement is not Authority;
11. License denial causes zero Authority calls in the integration gate;
12. concurrency and composition isolation hold;
13. normal observability excludes private License/security content;
14. no direct console logging bypass exists in License production paths;
15. no Android/hardware-security claim is made by this core-only layer;
16. License denial/expiry does not destroy cognitive data.

## Freeze evidence

Implementation slices:

- PR #30 — immutable models + exact ownership;
- PR #31 — canonical entitlement verification;
- PR #32 — policy and decision semantics;
- PR #33 — controlled License→Authority boundary;
- PR #34 — readiness hardening.

Each implementation slice passed exact-head Core CI before merge and merge/main Core CI afterward. The freeze checkpoint updates canonical documentation only after PR #34 merge/main CI `33327943577` is GREEN on `3b249b1d1f7b2c0128e8f3ca6fe4cdc449cb663b`.

## Relationship to accepted roadmap

License Core v0.1 is Phase A of `SECURITY_LICENSING_V0_1_CONTRACT.md`.

After this freeze:

`Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Later phases may add stronger device/time/service evidence, but must not weaken the frozen exact ownership, fail-closed policy, Authority separation, privacy, observability or cognitive-data separation rules.