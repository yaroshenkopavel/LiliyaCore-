package pro.liliya.android.semanticprovider

import java.util.TreeMap

/**
 * Bounded-memory active semantic shard index over v3 segmented manifests.
 *
 * Durable history is never capped. Only not-yet-persisted descriptor mutations are bounded;
 * callers can persist and continue when that operational buffer is exhausted.
 */
internal class SemanticShardSegmentedIndexV3(
    private val shardStore: SemanticShardStore,
    private val manifestStore: SemanticShardManifestV3Store,
    initialRoot: SemanticShardManifestRootV3
) : SemanticShardActiveIndex {
    private var root: SemanticShardManifestRootV3 = initialRoot
    private val pending = TreeMap<SemanticShardId, SemanticShardDescriptor?>(SHARD_ID_COMPARATOR)
    private val basePresence = HashMap<SemanticShardId, Boolean>()
    private var orphanIntentTracker = SemanticShardOrphanIntentTracker(
        store = SemanticShardOrphanIntentStore(manifestStore.storage),
        mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS
    )
    private var orphanIntentCleanupBlocked: Boolean = false
    private val orphanIntentGarbageCollector = SemanticShardOrphanIntentGarbageCollector(
        storage = manifestStore.storage,
        manifestStore = manifestStore,
        shardStore = shardStore
    )
    private var liveEntryCount: Long = authoritativeEntryCount(initialRoot.authoritative)

    @Synchronized
    fun validate(): Boolean {
        var descriptorCount = 0L
        var entryCount = 0L
        var previous: SemanticShardId? = null

        for (ordinal in 0 until root.segmentCount) {
            val segment = when (val loaded = manifestStore.readSegment(root, ordinal)) {
                is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                SemanticShardManifestSegmentLoadResult.Missing,
                SemanticShardManifestSegmentLoadResult.Corrupt,
                is SemanticShardManifestSegmentLoadResult.Incompatible,
                is SemanticShardManifestSegmentLoadResult.Failed -> return false
            }
            for (descriptor in segment.shards) {
                val before = previous
                if (before != null && SHARD_ID_COMPARATOR.compare(before, descriptor.shardId) >= 0) {
                    return false
                }
                previous = descriptor.shardId
                descriptorCount = try {
                    Math.addExact(descriptorCount, 1L)
                } catch (_: ArithmeticException) {
                    return false
                }
                entryCount = try {
                    Math.addExact(entryCount, descriptor.entryCount.toLong())
                } catch (_: ArithmeticException) {
                    return false
                }
            }
        }

        return descriptorCount == root.shardDescriptorCount &&
            entryCount == authoritativeEntryCount(root.authoritative)
    }

    @Synchronized
    override fun rank(
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): SemanticShardRankResult {
        require(maxCandidates > 0)
        var globalTopK: List<SemanticRankedCandidate> = emptyList()
        var descriptorCount = 0L
        var previous: SemanticShardId? = null

        for (ordinal in 0 until root.segmentCount) {
            val segment = when (val loaded = manifestStore.readSegment(root, ordinal)) {
                is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                SemanticShardManifestSegmentLoadResult.Missing,
                SemanticShardManifestSegmentLoadResult.Corrupt ->
                    return SemanticShardRankResult.Corrupt
                is SemanticShardManifestSegmentLoadResult.Incompatible ->
                    return SemanticShardRankResult.Incompatible(loaded.reason)
                is SemanticShardManifestSegmentLoadResult.Failed ->
                    return SemanticShardRankResult.Failed(loaded.reason, loaded.throwable)
            }

            val effective = ArrayList<SemanticShardDescriptor>(segment.shards.size)
            for (descriptor in segment.shards) {
                val before = previous
                if (before != null && SHARD_ID_COMPARATOR.compare(before, descriptor.shardId) >= 0) {
                    return SemanticShardRankResult.Corrupt
                }
                previous = descriptor.shardId
                descriptorCount += 1L
                if (pending.containsKey(descriptor.shardId)) {
                    pending[descriptor.shardId]?.let(effective::add)
                } else {
                    effective += descriptor
                }
            }

            when (val ranked = shardStore.rankDescriptors(effective, domain, query, maxCandidates)) {
                is SemanticShardRankResult.Ranked -> {
                    globalTopK = SemanticShardRankMerger.merge(
                        shardCandidates = listOf(globalTopK, ranked.candidates),
                        maxCandidates = maxCandidates
                    )
                }
                SemanticShardRankResult.Corrupt -> return ranked
                is SemanticShardRankResult.Incompatible -> return ranked
                is SemanticShardRankResult.Failed -> return ranked
            }
        }

        if (descriptorCount != root.shardDescriptorCount) {
            return SemanticShardRankResult.Corrupt
        }

        val additions = pending.entries
            .asSequence()
            .filter { (id, descriptor) -> descriptor != null && basePresence[id] == false }
            .mapNotNull { it.value }
            .filter { it.shardId.domain == domain }
            .toList()

        if (additions.isNotEmpty()) {
            when (val ranked = shardStore.rankDescriptors(additions, domain, query, maxCandidates)) {
                is SemanticShardRankResult.Ranked -> {
                    globalTopK = SemanticShardRankMerger.merge(
                        shardCandidates = listOf(globalTopK, ranked.candidates),
                        maxCandidates = maxCandidates
                    )
                }
                SemanticShardRankResult.Corrupt -> return ranked
                is SemanticShardRankResult.Incompatible -> return ranked
                is SemanticShardRankResult.Failed -> return ranked
            }
        }

        return SemanticShardRankResult.Ranked(globalTopK)
    }

    @Synchronized
    override fun add(
        source: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticShardMutationResult {
        val shardId = SemanticShardLayout.shardFor(source)
        if (!reservePendingKeys(listOf(shardId))) return SemanticShardMutationResult.StorageFailed
        val loaded = loadSeeds(shardId) ?: return SemanticShardMutationResult.StorageFailed
        try {
            val matching = loaded.seeds.firstOrNull { sameEntity(it.source, source) }
            if (matching != null) {
                return if (matching.source == source) {
                    SemanticShardMutationResult.AlreadyApplied
                } else {
                    SemanticShardMutationResult.StaleOrConflicting
                }
            }

            val next = ArrayList<SemanticIndexSeed>(loaded.seeds.size + 1)
            next += loaded.seeds
            next += SemanticIndexSeed(source, vector)
            val descriptor = writeReplacementShard(shardId, next)
                ?: return SemanticShardMutationResult.StorageFailed
            pending[shardId] = descriptor
            liveEntryCount = try {
                Math.addExact(liveEntryCount, 1L)
            } catch (_: ArithmeticException) {
                return SemanticShardMutationResult.StorageFailed
            }
            return SemanticShardMutationResult.Applied
        } finally {
            loaded.clear()
        }
    }

    @Synchronized
    override fun replace(
        expected: SemanticIndexSourceReference,
        replacement: SemanticIndexSourceReference,
        replacementVector: SemanticEmbeddingVector
    ): SemanticShardMutationResult {
        if (!sameEntity(expected, replacement) ||
            replacement.generationValue <= expected.generationValue
        ) {
            return SemanticShardMutationResult.StaleOrConflicting
        }

        val oldShardId = SemanticShardLayout.shardFor(expected)
        val newShardId = SemanticShardLayout.shardFor(replacement)
        if (!reservePendingKeys(listOf(oldShardId, newShardId))) {
            return SemanticShardMutationResult.StorageFailed
        }

        val oldLoaded = loadSeeds(oldShardId)
            ?: return SemanticShardMutationResult.StorageFailed
        try {
            val expectedIndex = oldLoaded.seeds.indexOfFirst { it.source == expected }
            if (expectedIndex < 0) {
                return SemanticShardMutationResult.StaleOrConflicting
            }

            if (oldShardId == newShardId) {
                val next = ArrayList<SemanticIndexSeed>(oldLoaded.seeds.size)
                oldLoaded.seeds.forEachIndexed { index, seed ->
                    next += if (index == expectedIndex) {
                        SemanticIndexSeed(replacement, replacementVector)
                    } else {
                        seed
                    }
                }
                val descriptor = writeReplacementShard(oldShardId, next)
                    ?: return SemanticShardMutationResult.StorageFailed
                pending[oldShardId] = descriptor
                return SemanticShardMutationResult.Applied
            }

            val newLoaded = loadSeeds(newShardId)
                ?: return SemanticShardMutationResult.StorageFailed
            try {
                if (newLoaded.seeds.any { sameEntity(it.source, replacement) }) {
                    return SemanticShardMutationResult.StaleOrConflicting
                }

                val oldNext = oldLoaded.seeds.filterIndexed { index, _ -> index != expectedIndex }
                val newNext = ArrayList<SemanticIndexSeed>(newLoaded.seeds.size + 1)
                newNext += newLoaded.seeds
                newNext += SemanticIndexSeed(replacement, replacementVector)

                val oldDescriptor = if (oldNext.isEmpty()) {
                    null
                } else {
                    writeReplacementShard(oldShardId, oldNext)
                        ?: return SemanticShardMutationResult.StorageFailed
                }
                val newDescriptor = writeReplacementShard(newShardId, newNext)
                    ?: return SemanticShardMutationResult.StorageFailed

                pending[oldShardId] = oldDescriptor
                pending[newShardId] = newDescriptor
                return SemanticShardMutationResult.Applied
            } finally {
                newLoaded.clear()
            }
        } finally {
            oldLoaded.clear()
        }
    }

    @Synchronized
    override fun remove(source: SemanticIndexSourceReference): SemanticShardMutationResult {
        val shardId = SemanticShardLayout.shardFor(source)
        if (!reservePendingKeys(listOf(shardId))) return SemanticShardMutationResult.StorageFailed
        val loaded = loadSeeds(shardId)
            ?: return SemanticShardMutationResult.StorageFailed
        try {
            val index = loaded.seeds.indexOfFirst { it.source == source }
            if (index < 0) return SemanticShardMutationResult.StaleOrConflicting

            val remaining = loaded.seeds.filterIndexed { current, _ -> current != index }
            pending[shardId] = if (remaining.isEmpty()) {
                null
            } else {
                writeReplacementShard(shardId, remaining)
                    ?: return SemanticShardMutationResult.StorageFailed
            }
            liveEntryCount -= 1L
            return SemanticShardMutationResult.Applied
        } finally {
            loaded.clear()
        }
    }

    @Synchronized
    override fun persistManifest(
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Boolean {
        if (authoritativeEntryCount(authoritative) != liveEntryCount) return false

        val writer = SemanticShardManifestV3PublicationWriter(
            store = manifestStore,
            model = root.model
        )
        val pendingIterator = pending.entries.iterator()
        var pendingEntry = if (pendingIterator.hasNext()) pendingIterator.next() else null
        var descriptorCount = 0L
        var previous: SemanticShardId? = null

        for (ordinal in 0 until root.segmentCount) {
            val segment = when (val loaded = manifestStore.readSegment(root, ordinal)) {
                is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                SemanticShardManifestSegmentLoadResult.Missing,
                SemanticShardManifestSegmentLoadResult.Corrupt,
                is SemanticShardManifestSegmentLoadResult.Incompatible,
                is SemanticShardManifestSegmentLoadResult.Failed -> return false
            }

            for (base in segment.shards) {
                val before = previous
                if (before != null && SHARD_ID_COMPARATOR.compare(before, base.shardId) >= 0) {
                    return false
                }
                previous = base.shardId
                descriptorCount += 1L

                while (
                    pendingEntry != null &&
                    SHARD_ID_COMPARATOR.compare(pendingEntry!!.key, base.shardId) < 0
                ) {
                    pendingEntry!!.value?.let {
                        if (!writer.append(it)) return false
                    }
                    pendingEntry = if (pendingIterator.hasNext()) pendingIterator.next() else null
                }

                if (
                    pendingEntry != null &&
                    SHARD_ID_COMPARATOR.compare(pendingEntry!!.key, base.shardId) == 0
                ) {
                    pendingEntry!!.value?.let {
                        if (!writer.append(it)) return false
                    }
                    pendingEntry = if (pendingIterator.hasNext()) pendingIterator.next() else null
                } else {
                    if (!writer.append(base)) return false
                }
            }
        }

        if (descriptorCount != root.shardDescriptorCount) return false

        while (pendingEntry != null) {
            pendingEntry!!.value?.let {
                if (!writer.append(it)) return false
            }
            pendingEntry = if (pendingIterator.hasNext()) pendingIterator.next() else null
        }

        val previousRoot = root
        val garbageCollector = SemanticShardManifestV3GarbageCollector(
            storage = manifestStore.storage,
            manifestStore = manifestStore
        )
        val nextRoot = writer.finish(
            authoritative = authoritative,
            beforeCommit = { candidate ->
                garbageCollector.prepare(
                    previousRoot = previousRoot,
                    targetRoot = candidate
                )
            }
        ) ?: return false

        root = nextRoot
        pending.clear()
        basePresence.clear()

        // Publication is already committed. Both cleanup passes are best-effort and resumable;
        // a cleanup failure must not invalidate the new derived semantic state.
        when (orphanIntentGarbageCollector.resumeIfSafe()) {
            SemanticShardOrphanIntentGcResult.Completed,
            SemanticShardOrphanIntentGcResult.NothingToDo -> {
                orphanIntentTracker = SemanticShardOrphanIntentTracker(
                    store = SemanticShardOrphanIntentStore(manifestStore.storage),
                    mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS
                )
                orphanIntentCleanupBlocked = false
            }
            SemanticShardOrphanIntentGcResult.Deferred,
            SemanticShardOrphanIntentGcResult.CorruptIntent,
            SemanticShardOrphanIntentGcResult.CorruptCurrentManifest ->
                orphanIntentCleanupBlocked = true
        }
        garbageCollector.reclaimCommitted(previousRoot, nextRoot)
        return true
    }

    @Synchronized
    override fun entryCount(): Long = liveEntryCount

    @Synchronized
    internal fun currentRoot(): SemanticShardManifestRootV3 = root

    private fun reservePendingKeys(ids: List<SemanticShardId>): Boolean {
        val additional = ids.distinct().count { !pending.containsKey(it) }
        return pending.size + additional <= MAX_PENDING_DESCRIPTOR_MUTATIONS
    }

    private fun loadSeeds(shardId: SemanticShardId): LoadedSeeds? {
        val descriptor = if (pending.containsKey(shardId)) {
            pending[shardId]
        } else {
            when (val found = findBaseDescriptor(shardId)) {
                is DescriptorLookup.Found -> {
                    basePresence.putIfAbsent(shardId, true)
                    found.descriptor
                }
                DescriptorLookup.Missing -> {
                    basePresence.putIfAbsent(shardId, false)
                    null
                }
                DescriptorLookup.Failed -> return null
            }
        } ?: return LoadedSeeds(emptyList())

        return when (val loaded = shardStore.readShard(descriptor)) {
            is SemanticShardLoadResult.Loaded -> LoadedSeeds(loaded.checkpoint.seeds)
            SemanticShardLoadResult.Corrupt,
            is SemanticShardLoadResult.Incompatible,
            is SemanticShardLoadResult.Failed -> null
        }
    }

    private fun findBaseDescriptor(shardId: SemanticShardId): DescriptorLookup {
        var descriptorCount = 0L
        var previous: SemanticShardId? = null
        for (ordinal in 0 until root.segmentCount) {
            val segment = when (val loaded = manifestStore.readSegment(root, ordinal)) {
                is SemanticShardManifestSegmentLoadResult.Loaded -> loaded.segment
                SemanticShardManifestSegmentLoadResult.Missing,
                SemanticShardManifestSegmentLoadResult.Corrupt,
                is SemanticShardManifestSegmentLoadResult.Incompatible,
                is SemanticShardManifestSegmentLoadResult.Failed -> return DescriptorLookup.Failed
            }
            for (descriptor in segment.shards) {
                val before = previous
                if (before != null && SHARD_ID_COMPARATOR.compare(before, descriptor.shardId) >= 0) {
                    return DescriptorLookup.Failed
                }
                previous = descriptor.shardId
                descriptorCount += 1L

                val compared = SHARD_ID_COMPARATOR.compare(descriptor.shardId, shardId)
                if (compared == 0) return DescriptorLookup.Found(descriptor)
                if (compared > 0) {
                    return if (descriptorCount <= root.shardDescriptorCount) {
                        DescriptorLookup.Missing
                    } else {
                        DescriptorLookup.Failed
                    }
                }
            }
        }
        return if (descriptorCount == root.shardDescriptorCount) {
            DescriptorLookup.Missing
        } else {
            DescriptorLookup.Failed
        }
    }

    private fun writeReplacementShard(
        shardId: SemanticShardId,
        seeds: List<SemanticIndexSeed>
    ): SemanticShardDescriptor? {
        if (orphanIntentCleanupBlocked) return null
        val ordered = seeds.sortedWith(
            compareBy<SemanticIndexSeed> { it.source.generationValue }
                .thenBy { stableEntityKey(it.source) }
        )
        return try {
            shardStore.writeShard(
                SemanticShardCheckpoint(
                    version = SemanticShardCheckpoint.CURRENT_VERSION,
                    shardId = shardId,
                    seeds = ordered
                ),
                beforeWrite = orphanIntentTracker::record
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun authoritativeEntryCount(
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Long = Math.addExact(
        authoritative.memory.entryCount,
        authoritative.knowledge.entryCount
    )

    private fun sameEntity(
        left: SemanticIndexSourceReference,
        right: SemanticIndexSourceReference
    ): Boolean = stableEntityKey(left) == stableEntityKey(right)

    private fun stableEntityKey(source: SemanticIndexSourceReference): String = when (source) {
        is SemanticIndexSourceReference.Memory -> "M:" + source.id.value
        is SemanticIndexSourceReference.Knowledge -> "K:" + source.id.value
    }

    private sealed interface DescriptorLookup {
        data class Found(val descriptor: SemanticShardDescriptor) : DescriptorLookup
        data object Missing : DescriptorLookup
        data object Failed : DescriptorLookup
    }

    private class LoadedSeeds(val seeds: List<SemanticIndexSeed>) {
        fun clear() {
            seeds.forEach { it.vector.clear() }
        }
    }

    private companion object {
        const val MAX_PENDING_DESCRIPTOR_MUTATIONS = 1_024

        val SHARD_ID_COMPARATOR = Comparator<SemanticShardId> { left, right ->
            val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
            if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
        }
    }
}
