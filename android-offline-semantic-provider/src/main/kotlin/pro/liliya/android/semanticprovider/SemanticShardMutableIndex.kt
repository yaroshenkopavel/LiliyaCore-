package pro.liliya.android.semanticprovider

internal sealed interface SemanticShardMutationResult {
    data object Applied : SemanticShardMutationResult
    data object AlreadyApplied : SemanticShardMutationResult
    data object StaleOrConflicting : SemanticShardMutationResult
    data object StorageFailed : SemanticShardMutationResult
}

/**
 * Mutable in-process descriptor state over immutable content-addressed semantic shard blobs.
 *
 * Shard blobs are written first. Descriptor state changes only after all required blob writes
 * succeed. Durable manifest publication is explicit and separate so the host can bind it to one
 * authoritative Memory/Knowledge metadata checkpoint.
 */
internal class SemanticShardMutableIndex(
    private val store: SemanticShardStore,
    initialManifest: SemanticShardManifest
) {
    private var manifest: SemanticShardManifest = initialManifest

    @Synchronized
    fun rank(
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): SemanticShardRankResult =
        store.rank(manifest, domain, query, maxCandidates)

    @Synchronized
    fun add(
        source: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticShardMutationResult {
        val shardId = SemanticShardLayout.shardFor(source)
        val loaded = loadSeeds(shardId)
            ?: return SemanticShardMutationResult.StorageFailed
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
            updateDescriptor(descriptor)
            return SemanticShardMutationResult.Applied
        } finally {
            loaded.clear()
        }
    }

    @Synchronized
    fun replace(
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
                updateDescriptor(descriptor)
                return SemanticShardMutationResult.Applied
            }

            val newLoaded = loadSeeds(newShardId)
                ?: return SemanticShardMutationResult.StorageFailed
            try {
                if (newLoaded.seeds.any { sameEntity(it.source, replacement) }) {
                    return SemanticShardMutationResult.StaleOrConflicting
                }

                val oldNext = oldLoaded.seeds.filterIndexed { index, _ ->
                    index != expectedIndex
                }
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

                if (oldDescriptor == null) removeDescriptor(oldShardId)
                else updateDescriptor(oldDescriptor)
                updateDescriptor(newDescriptor)
                return SemanticShardMutationResult.Applied
            } finally {
                newLoaded.clear()
            }
        } finally {
            oldLoaded.clear()
        }
    }

    @Synchronized
    fun remove(
        source: SemanticIndexSourceReference
    ): SemanticShardMutationResult {
        val shardId = SemanticShardLayout.shardFor(source)
        val loaded = loadSeeds(shardId)
            ?: return SemanticShardMutationResult.StorageFailed
        try {
            val index = loaded.seeds.indexOfFirst { it.source == source }
            if (index < 0) return SemanticShardMutationResult.StaleOrConflicting

            val remaining = loaded.seeds.filterIndexed { current, _ -> current != index }
            if (remaining.isEmpty()) {
                removeDescriptor(shardId)
                return SemanticShardMutationResult.Applied
            }

            val descriptor = writeReplacementShard(shardId, remaining)
                ?: return SemanticShardMutationResult.StorageFailed
            updateDescriptor(descriptor)
            return SemanticShardMutationResult.Applied
        } finally {
            loaded.clear()
        }
    }

    @Synchronized
    fun appendRebuildBatch(
        seeds: List<SemanticIndexSeed>
    ): Boolean {
        if (seeds.isEmpty()) return true

        val grouped = LinkedHashMap<SemanticShardId, MutableList<SemanticIndexSeed>>()
        seeds.forEach { seed ->
            grouped.getOrPut(SemanticShardLayout.shardFor(seed.source)) {
                ArrayList()
            } += seed
        }

        val pendingDescriptors = ArrayList<SemanticShardDescriptor>(grouped.size)
        for ((shardId, additions) in grouped) {
            val loaded = loadSeeds(shardId) ?: return false
            try {
                val identities = loaded.seeds
                    .mapTo(HashSet(loaded.seeds.size + additions.size)) {
                        stableEntityKey(it.source)
                    }
                if (additions.any { !identities.add(stableEntityKey(it.source)) }) {
                    return false
                }
                val combined = ArrayList<SemanticIndexSeed>(
                    loaded.seeds.size + additions.size
                )
                combined += loaded.seeds
                combined += additions
                val descriptor = writeReplacementShard(shardId, combined)
                    ?: return false
                pendingDescriptors += descriptor
            } finally {
                loaded.clear()
            }
        }

        pendingDescriptors.forEach(::updateDescriptor)
        return true
    }

    @Synchronized
    fun persistManifest(
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Boolean {
        val next = SemanticShardManifest(
            version = SemanticShardManifest.CURRENT_VERSION,
            model = manifest.model,
            authoritative = authoritative,
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            shards = manifest.shards
        )
        if (!store.writeManifest(next)) return false
        manifest = next
        return true
    }

    @Synchronized
    fun currentManifest(): SemanticShardManifest = manifest

    private fun loadSeeds(shardId: SemanticShardId): LoadedSeeds? {
        val descriptor = manifest.shards.firstOrNull { it.shardId == shardId }
            ?: return LoadedSeeds(emptyList())
        return when (val loaded = store.readShard(descriptor)) {
            is SemanticShardLoadResult.Loaded -> LoadedSeeds(loaded.checkpoint.seeds)
            SemanticShardLoadResult.Corrupt,
            is SemanticShardLoadResult.Incompatible,
            is SemanticShardLoadResult.Failed -> null
        }
    }

    private fun writeReplacementShard(
        shardId: SemanticShardId,
        seeds: List<SemanticIndexSeed>
    ): SemanticShardDescriptor? {
        val ordered = seeds.sortedWith(
            compareBy<SemanticIndexSeed> { it.source.generationValue }
                .thenBy { stableEntityKey(it.source) }
        )
        return try {
            store.writeShard(
                SemanticShardCheckpoint(
                    version = SemanticShardCheckpoint.CURRENT_VERSION,
                    shardId = shardId,
                    seeds = ordered
                )
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun updateDescriptor(descriptor: SemanticShardDescriptor) {
        val next = manifest.shards
            .filterNot { it.shardId == descriptor.shardId }
            .plus(descriptor)
            .sortedWith(
                compareBy<SemanticShardDescriptor> { it.shardId.domain.ordinal }
                    .thenBy { it.shardId.ordinal }
            )
        manifest = manifest.copy(shards = next)
    }

    private fun removeDescriptor(shardId: SemanticShardId) {
        manifest = manifest.copy(
            shards = manifest.shards.filterNot { it.shardId == shardId }
        )
    }

    private fun sameEntity(
        left: SemanticIndexSourceReference,
        right: SemanticIndexSourceReference
    ): Boolean = stableEntityKey(left) == stableEntityKey(right)

    private fun stableEntityKey(source: SemanticIndexSourceReference): String = when (source) {
        is SemanticIndexSourceReference.Memory -> "M:" + source.id.value
        is SemanticIndexSourceReference.Knowledge -> "K:" + source.id.value
    }

    private class LoadedSeeds(
        val seeds: List<SemanticIndexSeed>
    ) {
        fun clear() {
            seeds.forEach { it.vector.clear() }
        }
    }
}
