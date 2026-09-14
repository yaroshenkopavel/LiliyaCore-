package pro.liliya.core.learning

import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot

sealed interface LearningApplicationMutationExactCompletionRecoveryResult {
    data object NoRecoveryRequired : LearningApplicationMutationExactCompletionRecoveryResult

    data class Completed(
        val receipts: List<LearningApplicationMutationApplicationReceipt>
    ) : LearningApplicationMutationExactCompletionRecoveryResult

    data class Blocked(
        val decision: LearningApplicationMutationRecoveryCompletionEligibilityDecision
    ) : LearningApplicationMutationExactCompletionRecoveryResult

    data class StaleEvidence(
        val completedCount: Int
    ) : LearningApplicationMutationExactCompletionRecoveryResult

    data class CompletionFailed(
        val completedCount: Int
    ) : LearningApplicationMutationExactCompletionRecoveryResult
}

/** Narrow production capability for the bounded P2 exact-completion recovery action. */
fun interface LearningApplicationMutationExactCompletionRecoveryPort {
    fun recoverExactCompletion(): LearningApplicationMutationExactCompletionRecoveryResult
}

/**
 * P2 exact-downstream recovery executor.
 *
 * This owner never creates, updates, removes, compensates, or replays a downstream object. It may
 * only finalize a restored Prepared mutation after the complete current Prepared set is classified
 * as exact-completion eligible and the exact downstream payload + generation are revalidated.
 *
 * Recovery completion != fresh mutation execution.
 * Recovery completion != Authority minting or replay authority.
 */
class LearningApplicationMutationExactCompletionRecovery(
    private val mutations: PersistentLearningApplicationMutationClaimPort,
    private val preparedMutations: () -> List<LearningApplicationMutationSnapshot>,
    private val classifyEvidence: LearningApplicationMutationRecoveryEvidencePort,
    private val inspectMemory: (MemoryRecordId) -> MemoryRecordSnapshot?,
    private val inspectKnowledge: (KnowledgeItemId) -> KnowledgeItemSnapshot?
) {
    constructor(
        mutations: EncryptedPersistentLearningApplicationMutationComposition,
        memory: EncryptedPersistentMemoryComposition,
        knowledge: EncryptedPersistentKnowledgeComposition
    ) : this(
        mutations = mutations.claimPort(),
        preparedMutations = mutations::snapshotEntries,
        classifyEvidence = LearningApplicationMutationRecoveryEvidencePort {
            LearningApplicationMutationRecoveryEvidenceClassifier.classify(
                preparedMutations = mutations.snapshotEntries(),
                inspectMemory = memory::inspect,
                inspectKnowledge = knowledge::inspect
            )
        },
        inspectMemory = memory::inspect,
        inspectKnowledge = knowledge::inspect
    )

    fun recover(): LearningApplicationMutationExactCompletionRecoveryResult {
        val prepared = try {
            preparedMutations()
        } catch (_: Exception) {
            return LearningApplicationMutationExactCompletionRecoveryResult.Blocked(
                LearningApplicationMutationRecoveryCompletionEligibilityDecision.EVIDENCE_FAILED
            )
        }

        val evidenceResult = try {
            classifyEvidence.classifyPreparedMutations()
        } catch (_: Exception) {
            LearningApplicationMutationRecoveryEvidenceResult.Failed
        }

        val decision = LearningApplicationMutationRecoveryCompletionEligibilityPolicy.decide(
            preparedMutations = prepared,
            evidence = evidenceResult
        )
        when (decision) {
            LearningApplicationMutationRecoveryCompletionEligibilityDecision.NO_RECOVERY_REQUIRED ->
                return LearningApplicationMutationExactCompletionRecoveryResult.NoRecoveryRequired

            LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EXACT_COMPLETION_EVIDENCE_READY -> Unit

            else -> return LearningApplicationMutationExactCompletionRecoveryResult.Blocked(decision)
        }

        val classified = evidenceResult as LearningApplicationMutationRecoveryEvidenceResult.Classified
        val preparedByReference = prepared.associateBy {
            LearningApplicationMutationReference(it.plan.id, it.generation)
        }
        val completed = mutableListOf<LearningApplicationMutationApplicationReceipt>()

        for (evidence in classified.summary.exactDownstream) {
            val snapshot = preparedByReference[evidence.mutation]
                ?: return LearningApplicationMutationExactCompletionRecoveryResult.StaleEvidence(
                    completed.size
                )
            if (!revalidate(snapshot, evidence)) {
                return LearningApplicationMutationExactCompletionRecoveryResult.StaleEvidence(
                    completed.size
                )
            }

            val claim = when (val claimed = try {
                mutations.claim(evidence.mutation)
            } catch (_: Exception) {
                null
            }) {
                is PersistentLearningApplicationMutationClaimResult.Claimed -> claimed.claim
                else -> return LearningApplicationMutationExactCompletionRecoveryResult
                    .CompletionFailed(completed.size)
            }

            // Revalidate once more after taking the exact mutation claim so evidence cannot be
            // accepted across a concurrent authoritative downstream change.
            if (!revalidate(snapshot, evidence)) {
                claim.release()
                return LearningApplicationMutationExactCompletionRecoveryResult.StaleEvidence(
                    completed.size
                )
            }

            val receipt = LearningApplicationMutationApplicationReceipt(
                mutation = evidence.mutation,
                target = evidence.target,
                downstream = evidence.downstream
            )
            val completion = try {
                claim.complete(receipt)
            } catch (_: Exception) {
                null
            }
            if (completion !is PersistentLearningApplicationMutationResult.Committed) {
                claim.release()
                return LearningApplicationMutationExactCompletionRecoveryResult.CompletionFailed(
                    completed.size
                )
            }
            completed += receipt
        }

        return LearningApplicationMutationExactCompletionRecoveryResult.Completed(completed.toList())
    }

    private fun revalidate(
        prepared: LearningApplicationMutationSnapshot,
        evidence: LearningApplicationMutationExactRecoveryEvidence
    ): Boolean {
        if (
            LearningApplicationMutationReference(prepared.plan.id, prepared.generation) !=
                evidence.mutation ||
            prepared.plan.target != evidence.target
        ) {
            return false
        }

        return try {
            when (val payload = prepared.plan.payload) {
                is LearningApplicationMutationPayload.Memory -> {
                    val downstream = evidence.downstream as?
                        LearningApplicationDownstreamReference.Memory ?: return false
                    val current = inspectMemory(payload.record.id) ?: return false
                    current.record == payload.record &&
                        current.record.id == downstream.recordId &&
                        current.generation == downstream.generation
                }

                is LearningApplicationMutationPayload.Knowledge -> {
                    val downstream = evidence.downstream as?
                        LearningApplicationDownstreamReference.Knowledge ?: return false
                    val current = inspectKnowledge(payload.item.id) ?: return false
                    current.item == payload.item &&
                        current.item.id == downstream.itemId &&
                        current.generation == downstream.generation
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
