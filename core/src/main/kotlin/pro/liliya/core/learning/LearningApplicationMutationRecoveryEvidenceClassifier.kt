package pro.liliya.core.learning

import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot

/**
 * Exact read-only evidence for one restored Prepared governed-learning mutation whose authoritative
 * downstream object fully matches the mutation payload.
 *
 * Evidence != completion authority.
 * Evidence != replay authority.
 * Evidence != recovery execution.
 */
data class LearningApplicationMutationExactRecoveryEvidence(
    val mutation: LearningApplicationMutationReference,
    val target: LearningApplicationTarget,
    val downstream: LearningApplicationDownstreamReference
) {
    init {
        require(
            when (target) {
                LearningApplicationTarget.MEMORY -> downstream is LearningApplicationDownstreamReference.Memory
                LearningApplicationTarget.KNOWLEDGE -> downstream is LearningApplicationDownstreamReference.Knowledge
            }
        ) { "learning recovery evidence downstream must match target" }
    }
}

data class LearningApplicationMutationRecoveryEvidenceSummary(
    val exactDownstream: List<LearningApplicationMutationExactRecoveryEvidence>,
    val noDownstream: Int,
    val mismatchedDownstreamPresent: Int
) {
    init {
        require(noDownstream >= 0) { "no-downstream recovery evidence count must not be negative" }
        require(mismatchedDownstreamPresent >= 0) {
            "mismatched-downstream recovery evidence count must not be negative"
        }
    }

    val totalPrepared: Int
        get() = exactDownstream.size + noDownstream + mismatchedDownstreamPresent

    val requiresRecovery: Boolean
        get() = totalPrepared != 0
}

sealed interface LearningApplicationMutationRecoveryEvidenceResult {
    data class Classified(
        val summary: LearningApplicationMutationRecoveryEvidenceSummary
    ) : LearningApplicationMutationRecoveryEvidenceResult

    data object Failed : LearningApplicationMutationRecoveryEvidenceResult
}

fun interface LearningApplicationMutationRecoveryEvidencePort {
    fun classifyPreparedMutations(): LearningApplicationMutationRecoveryEvidenceResult
}

/**
 * Read-only enrichment of P2.1 recovery classification with the exact mutation/downstream
 * generations needed by any later completion-policy gate.
 *
 * This owner deliberately exposes no mutation claim/complete/remove, downstream write, replay,
 * compensation, or Authority port. A matching payload produces evidence only.
 */
object LearningApplicationMutationRecoveryEvidenceClassifier {
    fun classify(
        preparedMutations: List<LearningApplicationMutationSnapshot>,
        inspectMemory: (MemoryRecordId) -> MemoryRecordSnapshot?,
        inspectKnowledge: (KnowledgeItemId) -> KnowledgeItemSnapshot?
    ): LearningApplicationMutationRecoveryEvidenceResult = try {
        val exact = mutableListOf<LearningApplicationMutationExactRecoveryEvidence>()
        var noDownstream = 0
        var mismatchedDownstream = 0

        preparedMutations.forEach { snapshot ->
            val mutation = LearningApplicationMutationReference(
                mutationId = snapshot.plan.id,
                generation = snapshot.generation
            )
            when (val payload = snapshot.plan.payload) {
                is LearningApplicationMutationPayload.Memory -> {
                    when (val current = inspectMemory(payload.record.id)) {
                        null -> noDownstream += 1
                        else -> if (current.record == payload.record) {
                            exact += LearningApplicationMutationExactRecoveryEvidence(
                                mutation = mutation,
                                target = LearningApplicationTarget.MEMORY,
                                downstream = LearningApplicationDownstreamReference.Memory(
                                    recordId = current.record.id,
                                    generation = current.generation
                                )
                            )
                        } else {
                            mismatchedDownstream += 1
                        }
                    }
                }

                is LearningApplicationMutationPayload.Knowledge -> {
                    when (val current = inspectKnowledge(payload.item.id)) {
                        null -> noDownstream += 1
                        else -> if (current.item == payload.item) {
                            exact += LearningApplicationMutationExactRecoveryEvidence(
                                mutation = mutation,
                                target = LearningApplicationTarget.KNOWLEDGE,
                                downstream = LearningApplicationDownstreamReference.Knowledge(
                                    itemId = current.item.id,
                                    generation = current.generation
                                )
                            )
                        } else {
                            mismatchedDownstream += 1
                        }
                    }
                }
            }
        }

        LearningApplicationMutationRecoveryEvidenceResult.Classified(
            LearningApplicationMutationRecoveryEvidenceSummary(
                exactDownstream = exact.toList(),
                noDownstream = noDownstream,
                mismatchedDownstreamPresent = mismatchedDownstream
            )
        )
    } catch (_: Exception) {
        LearningApplicationMutationRecoveryEvidenceResult.Failed
    }
}
