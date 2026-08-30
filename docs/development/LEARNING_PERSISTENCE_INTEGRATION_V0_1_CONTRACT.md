# Learning Persistence Integration v0.1 Contract

Status: **FROZEN**

Canonical freeze document: `LEARNING_PERSISTENCE_INTEGRATION_V0_1_FREEZE.md`.

## Purpose

This contract defines the storage-neutral persistence boundary for the frozen controlled Learning application mutation domain without converting durable state into Authority, execution permission, scheduler state or an exactly-once side-effect guarantee.

Frozen direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

## Mandatory separation

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

`Completed Learning receipt != credential != capability != permission != Authority receipt`

Persisted principal/application/decision/policy references are structural historical state only. Reopen never pre-authorizes a downstream mutation.

## Frozen durable semantics

Durable prepare:

`validate plan → encode prepared record → durable commit → exact committed local install → Prepared`

Durable removal:

`validate exact unclaimed ownership → durable exact-generation remove → exact local remove → success`

Durable completion:

`validate exact active claim + exact receipt → one exact durable prepared→completed transition → exact local completion/index publication → success`

Durable acknowledgement precedes local publication. Failed/conflicting durable operations do not fabricate local success and do not introduce hidden retry/refresh/reconciliation.

## Restoration and ownership

Frozen guarantees include:

- exact `LearningApplicationMutationId` and positive generation restoration;
- persistent generation high-watermark preservation;
- exact idempotency/completed indexes;
- exact completed receipt restoration;
- fail-closed corrupt/incompatible/impossible state;
- completed receipt generation must equal persistent entry generation;
- zero resurrection of process-local claims;
- active-claim removal barrier;
- stale/ABA-safe exact ownership;
- deterministic live snapshots;
- same-composition serialization;
- explicit shared-backend optimistic-CAS conflicts.

No partially hydrated Learning composition escapes on reopen failure.

## Canonical payload/privacy boundary

Prepared/completed codecs preserve all required Memory/Knowledge payload state exactly, including private content and structural provenance/origin, but normal rendering and observability must not expose private cognitive content or raw persistent bytes.

Allowed observability is structural: approved IDs/generations, target, idempotency key, schema/version, timestamps and structural payload/downstream IDs.

Forbidden by default:

- Memory/Knowledge content;
- raw persistent payload bytes;
- serialized plan bodies;
- backend exception messages that may contain private text.

Public/durable failure rendering may expose structural reason/category and exception class, not secret-bearing exception messages.

## Post-freeze observability audit closure

The deferred post-freeze Learning/Persistence observability audit was completed after the original freeze documentation checkpoint.

Result: **CLEAN**.

Verified against current frozen production paths:

- persistence durable transitions use Foundation Logging/Diagnostics/CoreObservability;
- Learning durable/public failure rendering does not expose private payload or backend exception message content;
- corruption/incompatibility/reopen/generation mismatch paths remain explicit and fail closed;
- no audited production path used `println`, `System.out`, `printStackTrace` or `throwable.message` as an observability bypass;
- readiness contract `durable_failure_rendering_does_not_expose_private_payload_or_exception_message` remains GREEN in the full Core suite.

This audit adds no new Learning semantics; it closes the delayed verification gate.

## Atomic persistent transition

Learning completion uses the narrow internal persistence primitive:

`exact live source + exact generation → one backend CAS commit → replacement with same generation/high-watermark`

It is not a general transaction engine, workflow primitive, scheduler, distributed lock or authorization mechanism.

## Known cross-domain crash window

Learning persistence does not create a transaction spanning Learning and Memory/Knowledge.

The controlled path remains:

`downstream Memory/Knowledge mutation → durable Learning completion`

A crash/failure may happen between those boundaries. Therefore v0.1 does not claim exactly-once downstream mutation, automatic replay, hidden retry, automatic compensation or reconciliation.

Future cross-domain crash atomicity requires a separate reviewed architecture and executable proof.

## Encryption/licensing boundary

This integration is storage-engine-neutral and encryption-ready, not encrypted storage itself. Android/filesystem/SQLite/SQLCipher/Keystore/StrongBox remain future adapters.

Licensing remains separate. License expiry/denial must not intentionally destroy or make legitimate Learning history unrecoverable.

## Frozen evidence

Implementation/freeze PRs #23–#28 passed exact-head and merge/main Core CI gates. The later post-freeze observability audit is documented in the License Core v0.1 freeze checkpoint and this canonical contract.
