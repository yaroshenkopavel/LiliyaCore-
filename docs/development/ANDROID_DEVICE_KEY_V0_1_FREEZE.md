# Android Device Key v0.1 — Freeze Checkpoint

Status: **FROZEN pending this checkpoint PR merge/main gate**.

## Frozen direction

`Android Keystore key identity → verified security-level evidence → controlled device-key operation → structural enrollment/binding evidence → later DEK wrapping/unwrap consumers`

Mandatory separations:

`Device Key != Device Identity != Enrollment != License != DEK != Capability != Authority != Execution`

`Keystore presence != hardware-backed proof`

`Hardware-backed proof != enrollment`

`Enrollment evidence != License entitlement`

`License entitlement != key unwrap permission != Authority`

## Frozen v0.1 guarantees

- exact local ownership is `(DeviceKeyId, DeviceKeyGeneration)`;
- duplicate publication rejects; stale ownership cannot remove or authorize a replacement generation;
- generation allocation fails closed on overflow;
- `DeviceKeyId` and `DeviceKeyPlatformReference` render redacted;
- capability/profile/state collections are detached from mutable caller input;
- `UNKNOWN` security level cannot become ready state or hardware-backed evidence;
- StrongBox requests do not silently downgrade in the same operation; a lower-security request must be a new explicit request;
- actual platform security level is inspected and policy-checked before publication;
- logical key IDs are not used directly as Android Keystore aliases;
- Android aliases are derived from a SHA-256 digest of the logical ID;
- `DeviceKeyPlatformReference` is derived from public-key material and provides exact platform-instance/ABA evidence;
- creation uses generate → metadata commit → exact inspect/publication semantics with exact cleanup on post-generation failure;
- cleanup failure is explicit as `CLEANUP_FAILED`;
- malformed alias/metadata combinations fail closed as `MALFORMED_METADATA`;
- private key material never leaves the Android Keystore boundary;
- proof signing requires exact live local generation, exact platform instance and `SIGN_CHALLENGE` capability;
- the Android signer re-derives the platform reference from the actual entry immediately before signing, rejecting stale/replaced platform state;
- proof publication rechecks exact local ownership after signing;
- challenge/signature/enrollment/platform references do not render raw material;
- enrollment evidence is structural evidence only and is not License, Capability, Authority or permission;
- invalidation/authentication/missing/stale/malformed/cleanup outcomes remain typed and fail closed;
- reviewed production paths do not use direct console output or forward secret-bearing exception messages into normal observability;
- Core remains Android-framework-free; Android-specific implementation lives in the separate `:android-device-key` module.

## Concrete Android runtime evidence

PR #44 added `AndroidSystemKeystorePlatform` and the Android instrumentation CI gate.

Verified PR #44 exact-head gate:

- run `33360156420` — `Test LiliyaCore` GREEN;
- run `33360156420` — `Android Keystore Instrumentation` GREEN.

PR #44 merge commit:

`cdc2e279f5b3bf56f7c14a0f3cc00f82ff7aac09`

Verified PR #44 merge/main gate:

- run `33362045123` — `Test LiliyaCore` GREEN;
- run `33362045123` — `Android Keystore Instrumentation` GREEN.

The instrumentation exercises real Android Keystore behavior on an API 35 x86_64 emulator, including create → resolve/inspect → sign → retire → missing-key behavior and alias-reuse/ABA stale-state rejection.

## Final readiness cleanup

PR #45 removed the pre-freeze `UNWRAP_WRAPPED_KEY` capability because v0.1 intentionally exposes no DEK unwrap operation. DEK unwrap remains a later cognitive-storage-encryption contract and must not be inferred from Device Key possession or enrollment evidence.

Verified PR #45 exact-head gate:

- exact head `88c7789ba3acb689fba5083eb6586ddabf51fc33`;
- run `33363112163` — `Test LiliyaCore` GREEN;
- run `33363112163` — `Android Keystore Instrumentation` GREEN.

PR #45 merge commit:

`91c43d83cd2077bdb3c14d6bb7cbdb20c7c4cd27`

Verified PR #45 merge/main gate:

- run `33363644453` — `Test LiliyaCore` GREEN;
- run `33363644453` — `Android Keystore Instrumentation` GREEN.

## Explicit limitations

The emulator gate proves concrete Android Keystore runtime integration and lifecycle semantics for the tested software-backed emulator environment. It does **not** prove that an arbitrary user device has StrongBox or TEE hardware backing.

Hardware-backed claims remain conditional on runtime-observed `KeyInfo` evidence from the actual device. API 31+ maps the observed Android security level directly. API 29–30 cannot distinguish StrongBox from TEE through the same `KeyInfo` surface, so secure-hardware evidence is conservatively under-reported as `TRUSTED_ENVIRONMENT` rather than over-claimed.

No Play Integrity trust root, generic attestation service, license issuance/refresh, DEK unwrap, cognitive storage encryption, model encryption, Authority grant or execution permission is part of Device Key v0.1.

## Freeze boundary

Once this freeze checkpoint PR itself passes exact-head Core + Android instrumentation, merges, and its merge/main Core + Android instrumentation gate is GREEN, Android Device Key v0.1 is **FROZEN**.

The next roadmap phase is cognitive storage encryption. Any wrapped-DEK API must be introduced there through a separately reviewed exact binding/policy/Authority contract and may not weaken this frozen Device Key boundary.
