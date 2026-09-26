package pro.liliya.android.semanticprovider

internal sealed interface SemanticShardOrphanIntentGcResult {
    data object Completed : SemanticShardOrphanIntentGcResult
    data object NothingToDo : SemanticShardOrphanIntentGcResult
    data object Deferred : SemanticShardOrphanIntentGcResult
    data object CorruptIntent : SemanticShardOrphanIntentGcResult
    data object CorruptCurrentManifest : SemanticShardOrphanIntentGcResult
}

internal class SemanticShardOrphanIntentGarbageCollector(
    private val storage: AndroidOfflineSemanticShardStorage,
    private val intentStore: SemanticShardOrphanIntentStore =
        SemanticShardOrphanIntentStore(storage),
    private val manifestStore: SemanticShardManifestV3Store =
        SemanticShardManifestV3Store(storage),
    private val shardStore: SemanticShardStore
) {
    fun resumeIfSafe(): SemanticShardOrphanIntentGcResult {
        val intentRoot = when (val loaded = intentStore.loadRoot()) {
            SemanticShardOrphanIntentRootLoadResult.Missing ->
                return SemanticShardOrphanIntentGcResult.NothingToDo
            SemanticShardOrphanIntentRootLoadResult.Corrupt ->
                return SemanticShardOrphanIntentGcResult.CorruptIntent
            is SemanticShardOrphanIntentRootLoadResult.Failed ->
                return SemanticShardOrphanIntentGcResult.Deferred
            is SemanticShardOrphanIntentRootLoadResult.Loaded -> loaded.root
        }
        if (intentRoot.phase == SemanticShardOrphanIntentPhase.RECLAIMED) {
            return clearIntentMetadata(intentRoot)
        }

        return when (val currentV3 = manifestStore.loadRoot()) {
            is SemanticShardManifestRootV3LoadResult.Loaded ->
                reclaimAgainstV3(intentRoot, currentV3.root)
            SemanticShardManifestRootV3LoadResult.Missing ->
                when (val currentV2 = shardStore.loadManifest()) {
                    is SemanticShardManifestLoadResult.Loaded ->
                        reclaimAgainstV2(intentRoot, currentV2.manifest)
                    SemanticShardManifestLoadResult.Missing ->
                        reclaimWithoutCommittedManifest(intentRoot)
                    SemanticShardManifestLoadResult.Corrupt,
                    is SemanticShardManifestLoadResult.Incompatible ->
                        SemanticShardOrphanIntentGcResult.CorruptCurrentManifest
                    is SemanticShardManifestLoadResult.Failed ->
                        SemanticShardOrphanIntentGcResult.Deferred
                }
            SemanticShardManifestRootV3LoadResult.Corrupt,
            is SemanticShardManifestRootV3LoadResult.Incompatible ->
                SemanticShardOrphanIntentGcResult.CorruptCurrentManifest
            is SemanticShardManifestRootV3LoadResult.Failed ->
                SemanticShardOrphanIntentGcResult.Deferred
        }
    }

    fun reclaimAgainstV3(
        intentRoot: SemanticShardOrphanIntentRoot,
        currentRoot: SemanticShardManifestRootV3
    ): SemanticShardOrphanIntentGcResult {
        if (!isCurrentV3(currentRoot.publicationId)) {
            return SemanticShardOrphanIntentGcResult.Deferred
        }

        val candidates = when (intentRoot.mode) {
            SemanticShardOrphanIntentMode.ORDERED_REBUILD ->
                return mergeSweep(
                    intentRoot = intentRoot,
                    current = V3CurrentCursor(manifestStore, currentRoot),
                    currentRootPublicationId = currentRoot.publicationId
                )
            SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS ->
                loadBoundedCandidates(intentRoot)
                    ?: return SemanticShardOrphanIntentGcResult.CorruptIntent
        }

        val sorted = candidates.sortedWith(DESCRIPTOR_COMPARATOR)
        val result = sweepSortedCandidates(
            candidates = sorted.iterator(),
            current = V3CurrentCursor(manifestStore, currentRoot),
            currentRootPublicationId = currentRoot.publicationId
        )
        if (result != SemanticShardOrphanIntentGcResult.Completed) return result
        if (!isCurrentV3(currentRoot.publicationId)) {
            return SemanticShardOrphanIntentGcResult.Deferred
        }
        return markReclaimedAndClear(intentRoot)
    }

    private fun reclaimAgainstV2(
        intentRoot: SemanticShardOrphanIntentRoot,
        currentManifest: SemanticShardManifest
    ): SemanticShardOrphanIntentGcResult {
        val candidates = if (intentRoot.mode == SemanticShardOrphanIntentMode.ORDERED_REBUILD) {
            null
        } else {
            loadBoundedCandidates(intentRoot)
                ?: return SemanticShardOrphanIntentGcResult.CorruptIntent
        }

        val current = V2CurrentCursor(currentManifest)
        val result = if (candidates == null) {
            sweepIntentCursor(
                intent = IntentCursor(intentStore, intentRoot),
                current = current,
                currentRootPublicationId = null
            )
        } else {
            sweepSortedCandidates(
                candidates = candidates.sortedWith(DESCRIPTOR_COMPARATOR).iterator(),
                current = current,
                currentRootPublicationId = null
            )
        }
        if (result != SemanticShardOrphanIntentGcResult.Completed) return result
        return markReclaimedAndClear(intentRoot)
    }

    private fun reclaimWithoutCommittedManifest(
        intentRoot: SemanticShardOrphanIntentRoot
    ): SemanticShardOrphanIntentGcResult {
        val cursor = IntentCursor(intentStore, intentRoot)
        while (true) {
            when (val next = cursor.next()) {
                is CursorValue.Descriptor -> {
                    if (!deleteCandidate(next.value)) {
                        return SemanticShardOrphanIntentGcResult.Deferred
                    }
                }
                CursorValue.End -> break
                CursorValue.Failed ->
                    return SemanticShardOrphanIntentGcResult.CorruptIntent
            }
        }
        if (!cursor.complete()) return SemanticShardOrphanIntentGcResult.CorruptIntent
        return markReclaimedAndClear(intentRoot)
    }

    private fun mergeSweep(
        intentRoot: SemanticShardOrphanIntentRoot,
        current: CurrentCursor,
        currentRootPublicationId: String?
    ): SemanticShardOrphanIntentGcResult {
        val intent = IntentCursor(intentStore, intentRoot)
        val result = sweepIntentCursor(intent, current, currentRootPublicationId)
        if (result != SemanticShardOrphanIntentGcResult.Completed) return result
        if (!intent.complete() || !current.completeAfterDrain()) {
            return SemanticShardOrphanIntentGcResult.CorruptCurrentManifest
        }
        if (
            currentRootPublicationId != null &&
            !isCurrentV3(currentRootPublicationId)
        ) {
            return SemanticShardOrphanIntentGcResult.Deferred
        }
        return markReclaimedAndClear(intentRoot)
    }

    private fun sweepIntentCursor(
        intent: IntentCursor,
        current: CurrentCursor,
        currentRootPublicationId: String?
    ): SemanticShardOrphanIntentGcResult {
        var currentValue = current.next()
        var candidate = intent.next()

        while (candidate is CursorValue.Descriptor) {
            while (
                currentValue is CursorValue.Descriptor &&
                compareIds(currentValue.value.shardId, candidate.value.shardId) < 0
            ) {
                currentValue = current.next()
            }
            if (currentValue == CursorValue.Failed) {
                return SemanticShardOrphanIntentGcResult.CorruptCurrentManifest
            }

            val referenced =
                currentValue is CursorValue.Descriptor &&
                    currentValue.value.shardId == candidate.value.shardId &&
                    currentValue.value.blobSha256 == candidate.value.blobSha256

            if (!referenced && !deleteCandidate(candidate.value)) {
                return SemanticShardOrphanIntentGcResult.Deferred
            }
            candidate = intent.next()
        }

        if (candidate == CursorValue.Failed) {
            return SemanticShardOrphanIntentGcResult.CorruptIntent
        }
        if (!intent.complete()) return SemanticShardOrphanIntentGcResult.CorruptIntent
        return SemanticShardOrphanIntentGcResult.Completed
    }

    private fun sweepSortedCandidates(
        candidates: Iterator<SemanticShardDescriptor>,
        current: CurrentCursor,
        currentRootPublicationId: String?
    ): SemanticShardOrphanIntentGcResult {
        var currentValue = current.next()
        while (candidates.hasNext()) {
            val candidate = candidates.next()
            while (
                currentValue is CursorValue.Descriptor &&
                compareIds(currentValue.value.shardId, candidate.shardId) < 0
            ) {
                currentValue = current.next()
            }
            if (currentValue == CursorValue.Failed) {
                return SemanticShardOrphanIntentGcResult.CorruptCurrentManifest
            }

            val referenced =
                currentValue is CursorValue.Descriptor &&
                    currentValue.value.shardId == candidate.shardId &&
                    currentValue.value.blobSha256 == candidate.blobSha256
            if (!referenced && !deleteCandidate(candidate)) {
                return SemanticShardOrphanIntentGcResult.Deferred
            }
        }

        return if (current.completeAfterDrain()) {
            SemanticShardOrphanIntentGcResult.Completed
        } else {
            SemanticShardOrphanIntentGcResult.CorruptCurrentManifest
        }
    }

    private fun loadBoundedCandidates(
        root: SemanticShardOrphanIntentRoot
    ): List<SemanticShardDescriptor>? {
        if (root.mode != SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS) return null
        val result = ArrayList<SemanticShardDescriptor>()
        val cursor = IntentCursor(intentStore, root)
        while (true) {
            when (val next = cursor.next()) {
                is CursorValue.Descriptor -> {
                    result += next.value
                    if (result.size > SemanticShardOrphanIntentRoot.MAX_BOUNDED_DESCRIPTORS) {
                        return null
                    }
                }
                CursorValue.End -> break
                CursorValue.Failed -> return null
            }
        }
        return if (cursor.complete()) result else null
    }

    private fun deleteCandidate(descriptor: SemanticShardDescriptor): Boolean =
        when (
            storage.delete(
                AndroidOfflineSemanticShardStorageKey.forShard(
                    descriptor.shardId,
                    descriptor.blobSha256
                )
            )
        ) {
            AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
            AndroidOfflineSemanticShardStorageDeleteResult.Missing -> true
            AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
            is AndroidOfflineSemanticShardStorageDeleteResult.Failed -> false
        }

    private fun markReclaimedAndClear(
        root: SemanticShardOrphanIntentRoot
    ): SemanticShardOrphanIntentGcResult {
        val reclaimed = root.copy(phase = SemanticShardOrphanIntentPhase.RECLAIMED)
        if (!intentStore.writeRoot(reclaimed)) {
            return SemanticShardOrphanIntentGcResult.Deferred
        }
        return clearIntentMetadata(reclaimed)
    }

    private fun clearIntentMetadata(
        root: SemanticShardOrphanIntentRoot
    ): SemanticShardOrphanIntentGcResult =
        if (intentStore.clear(root)) {
            SemanticShardOrphanIntentGcResult.Completed
        } else {
            SemanticShardOrphanIntentGcResult.Deferred
        }

    private fun isCurrentV3(publicationId: String): Boolean =
        when (val loaded = manifestStore.loadRoot()) {
            is SemanticShardManifestRootV3LoadResult.Loaded ->
                loaded.root.publicationId == publicationId
            SemanticShardManifestRootV3LoadResult.Missing,
            SemanticShardManifestRootV3LoadResult.Corrupt,
            is SemanticShardManifestRootV3LoadResult.Incompatible,
            is SemanticShardManifestRootV3LoadResult.Failed -> false
        }

    private class IntentCursor(
        private val store: SemanticShardOrphanIntentStore,
        private val root: SemanticShardOrphanIntentRoot
    ) {
        private var segmentOrdinal = 0L
        private var descriptorIndex = 0
        private var currentSegment: SemanticShardOrphanIntentSegment? = null
        private var previousOrderedId: SemanticShardId? = null
        private var failed = false
        private var ended = false
        private var emitted = 0L

        fun next(): CursorValue {
            if (failed) return CursorValue.Failed
            if (ended) return CursorValue.End

            while (true) {
                val segment = currentSegment
                if (segment != null && descriptorIndex < segment.descriptors.size) {
                    val descriptor = segment.descriptors[descriptorIndex++]
                    if (root.mode == SemanticShardOrphanIntentMode.ORDERED_REBUILD) {
                        val previous = previousOrderedId
                        if (previous != null && compareIds(previous, descriptor.shardId) >= 0) {
                            failed = true
                            return CursorValue.Failed
                        }
                        previousOrderedId = descriptor.shardId
                    }
                    emitted += 1L
                    if (
                        root.mode == SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS &&
                        emitted > SemanticShardOrphanIntentRoot.MAX_BOUNDED_DESCRIPTORS
                    ) {
                        failed = true
                        return CursorValue.Failed
                    }
                    return CursorValue.Descriptor(descriptor)
                }

                if (segmentOrdinal >= root.segmentCount) {
                    ended = true
                    return CursorValue.End
                }

                currentSegment = when (val loaded = store.readSegment(root, segmentOrdinal)) {
                    is SemanticShardOrphanIntentSegmentLoadResult.Loaded -> loaded.segment
                    SemanticShardOrphanIntentSegmentLoadResult.Missing,
                    SemanticShardOrphanIntentSegmentLoadResult.Corrupt,
                    is SemanticShardOrphanIntentSegmentLoadResult.Failed -> {
                        failed = true
                        return CursorValue.Failed
                    }
                }
                segmentOrdinal += 1L
                descriptorIndex = 0
            }
        }

        fun complete(): Boolean = ended && !failed
    }

    private interface CurrentCursor {
        fun next(): CursorValue
        fun completeAfterDrain(): Boolean
    }

    private class V3CurrentCursor(
        private val store: SemanticShardManifestV3Store,
        private val root: SemanticShardManifestRootV3
    ) : CurrentCursor {
        private var segmentOrdinal = 0L
        private var descriptorIndex = 0
        private var currentSegment: SemanticShardManifestSegment? = null
        private var previousId: SemanticShardId? = null
        private var emitted = 0L
        private var failed = false
        private var ended = false

        override fun next(): CursorValue {
            if (failed) return CursorValue.Failed
            if (ended) return CursorValue.End
            while (true) {
                val segment = currentSegment
                if (segment != null && descriptorIndex < segment.shards.size) {
                    val descriptor = segment.shards[descriptorIndex++]
                    val previous = previousId
                    if (previous != null && compareIds(previous, descriptor.shardId) >= 0) {
                        failed = true
                        return CursorValue.Failed
                    }
                    previousId = descriptor.shardId
                    emitted += 1L
                    if (emitted > root.shardDescriptorCount) {
                        failed = true
                        return CursorValue.Failed
                    }
                    return CursorValue.Descriptor(descriptor)
                }

                if (segmentOrdinal >= root.segmentCount) {
                    ended = true
                    return if (emitted == root.shardDescriptorCount) {
                        CursorValue.End
                    } else {
                        failed = true
                        CursorValue.Failed
                    }
                }

                currentSegment = when (val loaded = store.readSegment(root, segmentOrdinal)) {
                    is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                    SemanticShardManifestSegmentLoadResult.Missing,
                    SemanticShardManifestSegmentLoadResult.Corrupt,
                    is SemanticShardManifestSegmentLoadResult.Incompatible,
                    is SemanticShardManifestSegmentLoadResult.Failed -> {
                        failed = true
                        return CursorValue.Failed
                    }
                }
                segmentOrdinal += 1L
                descriptorIndex = 0
            }
        }

        override fun completeAfterDrain(): Boolean {
            while (next() is CursorValue.Descriptor) {
                // bounded one-segment-at-a-time drain
            }
            return ended && !failed && emitted == root.shardDescriptorCount
        }
    }

    private class V2CurrentCursor(
        manifest: SemanticShardManifest
    ) : CurrentCursor {
        private val iterator = manifest.shards.iterator()
        private var previousId: SemanticShardId? = null
        private var failed = false
        private var ended = false

        override fun next(): CursorValue {
            if (failed) return CursorValue.Failed
            if (ended) return CursorValue.End
            if (!iterator.hasNext()) {
                ended = true
                return CursorValue.End
            }
            val descriptor = iterator.next()
            val previous = previousId
            if (previous != null && compareIds(previous, descriptor.shardId) >= 0) {
                failed = true
                return CursorValue.Failed
            }
            previousId = descriptor.shardId
            return CursorValue.Descriptor(descriptor)
        }

        override fun completeAfterDrain(): Boolean {
            while (next() is CursorValue.Descriptor) {
                // drain
            }
            return ended && !failed
        }
    }

    private sealed interface CursorValue {
        data class Descriptor(val value: SemanticShardDescriptor) : CursorValue
        data object End : CursorValue
        data object Failed : CursorValue
    }

    private companion object {
        val DESCRIPTOR_COMPARATOR =
            compareBy<SemanticShardDescriptor> { it.shardId.domain.ordinal }
                .thenBy { it.shardId.ordinal }
                .thenBy { it.blobSha256 }

        fun compareIds(left: SemanticShardId, right: SemanticShardId): Int {
            val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
            return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
        }
    }
}
