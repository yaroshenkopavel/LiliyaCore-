# Learning Persistence Observability Audit v0.1

Status: **COMPLETED — LEARNING/PERSISTENCE BOUNDARY CLEAN, FOUNDATION THROWABLE CAVEAT RECORDED**

Verified code baseline: `f268012f1d491f8f53859a9259be98a14c12c10f`.

This audit was performed after the original Learning Persistence freeze gate was missed. It corrects the process record and scopes the privacy claim precisely. It does not change frozen Learning semantics.

## Audited boundary

The audit covered the frozen Learning persistence path and the Foundation emission plumbing it depends on:

- `LearningApplicationMutationModels.kt`;
- `PersistentLearningApplicationMutationComposition.kt`;
- `LearningApplicationMutationApplier.kt`;
- `PersistentRecordStore.kt`;
- `DiagnosticRecorder.kt`;
- `StructuredLogger.kt`;
- existing Learning persistence readiness contracts.

## Result

The frozen Learning/Persistence boundary is **CLEAN for normal operational emission of backend exception messages and private cognitive payloads**.

The important distinction is:

- Learning durable/public failure values may retain a `Throwable` for programmatic failure reporting, but their `toString()` rendering exposes the exception class only;
- `PersistentRecordStore` emits structural failure categories and record metadata but does **not** pass backend throwables into `CoreObservability.record(...)`;
- `LearningApplicationMutationApplier` emits structural result metadata and does **not** pass a throwable into Foundation observability;
- private Memory/Knowledge payload text is not added to Learning/Persistence operational metadata.

Therefore the audited backend failure path does not place a secret-bearing backend exception message into the emitted Learning/Persistence log/diagnostic events.

## Foundation throwable caveat

The Foundation plumbing is not a universal exception-message redactor.

`DiagnosticRecorder` and `StructuredLogger` populate `throwableMessage` from `throwable.message` when a caller explicitly supplies a throwable to Foundation observability/logging.

Consequently this audit must **not** be read as a repository-wide guarantee that any arbitrary throwable is safe to pass into observability. Callers handling potentially secret-bearing exceptions must either avoid forwarding the throwable or introduce a separately reviewed sanitization boundary.

This caveat is cross-cutting Foundation behavior; it is not evidence of a leak in the audited frozen Learning/Persistence callsites because those callsites do not forward the backend throwable into operational emission.

## Failure rendering evidence

`PersistentLearningApplicationMutationPrepareResult.Failed`, `PersistentLearningApplicationMutationResult.Failed`, and `PersistentLearningApplicationMutationOpenResult.Failed` render fixed structural reason plus exception class and omit `Throwable.message`.

The readiness contract `durable_failure_rendering_does_not_expose_private_payload_or_exception_message` injects private cognitive content and a secret-bearing backend exception message and verifies the public failure rendering contains neither value while retaining the exception class.

## Console / bypass audit

Targeted repository searches and inspected production paths found no Learning/Persistence use of direct `println`, `System.out`, `printStackTrace`, or equivalent console bypass in the audited boundary.

Search results are supporting evidence, not a mathematical proof of absence across unrelated subsystems.

## Privacy conclusion

For the frozen Learning Persistence Integration v0.1 boundary:

- private Memory/Knowledge content remains outside normal operational observability;
- raw persistent payload bytes are represented only by structural byte count where needed;
- backend exception-message content is not forwarded into emitted Learning/Persistence events;
- public failure rendering exposes structural reason/category and exception class only;
- malformed/corrupt/incompatible/restoration-invalid state remains fail closed.

No Learning Persistence implementation change is required by this audit.

## Process correction

The original process required this audit before later License work. That gate was missed and subsequent work advanced before the audit was actually completed. The earlier documentation that simply declared the delayed audit `CLEAN` was therefore premature.

This document is the corrective evidence checkpoint. Later subsystem work must not use a documentation claim as a substitute for performing the required audit before its gate.
