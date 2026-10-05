package pro.liliya.android.semanticprovider

internal sealed interface SemanticShardManifestV3GcResult {
    data object Completed : SemanticShardManifestV3GcResult
    data object NothingToDo : SemanticShardManifestV3GcResult
    data object Deferred : SemanticShardManifestV3GcResult
    data object UnsafeCurrentRoot : SemanticShardManifestV3GcResult
    data object CorruptManifest : SemanticShardManifestV3GcResult
}

internal class SemanticShardManifestV3GarbageCollector(
    private val storage: AndroidOfflineSemanticShardStorage,
    private val manifestStore: SemanticShardManifestV3Store =
        SemanticShardManifestV3Store(storage),
    private val journalStore: SemanticShardManifestV3GcJournalStore =
        SemanticShardManifestV3GcJournalStore(storage)
) {
    fun resumeIfSafe(): SemanticShardManifestV3GcResult {
        val journal = when (val read = journalStore.read()) {
            SemanticShardManifestV3GcJournalReadResult.Missing ->
                return SemanticShardManifestV3GcResult.NothingToDo
            SemanticShardManifestV3GcJournalReadResult.Corrupt,
            is SemanticShardManifestV3GcJournalReadResult.Failed ->
                return SemanticShardManifestV3GcResult.Deferred
            is SemanticShardManifestV3GcJournalReadResult.Loaded -> read.journal
        }

        val current = when (val loaded = manifestStore.loadRoot()) {
            is SemanticShardManifestRootV3LoadResult.Loaded -> loaded.root
            SemanticShardManifestRootV3LoadResult.Missing,
            SemanticShardManifestRootV3LoadResult.Corrupt,
            is SemanticShardManifestRootV3LoadResult.Incompatible,
            is SemanticShardManifestRootV3LoadResult.Failed ->
                return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
        }

        val sourceRoot = when (current.publicationId) {
            journal.targetRoot.publicationId -> journal.previousRoot
            journal.previousRoot.publicationId -> journal.targetRoot
            else ->
                // A third publication is current. Do not infer reachability across an unknown
                // publication transition; keep the journal for an explicit later reconciliation.
                return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
        }

        return when (journal.phase) {
            SemanticShardManifestV3GcPhase.PREPARED ->
                reclaimCommitted(sourceRoot, current)
            SemanticShardManifestV3GcPhase.SHARDS_RECLAIMED ->
                finishMetadataCleanup(sourceRoot, current)
        }
    }

    fun prepare(
        previousRoot: SemanticShardManifestRootV3,
        targetRoot: SemanticShardManifestRootV3
    ): Boolean {
        when (journalStore.read()) {
            SemanticShardManifestV3GcJournalReadResult.Missing -> Unit
            is SemanticShardManifestV3GcJournalReadResult.Loaded -> {
                when (resumeIfSafe()) {
                    SemanticShardManifestV3GcResult.Completed,
                    SemanticShardManifestV3GcResult.NothingToDo -> Unit
                    SemanticShardManifestV3GcResult.Deferred,
                    SemanticShardManifestV3GcResult.UnsafeCurrentRoot,
                    SemanticShardManifestV3GcResult.CorruptManifest -> return false
                }
            }
            SemanticShardManifestV3GcJournalReadResult.Corrupt,
            is SemanticShardManifestV3GcJournalReadResult.Failed -> return false
        }
        return journalStore.write(
            SemanticShardManifestV3GcJournal(
                previousRoot = previousRoot,
                targetRoot = targetRoot
            )
        )
    }

    fun reclaimCommitted(
        previousRoot: SemanticShardManifestRootV3,
        currentRoot: SemanticShardManifestRootV3
    ): SemanticShardManifestV3GcResult {
        if (
            previousRoot.publicationId == currentRoot.publicationId ||
            !isCurrentRoot(currentRoot.publicationId)
        ) {
            return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
        }
        if (!validateRoot(previousRoot) || !validateRoot(currentRoot)) {
            return SemanticShardManifestV3GcResult.CorruptManifest
        }
        if (!isCurrentRoot(currentRoot.publicationId)) {
            return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
        }

        val oldCursor = DescriptorCursor(manifestStore, previousRoot)
        val newCursor = DescriptorCursor(manifestStore, currentRoot)

        var old = oldCursor.next()
        var current = newCursor.next()

        while (old is CursorResult.Descriptor) {
            if (!isCurrentRoot(currentRoot.publicationId)) {
                return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
            }

            while (
                current is CursorResult.Descriptor &&
                compareIds(current.value.shardId, old.value.shardId) < 0
            ) {
                current = newCursor.next()
            }
            if (current is CursorResult.Failed) {
                return SemanticShardManifestV3GcResult.CorruptManifest
            }

            val stillReferenced =
                current is CursorResult.Descriptor &&
                    current.value.shardId == old.value.shardId &&
                    current.value.blobSha256 == old.value.blobSha256

            if (!stillReferenced) {
                when (
                    storage.delete(
                        AndroidOfflineSemanticShardStorageKey.forShard(
                            old.value.shardId,
                            old.value.blobSha256
                        )
                    )
                ) {
                    AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
                    AndroidOfflineSemanticShardStorageDeleteResult.Missing -> Unit
                    AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
                    is AndroidOfflineSemanticShardStorageDeleteResult.Failed ->
                        return SemanticShardManifestV3GcResult.Deferred
                }
            }
            old = oldCursor.next()
        }

        if (old is CursorResult.Failed || current is CursorResult.Failed) {
            return SemanticShardManifestV3GcResult.CorruptManifest
        }

        // Fully consume the current manifest so missing/tampered tail segments cannot be ignored.
        while (current is CursorResult.Descriptor) {
            current = newCursor.next()
        }
        if (current is CursorResult.Failed) {
            return SemanticShardManifestV3GcResult.CorruptManifest
        }
        if (!oldCursor.complete() || !newCursor.complete()) {
            return SemanticShardManifestV3GcResult.CorruptManifest
        }

        if (!isCurrentRoot(currentRoot.publicationId)) {
            return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
        }

        if (!markShardsReclaimed(previousRoot, currentRoot)) {
            return SemanticShardManifestV3GcResult.Deferred
        }
        return finishMetadataCleanup(previousRoot, currentRoot)
    }

    private fun markShardsReclaimed(
        sourceRoot: SemanticShardManifestRootV3,
        currentRoot: SemanticShardManifestRootV3
    ): Boolean {
        val journal = when (val read = journalStore.read()) {
            is SemanticShardManifestV3GcJournalReadResult.Loaded -> read.journal
            SemanticShardManifestV3GcJournalReadResult.Missing,
            SemanticShardManifestV3GcJournalReadResult.Corrupt,
            is SemanticShardManifestV3GcJournalReadResult.Failed -> return false
        }
        val rootsMatch =
            (journal.previousRoot.publicationId == sourceRoot.publicationId &&
                journal.targetRoot.publicationId == currentRoot.publicationId) ||
                (journal.targetRoot.publicationId == sourceRoot.publicationId &&
                    journal.previousRoot.publicationId == currentRoot.publicationId)
        if (!rootsMatch || journal.phase != SemanticShardManifestV3GcPhase.PREPARED) {
            return false
        }
        return journalStore.write(
            journal.copy(phase = SemanticShardManifestV3GcPhase.SHARDS_RECLAIMED)
        )
    }

    private fun finishMetadataCleanup(
        sourceRoot: SemanticShardManifestRootV3,
        currentRoot: SemanticShardManifestRootV3
    ): SemanticShardManifestV3GcResult {
        if (!isCurrentRoot(currentRoot.publicationId)) {
            return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
        }
        for (ordinal in 0 until sourceRoot.segmentCount) {
            if (!isCurrentRoot(currentRoot.publicationId)) {
                return SemanticShardManifestV3GcResult.UnsafeCurrentRoot
            }
            when (
                storage.delete(
                    AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                        sourceRoot.publicationId,
                        ordinal
                    )
                )
            ) {
                AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
                AndroidOfflineSemanticShardStorageDeleteResult.Missing -> Unit
                AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
                is AndroidOfflineSemanticShardStorageDeleteResult.Failed ->
                    return SemanticShardManifestV3GcResult.Deferred
            }
        }
        return if (journalStore.clear()) {
            SemanticShardManifestV3GcResult.Completed
        } else {
            SemanticShardManifestV3GcResult.Deferred
        }
    }

    private fun validateRoot(root: SemanticShardManifestRootV3): Boolean {
        val cursor = DescriptorCursor(manifestStore, root)
        while (true) {
            when (cursor.next()) {
                is CursorResult.Descriptor -> Unit
                CursorResult.End -> return cursor.complete()
                CursorResult.Failed -> return false
            }
        }
    }

    private fun isCurrentRoot(expectedPublicationId: String): Boolean =
        when (val loaded = manifestStore.loadRoot()) {
            is SemanticShardManifestRootV3LoadResult.Loaded ->
                loaded.root.publicationId == expectedPublicationId
            SemanticShardManifestRootV3LoadResult.Missing,
            SemanticShardManifestRootV3LoadResult.Corrupt,
            is SemanticShardManifestRootV3LoadResult.Incompatible,
            is SemanticShardManifestRootV3LoadResult.Failed -> false
        }

    private class DescriptorCursor(
        private val store: SemanticShardManifestV3Store,
        private val root: SemanticShardManifestRootV3
    ) {
        private var segmentOrdinal = 0L
        private var descriptorIndex = 0
        private var currentSegment: SemanticShardManifestSegment? = null
        private var emitted = 0L
        private var previousId: SemanticShardId? = null
        private var failed = false
        private var ended = false

        fun next(): CursorResult {
            if (failed) return CursorResult.Failed
            if (ended) return CursorResult.End

            while (true) {
                val segment = currentSegment
                if (segment != null && descriptorIndex < segment.shards.size) {
                    val descriptor = segment.shards[descriptorIndex++]
                    val previous = previousId
                    if (previous != null && compareIds(previous, descriptor.shardId) >= 0) {
                        failed = true
                        return CursorResult.Failed
                    }
                    previousId = descriptor.shardId
                    emitted += 1L
                    if (emitted > root.shardDescriptorCount) {
                        failed = true
                        return CursorResult.Failed
                    }
                    return CursorResult.Descriptor(descriptor)
                }

                if (segmentOrdinal >= root.segmentCount) {
                    ended = true
                    return if (emitted == root.shardDescriptorCount) {
                        CursorResult.End
                    } else {
                        failed = true
                        CursorResult.Failed
                    }
                }

                currentSegment = when (val loaded = store.readSegment(root, segmentOrdinal)) {
                    is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                    SemanticShardManifestSegmentLoadResult.Missing,
                    SemanticShardManifestSegmentLoadResult.Corrupt,
                    is SemanticShardManifestSegmentLoadResult.Incompatible,
                    is SemanticShardManifestSegmentLoadResult.Failed -> {
                        failed = true
                        return CursorResult.Failed
                    }
                }
                if (currentSegment!!.ordinal != segmentOrdinal) {
                    failed = true
                    return CursorResult.Failed
                }
                segmentOrdinal += 1L
                descriptorIndex = 0
            }
        }

        fun complete(): Boolean = ended && !failed && emitted == root.shardDescriptorCount
    }

    private sealed interface CursorResult {
        data class Descriptor(val value: SemanticShardDescriptor) : CursorResult
        data object End : CursorResult
        data object Failed : CursorResult
    }

    private companion object {
        fun compareIds(left: SemanticShardId, right: SemanticShardId): Int {
            val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
            return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
        }
    }
}
