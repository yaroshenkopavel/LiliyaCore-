# Learning Persistence Integration v0.1 — Freeze Contract

Status: **FROZEN pending documentation-checkpoint merge**

Verified code baseline: `b04bbd6020ff9c9807e7db4f378d969534cee362`.

Verified implementation slices:

- PR #23 — Architecture Contract, exact head `f51a9474352a596028e193605ec9c1ecae636388`, merge `0e8176d65eca1592fcd53434773f642db637f3bb`, exact-head CI `33321153413` GREEN, merge/main CI `33321283053` GREEN;
- PR #24 — Codec and Restoration Boundary, exact head `c76ceb4a5fea2ca727bdb8856ab07dbbe74c0538`, merge `db1af23965a747d6993711e07de52f0c20469d0a`, exact-head CI `33321940324` GREEN, merge/main CI `33322073125` GREEN;
- PR #25 — Durable Prepare Remove and Reopen, exact head `d61108fe5b6600ad3e7a1089d9147d98ad047546`, merge `c6404c5056370e26a07abc7c94e0e32eb794e147`, exact-head CI `33322377913` GREEN, merge/main CI `33322531446` GREEN;
- PR #26 — Atomic Durable Completion, exact head `1d1ad10f3107b348c834964d5aa3e2b279b8833a`, merge `c89c383611a8f26194361eec6592e96506cd7760`, exact-head CI `33322846949` GREEN, merge/main CI `33322990978` GREEN;
- PR #27 — Readiness Hardening, exact head `380aa9e2c7315ec07188061fdc4372cfa8640f26`, merge `b04bbd6020ff9c9807e7db4f378d969534cee362`, exact-head CI `33323246383` GREEN, merge/main CI `33323408553` GREEN.

Canonical architecture contract: `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`.

## Frozen integration boundary

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Durable prepare ordering:

`validate plan → encode prepared record → durable install/commit → exact committed local install → Prepared`

Durable removal ordering:

`validate exact unclaimed ownership → durable exact-generation prepared remove → exact local remove → success`

Durable completion ordering:

`validate exact active claim + exact receipt → one durable exact prepared→completed transition → exact local completion/index publication → success`

## Frozen guarantees

- exact `LearningApplicationMutationId` identity survives codec/reopen;
- exact positive `LearningApplicationMutationGeneration` ownership is preserved;
- persistent generation high-watermark survives removal, completion and reopen and remains monotonic;
- prepared and completed records use distinct canonical schema/entity namespaces;
- Memory and Knowledge mutation payloads round-trip exactly, including nested structural provenance/origin, private content and caller timestamps;
- completed receipts round-trip exactly for both Memory and Knowledge downstream reference types;
- completed outcomes are restored consistently by mutation ID and idempotency key;
- equal completed-plan preparation returns the exact recorded `AlreadyCompleted(receipt)` result;
- conflicting completed mutation-ID or idempotency-key reuse remains rejected;
- duplicate live IDs, duplicate live generations, duplicate live idempotency keys, live/completed ID overlap and live/completed idempotency overlap fail closed;
- malformed/trailing payloads, wrong schema/version, entity-ID mismatch, timestamp mismatch, invalid target/payload/downstream discriminators and impossible receipt state fail closed;
- completed receipt generation must exactly equal the persistent entry generation on reopen;
- reopen is atomic at the composition boundary: corrupt, incompatible or restoration-invalid state never publishes a partially hydrated composition;
- active claim tokens are never persisted or resurrected; reopened prepared mutations start unclaimed;
- one active process-local claim per exact live mutation remains enforced;
- an actively claimed persisted mutation is non-removable before any durable remove is attempted;
- invalid completion does not silently release a claim; release remains explicit;
- successful `Prepared` is never returned before durable commit acknowledgement;
- failed/conflicting durable prepare leaves local mutation/idempotency state absent;
- failed/conflicting durable remove keeps local prepared state/idempotency reservation live;
- stale/ABA ownership cannot remove a newer replacement generation;
- durable completion is one backend CAS revision that replaces the exact prepared record with the completed record while preserving generation/high-watermark;
- completed local indexes are not published before durable completion acknowledgement;
- failed/conflicting durable completion leaves local prepared state uncompleted and completed indexes absent;
- same-composition durable mutation pipelines are serialized;
- independently opened compositions sharing one backend expose optimistic-CAS conflict rather than hidden retry/refresh/reconciliation;
- deterministic live snapshots retain `createdAt`, then mutation-ID ordering after reopen;
- normal decode/failure rendering excludes private Memory/Knowledge content, raw payload bytes and backend exception messages;
- structural logging/diagnostic metadata remains limited to approved IDs, generations, target, idempotency key, schema/version, timestamps and payload structural IDs.

## Atomic persistent transition rule

The generic persistence layer gained one narrowly scoped internal primitive for domain-owned state transitions:

`exact live source entity + exact generation → one backend CAS commit → replacement entity with the same generation/high-watermark`

It exists to avoid a crash-visible gap between removing prepared Learning state and installing completed Learning state. It does not add generic retry, refresh, merge, distributed locking, transactions, scheduler ownership or reconciliation semantics.

The primitive remains internal and must not be treated as a general authorization or workflow mechanism.

## Claim and authority rule

Claim acquisition/release remains process-local concurrency control only. Claim tokens, leases, workers and scheduler ownership are not persisted.

A reopened prepared mutation is not pre-authorized. Persisted principal/application/decision/policy references are structural recorded state only. Fresh controlled authorization remains mandatory before real downstream Memory/Knowledge mutation.

A completed Learning receipt is historical structural evidence, not a credential, capability, permission token or Authority receipt.

Mandatory separation remains:

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

## Downstream crash-window rule

This freeze does **not** create a transaction spanning Learning plus Memory/Knowledge.

The existing controlled application path performs downstream Memory/Knowledge mutation before Learning completion is durably recorded. Therefore a crash or persistence failure can still occur after downstream state changed but before durable Learning completion.

Frozen interpretation:

- durable completed history prevents forgetting a completion that was successfully recorded;
- it does not prove the downstream side effect happened exactly once;
- it does not eliminate the downstream-commit → Learning-completion crash window;
- it does not authorize automatic replay after reopen;
- it does not add hidden retry, compensation or reconciliation;
- future exactly-once/cross-domain crash atomicity requires a separate reviewed architecture such as transaction/outbox/idempotent-downstream coordination with executable proof.

## Failure and compensation rule

Durable success is never inferred from local state. Durable acknowledgement precedes local publication for prepare and completion; durable exact-generation removal precedes local removal.

If durable prepare commits but exact local committed installation unexpectedly rejects, only exact-generation-safe durable compensation may be attempted and failure remains explicit.

If durable completion commits but local exact completion unexpectedly fails, the result is explicit failure; reopen from persisted state is the authoritative recovery boundary. No hidden retry or local fabrication of completed history is allowed.

## Concurrency rule

One `PersistentLearningApplicationMutationComposition` serializes durable mutation pipelines so local prepare/remove/complete publication cannot interleave across the durable boundary.

Two independently opened compositions over one shared backend are optimistic-CAS participants, not a distributed claim coordinator. A stale composition receives explicit conflict/rejection. No automatic refresh, retry, merge, lease or distributed lock is introduced.

## Privacy, logging and diagnostics rule

Learning plans may contain private Memory/Knowledge cognitive payloads. Persistence preserves those payloads, but normal observability must not expose them.

Allowed structural observability includes mutation/application IDs and generations, target, idempotency key, principal identifier where already approved, downstream/payload structural IDs, schema/version and timestamps.

Forbidden by default in normal logs, diagnostics, `toString` and public integration failure text:

- Memory content;
- Knowledge content;
- raw persistent payload bytes;
- arbitrary serialized plan bytes;
- backend exception messages that may carry private text.

Failure rendering may expose structural category/reason and exception class, but not private exception message content.

Logging and diagnostics remain part of the Foundation observability boundary; persistence does not bypass them with direct console output or private payload dumping.

## Physical durability and encryption boundary

Learning Persistence Integration v0.1 is storage-engine-neutral. Physical crash durability depends on the concrete `PersistentRecordBackend`; the in-memory contract backend only survives reopen while that backend instance lives.

This integration does not itself implement authenticated encryption. Future device/backend encryption adapters must preserve the frozen identities, generations, record kinds, idempotency state, receipts, atomic transition and reopen semantics.

Licensing remains separate. License expiry must not intentionally destroy or make persistent cognitive history unrecoverable.

## Explicit non-goals

This freeze does not add:

- exactly-once downstream mutation guarantees;
- cross-domain Learning + Memory/Knowledge transactions;
- automatic crash replay;
- automatic retry/refresh/reconciliation;
- automatic compensation of downstream side effects;
- persisted claim tokens or leases;
- scheduler/background workers;
- distributed locks, leader election or consensus;
- multi-writer merge/conflict resolution;
- Android/device storage;
- SQLite/SQLCipher or filesystem layout;
- Keystore/StrongBox;
- authenticated-encryption implementation;
- licensing behavior;
- cloud sync or backup policy;
- new Authority/capability semantics.

## Readiness conclusion

The final readiness audit closed the last blocking restoration gap by requiring the completed receipt generation to exactly match the persistent entry generation. Executable contracts now cover canonical codecs/restoration, durable prepare/remove/reopen, atomic durable completion, zero claim resurrection, explicit claim behavior on failed/invalid completion, same-composition concurrency, shared-backend CAS conflict, privacy-safe failure rendering and fail-closed reopen.

No remaining correctness, privacy, logging/diagnostics or ownership defect requires another Learning Persistence Integration v0.1 implementation slice before freeze.

After this documentation checkpoint merges with exact-head and merge/main Core CI GREEN, Learning Persistence Integration v0.1 is fully frozen.

## Next controlled stage

Do not infer a new subsystem merely from completion of this freeze. Select the next stage only from the current repository roadmap/architecture after the freeze checkpoint is verified on `main`.
