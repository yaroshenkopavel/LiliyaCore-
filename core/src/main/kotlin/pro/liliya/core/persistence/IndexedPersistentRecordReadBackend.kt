package pro.liliya.core.persistence

import java.time.Instant

/**
 * Optional indexed read capability for durable backends.
 *
 * PersistentRecordBackend remains the compatibility contract. Backends that implement this seam
 * can expose store metadata, exact records and bounded ordered pages without materializing the
 * complete PersistentBackendState. Stage 2 consumers may feature-detect this interface and fall
 * back to the legacy snapshot contract when it is unavailable.
 */
interface IndexedPersistentRecordReadBackend : PersistentRecordBackend {
    fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult

    fun loadEntry(
        storeId: PersistentStoreId,
        entityId: PersistentEntityId
    ): PersistentBackendEntryLoadResult

    fun loadPage(
        storeId: PersistentStoreId,
        request: PersistentBackendPageRequest
    ): PersistentBackendPageLoadResult
}

data class PersistentBackendMetadata(
    val revision: Long,
    val highWatermark: Long,
    val entryCount: Long
) {
    init {
        require(revision > 0L) { "indexed persistent revision must be positive" }
        require(highWatermark >= 0L) { "indexed persistent high watermark must not be negative" }
        require(entryCount >= 0L) { "indexed persistent entry count must not be negative" }
    }
}

sealed interface PersistentBackendMetadataLoadResult {
    data object Missing : PersistentBackendMetadataLoadResult
    data class Loaded(val metadata: PersistentBackendMetadata) : PersistentBackendMetadataLoadResult
    data object Corrupt : PersistentBackendMetadataLoadResult
    data class Incompatible(val reason: String) : PersistentBackendMetadataLoadResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentBackendMetadataLoadResult
}

sealed interface PersistentBackendEntryLoadResult {
    data object Missing : PersistentBackendEntryLoadResult
    data class Loaded(val snapshot: PersistentRecordSnapshot) : PersistentBackendEntryLoadResult
    data object Corrupt : PersistentBackendEntryLoadResult
    data class Incompatible(val reason: String) : PersistentBackendEntryLoadResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentBackendEntryLoadResult
}

enum class PersistentBackendPageOrder {
    OLDEST_FIRST,
    NEWEST_FIRST
}

data class PersistentBackendPageCursor(
    val createdAt: Instant,
    val entityId: PersistentEntityId
)

data class PersistentBackendPageRequest(
    val limit: Int,
    val order: PersistentBackendPageOrder = PersistentBackendPageOrder.OLDEST_FIRST,
    val cursorExclusive: PersistentBackendPageCursor? = null,
    val schemaId: PersistentSchemaId? = null
) {
    init {
        require(limit in 1..MAX_PAGE_SIZE) {
            "indexed persistent page limit must be in 1..$MAX_PAGE_SIZE"
        }
    }

    companion object {
        const val MAX_PAGE_SIZE: Int = 512
    }
}

data class PersistentBackendPage(
    val entries: List<PersistentRecordSnapshot>,
    val nextCursor: PersistentBackendPageCursor?
)

sealed interface PersistentBackendPageLoadResult {
    data object Missing : PersistentBackendPageLoadResult
    data class Loaded(val page: PersistentBackendPage) : PersistentBackendPageLoadResult
    data object Corrupt : PersistentBackendPageLoadResult
    data class Incompatible(val reason: String) : PersistentBackendPageLoadResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentBackendPageLoadResult
}
