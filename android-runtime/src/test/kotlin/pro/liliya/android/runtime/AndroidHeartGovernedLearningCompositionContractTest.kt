package pro.liliya.android.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.learning.LearningApplicationDownstreamReference
import pro.liliya.core.learning.LearningApplicationGeneration
import pro.liliya.core.learning.LearningApplicationId
import pro.liliya.core.learning.LearningApplicationIntentReference
import pro.liliya.core.learning.LearningApplicationMutationApplicationReceipt
import pro.liliya.core.learning.LearningApplicationMutationGeneration
import pro.liliya.core.learning.LearningApplicationMutationId
import pro.liliya.core.learning.LearningApplicationMutationReference
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningDecisionGeneration
import pro.liliya.core.learning.LearningDecisionId
import pro.liliya.core.learning.LearningDecisionReference
import pro.liliya.core.learning.LearningGeneration
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class AndroidHeartGovernedLearningCompositionContractTest {

    @Test
    fun synchronized_authoritative_learning_keeps_semantic_path_available() {
        var unavailable = false
        val applied = applied()
        val composition = AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort { applied },
            semantic = AndroidHeartAppliedSemanticSyncPort {
                AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED
            },
            onSemanticUnavailable = { unavailable = true }
        )

        val result = composition.process(learningReference())

        assertEquals(applied, result.governed)
        assertEquals(
            AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED,
            result.semanticSync
        )
        assertEquals(false, unavailable)
    }

    @Test
    fun semantic_sync_failure_requires_rebuild_without_rewriting_core_applied_result() {
        var unavailable = false
        val applied = applied()
        val composition = AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort { applied },
            semantic = AndroidHeartAppliedSemanticSyncPort {
                AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED
            },
            onSemanticUnavailable = { unavailable = true }
        )

        val result = composition.process(learningReference())

        assertEquals(applied, result.governed)
        assertEquals(
            AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED,
            result.semanticSync
        )
        assertTrue(unavailable)
    }

    private fun learningReference() = CognitiveLearningReference(
        LearningCandidateId("heart-learning-candidate"),
        LearningGeneration(1)
    )

    private fun applied(): CognitiveGovernedLearningResult.Applied {
        val mutation = LearningApplicationMutationReference(
            LearningApplicationMutationId("heart-learning-mutation"),
            LearningApplicationMutationGeneration(1)
        )
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = mutation,
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("heart-learned-memory"),
                generation = MemoryGeneration(1)
            )
        )
        return CognitiveGovernedLearningResult.Applied(
            decision = LearningDecisionReference(
                LearningDecisionId("heart-learning-decision"),
                LearningDecisionGeneration(1)
            ),
            application = LearningApplicationIntentReference(
                LearningApplicationId("heart-learning-application"),
                LearningApplicationGeneration(1)
            ),
            mutation = mutation,
            receipt = receipt
        )
    }
}
