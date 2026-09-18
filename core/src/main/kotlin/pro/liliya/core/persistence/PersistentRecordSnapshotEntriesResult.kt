package pro.liliya.core.persistence

/**
 * Fail-closed result for explicit full-store enumeration.
 *
 * Indexed backends may satisfy this operation through bounded pages. The operation is explicit
 * because it can still materialize all selected snapshots in the caller; normal exact access should
 * prefer inspectResult().
 */
sealed interface PersistentRecordSnapshotEntriesResult {
    data class Loaded(
        val entries: List<PersistentRecordSnapshot>
    ) : PersistentRecordSnapshotEntriesResult

    /** The opened store currently has no durable rows. */
    data object Empty : PersistentRecordSnapshotEntriesResult

    data object Corrupt : PersistentRecordSnapshotEntriesResult

    data class Incompatible(
        val reason: String
    ) : PersistentRecordSnapshotEntriesResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentRecordSnapshotEntriesResult
}
