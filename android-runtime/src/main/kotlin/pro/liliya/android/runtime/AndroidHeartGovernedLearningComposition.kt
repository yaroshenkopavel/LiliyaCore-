package pro.liliya.android.runtime

import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveLearningReference

enum class AndroidHeartSemanticLearningSyncStatus {
    NOT_APPLICABLE,
    SYNCHRONIZED,
    REBUILD_REQUIRED
}

data class AndroidHeartGovernedLearningResult(
    val governed: CognitiveGovernedLearningResult,
    val semanticSync: AndroidHeartSemanticLearningSyncStatus
)

internal fun interface AndroidHeartGovernedLearningPort {
    fun process(reference: CognitiveLearningReference): CognitiveGovernedLearningResult
}

internal fun interface AndroidHeartAppliedSemanticSyncPort {
    fun synchronize(
        applied: CognitiveGovernedLearningResult.Applied
    ): AndroidHeartSemanticLearningSyncStatus
}

/**
 * Android orchestration around the existing Core governed-learning composition.
 *
 * Core remains authoritative for governance/Authority and durable mutation outcome. This wrapper
 * only projects a successfully committed new Memory/Knowledge snapshot into the derivative semantic
 * index. A semantic failure never rolls back the committed authoritative mutation.
 */
class AndroidHeartGovernedLearningComposition internal constructor(
    private val governed: AndroidHeartGovernedLearningPort,
    private val semantic: AndroidHeartAppliedSemanticSyncPort,
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

        val sync = semantic.synchronize(result)
        if (sync == AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED) {
            onSemanticUnavailable()
        }
        return AndroidHeartGovernedLearningResult(
            governed = result,
            semanticSync = sync
        )
    }
}
