# Learning Persistence Integration v0.1 — Freeze Contract

Status: **FROZEN**

Canonical architecture contract: `LEARNING_PERSISTENCE_INTEGRATION_V0_1_CONTRACT.md`.

## Verified implementation history

- PR #23 — Architecture Contract;
- PR #24 — Codec and Restoration Boundary;
- PR #25 — Durable Prepare Remove and Reopen;
- PR #26 — Atomic Durable Completion;
- PR #27 — Readiness Hardening;
- PR #28 — Freeze Checkpoint.

All implementation/freeze slices passed exact-head Core CI before merge and merge/main Core CI after merge.

## Frozen integration boundary

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

Durable prepare:

`validate plan → encode prepared → durable commit → exact committed local install → Prepared`

Durable removal:

`validate exact unclaimed ownership → durable exact-generation remove → exact local remove → success`

Durable completion:

`validate exact active claim + exact receipt → one durable exact prepared→completed transition → exact local completion/index publication → success`

## Frozen guarantees

- exact mutation ID/generation restoration;
- persistent generation high-watermark preservation;
- exact prepared/completed schema separation;
- exact Memory/Knowledge payload round-trip;
- exact completed receipts and both completed indexes;
- fail-closed malformed/incompatible/impossible reopen state;
- completed receipt generation equals persistent entry generation;
- no process-local claim resurrection;
- one exact active claim per live mutation;
- active-claim removal barrier;
- invalid completion does not silently release a claim;
- durable acknowledgement precedes local success publication;
- stale/ABA-safe exact ownership;
- one backend-CAS durable prepared→completed transition preserving generation/high-watermark;
- same-composition serialization;
- explicit shared-backend CAS conflict rather than hidden refresh/retry;
- deterministic live snapshots;
- privacy-safe failure rendering and structural observability.

## Authority and idempotency boundary

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

Claims are process-local concurrency control only. Persisted principal/application/decision/policy references remain structural historical state. Reopen does not pre-authorize downstream mutation.

Completed receipts are evidence, not credentials/capabilities/Authority receipts.

## Downstream crash-window limitation

This freeze does not create a transaction spanning Learning plus Memory/Knowledge.

The controlled path remains:

`downstream Memory/Knowledge mutation → durable Learning completion`

A crash/failure may happen between these boundaries. No exactly-once downstream guarantee, automatic replay, hidden retry, automatic compensation or reconciliation is claimed.

## Privacy, logging and diagnostics

Learning persistence may store private cognitive payloads, but normal observability excludes private Memory/Knowledge content, raw persistent bytes, serialized plan bodies and secret-bearing backend exception messages.

Failure rendering may expose structural reason/category plus exception class.

Foundation Logging/Diagnostics/CoreObservability remains the only production observability path.

## Post-freeze observability audit

A post-freeze observability audit that should have been closed before the original freeze handoff was completed later against the frozen production paths.

Audit result: **CLEAN**.

Verified:

- durable persistence transitions route through Foundation observability;
- private payload and backend exception-message content remain absent from normal Learning durable/public failure rendering;
- corruption/incompatibility/reopen/generation mismatch remains fail closed;
- targeted production-path searches found no `println`, `System.out`, `printStackTrace` or `throwable.message` observability bypass;
- `durable_failure_rendering_does_not_expose_private_payload_or_exception_message` remains GREEN in the full Core suite.

The delayed audit closes a process/documentation gap only; it does not alter frozen Learning semantics.

## Physical durability / encryption boundary

Physical durability depends on the concrete `PersistentRecordBackend`; the in-memory backend is test/dev infrastructure.

Authenticated encryption is not implemented by this layer. Future storage/device encryption must preserve exact identities, generations, idempotency history, atomic completion and fail-closed reopen semantics.

License expiry must not intentionally destroy or make persistent cognitive history unrecoverable.
