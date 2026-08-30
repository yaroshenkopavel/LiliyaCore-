package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentStoreId

class PersistentLearningApplicationMutationReadinessContractTest {
    private val createdAt = Instant.parse("2026-08-30T16:45:00Z")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator {
                "persistent-learning-readiness-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun plan(
        id: String = "readiness-mutation",
        key: String = "readiness-key",
        content: String = "private readiness learning payload",
        createdAt: Instant = this.createdAt
    ) = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId("readiness-application"),
            generation = LearningApplicationGeneration(5)
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
        storeId: PersistentStoreId = PersistentStoreId("learning-readiness-store")
    ): PersistentLearningApplicationMutationComposition =
        assertIs<PersistentLearningApplicationMutationOpenResult.Opened>(
            PersistentLearningApplicationMutationComposition.open(
                foundation = foundation(),
                storeId = storeId,
                backend = backend
            )
        ).composition

    private fun receipt(reference: LearningApplicationMutationReference) =
        LearningApplicationMutationApplicationReceipt(
            mutation = reference,
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("memory-completed"),
                generation = MemoryGeneration(31)
            )
        )

    @Test
    fun reopen_never_resurrects_process_local_claims() {
        val backend = InMemoryPersistentRecordBackend()
        val mutation = plan()
        val first = open(backend)
        val ownership = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            first.prepare(mutation)
        ).ownership
        val reference = LearningApplicationMutationReference(mutation.id, ownership.generation)
        val firstClaim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            first.claim(reference)
        ).claim

        val reopened = open(backend)
        val reopenedClaim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            reopened.claim(reference)
        ).claim

        assertTrue(reopenedClaim.release())
        assertTrue(firstClaim.release())
    }

    @Test
    fun completed_receipt_generation_must_match_persistent_entry_generation_on_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val storeId = PersistentStoreId("learning-generation-mismatch-store")
        val mutation = plan()
        val completion = receipt(
            LearningApplicationMutationReference(
                mutationId = mutation.id,
                generation = LearningApplicationMutationGeneration(1)
            )
        )
        val record = LearningApplicationMutationPersistentCodec.encodeCompleted(mutation, completion)
        backend.forceLoad(
            storeId,
            PersistentBackendLoadResult.Loaded(
                revision = 1,
                state = PersistentBackendState(
                    storeId = storeId,
                    highWatermark = 2,
                    entries = mapOf(
                        record.id to PersistentBackendEntry(
                            generation = PersistentGeneration(2),
                            record = record
                        )
                    )
                )
            )
        )

        assertIs<PersistentLearningApplicationMutationOpenResult.RestorationFailed>(
            PersistentLearningApplicationMutationComposition.open(
                foundation = foundation(),
                storeId = storeId,
                backend = backend
            )
        )
    }

    @Test
    fun invalid_completion_retains_claim_until_explicit_release() {
        val backend = InMemoryPersistentRecordBackend()
        val mutation = plan()
        val composition = open(backend)
        val ownership = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(mutation)
        ).ownership
        val reference = LearningApplicationMutationReference(mutation.id, ownership.generation)
        val claim = assertIs<PersistentLearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference)
        ).claim
        val invalid = receipt(
            LearningApplicationMutationReference(
                mutation.id,
                LearningApplicationMutationGeneration(reference.generation.value + 1)
            )
        )

        assertIs<PersistentLearningApplicationMutationResult.Rejected>(claim.complete(invalid))
        val duplicateClaim = assertIs<PersistentLearningApplicationMutationClaimResult.Rejected>(
            composition.claim(reference)
        )
        assertEquals(LearningApplicationMutationClaimRejection.ALREADY_CLAIMED, duplicateClaim.reason)
        assertTrue(claim.release())
    }

    @Test
    fun durable_failure_rendering_does_not_expose_private_payload_or_exception_message() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val secret = "PRIVATE-LEARNING-SECRET-READINESS"
        backend.failNextCommit(IllegalStateException("backend leaked $secret"))

        val failed = assertIs<PersistentLearningApplicationMutationPrepareResult.Failed>(
            composition.prepare(plan(content = secret))
        )
        val rendered = failed.toString()

        assertFalse(secret in rendered)
        assertFalse("backend leaked" in rendered)
        assertTrue(IllegalStateException::class.java.name in rendered)
    }

    @Test
    fun concurrent_distinct_prepares_on_one_composition_are_serialized_and_reopen_exactly() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val attempts = 8
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val prepared = AtomicInteger(0)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        val result = composition.prepare(
                            plan(
                                id = "mutation-$index",
                                key = "key-$index",
                                content = "private-$index",
                                createdAt = createdAt.plusSeconds(index.toLong())
                            )
                        )
                        if (result is PersistentLearningApplicationMutationPrepareResult.Prepared) {
                            prepared.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(attempts, prepared.get())
            assertEquals(attempts, composition.snapshotEntries().size)
            assertEquals(
                (1L..attempts.toLong()).toSet(),
                composition.snapshotEntries().map { it.generation.value }.toSet()
            )

            val reopened = open(backend)
            assertEquals(composition.snapshotEntries(), reopened.snapshotEntries())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
