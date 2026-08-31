# LiliyaCore — Canonical Project Handoff

Status: **PROJECT PAUSED FOR CHAT HANDOFF — DO NOT RESUME IMPLEMENTATION WITHOUT AN EXPLICIT USER REQUEST**.

Handoff date: 2026-08-31.

Primary repository: `yaroshenkopavel/LiliyaCore-`.

Default branch: `main`.

Legacy `Vikrot123/LiliyaCore` is backup/migration history only and must not be modified as part of current development.

## Source-of-truth order

For a new chat/session, use this order:

1. current GitHub `main` and current CI state;
2. production source and executable contract tests;
3. this `HANDOFF.md` and `CURRENT_STATE.md`;
4. subsystem contract/freeze documents;
5. `START_HERE.md`, `NUANCES.md`, `DECISIONS.md`, `ARCHITECTURE.md`, `STRUCTURE.md`;
6. chat history only as supplementary context.

If documentation conflicts with GitHub/source, verify GitHub/source first and repair the documentation before implementation continues.

## Exact pause checkpoint

The last implementation merge before this documentation-only handoff is:

`ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`

This is Runtime Hardening v0.1 Slice 1, PR #65.

Its merge/main CI run is:

`33427756131` — GREEN for both required jobs:

- `Test LiliyaCore` — success;
- `Android Keystore Instrumentation` — success.

The handoff documentation PR may advance `main` beyond this SHA. When work resumes, use the then-current `main`, but verify that changes after `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6` are documentation-only unless GitHub proves otherwise.

## Current phase

**Runtime Hardening v0.1 — ACTIVE BUT PAUSED AFTER SLICE 1.**

Canonical architecture contract:

`docs/development/RUNTIME_HARDENING_V0_1_CONTRACT.md`

Architecture PR #64:

- exact head: `bb53c7e18aa020f57a7b1d59b19b9899b72dd47c`;
- exact-head CI: `33424400826` — Core + Android GREEN;
- focused architecture/security/privacy/logging/readiness audit: CLEAN;
- merge: `4a64b753d37d7bad53b49096c52e193102fb87ba`;
- merge/main CI: `33425537207` — Core + Android GREEN.

Runtime Hardening direction:

`approved protected-model target → exact runtime session ownership → bounded model activation → supervised use → explicit fault classification → fail-closed isolation/retirement → controlled replacement/recovery`

Mandatory separations:

`Runtime Session != Protected Model Package != Model DEK != License != Capability != Authority != Execution`

`Loaded model != authorized action`

`Healthy runtime != valid License entitlement`

`Recovered process state != replay permission`

`Crash recovery != retry authorization`

`Runtime ownership != durable permission`

`Model activation != autonomous execution`

## Runtime Hardening Slice 1 — completed

PR #65 added Core session/reference/configuration/failure structural models and exact process-local ownership.

Exact head:

`f2567296189142b7f51b76006728457121eee6ad`

Exact-head CI:

`33426885570` — Core + Android GREEN.

Focused pre-merge audit of both changed files: CLEAN.

Merge:

`ca7b43c971eccd473d64617ef2f6c8e25a93b2b6`

Merge/main CI:

`33427756131` — Core + Android GREEN.

Slice 1 production surface:

`core/src/main/kotlin/pro/liliya/core/runtime/hardening/RuntimeHardeningSessionModels.kt`

Slice 1 contracts:

`core/src/test/kotlin/pro/liliya/core/runtime/hardening/RuntimeHardeningSessionModelsContractTest.kt`

Implemented guarantees include:

- redacted `RuntimeModelSessionId`;
- positive, monotonic `RuntimeModelSessionGeneration`;
- exact `RuntimeModelSessionReference` bound to one `ProtectedModelReference`;
- v0.1 exactly one live runtime session per owning registry/composition;
- duplicate-live registration fails closed;
- generation overflow fails closed;
- exact-entry stale/ABA-safe ownership and retirement;
- deterministic current-session snapshot;
- explicit positive resource-limit configuration;
- ownership remains structural state, not License, Authority, capability or execution permission.

Slice 1 intentionally does **not** implement activation/publication, operation supervision, retry/replay or recovery.

## Next allowed implementation after explicit resume

**Runtime Hardening Slice 2 — Activation and Publication Barrier.**

Do not start it while the project is paused.

When the user explicitly resumes work:

1. verify current `main` SHA and latest required CI;
2. verify no code changes occurred after implementation checkpoint `ca7b43c971eccd473d64617ef2f6c8e25a93b2b6` except any explicitly reviewed later work;
3. read `RUNTIME_HARDENING_V0_1_CONTRACT.md`, this file, `CURRENT_STATE.md`, Slice 1 source/tests, and the frozen Protected Model contract/freeze;
4. create a fresh Slice 2 feature branch from current verified `main`;
5. implement only activation/publication scope;
6. require exact-head Core + Android GREEN;
7. perform focused architecture/security/privacy/logging/readiness audit of the exact PR head;
8. merge using the verified expected head SHA;
9. require merge/main Core + Android GREEN before Slice 3.

Slice 2 must consume an already-approved protected-model output. It must not bypass or duplicate protected-model authenticity verification, fresh policy evaluation or authenticated decryption. Activation must bind one exact model reference to one exact runtime-session generation and publish only behind an exact ownership barrier with stale and reentrant mutation protection.

## Remaining Runtime Hardening plan

- Slice 2 — activation and publication barrier;
- Slice 3 — operation supervision and resource bounds;
- Slice 4 — failure containment, replacement and recovery readiness;
- Slice 5 — platform/runtime integration evidence only if actually required; otherwise an explicit no-op evidence checkpoint;
- Slice 6 — formal freeze checkpoint.

Runtime Hardening v0.1 is **not FROZEN** yet.

## Frozen security/storage baselines

The following must be treated as frozen dependencies rather than casually redesigned:

- Persistent Cognitive Storage v0.1;
- Memory Persistence Integration v0.1;
- Knowledge Persistence Integration v0.1;
- Learning Persistence Integration v0.1;
- License Core v0.1;
- Android Device Key v0.1;
- Cognitive Storage Encryption v0.1;
- Protected Model Package / Loader v0.1.

### Android Device Key hard limit

Frozen Device Key v0.1 exposes only `SIGN_CHALLENGE`.

Do not reintroduce DEK unwrap/decrypt/wrap into the Device Key surface. Protected-model and cognitive-storage key protectors remain separate purpose-specific key domains.

### Cognitive Storage Encryption

Formally frozen through merge `a310749c02ef6792836601b18cfdb5949ee90eb0` and its verified merge/main gate.

Direction:

`persistent cognitive payload → explicit encryption profile → exact DEK identity/generation → authenticated ciphertext envelope → exact wrapped-DEK binding → purpose-specific key-protector boundary → bounded plaintext consumer`

Crypto profile: AES-256-GCM, 96-bit nonce, 128-bit authentication tag.

### Protected Model Package / Loader

Formally frozen through PR #62 merge:

`aeccc0713ad1466a9ed371ff028e48406ed945e4`

Merge/main run:

`33416794458` — Core + Android GREEN.

Canonical docs:

- `PROTECTED_MODEL_PACKAGE_V0_1_CONTRACT.md`;
- `PROTECTED_MODEL_PACKAGE_V0_1_FREEZE.md`.

Direction:

`protected model package → canonical manifest validation → integrity/authenticity verification → fresh entitlement/policy decision → exact key-resolution boundary → authenticated model decryption/open → bounded loader handoff → runtime model consumer`

Do not collapse Model Package, signature, encryption, Model DEK, Key Protector, Device Key, License, Capability, Authority or Execution into one permission concept.

## Strict workflow — mandatory

`feature branch → minimal coherent commits → PR → exact-head Core/required platform CI GREEN → focused architecture/security/privacy/logging-diagnostics/readiness audit → merge with verified expected head → merge/main CI GREEN → journal/freeze checkpoint`

Never claim an audit was performed unless the exact changed-file set and relevant patches/source were actually inspected.

Never merge merely because CI is GREEN; required focused audit is a separate gate.

Do not start the next implementation slice until the previous merge/main required CI is GREEN.

For architecture/security work, fail closed on uncertain ownership, stale state, authentication or cleanup.

No hidden retry, replay, reconciliation or exactly-once semantics may be claimed unless explicitly implemented and tested.

## Privacy / observability invariants

Foundation Logging/Diagnostics/CoreObservability remains the approved path.

Do not introduce direct production `println`, `print`, `System.out`, `System.err` or `printStackTrace` bypasses.

Normal diagnostics must not expose model/cognitive plaintext, raw DEK material, wrapped-key bytes, protected payloads, private proof material or secret-bearing exception messages.

Foundation is not a universal throwable-message sanitizer; do not blindly forward secret-bearing throwables into observability.

## Historical audit caveat

Protected Model Slice 1 PR #56 was already merged before retained process evidence proved a focused pre-merge audit. Before Protected Model freeze, a corrective **post-merge** audit re-listed both exact changed files, reread both complete patches and found no freeze blocker. `PROTECTED_MODEL_PACKAGE_V0_1_FREEZE.md` records this accurately and does not misrepresent it as a pre-merge audit.

Older historical phases may have weaker retained audit evidence than the current workflow. Do not manufacture missing evidence; where historical proof matters, verify repository/PR history and state uncertainty explicitly.

## Roadmap after Runtime Hardening freeze

`licensing service/offline lease issuance+refresh → Update System integration → red-team/readiness`

Do not jump ahead while Runtime Hardening remains unfrozen.

## Handoff instruction to the next chat

The project is intentionally paused. First read this file and verify GitHub state. Do not mutate code until the user explicitly says to resume development. Once resumed, continue from Runtime Hardening Slice 2 under the strict gates above, without reopening frozen baselines unless a concrete blocker proves that a new version/contract is required.
