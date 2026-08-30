# START HERE — LiliyaCore Session Handoff

## Active project

Repository: `yaroshenkopavel/LiliyaCore-`

Default branch: `main`

Current project type: core-only Kotlin/JVM foundation. Android/device adapters are not part of current `main`.

## Source of truth

Before changing code, read:

1. `CURRENT_STATE.md`;
2. `ARCHITECTURE.md`;
3. `STRUCTURE.md`;
4. `NUANCES.md`;
5. the canonical contract/freeze document for the touched subsystem;
6. production source and executable contracts;
7. current GitHub PR/CI state.

## Hard engineering rules

- work on feature branches;
- merge only after exact-head Core CI GREEN;
- verify merge/main CI after architectural slices;
- exact `(ID, generation)` ownership beats ID-only ownership;
- stale/ABA ownership must never delete a replacement generation;
- capability is not permission; Authority is separate from Execution;
- structural provenance strings are evidence, not credentials/capabilities/Authority receipts;
- private cognitive payloads and cryptographic/license secrets stay out of operational observability;
- logging and diagnostics remain Foundation infrastructure and must not be bypassed by direct console output;
- persistence, encryption, licensing, device enrollment, Authority and cognitive permission remain separate;
- frozen baselines are not casually redesigned.

## Current verified baseline

Verified `main`:

`e5b3113d9342056e3167f9337ca87860fed85171`

Latest Learning Persistence freeze exact-head Core CI: `33323689991` GREEN.

Latest Learning Persistence freeze merge/main Core CI: `33323803034` GREEN.

## Frozen persistence baselines

Persistent Cognitive Storage v0.1 is fully frozen.

Memory Persistence Integration v0.1 is fully frozen.

Knowledge Persistence Integration v0.1 is fully frozen.

Learning Persistence Integration v0.1 is fully frozen.

Canonical documents:

- `PERSISTENT_COGNITIVE_STORAGE_V0_1_CONTRACT.md`
- `PERSISTENT_COGNITIVE_STORAGE_V0_1_FREEZE.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `MEMORY_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `KNOWLEDGE_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`
- `LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`

## Frozen Learning persistence boundary

Direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Durable prepare:

`validate plan → encode prepared → durable commit → exact committed local install → Prepared`

Durable removal:

`validate exact unclaimed ownership → durable exact-generation remove → exact local remove → success`

Durable completion:

`validate exact active claim + exact receipt → one durable exact prepared→completed transition → exact local completion/index publication → success`

The integration preserves exact mutation IDs/generations, persistent high-watermark, full Memory/Knowledge payload fidelity, exact idempotency/completed indexes, deterministic live ordering, stale/ABA-safe ownership, fail-closed reopen, zero claim resurrection, active-claim removal blocking, same-composition serialization and explicit shared-backend CAS conflict semantics.

The generic persistence layer contains one narrow internal exact transition primitive used by Learning completion. It is not a scheduler, retry engine, distributed transaction system or Authority mechanism.

## Critical retained Learning limitation

Learning persistence does not create a transaction spanning Learning plus Memory/Knowledge.

The controlled application path still performs:

`downstream Memory/Knowledge mutation → durable Learning completion`

A crash/failure may occur between those boundaries. There is no exactly-once downstream mutation guarantee, automatic replay, implicit retry, compensation or reconciliation.

## Logging and diagnostics boundary

All new production paths remain inside Foundation Logging/Diagnostics/CoreObservability.

Safe operational metadata is structural only: IDs, generations, schema/version, key/epoch identifiers, decision/rejection categories, timestamps and approved target references.

Do not log raw bearer license tokens, signed-envelope bytes, private/signing keys, DEKs/wrapping keys, Memory/Knowledge plaintext, model plaintext, full attestation tokens or secret-bearing exception messages.

Do not add `println`, `System.out`, direct payload dumps or alternative hidden logging paths.

## Current active stage — License Core v0.1

This is Phase A of `SECURITY_LICENSING_V0_1_CONTRACT.md` and remains core-only.

Canonical focused contract:

`LICENSE_CORE_V0_1_CONTRACT.md`

Selected direction:

`signed/canonical entitlement evidence → trusted verification boundary → exact license state ownership → explicit policy decision → optional scoped Authority request → controlled protected use`

Mandatory separation:

`License != Signature != Device Enrollment != Key Access != Capability != Authority != Execution`

`Valid signature != entitlement decision != Authority grant`

`License evidence != durable permission`

`License expiry != cognitive-data destruction`

## First License Core implementation boundary

Proceed through narrow reviewed slices:

1. immutable license models and exact-generation ownership/store contracts;
2. canonical entitlement/envelope representation plus trusted verification abstraction;
3. explicit LicensePolicy/LicenseDecision with time, product/feature, replay and revocation semantics;
4. controlled License→Authority composition boundary where useful;
5. readiness hardening for privacy, deterministic time, stale ownership, isolation/concurrency and observability;
6. freeze checkpoint.

Do not jump directly to Android or encrypted storage before the Core semantics are executable and frozen.

## License Core hard rules

- default deny;
- explicit time input, no hidden system-clock dependency in core contracts;
- strict not-before/expiry semantics;
- unknown key/version/algorithm fails closed;
- the envelope cannot select its own trust root;
- no `alg=none`/algorithm confusion/fallback trust;
- exact License ID/generation ownership is stale/ABA-safe;
- old License decisions/receipts are historical evidence only;
- a positive License decision is not an Authority grant;
- denied licensing means zero protected-boundary calls where a gate is introduced;
- raw HWID/IMEI/Android ID/serial hashes are not cryptographic device binding;
- license expiry/denial must not intentionally destroy or make legitimate cognitive data irrecoverable;
- security transitions are observable structurally without exposing secrets.

## Explicit non-goals for License Core v0.1

Keep outside this stage:

- Android Keystore/StrongBox;
- hardware-backed device binding;
- attestation/Play Integrity;
- SQLite/SQLCipher;
- cognitive-store encryption;
- protected model package/decryption/streaming loader;
- online enrollment/billing service;
- background license refresh;
- scheduler/retry/reconciliation;
- Update System activation;
- universal anti-tamper/anti-dump claims.

## Accepted later security roadmap

After License Core v0.1 freezes:

`Android device-key boundary → cognitive storage encryption → protected model package/loader → runtime hardening → licensing service/offline leases → Update System integration → red-team/readiness`

Each later phase requires a separate reviewed contract and executable proof.

## Resume procedure

1. verify current `main` SHA and latest merge/main Core CI;
2. read `SECURITY_LICENSING_V0_1_CONTRACT.md` and `LICENSE_CORE_V0_1_CONTRACT.md`;
3. inspect frozen Authority/Trust/Security/Foundation observability patterns before adding License production code;
4. implement immutable License models plus exact-generation composition-owned store first;
5. prove stale/ABA ownership, duplicate behavior, deterministic detached snapshots, composition isolation and privacy-safe observability;
6. only then add canonical verification and policy slices;
7. merge each slice only after exact-head Core CI GREEN plus architecture/security/privacy/logging-diagnostics audit;
8. never infer Android hardware security or protected-store encryption from core-only License models.
