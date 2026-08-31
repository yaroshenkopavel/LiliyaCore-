# Protected Model Package / Loader v0.1 — Freeze Checkpoint

Status: **FROZEN pending this checkpoint gate**.

## Frozen direction

`protected model package → canonical manifest validation → integrity/authenticity verification → fresh entitlement/policy decision → exact key-resolution boundary → authenticated model decryption/open → bounded loader handoff → runtime model consumer`

Mandatory separations:

`Model Package != Model Payload != Package Signature != Encryption != Model DEK != Key Protector != Device Key != Enrollment != License != Capability != Authority != Execution`

`Valid signature != License entitlement`

`License approval != key possession`

`Successful unwrap/decrypt != Authority grant`

`Authority != execution`

`Device Key != protected-model decryption permission`

`License expiry != protected-model destruction`

## Frozen v0.1 guarantees

- the protected-model manifest and package envelope use explicit versioned structural types and defensive copies for mutable cryptographic byte inputs;
- protected-model identifiers, DEK identifiers, signer identifiers and cryptographic bytes render redacted on reviewed normal diagnostic surfaces;
- package ownership is exact and process-local with monotonic generations, duplicate live registration rejection, generation-overflow fail-closed behavior and stale-owner retirement protection;
- v0.1 authenticity uses canonical manifest encoding plus detached payload digest, nonce and authentication tag under Ed25519 verification;
- signature verification covers the exact canonical manifest, payload digest, nonce and authentication tag, so substitution of any signed envelope component fails closed;
- v0.1 payload encryption is fixed to AES-256-GCM with a 96-bit nonce and 128-bit authentication tag;
- the loader enforces structural plaintext/ciphertext bounds before key resolution and decryption, including the current v0.1 direct-payload invariant that ciphertext bytes excluding the tag equal plaintext bytes;
- DEK resolution is purpose-specific for one exact `(ProtectedModelReference, ModelDekReference)` and is not a generic decrypt/unwrap API;
- the Core loader rejects unavailable, non-AES and non-256-bit exportable DEK material before decryption and clears reviewed transient encoded-key copies best-effort;
- package authenticity/integrity is verified before exact model-DEK resolution;
- authenticated decryption binds the exact canonical manifest as AAD and releases plaintext only after AEAD verification and exact plaintext-size validation;
- plaintext handoff is synchronous and bounded; reviewed plaintext, nonce, tag, AAD, combined cipher input and temporary key buffers are cleared best-effort after use;
- consumer/provider failures are typed and reviewed failure rendering exposes exception class rather than secret exception message;
- the dedicated protected-model key-protector boundary is distinct from cognitive-storage encryption and from the frozen Device Key v0.1 surface;
- Device Key v0.1 remains unchanged and signing-only; protected-model key protection does not add unwrap/decrypt capability to Device Key;
- Android protected-model key protection uses a dedicated Android Keystore AES-256-GCM key domain with opaque exact platform-instance references;
- StrongBox requests do not silently downgrade in the same operation; requested versus observed security level remains explicit;
- Android wrapped model-DEK material is authenticated against exact DEK id/generation, exact protector id/generation/platform reference, purpose and algorithm through canonical purpose-specific AAD;
- Android create/inspect/wrap/unwrap/retire paths preserve typed missing, stale, invalidated, security-level, cleanup and provider failures and fail closed on post-create cleanup uncertainty;
- real Android instrumentation covers protected-model DEK wrap/unwrap/retire and replacement-generation ABA behavior in the tested emulator environment;
- fresh access policy is a higher-layer seam evaluated for every open attempt before key resolution; crypto primitives remain License-free and Authority-free;
- policy approval is not key possession, Authority or execution permission;
- exact runtime target ownership uses generation-bound tickets and stale-worker checks before protected-model publication;
- final publication and ownership replacement/retirement share one atomic ownership barrier, so a competing thread cannot replace the target between the final stale check and runtime publication;
- same-thread reentrant replacement, retirement and nested publication are rejected while publication is in progress, preventing monitor reentrancy from bypassing stale ownership;
- rotation requires explicit exact target replacement; retired or missing targets fail closed without implicit recovery;
- no hidden retry, replay, reconciliation, rollback or exactly-once claim is introduced by v0.1;
- Core remains Android-framework-free; concrete Android Keystore integration remains in the separate `:android-device-key` module.

## Slice evidence

Architecture contract PR #55 established the v0.1 boundary. Exact head `d67f10462937fd25f9fedf6355b2a41bdf7cf963` passed run `33381844766` GREEN for Core and Android instrumentation. PR #55 merged as `bb9ecfff242f9ac14943bcacfc4ebe48d5756f6b`; merge/main run `33382766090` was GREEN for both required jobs.

Slice 1 PR #56 added Core package/manifest/encryption models and exact package ownership. Exact head `4bccc22308b233917d8b1c52bbea60ccad215118` passed run `33384941933` GREEN. PR #56 merged as `2a942d15083bd4467afbeaa0ee5461796378af3f`; merge/main run `33394762925` was GREEN.

A corrective focused audit of PR #56 was performed before this freeze because the original retained workflow record did not prove a pre-merge audit. The audit re-listed the exact two changed files and re-read both complete PR patches. It confirmed exact/ABA-safe ownership, fail-closed generation overflow, defensive copies, fixed AES-256-GCM profile, structural rejection reasons and redacted rendering. No freeze blocker was found. This corrective audit is explicitly post-merge evidence and is not represented as an original pre-merge audit.

Slice 2 PR #58 added canonical manifest encoding plus signature/integrity verification. The implementation was hardened before merge so the signature input includes canonical manifest, payload digest, nonce and authentication tag, and provider digest failure remains typed. Final exact head `2d62ece5cf51f0fb4d4a824c5b440f7125f4ae50` passed run `33400198384` GREEN for Core and Android instrumentation. PR #58 merged as `6799d63f8b6e8a9e2b4759410697b96c7f740737`; merge/main run `33401172037` was GREEN for both required jobs.

Slice 3 PR #59 added purpose-specific payload crypto and bounded loader handoff. Audit hardening added strict AES-256 key validation, early ciphertext bounds, overflow protection and buffer clearing. Final exact head `ce7cde8d190fa53ffc4e61d09c5f638374bf093c` passed run `33404822687` GREEN for Core and Android instrumentation. PR #59 merged as `420378b9409ca5af1aaf331aefbd82b028962420`; merge/main run `33405758818` was GREEN for both required jobs.

Slice 4 PR #60 added the dedicated Android protected-model key protector and real Android Keystore instrumentation. Focused audit found and fixed post-create cleanup uncertainty plus typed invalidation handling before merge. Final exact head `40cfc55ba09e04409bb3540d320fe4805d4540f9` passed run `33408014356` GREEN for Core and Android instrumentation. PR #60 merged as `535b4059883009015033e429d29a973058ebb0e7`; merge/main run `33408875474` was GREEN for both required jobs.

Slice 5 PR #61 added fresh access-policy composition, explicit rotation/recovery behavior, stale-worker publication protection, concurrency readiness and privacy-safe failure rendering. Focused audit identified and fixed both the cross-thread stale-check/publication race and same-thread monitor-reentrancy bypass before merge, with deterministic concurrency contracts added. Final exact head `040aca726ec0f235020ea997144c57d0c630465d` passed run `33414111023` GREEN for Core and Android instrumentation. PR #61 merged as `e55b7c56d53e791f8de207f24e23027d2a0331f2`; merge/main run `33414965173` was GREEN for both required jobs.

## Readiness audit

The reviewed v0.1 surface preserves the architecture contract across package ownership, canonical verification, payload crypto, Android key protection and policy/runtime publication:

- no generic crypto executor was introduced;
- no Device Key unwrap/decrypt capability was introduced;
- no License or Authority dependency was added inside manifest verification, payload crypto or Android key-protector primitives;
- fresh policy is evaluated at the higher-layer access coordinator before key resolution;
- no Android framework dependency was introduced into Core;
- model-DEK and key-protector domains remain separate from cognitive-storage DEKs/protectors;
- exact model/package/DEK/protector generation ownership remains explicit and stale/ABA state fails closed;
- signed-envelope substitution resistance covers canonical manifest, payload digest, nonce and authentication tag;
- payload decryption authenticates canonical manifest AAD before bounded plaintext handoff;
- runtime publication is protected against stale workers, cross-thread replacement races and same-thread reentrant mutation;
- reviewed rendering is structural/redacted and does not intentionally emit model plaintext, raw DEK material or secret exception messages;
- no hidden retry, replay, reconciliation, rollback or durable permission token is created by this phase.

## Explicit limitations

Protected Model Package / Loader v0.1 does not claim cloud model distribution, package download/update transport, cross-device key recovery, key escrow, backup restoration, streaming/chunked model decryption, memory-mapped encrypted GGUF loading, zero-copy secure memory, anti-debugging, rollback-resistant hardware counters, License issuance/refresh, Authority grant or model execution permission.

The current Core payload loader accepts transient exportable AES-256 DEK material at its resolver boundary. The Android protector keeps its long-lived wrapping key non-exportable in Android Keystore, but a higher-layer adapter that unwraps a model DEK for the Core loader will necessarily handle transient raw model-DEK material in process memory. v0.1 relies on bounded lifetime and best-effort clearing; it does not claim protection from privileged process inspection, memory forensics or a compromised OS/runtime.

The Android emulator gate proves concrete Android Keystore behavior only in the tested API 35 x86_64 emulator environment. It does not prove StrongBox or TEE availability on an arbitrary device; hardware-backed claims remain conditional on runtime-observed security-level evidence.

The v0.1 direct-payload size invariant assumes uncompressed, unsegmented AES-GCM payload bytes where ciphertext excluding the authentication tag has the same length as plaintext. Compression, segmentation or a different package layout requires a new explicit version/profile rather than silent reinterpretation.

Fresh policy is an abstract higher-layer seam in this phase. Concrete License/offline-lease issuance, refresh and service integration remain a later roadmap phase.

## Freeze checkpoint gate

This document is the Slice 6 formal freeze checkpoint. The phase becomes formally **FROZEN** only when this exact checkpoint head passes both required CI jobs, the PR merges using the verified expected head, and the resulting merge/main commit also passes both required jobs.

After that gate closes, the next roadmap phase is runtime hardening. Runtime hardening must consume this frozen protected-model boundary without weakening the separations, exact ownership, authentication, policy and publication guarantees above.
