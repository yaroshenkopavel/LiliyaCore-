package pro.liliya.android.semanticprovider

import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordSnapshot

/**
 * Public post-commit synchronization boundary between authoritative Memory/Knowledge and the
 * derived semantic index.
 *
 * Callers must invoke these methods only after the authoritative mutation has committed.
 * Semantic synchronization never authorizes, rolls back, rewrites or otherwise controls the
 * authoritative mutation.
 */
class AndroidOfflineSemanticMutationSynchronizer internal constructor(
    private val assembly: AndroidOfflineSemanticProviderAssembly
) {
    fun addMemory(
        committed: MemoryRecordSnapshot
    ): AndroidOfflineSemanticMutationSyncResult =
        map(
            assembly.synchronizeAdd(
                SemanticSourceObservation(
                    source = SemanticIndexSourceReference.Memory(
                        id = committed.record.id,
                        generation = committed.generation
                    ),
                    content = committed.record.content
                )
            )
        )

    fun addKnowledge(
        committed: KnowledgeItemSnapshot
    ): AndroidOfflineSemanticMutationSyncResult =
        map(
            assembly.synchronizeAdd(
                SemanticSourceObservation(
                    source = SemanticIndexSourceReference.Knowledge(
                        id = committed.item.id,
                        generation = committed.generation
                    ),
                    content = committed.item.content
                )
            )
        )

    fun replaceMemory(
        expectedPrevious: MemoryRecordSnapshot,
        committedReplacement: MemoryRecordSnapshot
    ): AndroidOfflineSemanticMutationSyncResult =
        map(
            assembly.synchronizeReplace(
                expected = SemanticIndexSourceReference.Memory(
                    id = expectedPrevious.record.id,
                    generation = expectedPrevious.generation
                ),
                replacement = SemanticSourceObservation(
                    source = SemanticIndexSourceReference.Memory(
                        id = committedReplacement.record.id,
                        generation = committedReplacement.generation
                    ),
                    content = committedReplacement.record.content
                )
            )
        )

    fun replaceKnowledge(
        expectedPrevious: KnowledgeItemSnapshot,
        committedReplacement: KnowledgeItemSnapshot
    ): AndroidOfflineSemanticMutationSyncResult =
        map(
            assembly.synchronizeReplace(
                expected = SemanticIndexSourceReference.Knowledge(
                    id = expectedPrevious.item.id,
                    generation = expectedPrevious.generation
                ),
                replacement = SemanticSourceObservation(
                    source = SemanticIndexSourceReference.Knowledge(
                        id = committedReplacement.item.id,
                        generation = committedReplacement.generation
                    ),
                    content = committedReplacement.item.content
                )
            )
        )

    fun removeMemory(
        committedRemoved: MemoryRecordSnapshot
    ): AndroidOfflineSemanticMutationSyncResult =
        map(
            assembly.synchronizeRemove(
                SemanticIndexSourceReference.Memory(
                    id = committedRemoved.record.id,
                    generation = committedRemoved.generation
                )
            )
        )

    fun removeKnowledge(
        committedRemoved: KnowledgeItemSnapshot
    ): AndroidOfflineSemanticMutationSyncResult =
        map(
            assembly.synchronizeRemove(
                SemanticIndexSourceReference.Knowledge(
                    id = committedRemoved.item.id,
                    generation = committedRemoved.generation
                )
            )
        )

    private fun map(
        result: AndroidOfflineSemanticMutationApplyResult
    ): AndroidOfflineSemanticMutationSyncResult = when (result) {
        AndroidOfflineSemanticMutationApplyResult.Applied ->
            AndroidOfflineSemanticMutationSyncResult.Synchronized
        AndroidOfflineSemanticMutationApplyResult.AlreadyApplied ->
            AndroidOfflineSemanticMutationSyncResult.AlreadySynchronized
        AndroidOfflineSemanticMutationApplyResult.RebuildRequired ->
            AndroidOfflineSemanticMutationSyncResult.RebuildRequired
        AndroidOfflineSemanticMutationApplyResult.NotReady ->
            AndroidOfflineSemanticMutationSyncResult.NotReady
    }

    companion object {
        fun create(
            assembly: AndroidOfflineSemanticProviderAssembly
        ): AndroidOfflineSemanticMutationSynchronizer =
            AndroidOfflineSemanticMutationSynchronizer(assembly)
    }
}

sealed interface AndroidOfflineSemanticMutationSyncResult {
    data object Synchronized : AndroidOfflineSemanticMutationSyncResult
    data object AlreadySynchronized : AndroidOfflineSemanticMutationSyncResult
    data object RebuildRequired : AndroidOfflineSemanticMutationSyncResult
    data object NotReady : AndroidOfflineSemanticMutationSyncResult
}
