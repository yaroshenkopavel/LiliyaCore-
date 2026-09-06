package pro.liliya.android.runtime

import pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSyncResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSynchronizer
import pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.learning.LearningApplicationDownstreamReference
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition

enum class AndroidHeartSemanticLearningSyncStatus {
    NOT_APPLICABLE,
    SYNCHRONIZED,
    REBUILD_REQUIRED
}

data class AndroidHeartGovernedLearningResult(
    val governed: CognitiveGovernedLearningResult,
    val semanticSync: AndroidHeartSemanticLearningSyncStatus
)

/**
 * Android orchestration around the existing Core governed-learning composition.
 *
 * Core remains authoritative for governance/Authority and durable mutation outcome. This wrapper
 * only projects a successfully committed new Memory/Knowledge snapshot into the derivative semantic
 * index. A semantic failure never rolls back the committed authoritative mutation.
 */
class AndroidHeartGovernedLearningComposition internal constructor(
    private val governed: CognitiveGovernedLearningComposition,
    private val memory: EncryptedPersistentMemoryComposition,
    private val knowledge: EncryptedPersistentKnowledgeComposition,
    private val semantic: AndroidOfflineSemanticMutationSynchronizer,
    private val onSemanticUnavailable: () -> Unit
) {
    fun process(reference: CognitiveLearningReference): AndroidHeartGovernedLearningResult {
        val result = governed.process(reference)
        if (result !is CognitiveGovernedLearningResult.Applied) {
            return AndroidHeartGovernedLearningResult(
                governed = result,
                semanticSync = AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE
            )
        }

        val sync = when (val downstream = result.receipt.downstream) {
            is LearningApplicationDownstreamReference.Memory -> {
                val snapshot = memory.inspect(downstream.recordId)
                if (snapshot == null || snapshot.generation != downstream.generation) {
                    null
                } else {
                    semantic.addMemory(snapshot)
                }
            }

            is LearningApplicationDownstreamReference.Knowledge -> {
                val snapshot = knowledge.inspect(downstream.itemId)
                if (snapshot == null || snapshot.generation != downstream.generation) {
                    null
                } else {
                    semantic.addKnowledge(snapshot)
                }
            }
        }

        return when (sync) {
            AndroidOfflineSemanticMutationSyncResult.Synchronized,
            AndroidOfflineSemanticMutationSyncResult.AlreadySynchronized ->
                AndroidHeartGovernedLearningResult(
                    governed = result,
                    semanticSync = AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED
                )

            AndroidOfflineSemanticMutationSyncResult.RebuildRequired,
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            null -> {
                onSemanticUnavailable()
                AndroidHeartGovernedLearningResult(
                    governed = result,
                    semanticSync = AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED
                )
            }
        }
    }
}
