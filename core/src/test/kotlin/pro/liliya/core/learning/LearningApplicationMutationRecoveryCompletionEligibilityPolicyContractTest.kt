package pro.liliya.core.learning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference

class LearningApplicationMutationRecoveryCompletionEligibilityPolicyContractTest {

    @Test
    fun exact_prepared_mutation_and_exact_downstream_are_eligible_without_side_effects() {
        val prepared = memorySnapshot("eligible", "memory-eligible", generation = 4)
        val evidence = memoryEvidence(prepared, downstreamGeneration = 9)

        val result = assertIs<LearningApplicationMutationRecoveryCompletionEligibilityResult.Eligible>(
            LearningApplicationMutationRecoveryCompletionEligibilityPolicy.evaluate(
                evidence = evidence,
                preparedMutation = prepared,
                completedReceipt = null
            )
        )

        assertSame(evidence, result.evidence)
        assertEquals(LearningApplicationMutationGeneration(4), prepared.generation)
        assertEquals(LearningApplicationMutationId("mutation-eligible"), prepared.plan.id)
    }

    @Test
    fun missing_or_wrong_exact_prepared_identity_is_rejected() {
        val prepared = memorySnapshot("prepared", "memory-prepared", generation = 3)
        val evidence = memoryEvidence(prepared, downstreamGeneration = 7)

        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.MUTATION_MISSING,
            evidence,
            prepared = null
        )
        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.MUTATION_ID_MISMATCH,
            evidence,
            prepared = memorySnapshot("other", "memory-prepared", generation = 3)
        )
        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.MUTATION_GENERATION_MISMATCH,
            evidence,
            prepared = memorySnapshot("prepared", "memory-prepared", generation = 4)
        )
    }

    @Test
    fun target_or_downstream_identity_mismatch_is_rejected() {
        val prepared = memorySnapshot("identity", "memory-identity", generation = 2)
        val exact = memoryEvidence(prepared, downstreamGeneration = 5)
        val wrongTarget = LearningApplicationMutationExactRecoveryEvidence(
            mutation = exact.mutation,
            target = LearningApplicationTarget.KNOWLEDGE,
            downstream = LearningApplicationDownstreamReference.Knowledge(
                itemId = pro.liliya.core.knowledge.KnowledgeItemId("knowledge-other"),
                generation = pro.liliya.core.knowledge.KnowledgeGeneration(1)
            )
        )
        val wrongDownstream = exact.copy(
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("memory-other"),
                generation = MemoryGeneration(5)
            )
        )

        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.TARGET_MISMATCH,
            wrongTarget,
            prepared
        )
        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.DOWNSTREAM_ID_MISMATCH,
            wrongDownstream,
            prepared
        )
    }

    @Test
    fun any_completed_state_blocks_recovery_completion_eligibility() {
        val prepared = memorySnapshot("completed", "memory-completed", generation = 6)
        val evidence = memoryEvidence(prepared, downstreamGeneration = 8)
        val exactReceipt = LearningApplicationMutationApplicationReceipt(
            mutation = evidence.mutation,
            target = evidence.target,
            downstream = evidence.downstream
        )
        val unrelatedReceipt = exactReceipt.copy(
            mutation = LearningApplicationMutationReference(
                LearningApplicationMutationId("mutation-unrelated"),
                LearningApplicationMutationGeneration(1)
            )
        )

        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.ALREADY_COMPLETED,
            evidence,
            prepared,
            exactReceipt
        )
        assertRejected(
            LearningApplicationMutationRecoveryCompletionRejection.COMPLETED_STATE_MISMATCH,
            evidence,
            prepared,
            unrelatedReceipt
        )
    }

    private fun assertRejected(
        expected: LearningApplicationMutationRecoveryCompletionRejection,
        evidence: LearningApplicationMutationExactRecoveryEvidence,
        prepared: LearningApplicationMutationSnapshot?,
        completed: LearningApplicationMutationApplicationReceipt? = null
    ) {
        val result = assertIs<LearningApplicationMutationRecoveryCompletionEligibilityResult.Rejected>(
            LearningApplicationMutationRecoveryCompletionEligibilityPolicy.evaluate(
                evidence = evidence,
                preparedMutation = prepared,
                completedReceipt = completed
            )
        )
        assertEquals(expected, result.reason)
    }

    private fun memoryEvidence(
        prepared: LearningApplicationMutationSnapshot,
        downstreamGeneration: Long
    ): LearningApplicationMutationExactRecoveryEvidence {
        val record = (prepared.plan.payload as LearningApplicationMutationPayload.Memory).record
        return LearningApplicationMutationExactRecoveryEvidence(
            mutation = LearningApplicationMutationReference(prepared.plan.id, prepared.generation),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = record.id,
                generation = MemoryGeneration(downstreamGeneration)
            )
        )
    }

    private fun memorySnapshot(
        suffix: String,
        recordId: String,
        generation: Long
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T18:00:00Z")
        return LearningApplicationMutationSnapshot(
            plan = LearningApplicationMutationPlan(
                id = LearningApplicationMutationId("mutation-$suffix"),
                application = LearningApplicationIntentReference(
                    applicationId = LearningApplicationId("application-$suffix"),
                    generation = LearningApplicationGeneration(1)
                ),
                principal = AuthorityPrincipal("learning-recovery-completion-policy-test"),
                target = LearningApplicationTarget.MEMORY,
                idempotencyKey = LearningApplicationIdempotencyKey("idempotency-$suffix"),
                payload = LearningApplicationMutationPayload.Memory(
                    MemoryRecord(
                        id = MemoryRecordId(recordId),
                        provenance = MemoryProvenance(
                            sourceId = MemorySourceId("cognitive-runtime-learning"),
                            sourceReference = MemorySourceReference("learning-$suffix")
                        ),
                        content = "payload-$suffix",
                        createdAt = createdAt
                    )
                ),
                createdAt = createdAt
            ),
            generation = LearningApplicationMutationGeneration(generation)
        )
    }
}
