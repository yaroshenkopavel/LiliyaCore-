# License Core v0.1 — Freeze Checkpoint

Status: **FROZEN**

Verified implementation baseline before freeze documentation: `3b249b1d1f7b2c0128e8f3ca6fe4cdc449cb663b`.

Verified freeze checkpoint merge: `255b711a2577cae9358d19f22728ef0ea1ba2ebb`, with PR #35 exact-head Core CI `33328382254` GREEN and merge/main Core CI `33328556949` GREEN.

Canonical architecture contract: `LICENSE_CORE_V0_1_CONTRACT.md`.

Umbrella roadmap: `SECURITY_LICENSING_V0_1_CONTRACT.md`.

## Frozen boundary

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit LicensePolicy → LicenseDecision → optional fresh scoped Authority request`

Mandatory separations remain:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

## Verified implementation slices

- PR #30 — immutable License models and exact-generation ownership/store contracts;
- PR #31 — canonical entitlement/envelope and trusted verification boundary;
- PR #32 — explicit policy/time/offline-lease/replay/revocation decision semantics;
- PR #33 — controlled fresh License→Authority integration;
- PR #34 — readiness hardening for malformed evidence, stale/replay/revocation/time boundaries, concurrency/isolation and privacy-safe observability.

Each implementation slice had exact-head Core CI GREEN before its merge and merge/main Core CI verification afterward. PR #34 merge/main Core CI `33327943577` is GREEN on `3b249b1d1f7b2c0128e8f3ca6fe4cdc449cb663b`.

Process note: License slices #32-#34 advanced while the required Learning/Persistence observability gate was still overdue. That sequencing was a process violation even though the individual License code CI gates were GREEN. The missed Learning/Persistence gate is corrected by `LEARNING_PERSISTENCE_OBSERVABILITY_AUDIT_V0_1.md`; this correction does not retroactively describe the original sequencing as compliant.

## Frozen verification guarantees

- envelope content cannot select a trust root;
- schema and algorithm gates are explicit;
- trusted key lookup is exact by signing key ID;
- trusted key identity/algorithm mismatch fails closed;
- invalid signature fails closed;
- malformed canonical payload fails closed;
- payload signing-key identity must match the envelope;
- Verified evidence is constructible only through the verification path inside Core;
- verification success is still not entitlement or Authority.

## Frozen ownership guarantees

- live state uses exact `(LicenseId, LicenseGeneration)` ownership;
- generation is positive;
- duplicate live License ID is rejected;
- stale/ABA ownership cannot remove a newer generation;
- exact removal is one-shot;
- snapshots are deterministic detached views;
- compositions are isolated by default;
- generation is state ownership evidence, never permission.

## Frozen policy semantics

Policy has no hidden system clock. Time/revocation/replay evidence is explicit caller input.

Exact rules:

- `notBefore` is inclusive;
- `expiresAt` is an exclusive deadline;
- **offline-lease semantics are required in License Core v0.1**;
- `offlineLeaseUntil`, when carried by entitlement evidence, is an exclusive deadline;
- stale revocation epoch denies;
- required replay sequence missing denies;
- stale replay sequence denies;
- suspicious time/replay state denies;
- product/feature mismatch denies;
- explicit subject mismatch denies;
- no hidden refresh/retry/reconciliation is introduced.

Phase F may later add service issuance/refresh and stronger trusted-time evidence, but it may not weaken these local fail-closed policy semantics.

## Frozen Authority boundary

Controlled integration always performs:

`fresh Verified evidence → fresh LicensePolicy evaluation → fresh AuthorityRequest → AuthorityDecision`

License denial means zero Authority calls. Authority denial never becomes authorized. Old License decisions/receipts are historical evidence only and cannot bypass later expiry/revocation/replay checks.

No protected asset adapter, executor, Android Keystore or device control is part of this freeze.

## Privacy and observability

License transitions remain inside Foundation Logging/Diagnostics/CoreObservability.

Readiness contracts verify emitted normal observability excludes private subject text, verification key material, raw canonical payload content and signature bytes.

No direct `println`, `System.out` or `printStackTrace` bypass was found in the License production paths audited for this freeze.

Operational metadata remains structural only. Secret-bearing exception messages, bearer evidence, cryptographic key bytes and private cognitive/model content are excluded from normal License observability.

Foundation caveat: `DiagnosticRecorder` and `StructuredLogger` retain `throwable.message` when callers explicitly supply a throwable. Therefore secret-bearing exceptions must not be forwarded unsanitized; the Foundation plumbing is not itself a universal exception-message redactor.

## Cognitive-data separation

License expiry or denial is not a cognitive-data deletion policy. Memory/Knowledge recovery and future cognitive encryption remain independent security domains.

License tokens/signatures are not cognitive-store keys.

## Corrected Learning/Persistence observability audit closure

The Learning/Persistence observability audit was required before later License work but was not actually completed at that time. PR #35 documentation prematurely described that delayed audit as `CLEAN` before the Foundation throwable emission plumbing had been inspected.

The corrective audit is now completed and recorded in:

`LEARNING_PERSISTENCE_OBSERVABILITY_AUDIT_V0_1.md`

Result for the frozen Learning/Persistence boundary: **CLEAN**, with an explicit Foundation throwable caveat.

Confirmed by the corrective audit:

- durable persistence transitions route through Foundation observability;
- `PersistentRecordStore` does not forward backend throwables into operational emission;
- `LearningApplicationMutationApplier` emits structural result metadata without forwarding throwables;
- Learning durable/public failure rendering keeps private payload and backend exception messages out of rendering while retaining structural reason/exception class;
- malformed/corrupt/incompatible/reopen/generation mismatch paths remain fail closed;
- targeted production-path inspection found no direct console bypass in the audited Learning/Persistence boundary;
- readiness contract `durable_failure_rendering_does_not_expose_private_payload_or_exception_message` remains part of the Core suite.

This correction closes the missed audit evidence gate; it does not change frozen Learning or License semantics.

## Explicit non-goals retained

This freeze does not claim or implement Android Keystore/StrongBox, hardware-backed device binding, attestation, trusted monotonic time, encrypted persistent cognitive storage, protected model decryption, online enrollment/refresh, anti-tamper guarantees or Update System activation.

## Next controlled stage

The next security phase after the fully verified License Core freeze is:

`Android device-key boundary`

That phase has a separate architecture contract and must preserve all License Core v0.1 frozen boundaries.
