# License Core v0.1 Architecture Contract

Status: **ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET FROZEN**

## Purpose

Define the first core-only licensing boundary selected from `SECURITY_LICENSING_V0_1_CONTRACT.md` before Android Keystore, protected model loading, encrypted cognitive storage adapters or online enrollment are implemented.

Selected direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit policy decision → optional scoped Authority request → controlled protected use`

This stage is deliberately narrower than the full Security & Licensing roadmap. It establishes immutable core models, exact ownership, verification/policy semantics, replay/expiry/revocation handling and privacy-safe observability without pretending that a Kotlin/JVM-only implementation can provide device-bound hardware security.

## Mandatory separation

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

No license model, receipt or verification result may silently become a general execution permission.

## Scope of License Core v0.1

The implementation stage may introduce core equivalents of:

- `LicenseId`;
- `LicenseSubject`;
- `LicenseProductId`;
- `LicenseFeature`;
- `LicenseVersion`;
- `LicenseKeyId`;
- `LicenseIssuedAt` / `notBefore` / `expiry`;
- explicit offline-lease state where needed by the chosen policy;
- revocation/key epoch;
- canonical signed entitlement payload/envelope representation;
- trusted verification-key lookup abstraction;
- signature verification result abstraction;
- exact-generation live license state ownership;
- `LicensePolicy`;
- `LicenseDecision`;
- structural `LicenseReceipt` or equivalent decision evidence;
- composition-owned store/registry and deterministic snapshots;
- privacy-safe Logging/Diagnostics.

Names may change during implementation if responsibilities remain explicit and the executable contracts preserve this boundary.

## Explicit non-goals

License Core v0.1 does **not** implement:

- Android Keystore or StrongBox;
- hardware-backed device binding;
- attestation/Play Integrity;
- enrollment server protocols;
- SQLite/SQLCipher;
- cognitive-store encryption;
- protected model decryption/streaming;
- online billing or purchase flows;
- Update System activation;
- background refresh;
- scheduler/retry/reconciliation;
- distributed license coordination;
- secret storage in source code;
- a universal anti-tamper guarantee.

Those remain later phases of the accepted Security & Licensing roadmap.

## Canonical entitlement evidence

License/lease evidence must be versioned and canonical enough that the verifier signs/verifies one unambiguous byte representation.

The canonical payload must preserve at least the information required by policy, such as:

- license identity;
- subject/product identity;
- entitled features/scopes;
- issued-at;
- not-before;
- expiry or explicit non-expiring policy;
- offline lease deadline where applicable;
- license/schema version;
- signing key ID;
- revocation/key epoch;
- device-binding reference only if a future enrollment adapter supplies one;
- replay/sequence value where the chosen protocol requires it.

The envelope must not be allowed to select its own trust root.

Forbidden verification behavior:

- `alg=none` or equivalent unsigned acceptance;
- algorithm confusion;
- arbitrary embedded verification key acceptance;
- unknown signing algorithm fallback;
- caller-supplied trust root bypass;
- treating parse success as signature success;
- treating signature success as entitlement or Authority success.

The architecture does not require JWT/JWS. If a standard envelope is later selected, its accepted profile must remain explicit and constrained.

## Trusted verification boundary

The verification path must conceptually remain:

`canonical envelope → trusted key-id lookup → allowed algorithm check → signature verification → structural validation → verified entitlement evidence`

A trusted key resolver supplies already-trusted verification material by exact key ID/epoch. The envelope itself cannot promote an untrusted key into the trusted set.

Verification failures must be typed/structural enough to distinguish categories such as:

- malformed/incompatible envelope;
- unknown key ID;
- unsupported algorithm;
- invalid signature;
- invalid canonical payload;
- unsupported schema/license version.

Verification failure performs no policy grant and no protected operation.

## Exact license-state ownership

A live accepted license record/state must use exact `(LicenseId, LicenseGeneration)` ownership semantics consistent with frozen foundation rules.

Required invariants:

- positive generation;
- duplicate live License ID rejection unless an explicit replacement operation is separately defined;
- stale ownership cannot remove a newer generation;
- one-shot exact removal;
- deterministic detached snapshots;
- composition isolation by default;
- no mutable registry exposure;
- generation values are labels/ownership evidence, not trust or permission.

If replacement/renewal is introduced in v0.1, it must be an explicit exact transition with executable stale-generation protection rather than an ID-only overwrite.

## Time semantics

Policy must evaluate time explicitly; wall-clock values are inputs, not hidden globals.

At minimum:

- `notBefore` is strict: evidence is unusable before its permitted start;
- expiry is strict according to one documented inclusive/exclusive rule;
- issued-at cannot by itself prove current validity;
- a local clock rollback cannot be claimed solved by wall-clock comparison alone;
- offline lease semantics must be explicit if implemented;
- suspicious rollback/replay state must fail closed according to policy rather than silently extending entitlement.

Core v0.1 may model trusted-time/replay evidence abstractly. It must not claim hardware-backed monotonic time before an adapter exists.

## Replay, version and revocation semantics

The policy boundary must be able to reject stale/replayed evidence where the selected entitlement profile supplies a sequence, version or revocation epoch.

Required rules:

- unknown/future unsupported schema or license version fails explicitly;
- older revocation/key epoch cannot silently supersede a newer accepted epoch;
- stale exact ownership cannot remove/replace newer state;
- decision receipts from an earlier evaluation are historical evidence only and are not reusable permission tokens;
- no hidden automatic refresh/retry is added when replay/revocation checks reject.

## License policy and decision

Policy consumes verified structural evidence plus explicit evaluation context and produces a typed decision.

Conceptual path:

`VerifiedEntitlement + requested product/feature/scope + explicit time/revocation/device evidence → LicenseDecision`

Default posture is fail closed.

A positive decision must require exact requested product/feature/scope agreement. Broad wildcard behavior is forbidden unless separately contracted and tested.

A negative decision must perform zero protected-use calls at the license gate.

Suggested rejection categories include:

- license missing;
- signature/verification invalid;
- not yet valid;
- expired;
- offline lease expired;
- product mismatch;
- feature not entitled;
- subject mismatch where required;
- device binding missing/mismatch where a future adapter supplies it;
- stale/replayed evidence;
- revocation detected;
- unsupported version/epoch;
- suspicious time/replay state.

Exact public names are implementation details; these responsibilities are not optional.

## Authority boundary

A License decision does not replace existing frozen Authority.

For protected operations that require both licensing and Authority, the dependency direction remains:

`fresh LicenseDecision.Entitled + exact protected capability/scope → fresh AuthorityRequest → AuthorityDecision → controlled use`

Hard rules:

- a valid license cannot grant arbitrary execution/device control;
- an Authority grant cannot turn invalid/unentitled license evidence into entitled state where licensing is mandatory;
- old license receipts do not bypass fresh Authority;
- old Authority receipts do not bypass fresh license policy where the protected operation requires a license check;
- the protected-use boundary must remain explicit about which checks are required.

License Core v0.1 may stop at producing the decision/receipt; protected asset/store adapters arrive later.

## Device-binding boundary

License Core must be ready to carry an opaque structural device-enrollment reference, but it must not invent a raw HWID scheme.

Forbidden as cryptographic trust anchors:

- IMEI;
- Android ID;
- serial number;
- advertising ID;
- arbitrary concatenated device properties;
- hashes of the above treated as secret device keys.

The future accepted direction remains cryptographic enrollment backed by Android Keystore/StrongBox/TEE policy.

Until that adapter exists, any device reference in Core is structural data only, not proof of hardware possession.

## Cognitive-data separation

Commercial entitlement and user cognitive-data recovery are separate security domains.

License Core v0.1 must not:

- derive a cognitive-store key directly from a license signature/token;
- make license expiry destroy or intentionally render legitimate Memory/Knowledge unrecoverable;
- delete user cognitive state because entitlement is denied;
- log cognitive plaintext as part of license diagnostics.

A future protected cognitive-store gate may deny protected use while preserving recovery/migration policy independently.

## Logging and diagnostics

All license verification/state/policy transitions use Foundation Logging/Diagnostics/CoreObservability rather than direct console output or hidden global logging.

Safe structural metadata may include:

- redacted/stable License ID reference;
- product/feature ID;
- license generation;
- schema/license version;
- signing key ID/epoch;
- decision/rejection code;
- issued/not-before/expiry timestamps when policy permits;
- operation correlation ID.

Never log by default:

- private/signing keys;
- DEKs or wrapping keys;
- raw bearer license tokens;
- full signed envelope bytes;
- arbitrary signature bytes;
- Memory/Knowledge plaintext;
- model plaintext;
- attestation/integrity tokens;
- exception messages that may embed secrets/raw envelopes.

Public failure rendering should expose structural category plus exception class where relevant, not secret-bearing exception messages.

Listener/observability failures remain isolated according to frozen Foundation behavior; license failures themselves must not be swallowed.

## Determinism and isolation

Core tests must be deterministic and must not depend on network, Android, system wall-clock globals or real private keys.

Use explicit clock/time inputs and test verification abstractions/fixtures.

Separate compositions must remain isolated unless a shared backing object is intentionally supplied.

No global mutable entitlement cache, global policy singleton or hidden trusted-key registry may be introduced.

## First implementation slices

The implementation should proceed through reviewed slices rather than a single broad security patch:

1. immutable License models + exact-generation store ownership contracts;
2. canonical entitlement/envelope representation + trusted verification abstraction and malformed/signature/version contracts;
3. LicensePolicy/LicenseDecision with explicit time, feature/product, replay/revocation semantics;
4. controlled License→Authority integration boundary where useful without introducing protected asset adapters;
5. readiness hardening for privacy, deterministic time, concurrency/isolation, stale ownership and observability;
6. freeze checkpoint.

Each slice requires exact-head Core CI GREEN, architecture/security/privacy/logging-diagnostics audit, exact-head merge and merge/main CI GREEN.

## Executable readiness gates

Before License Core v0.1 can freeze, executable contracts must prove at minimum:

1. invalid/malformed/incompatible entitlement evidence cannot produce an entitled decision;
2. unknown key ID/unsupported algorithm/invalid signature fail closed;
3. trusted key material cannot be selected by untrusted envelope content outside exact key-ID lookup;
4. exact License ID/generation ownership is stale/ABA-safe;
5. duplicate/live replacement semantics are explicit and deterministic;
6. not-before and expiry boundary behavior is exact;
7. explicit offline-lease behavior is exact if included;
8. requested product/feature mismatch denies;
9. stale replay/version/revocation evidence denies according to selected profile;
10. an entitled License decision is not itself an Authority grant;
11. denied License means zero protected-boundary calls in any integration gate added by this stage;
12. old decision/receipt evidence is not durable permission;
13. composition isolation and deterministic snapshots hold;
14. normal logs/diagnostics/failure rendering contain no bearer token, key, raw envelope or private cognitive/model content;
15. no direct console logging bypass exists in new production paths;
16. no Android/Keystore/hardware-security claim is made by core-only code;
17. cognitive data is not destroyed or made irrecoverable merely by license expiry/denial.

## Relationship to accepted roadmap

This is Phase A of the existing Security & Licensing roadmap.

After License Core v0.1 is frozen, the next security stages remain separately reviewed:

`Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline leases → Update System integration → red-team/readiness`

No later phase may weaken frozen exact ownership, Authority separation, privacy, observability, rollback/recovery or composition-isolation rules.
