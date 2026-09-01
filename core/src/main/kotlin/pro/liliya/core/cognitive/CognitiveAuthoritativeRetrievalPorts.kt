package pro.liliya.core.cognitive

import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.memory.MemoryComposition

/**
 * Read-only bounded recent-window adapter over the authoritative Memory composition.
 *
 * Selection is deliberately recency-based for Cognitive Runtime v0.1. This adapter
 * makes no semantic relevance or ranking claim.
 */
class MemoryCompositionRetrievalPort(
    private val memory: MemoryComposition
) : MemoryRetrievalPort {
    override fun retrieve(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val entries = memory.snapshotEntries()
        val selected = if (entries.size <= request.maxResults) {
            entries
        } else {
            entries.takeLast(request.maxResults)
        }
        return MemoryRetrievalResult(selected)
    }
}

/**
 * Read-only bounded recent-window adapter over the authoritative Knowledge composition.
 *
 * Selection is deliberately recency-based for Cognitive Runtime v0.1. This adapter
 * makes no semantic relevance or ranking claim.
 */
class KnowledgeCompositionRetrievalPort(
    private val knowledge: KnowledgeComposition
) : KnowledgeRetrievalPort {
    override fun retrieve(request: KnowledgeRetrievalRequest): KnowledgeRetrievalResult {
        val entries = knowledge.snapshotEntries()
        val selected = if (entries.size <= request.maxResults) {
            entries
        } else {
            entries.takeLast(request.maxResults)
        }
        return KnowledgeRetrievalResult(selected)
    }
}
