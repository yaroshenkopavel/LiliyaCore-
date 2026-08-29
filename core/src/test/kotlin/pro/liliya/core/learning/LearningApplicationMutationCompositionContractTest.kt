package pro.liliya.core.learning

import java.time.Instant
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearningApplicationMutationCompositionContractTest {
    private fun foundation(correlation: String): FoundationComposition = FoundationComposition(
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
        loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
        correlationIds = CorrelationIdGenerator { correlation }
    )

    private fun plan(
        id: String = "mutation-1",
        key: String = "idem-1",
        applicationGeneration: Long = 1L
    ): LearningApplicationMutationPlan = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId("application-1"),
            generation = LearningApplicationGeneration(applicationGeneration)
        ),
        principal = AuthorityPrincipal("learning-controller"),
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId("memory-1"),
                provenance = MemoryProvenance(MemorySourceId("learning-application")),
                content = "sensitive prepared payload",
                createdAt = Instant.parse("2026-08-29T09:10:00Z")
            )
        ),
        createdAt = Instant.parse("2026-08-29T09:11:00Z")
    )

    private fun reference(ownership: LearningApplicationMutationOwnership) =
        LearningApplicationMutationReference(ownership.plan.id, ownership.generation)

    private fun receipt(ownership: LearningApplicationMutationOwnership) =
        LearningApplicationMutationApplicationReceipt(
            mutation = reference(ownership),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("memory-applied"),
                generation = MemoryGeneration(1L)
            )
        )

    @Test
    fun prepare_exposes_exact_controlled_ownership() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-composition"))
        val value = plan()

        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(value)
        ).ownership

        assertSame(value, ownership.plan)
        assertSame(value, composition.find(value.id))
        assertEquals(ownership.generation, composition.inspect(value.id)?.generation)
        assertEquals(value, composition.findByIdempotencyKey(value.idempotencyKey))
        assertTrue(ownership.remove())
        assertNull(composition.find(value.id))
        assertNull(composition.findByIdempotencyKey(value.idempotencyKey))
    }

    @Test
    fun duplicate_idempotency_key_is_rejected_without_replacing_current_plan() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-duplicate"))
        val first = plan(id = "mutation-a", key = "shared-key")
        val second = plan(id = "mutation-b", key = "shared-key", applicationGeneration = 2L)

        assertIs<LearningApplicationMutationPrepareResult.Prepared>(composition.prepare(first))
        assertIs<LearningApplicationMutationPrepareResult.Rejected>(composition.prepare(second))
        assertSame(first, composition.find(first.id))
        assertFalse(composition.contains(second.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-stale"))
        val first = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership

        assertTrue(first.remove())
        val replacement = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan(applicationGeneration = 2L))
        ).ownership

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove())
        assertTrue(composition.contains(replacement.plan.id))
        assertEquals(replacement.generation, composition.inspect(replacement.plan.id)?.generation)
    }

    @Test
    fun independent_compositions_do_not_share_prepared_mutations() {
        val first = LearningApplicationMutationComposition(foundation("mutation-first"))
        val second = LearningApplicationMutationComposition(foundation("mutation-second"))
        val value = plan()

        assertIs<LearningApplicationMutationPrepareResult.Prepared>(first.prepare(value))

        assertTrue(first.contains(value.id))
        assertFalse(second.contains(value.id))
        assertTrue(second.snapshot().isEmpty())
    }

    @Test
    fun exact_current_mutation_can_have_only_one_active_claim() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-claim-single"))
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership

        val first = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim
        val second = assertIs<LearningApplicationMutationClaimResult.Rejected>(
            composition.claim(reference(ownership))
        )

        assertSame(ownership.plan, first.plan)
        assertEquals(reference(ownership), first.reference)
        assertEquals(LearningApplicationMutationClaimRejection.ALREADY_CLAIMED, second.reason)
    }

    @Test
    fun active_claim_blocks_mutation_removal_until_release() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-claim-remove"))
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim

        assertFalse(ownership.remove())
        assertTrue(composition.contains(ownership.plan.id))
        assertTrue(claim.release())
        assertTrue(ownership.remove())
        assertFalse(composition.contains(ownership.plan.id))
    }

    @Test
    fun released_claim_cannot_release_again_and_new_claim_can_be_acquired() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-claim-release"))
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership
        val first = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim

        assertTrue(first.release())
        assertFalse(first.release())

        val second = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim
        assertEquals(reference(ownership), second.reference)
        assertTrue(second.release())
    }

    @Test
    fun stale_generation_cannot_claim_current_mutation() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-claim-stale"))
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership

        val result = assertIs<LearningApplicationMutationClaimResult.Rejected>(
            composition.claim(
                LearningApplicationMutationReference(
                    ownership.plan.id,
                    LearningApplicationMutationGeneration(ownership.generation.value + 1L)
                )
            )
        )

        assertEquals(LearningApplicationMutationClaimRejection.MUTATION_GENERATION_MISMATCH, result.reason)
    }

    @Test
    fun exact_claim_completion_removes_prepared_mutation_and_retains_structural_outcome() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-complete"))
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(plan())
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim
        val completedReceipt = receipt(ownership)

        assertTrue(claim.complete(completedReceipt))
        assertFalse(composition.contains(ownership.plan.id))
        assertNull(composition.findByIdempotencyKey(ownership.plan.idempotencyKey))
        assertTrue(composition.isCompletedIdempotencyKey(ownership.plan.idempotencyKey))
        assertEquals(completedReceipt, composition.completedOutcomeByMutationId(ownership.plan.id))
        assertEquals(completedReceipt, composition.completedOutcomeByIdempotencyKey(ownership.plan.idempotencyKey))
        assertFalse(claim.complete(completedReceipt))
        assertFalse(claim.release())
    }

    @Test
    fun exact_same_completed_plan_replays_previous_outcome_without_new_preparation() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-complete-replay"))
        val value = plan(id = "mutation-replay", key = "completed-replay-key")
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(value)
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim
        val completedReceipt = receipt(ownership)
        assertTrue(claim.complete(completedReceipt))

        val equalReplay = plan(id = "mutation-replay", key = "completed-replay-key")
        val replay = assertIs<LearningApplicationMutationPrepareResult.AlreadyCompleted>(
            composition.prepare(equalReplay)
        )

        assertEquals(completedReceipt, replay.receipt)
        assertFalse(composition.contains(value.id))
        assertEquals(completedReceipt, composition.completedOutcomeByIdempotencyKey(value.idempotencyKey))
    }

    @Test
    fun completed_idempotency_key_cannot_alias_a_different_plan() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-complete-idempotency"))
        val first = plan(id = "mutation-a", key = "completed-key")
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(first)
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim
        assertTrue(claim.complete(receipt(ownership)))

        val retry = plan(
            id = "mutation-b",
            key = "completed-key",
            applicationGeneration = 2L
        )
        assertIs<LearningApplicationMutationPrepareResult.Rejected>(composition.prepare(retry))
        assertFalse(composition.contains(retry.id))
        assertTrue(composition.isCompletedIdempotencyKey(retry.idempotencyKey))
    }

    @Test
    fun completed_mutation_id_cannot_alias_a_different_idempotency_key() {
        val composition = LearningApplicationMutationComposition(foundation("mutation-complete-id"))
        val first = plan(id = "completed-id", key = "first-key")
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            composition.prepare(first)
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            composition.claim(reference(ownership))
        ).claim
        assertTrue(claim.complete(receipt(ownership)))

        val retry = plan(
            id = "completed-id",
            key = "different-key",
            applicationGeneration = 2L
        )
        assertIs<LearningApplicationMutationPrepareResult.Rejected>(composition.prepare(retry))
        assertFalse(composition.contains(retry.id))
        assertEquals(receipt(ownership), composition.completedOutcomeByMutationId(retry.id))
    }
}
