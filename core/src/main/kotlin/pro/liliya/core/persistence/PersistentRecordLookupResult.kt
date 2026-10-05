package pro.liliya.core.persistence

/**
 * Fail-closed exact lookup result used by capability-aware persistent stores.
 *
 * The legacy nullable find/inspect APIs cannot distinguish an absent entity from backend corruption
 * or an incompatible durable format. Lazy indexed consumers must use this result when those
 * distinctions affect safety or recovery semantics.
 */
sealed interface PersistentRecordLookupResult {
    data object Missing : PersistentRecordLookupResult

    data class Found(
        val snapshot: PersistentRecordSnapshot
    ) : PersistentRecordLookupResult

    data object Corrupt : PersistentRecordLookupResult

    data class Incompatible(
        val reason: String
    ) : PersistentRecordLookupResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentRecordLookupResult
}
