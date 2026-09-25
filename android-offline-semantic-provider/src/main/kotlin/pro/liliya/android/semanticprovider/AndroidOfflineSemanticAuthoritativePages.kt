package pro.liliya.android.semanticprovider

import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordSnapshot

/**
 * Opens one bounded authoritative traversal used only to rebuild derived semantic shards.
 *
 * The reader owns persistence cursors internally. The semantic provider never receives backend
 * cursor identities and never treats page order or vector score as truth.
 */
fun interface AndroidOfflineSemanticAuthoritativePageSource {
    fun openReader(): AndroidOfflineSemanticAuthoritativePageReader
}

interface AndroidOfflineSemanticAuthoritativePageReader {
    fun nextMemoryPage(): AndroidOfflineSemanticMemoryPageResult
    fun nextKnowledgePage(): AndroidOfflineSemanticKnowledgePageResult
}

sealed interface AndroidOfflineSemanticMemoryPageResult {
    data object End : AndroidOfflineSemanticMemoryPageResult
    data class Loaded(
        val entries: List<MemoryRecordSnapshot>
    ) : AndroidOfflineSemanticMemoryPageResult {
        init {
            require(entries.isNotEmpty())
            require(entries.size <= MAX_PAGE_ENTRIES)
        }
    }
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticMemoryPageResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

sealed interface AndroidOfflineSemanticKnowledgePageResult {
    data object End : AndroidOfflineSemanticKnowledgePageResult
    data class Loaded(
        val entries: List<KnowledgeItemSnapshot>
    ) : AndroidOfflineSemanticKnowledgePageResult {
        init {
            require(entries.isNotEmpty())
            require(entries.size <= MAX_PAGE_ENTRIES)
        }
    }
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticKnowledgePageResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

internal const val MAX_PAGE_ENTRIES: Int = 512
