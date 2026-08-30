# Learning Persistence Integration v0.1 Contract

Status: ARCHITECTURE CONTRACT — IMPLEMENTATION NOT YET FROZEN

## Purpose

Define the first storage-neutral persistence boundary for the already-frozen controlled Learning application mutation domain without converting durable Learning state into Authority, execution permission, scheduler state, or an exactly-once side-effect guarantee.

Selected direction:

`frozen Learning mutation domain → canonical prepared/completed Learning codecs → exact persistent record store → reviewed exact-generation restoration → frozen Learning mutation/idempotency semantics`

This stage builds on the frozen Persistent Cognitive Storage v0.1 primitive plus the frozen Memory Persistence Integration v0.1 and Knowledge Persistence Integration v0.1 boundaries.

## Mandatory separation

`Learning != Persistence != Encryption != License != Authority != Execution`

`Idempotency evidence != exactly-once execution`

`Completed Learning receipt != credential != capability != permission != Authority receipt`

Persisting Learning state preserves cognitive/control history. It does not grant permission to perform downstream mutation and does not bypass the existing Learning authorization gate.

## Existing Learning semantics that must remain unchanged

The integration must preserve the current frozen Learning application mutation contract:

- exact `LearningApplicationMutationId` identity;
- positive `LearningApplicationMutationGeneration` ownership;
- exact `LearningApplicationIdempotencyKey` reservation/completion semantics;
- duplicate live mutation ID rejection;
- duplicate live idempotency-key rejection;
- stale/ABA-safe exact-generation ownership;
- one active claim per exact live mutation;
- claim release does not complete the mutation;
- controlled completion remains internal and requires an exact receipt;
- completion requires exact mutation reference, matching target, and downstream reference type;
- completed outcomes are indexed by both mutation ID and idempotency key;
- re-preparing an exactly equal completed plan returns `AlreadyCompleted(receipt)`;
- conflicting reuse of a completed mutation ID or idempotency key remains rejected;
- deterministic live snapshots ordered by `createdAt`, then mutation ID;
- payload content remains absent from normal operational observability except already-approved structural IDs;
- composition/backend isolation unless backend sharing is explicit.

The current in-memory generation allocator is process-local. Persistence integration may use persistent-store-assigned generations, but restored exact ownership and persisted high-watermark monotonicity must remain explicit.

## Persisted state classes

Learning Persistence Integration v0.1 must distinguish at least two logical record states.

### Prepared mutation

A prepared live mutation record must preserve exactly:

- mutation ID;
- mutation generation;
- application reference (`LearningApplicationId + LearningApplicationGeneration`);
- authority principal identity as structural data;
- target;
- idempotency key;
- payload type and complete payload needed to reconstruct the frozen plan;
- caller-supplied `createdAt`.

A persisted principal/reference remains structural recorded state. Reopen does not re-authorize future application and does not turn that value into current Authority.

### Completed outcome

A completed outcome record must preserve exactly:

- the exact completed mutation plan identity needed to enforce future duplicate/idempotency behavior;
- exact completed mutation reference including generation;
- target;
- exact downstream reference type and fields;
- idempotency key association.

Completed outcome persistence exists so completed-history/idempotency evidence can survive reopen. It must not be described as an exactly-once external side-effect guarantee.

## Canonical Learning persistent records

The integration must define explicit schema IDs and positive schema versions for each persisted Learning record kind. Prepared and completed state must not be ambiguously decoded from one untagged payload shape.

Canonical codecs must deterministically preserve all fields needed to reconstruct the frozen Learning semantics.

Persistent entity identity must be unambiguous and collision-safe across prepared/completed Learning records. The integration must not allow one record kind to accidentally overwrite another because of a shared raw mutation ID namespace.

Malformed payload, unsupported schema/version, invalid enum/discriminator, invalid nested Memory/Knowledge payload, mismatched entity identity, mismatched timestamps, partial bytes, trailing bytes or semantically impossible receipt state must fail explicitly and fail closed.

Private cognitive payload content and raw persistent bytes must not appear in normal `toString`, logs, diagnostics or public integration failure text by default.

## Restoration boundary

Hydration must not inject arbitrary internal maps.

The Learning domain must gain a reviewed internal/domain-owned restoration boundary able to reconstruct:

- all live prepared mutation snapshots with exact persisted generations;
- completed outcomes indexed consistently by mutation ID and idempotency key;
- persisted mutation generation high-watermark;
- no active claims.

Claims are process-local concurrency ownership and must never be revived across reopen. Reopen starts every live prepared mutation unclaimed.

Restoration must reject at least:

- negative high-watermark;
- live generation above high-watermark;
- duplicate live mutation ID;
- duplicate live generation where the frozen exact-generation model requires uniqueness;
- duplicate live idempotency key;
- overlap where a mutation ID is both live and completed;
- overlap where an idempotency key is both live and completed;
- inconsistent completed indexes;
- completed receipt mutation reference not matching the completed plan/generation;
- completed receipt target/downstream type mismatch;
- invalid nested Memory/Knowledge payload/domain value;
- semantically impossible reconstructed state.

No partially hydrated Learning composition may escape after any corrupt, incompatible or invalid entry.

## Durable prepare ordering

Persisted preparation must be durable-first:

`validate Learning mutation plan → encode prepared record → durable install/commit → exact committed Learning install locally → Prepared`

No public `Prepared` ownership may be returned before durable acknowledgement.

If durable install fails or conflicts:

- the Learning mutation remains locally absent;
- the idempotency key is not locally reserved;
- no generated local ownership is published;
- no hidden retry, refresh or reconciliation is allowed.

The local Learning generation must match the committed persistent generation exactly.

If durable commit succeeds but exact local publication unexpectedly rejects, the integration must report explicit failure and use only exact-generation-safe compensation if compensation is attempted.

## Durable removal ordering

Removal of a still-prepared, unclaimed mutation must remain exact-generation and durable-first:

`validate exact Learning ownership → durable exact-generation prepared-record remove → exact local remove → success`

If durable removal fails or conflicts, local prepared state and idempotency reservation remain live.

A stale ownership must never remove a newer replacement generation.

An actively claimed mutation remains non-removable according to the frozen domain semantics; persistence integration must not weaken that rule.

## Claim semantics across persistence

Claim acquisition/release remains an in-process concurrency control primitive in v0.1.

The persistence layer must not persist or resurrect active claim tokens, lease times, worker ownership, scheduler state or distributed locks.

Two independently opened compositions sharing one backend are not a distributed claim coordinator. v0.1 does not claim cross-process mutual exclusion for application execution.

## Durable completion ordering

Completion is the most sensitive Learning persistence transition because it changes both live mutation state and completed/idempotency history.

Within one persisted Learning composition the transition must be serialized and represented as one reviewed durable state transition before local completion is published:

`validate exact active claim + exact receipt → durable transition prepared → completed → update local completed indexes → Applied`

The persistent representation must not expose an intermediate durable state where the prepared mutation is gone but the completed/idempotency outcome is absent.

If the generic persistence primitive cannot express this transition safely as one logical committed revision, the implementation must introduce a Learning-owned canonical state representation that does; it must not emulate atomicity with two unrelated commits and then claim crash safety.

If durable completion fails or conflicts:

- the local mutation must not be marked completed;
- completed indexes must not be published locally;
- the claim may be released or retained only according to an explicit tested path;
- no hidden retry is allowed.

## Downstream side-effect crash window

Learning completion persistence does not by itself create a transaction spanning Learning plus Memory/Knowledge.

The existing controlled applier performs a downstream Memory/Knowledge mutation and then records Learning completion. A crash or persistence failure can occur between those boundaries.

Therefore v0.1 must explicitly preserve this limitation:

- durable completed-history prevents forgetting an already recorded completion;
- it does not prove that a downstream side effect happened exactly once;
- it does not eliminate the window where downstream mutation committed but Learning completion did not;
- it does not authorize automatic replay after reopen;
- it does not add hidden compensation/retry/reconciliation.

Any future exactly-once or crash-atomic cross-domain application guarantee requires a separate transaction/outbox/idempotent-downstream architecture contract and executable proof.

## Reopen semantics

Reopen must be fail-closed and atomic at the composition boundary:

`open persistent store → validate store → decode all Learning records → validate prepared/completed consistency → restore exact Learning state/high-watermark → publish ready composition`

`Corrupt`, `Incompatible`, restoration-invalid and backend `Failed` outcomes must remain explicit.

No partially hydrated Learning composition may escape.

A successful reopen must preserve:

- exact live mutation IDs and generations;
- exact application references, principal, target and idempotency keys;
- exact Memory/Knowledge payloads and caller-supplied timestamps;
- exact completed receipts/downstream references;
- completed lookup by mutation ID and idempotency key;
- `AlreadyCompleted` behavior for an equal completed plan;
- conflicting reuse rejection;
- deterministic live snapshots;
- persisted generation high-watermark across removals/completions/reopen;
- zero resurrected active claims.

## Backend sharing and concurrency

A single persisted Learning composition may serialize prepare/remove/complete persistence pipelines so durable commit and local publication cannot race each other.

Independent compositions explicitly sharing the same backend/store remain optimistic-CAS participants. A stale composition must receive explicit conflict/rejection rather than hidden refresh/retry/reconciliation.

v0.1 does not add distributed locks, claim leases, scheduler ownership, consensus, leader election, multi-writer merge or background reconciliation.

## Privacy and observability

Learning plans can contain private Memory or Knowledge payloads.

Allowed structural metadata remains limited to fields already permitted by frozen contracts, such as mutation/application IDs and generations, target, idempotency key, principal identifier, payload structural target ID, schema/version and timestamps.

Forbidden in normal operational observability by default:

- Memory content;
- Knowledge content;
- raw persistent payload bytes;
- arbitrary serialized plan bytes;
- exception messages that may contain private payload;
- backend detail strings that may contain cognitive text.

Public failure rendering must prefer structural category/reason plus exception class, not exception message.

## Authority and authorization boundary

Persistence must not weaken the controlled Learning authorization path.

A reopened prepared mutation is not pre-authorized merely because its original principal/application/decision/policy references were persisted.

Before any real downstream application, the existing fresh controlled authorization boundary remains authoritative.

A completed Learning receipt is historical structural evidence, not an Authority receipt or reusable permission token.

## Security / encryption boundary

This contract is storage-engine-neutral and encryption-ready, not an encryption implementation.

It does not select Android, filesystem layout, SQLite, SQLCipher, Keystore, StrongBox or any serialization/encryption library.

Future authenticated-encryption adapters must preserve exact identities, generations, idempotency state, record kind, receipt fidelity and reopen semantics.

Learning encryption keys must remain separate from licensing/model entitlement. License expiry must not intentionally destroy or make user Learning history unrecoverable.

## Non-goals

Learning Persistence Integration v0.1 does not add:

- exactly-once downstream mutation guarantees;
- cross-domain transactions spanning Learning and Memory/Knowledge;
- automatic replay after crash;
- automatic compensation after reopen;
- scheduler/background workers;
- persisted claim leases;
- distributed locks/consensus;
- hidden retry/refresh/reconciliation;
- Android/device storage;
- SQLite/SQLCipher/Keystore;
- encryption implementation;
- licensing behavior;
- Authority/capability bypass;
- semantic deduplication beyond the frozen exact idempotency-key semantics;
- cloud sync or multi-writer merge.

## First implementation slices and executable gates

Before this integration can be frozen, executable contracts must prove at minimum:

1. deterministic prepared-plan codec round-trip for Memory payload;
2. deterministic prepared-plan codec round-trip for Knowledge payload;
3. deterministic completed-outcome codec round-trip for both downstream reference types;
4. malformed/incompatible/trailing/mismatched decode fails explicitly without private-content leakage;
5. exact mutation-generation restoration;
6. persisted mutation high-watermark restoration;
7. duplicate live mutation ID/idempotency key and live/completed overlap rejection;
8. completed index restoration by both mutation ID and idempotency key;
9. reopened equal completed plan returns exact `AlreadyCompleted(receipt)`;
10. reopened conflicting completed ID/key reuse remains rejected;
11. active claims are never restored;
12. durable prepare precedes local `Prepared` publication;
13. failed durable prepare leaves local mutation/idempotency state absent;
14. durable exact-generation remove precedes local removal;
15. failed/conflicting remove keeps local prepared state live;
16. stale ownership cannot remove a newer prepared replacement generation;
17. durable completion transition atomically moves prepared state into completed/idempotency history before local completion publication;
18. failed/conflicting durable completion does not publish completed local state;
19. reopen restores exact live/completed state and deterministic live snapshots with no partial composition;
20. corrupt/incompatible/open/restoration failure publishes no partial composition;
21. same-composition concurrent persistence mutations cannot race local generation/completion publication;
22. explicitly shared backend preserves visible CAS conflict semantics without hidden retry;
23. failure rendering/observability do not expose Memory/Knowledge content or backend exception messages;
24. tests/documentation explicitly reject an exactly-once downstream claim and preserve the known downstream→Learning-completion crash window.

## Freeze criteria

Learning Persistence Integration v0.1 may be frozen only after:

- architecture contract exact-head and merge/main Core CI are GREEN;
- canonical prepared/completed codecs are GREEN;
- exact restoration/high-watermark/completed-index contracts are GREEN;
- durable prepare/remove/complete ordering is GREEN;
- stale/ABA and idempotency semantics are GREEN;
- reopen atomicity/failure contracts are GREEN;
- concurrency/shared-backend behavior is GREEN;
- privacy/failure rendering is GREEN;
- the cross-domain crash window is documented and not misrepresented as exactly-once;
- implementation/readiness audit finds no blocking correctness/privacy/ownership defect;
- freeze checkpoint PR exact-head and merge/main Core CI are GREEN.

Until then this document is the architecture contract, not a frozen implementation claim.
