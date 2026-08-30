package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class PersistentLearningApplicationMutationCompletionContractTest {
    private val createdAt = Instant.parse("2026-08-30T16:35:00Z")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator {
                "persistent-learning-completion-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun plan(
        id: String = "learning-mutation-complete-1",
        key: String = "learning-key-complete-1",
        content: String = "private learned completion payload"
    ) = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId("learning-application-complete-1"),
            generation = LearningApplicationGeneration(7)
        ),
        principal = AuthorityPrincipal("assistant-core"),
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId("memory-$id"),
                provenance = MemoryProvenance(MemorySourceId("learning")),
                content = content,
                createdAt = createdAt.minusSeconds(1)
            )
        ),
        createdAt = createdAt
    )

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        storeId: String = "learning-completion-store"
    ): PersistentLearningApplicationMutationComposition =
        assertIs<PersistentLearningApplicationMutationOpenResult.Opened>(
            PersistentLearningApplicationMutationComposition.open(
                foundation = foundation(),
                storeId = PersistentStoreId(storeId),
                backend = backend
            )
        ).composition

    private fun receipt(
        reference: LearningApplicationMutationReference,
        recordId: String = "memory-completed",
        generation: Long = 44
    ) = LearningApplicationMutationApplicationReceipt(
        mutation = reference,
        target = LearningApplicationTarget.MEMORY,
        downstream = LearningApplicationDownstreamReference.Memory(
            recordId = MemoryRecordId(recordId),
            generation = MemoryGeneration(generation)
        )
    )

    @Test
    fun durable_completion_atomically_replaces_prepared_state_and_reopen_restores_completed_receipt() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val mutation = plan()
        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        ).ownership
        val reference = LearningApplicationMutationReference(mutation.id, prepared.generation)
        val claim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference)
        ).claim
        val completion = receipt(reference)

        assertIs<PersistentLearningApplicationMutationResult.Committed>(claim.complete(completion))
        assertFalse(composition.contains(mutation.id))
        assertNull(composition.findByIdempotencyKey(mutation.idempotencyKey))
        assertEquals(completion, composition.completedOutcomeByMutationId(mutation.id))
        assertEquals(completion, composition.completedOutcomeByIdempotencyKey(mutation.idempotencyKey))

        val reopened = open(backend)
        assertFalse(reopened.contains(mutation.id))
        assertEquals(completion, reopened.completedOutcomeByMutationId(mutation.id))
        assertEquals(completion, reopened.completedOutcomeByIdempotencyKey(mutation.idempotencyKey))
        assertTrue(reopened.isCompletedIdempotencyKey(mutation.idempotencyKey))
        assertEquals(
            completion,
            assertIs<PersistentLearningApplicationMutationPrepareResult.AlreadyCompleted>(
                reopened.prepare(mutation)
            ).receipt
        )
    }

    @Test
    fun completion_transition_preserves_generation_high_watermark() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val firstPlan = plan(id = "first", key = "first-key")
        val first = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(firstPlan)
        ).ownership
        val firstReference = LearningApplicationMutationReference(firstPlan.id, first.generation)
        val claim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            composition.claim(firstReference)
        ).claim
        assertIs<PersistentLearningApplicationMutationResult.Committed>(claim.complete(receipt(firstReference)))

        val second = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan(id = "second", key = "second-key"))
        ).ownership
        assertEquals(first.generation.value + 1, second.generation.value)
    }

    @Test
    fun failed_durable_completion_keeps_prepared_state_and_completed_indexes_absent() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val mutation = plan()
        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        ).ownership
        val reference = LearningApplicationMutationReference(mutation.id, prepared.generation)
        val claim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference)
        ).claim
        backend.failNextCommit()

        assertIs<PersistentLearningApplicationMutationResult.Failed>(claim.complete(receipt(reference)))
        assertEquals(mutation, composition.find(mutation.id))
        assertNull(composition.completedOutcomeByMutationId(mutation.id))
        assertNull(composition.completedOutcomeByIdempotencyKey(mutation.idempotencyKey))
        assertTrue(claim.release())

        val reopened = open(backend)
        assertEquals(mutation, reopened.find(mutation.id))
        assertNull(reopened.completedOutcomeByMutationId(mutation.id))
    }

    @Test
    fun active_claim_rejects_removal_before_any_durable_remove() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val mutation = plan()
        val ownership = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        ).ownership
        val reference = LearningApplicationMutationReference(mutation.id, ownership.generation)
        val claim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference)
        ).claim

        assertIs<PersistentLearningApplicationMutationResult.Rejected>(ownership.remove())
        assertEquals(mutation, open(backend).find(mutation.id))
        assertTrue(claim.release())
        assertIs<PersistentLearningApplicationMutationResult.Committed>(ownership.remove())
    }

    @Test
    fun shared_backend_revision_conflict_does_not_publish_completed_state_locally() {
        val backend = InMemoryPersistentRecordBackend()
        val writer = open(backend)
        val mutation = plan(id = "shared", key = "shared-key")
        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            writer.prepare(mutation)
        ).ownership

        val stale = open(backend)
        val reference = LearningApplicationMutationReference(mutation.id, prepared.generation)
        val staleClaim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            stale.claim(reference)
        ).claim

        assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            writer.prepare(plan(id = "revision-bump", key = "revision-bump-key"))
        )

        assertIs<PersistentLearningApplicationMutationResult.Rejected>(
            staleClaim.complete(receipt(reference))
        )
        assertEquals(mutation, stale.find(mutation.id))
        assertNull(stale.completedOutcomeByMutationId(mutation.id))
        assertTrue(staleClaim.release())

        val reopened = open(backend)
        assertEquals(mutation, reopened.find(mutation.id))
        assertNull(reopened.completedOutcomeByMutationId(mutation.id))
    }
}
