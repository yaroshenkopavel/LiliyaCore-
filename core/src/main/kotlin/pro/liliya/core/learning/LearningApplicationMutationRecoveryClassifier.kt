package pro.liliya.core.learning

import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot

enum class LearningApplicationMutationRecoveryClassification {
    NO_DOWNSTREAM,
    EXACT_DOWNSTREAM_PRESENT,
    MISMATCHED_DOWNSTREAM_PRESENT
}

data class LearningApplicationMutationRecoverySummary(
    val noDownstream: Int,
    val exactDownstreamPresent: Int,
    val mismatchedDownstreamPresent: Int
) {
    init {
        require(noDownstream >= 0) { "no-downstream recovery count must not be negative" }
        require(exactDownstreamPresent >= 0) { "exact-downstream recovery count must not be negative" }
        require(mismatchedDownstreamPresent >= 0) {
            "mismatched-downstream recovery count must not be negative"
        }
    }

    val totalPrepared: Int
        get() = noDownstream + exactDownstreamPresent + mismatchedDownstreamPresent

    val requiresRecovery: Boolean
        get() = totalPrepared != 0

    companion object {
        val Clean = LearningApplicationMutationRecoverySummary(
            noDownstream = 0,
            exactDownstreamPresent = 0,
            mismatchedDownstreamPresent = 0
        )
    }
}

sealed interface LearningApplicationMutationRecoveryClassificationResult {
    data class Classified(
        val summary: LearningApplicationMutationRecoverySummary
    ) : LearningApplicationMutationRecoveryClassificationResult

    data object Failed : LearningApplicationMutationRecoveryClassificationResult
}

/**
 * Read-only classifier for restored Prepared governed-learning mutations.
 *
 * This owner deliberately has no mutation/remove/complete/Authority ports. It can only compare the
 * exact payload carried by each live mutation snapshot with authoritative downstream snapshots.
 * Classification is therefore evidence only; it never performs recovery.
 */
object LearningApplicationMutationRecoveryClassifier {
    fun classify(
        preparedMutations: List<LearningApplicationMutationSnapshot>,
        inspectMemory: (MemoryRecordId) -> MemoryRecordSnapshot?,
        inspectKnowledge: (KnowledgeItemId) -> KnowledgeItemSnapshot?
    ): LearningApplicationMutationRecoveryClassificationResult = try {
        var noDownstream = 0
        var exactDownstream = 0
        var mismatchedDownstream = 0

        preparedMutations.forEach { snapshot ->
            when (val payload = snapshot.plan.payload) {
                is LearningApplicationMutationPayload.Memory -> {
                    when (val current = inspectMemory(payload.record.id)) {
                        null -> noDownstream += 1
                        else -> if (current.record == payload.record) {
                            exactDownstream += 1
                        } else {
                            mismatchedDownstream += 1
                        }
                    }
                }

                is LearningApplicationMutationPayload.Knowledge -> {
                    when (val current = inspectKnowledge(payload.item.id)) {
                        null -> noDownstream += 1
                        else -> if (current.item == payload.item) {
                            exactDownstream += 1
                        } else {
                            mismatchedDownstream += 1
                        }
                    }
                }
            }
        }

        LearningApplicationMutationRecoveryClassificationResult.Classified(
            LearningApplicationMutationRecoverySummary(
                noDownstream = noDownstream,
                exactDownstreamPresent = exactDownstream,
                mismatchedDownstreamPresent = mismatchedDownstream
            )
        )
    } catch (_: Exception) {
        LearningApplicationMutationRecoveryClassificationResult.Failed
    }
}
