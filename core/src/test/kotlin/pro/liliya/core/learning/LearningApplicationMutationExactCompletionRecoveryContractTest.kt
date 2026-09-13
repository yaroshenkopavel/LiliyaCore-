package pro.liliya.core.learning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference

class LearningApplicationMutationExactCompletionRecoveryContractTest {

    @Test
    fun exact_memory_and_knowledge_complete_without_downstream_write_or_authority() {
        val memory = memorySnapshot("memory", "memory-1")
        val knowledge = knowledgeSnapshot("knowledge", "knowledge-1")
        val memoryRecord = (memory.plan.payload as LearningApplicationMutationPayload.Memory).record
        val knowledgeItem = (knowledge.plan.payload as LearningApplicationMutationPayload.Knowledge).item
        val evidence = listOf(
            memoryEvidence(memory, 7),
            knowledgeEvidence(knowledge, 11)
        )
        val claims = FakeClaimPort(listOf(memory, knowledge))
        var memoryInspections = 0
        var knowledgeInspections = 0

        val result = LearningApplicationMutationExactCompletionRecovery(
            mutations = claims,
            preparedMutations = { listOf(memory, knowledge) },
            classifyEvidence = evidencePort(exact = evidence),
            inspectMemory = {
                memoryInspections += 1
                MemoryRecordSnapshot(memoryRecord, MemoryGeneration(7))
            },
            inspectKnowledge = {
                knowledgeInspections += 1
                KnowledgeItemSnapshot(knowledgeItem, KnowledgeGeneration(11))
            }
        ).recover()

        val completed = assertIs<LearningApplicationMutationExactCompletionRecoveryResult.Completed>(result)
        assertEquals(2, completed.receipts.size)
        assertEquals(2, claims.completedReceipts.size)
        assertEquals(0, claims.releaseCount)
        assertEquals(2, memoryInspections)
        assertEquals(2, knowledgeInspections)
    }

    @Test
    fun no_downstream_recovery_never_claims_and_requires_fresh_authority_path() {
        val prepared = memorySnapshot("absent", "memory-absent")
        val claims = FakeClaimPort(listOf(prepared))

        val result = LearningApplicationMutationExactCompletionRecovery(
            mutations = claims,
            preparedMutations = { listOf(prepared) },
            classifyEvidence = evidencePort(noDownstream = 1),
            inspectMemory = { error("completion recovery must not inspect blocked replay path") },
            inspectKnowledge = { error("completion recovery must not inspect blocked replay path") }
        ).recover()

        val blocked = assertIs<LearningApplicationMutationExactCompletionRecoveryResult.Blocked>(result)
        assertEquals(
            LearningApplicationMutationRecoveryCompletionEligibilityDecision.REPLAY_REQUIRES_FRESH_AUTHORITY,
            blocked.decision
        )
        assertEquals(0, claims.claimCount)
    }

    @Test
    fun downstream_generation_change_after_classification_fails_closed_before_claim() {
        val prepared = memorySnapshot("stale", "memory-stale")
        val record = (prepared.plan.payload as LearningApplicationMutationPayload.Memory).record
        val claims = FakeClaimPort(listOf(prepared))

        val result = LearningApplicationMutationExactCompletionRecovery(
            mutations = claims,
            preparedMutations = { listOf(prepared) },
            classifyEvidence = evidencePort(exact = listOf(memoryEvidence(prepared, 4))),
            inspectMemory = { MemoryRecordSnapshot(record, MemoryGeneration(5)) },
            inspectKnowledge = { null }
        ).recover()

        assertIs<LearningApplicationMutationExactCompletionRecoveryResult.StaleEvidence>(result)
        assertEquals(0, claims.claimCount)
        assertEquals(0, claims.completedReceipts.size)
    }

    @Test
    fun downstream_change_after_claim_releases_claim_and_never_completes() {
        val prepared = memorySnapshot("race", "memory-race")
        val record = (prepared.plan.payload as LearningApplicationMutationPayload.Memory).record
        val claims = FakeClaimPort(listOf(prepared))
        var inspection = 0

        val result = LearningApplicationMutationExactCompletionRecovery(
            mutations = claims,
            preparedMutations = { listOf(prepared) },
            classifyEvidence = evidencePort(exact = listOf(memoryEvidence(prepared, 6))),
            inspectMemory = {
                inspection += 1
                MemoryRecordSnapshot(
                    if (inspection == 1) record else record.copy(content = "changed"),
                    MemoryGeneration(6)
                )
            },
            inspectKnowledge = { null }
        ).recover()

        assertIs<LearningApplicationMutationExactCompletionRecoveryResult.StaleEvidence>(result)
        assertEquals(1, claims.claimCount)
        assertEquals(1, claims.releaseCount)
        assertEquals(0, claims.completedReceipts.size)
    }

    @Test
    fun completion_failure_stops_batch_without_touching_later_mutation() {
        val first = memorySnapshot("first", "memory-first")
        val second = knowledgeSnapshot("second", "knowledge-second")
        val firstRecord = (first.plan.payload as LearningApplicationMutationPayload.Memory).record
        val secondItem = (second.plan.payload as LearningApplicationMutationPayload.Knowledge).item
        val claims = FakeClaimPort(listOf(first, second)).apply {
            failCompletionFor = referenceOf(first)
        }

        val result = LearningApplicationMutationExactCompletionRecovery(
            mutations = claims,
            preparedMutations = { listOf(first, second) },
            classifyEvidence = evidencePort(
                exact = listOf(memoryEvidence(first, 3), knowledgeEvidence(second, 5))
            ),
            inspectMemory = { MemoryRecordSnapshot(firstRecord, MemoryGeneration(3)) },
            inspectKnowledge = { KnowledgeItemSnapshot(secondItem, KnowledgeGeneration(5)) }
        ).recover()

        val failed = assertIs<LearningApplicationMutationExactCompletionRecoveryResult.CompletionFailed>(result)
        assertEquals(0, failed.completedCount)
        assertEquals(1, claims.claimCount)
        assertEquals(1, claims.releaseCount)
        assertEquals(0, claims.completedReceipts.size)
    }

    @Test
    fun evidence_failure_is_blocked_without_claiming() {
        val prepared = memorySnapshot("failed", "memory-failed")
        val claims = FakeClaimPort(listOf(prepared))
        val result = LearningApplicationMutationExactCompletionRecovery(
            mutations = claims,
            preparedMutations = { listOf(prepared) },
            classifyEvidence = LearningApplicationMutationRecoveryEvidencePort {
                LearningApplicationMutationRecoveryEvidenceResult.Failed
            },
            inspectMemory = { null },
            inspectKnowledge = { null }
        ).recover()

        val blocked = assertIs<LearningApplicationMutationExactCompletionRecoveryResult.Blocked>(result)
        assertEquals(
            LearningApplicationMutationRecoveryCompletionEligibilityDecision.EVIDENCE_FAILED,
            blocked.decision
        )
        assertEquals(0, claims.claimCount)
    }

    private fun evidencePort(
        exact: List<LearningApplicationMutationExactRecoveryEvidence> = emptyList(),
        noDownstream: Int = 0,
        mismatched: Int = 0
    ) = LearningApplicationMutationRecoveryEvidencePort {
        LearningApplicationMutationRecoveryEvidenceResult.Classified(
            LearningApplicationMutationRecoveryEvidenceSummary(
                exactDownstream = exact,
                noDownstream = noDownstream,
                mismatchedDownstreamPresent = mismatched
            )
        )
    }

    private class FakeClaimPort(
        snapshots: List<LearningApplicationMutationSnapshot>
    ) : PersistentLearningApplicationMutationClaimPort {
        private val byReference = snapshots.associateBy {
            LearningApplicationMutationReference(it.plan.id, it.generation)
        }
        var failCompletionFor: LearningApplicationMutationReference? = null
        var claimCount = 0
        var releaseCount = 0
        val completedReceipts = mutableListOf<LearningApplicationMutationApplicationReceipt>()

        override fun claim(
            reference: LearningApplicationMutationReference
        ): PersistentLearningApplicationMutationClaimResult {
            claimCount += 1
            val snapshot = byReference[reference]
                ?: return PersistentLearningApplicationMutationClaimResult.Rejected(
                    LearningApplicationMutationClaimRejection.MUTATION_MISSING
                )
            return PersistentLearningApplicationMutationClaimResult.Claimed(
                PersistentLearningApplicationMutationClaim(
                    plan = snapshot.plan,
                    reference = reference,
                    releaseAction = {
                        releaseCount += 1
                        true
                    },
                    completeAction = { receipt ->
                        if (reference == failCompletionFor) {
                            PersistentLearningApplicationMutationResult.Rejected(
                                "simulated exact completion rejection"
                            )
                        } else {
                            completedReceipts += receipt
                            PersistentLearningApplicationMutationResult.Committed
                        }
                    }
                )
            )
        }
    }

    private fun memoryEvidence(
        snapshot: LearningApplicationMutationSnapshot,
        generation: Long
    ): LearningApplicationMutationExactRecoveryEvidence {
        val record = (snapshot.plan.payload as LearningApplicationMutationPayload.Memory).record
        return LearningApplicationMutationExactRecoveryEvidence(
            mutation = referenceOf(snapshot),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                record.id,
                MemoryGeneration(generation)
            )
        )
    }

    private fun knowledgeEvidence(
        snapshot: LearningApplicationMutationSnapshot,
        generation: Long
    ): LearningApplicationMutationExactRecoveryEvidence {
        val item = (snapshot.plan.payload as LearningApplicationMutationPayload.Knowledge).item
        return LearningApplicationMutationExactRecoveryEvidence(
            mutation = referenceOf(snapshot),
            target = LearningApplicationTarget.KNOWLEDGE,
            downstream = LearningApplicationDownstreamReference.Knowledge(
                item.id,
                KnowledgeGeneration(generation)
            )
        )
    }

    private fun referenceOf(
        snapshot: LearningApplicationMutationSnapshot
    ): LearningApplicationMutationReference =
        LearningApplicationMutationReference(snapshot.plan.id, snapshot.generation)

    private fun memorySnapshot(
        suffix: String,
        recordId: String,
        generation: Long = 1
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T19:00:00Z")
        return snapshot(
            suffix = suffix,
            target = LearningApplicationTarget.MEMORY,
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId(recordId),
                    provenance = MemoryProvenance(
                        MemorySourceId("cognitive-runtime-learning"),
                        MemorySourceReference("learning-$suffix")
                    ),
                    content = "payload-$suffix",
                    createdAt = createdAt
                )
            ),
            generation = generation,
            createdAt = createdAt
        )
    }

    private fun knowledgeSnapshot(
        suffix: String,
        itemId: String,
        generation: Long = 1
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T19:00:00Z")
        return snapshot(
            suffix = suffix,
            target = LearningApplicationTarget.KNOWLEDGE,
            payload = LearningApplicationMutationPayload.Knowledge(
                KnowledgeItem(
                    id = KnowledgeItemId(itemId),
                    origin = KnowledgeOrigin.Declared(
                        KnowledgeSourceId("cognitive-runtime-learning"),
                        KnowledgeSourceReference("learning-$suffix")
                    ),
                    content = "payload-$suffix",
                    createdAt = createdAt
                )
            ),
            generation = generation,
            createdAt = createdAt
        )
    }

    private fun snapshot(
        suffix: String,
        target: LearningApplicationTarget,
        payload: LearningApplicationMutationPayload,
        generation: Long,
        createdAt: Instant
    ): LearningApplicationMutationSnapshot = LearningApplicationMutationSnapshot(
        plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-$suffix"),
            application = LearningApplicationIntentReference(
                LearningApplicationId("application-$suffix"),
                LearningApplicationGeneration(1)
            ),
            principal = AuthorityPrincipal("learning-recovery-executor-test"),
            target = target,
            idempotencyKey = LearningApplicationIdempotencyKey("idempotency-$suffix"),
            payload = payload,
            createdAt = createdAt
        ),
        generation = LearningApplicationMutationGeneration(generation)
    )
}
