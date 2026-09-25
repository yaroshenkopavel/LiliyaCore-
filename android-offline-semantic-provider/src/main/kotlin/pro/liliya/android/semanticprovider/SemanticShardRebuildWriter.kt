package pro.liliya.android.semanticprovider

/**
 * Bounded initial-rebuild writer.
 *
 * Authoritative pages must arrive in strictly increasing generation order within MEMORY first,
 * then KNOWLEDGE. At most one generation shard is retained in memory and each completed shard is
 * written exactly once. The manifest is published only by [finish] after the caller has verified
 * authoritative metadata stability.
 */
internal class SemanticShardRebuildWriter(
    private val store: SemanticShardStore,
    private val model: SemanticCheckpointModelBinding = SemanticCheckpointModelBinding.production()
) {
    private val descriptors = ArrayList<SemanticShardDescriptor>()
    private val pending = ArrayList<SemanticIndexSeed>(
        SemanticShardLayout.ENTRIES_PER_SHARD.toInt()
    )

    private var currentShard: SemanticShardId? = null
    private var lastDomainOrdinal: Int = -1
    private var lastGeneration: Long = 0L
    private var failed: Boolean = false

    fun append(
        seeds: List<SemanticIndexSeed>
    ): Boolean {
        if (failed) return false

        for (seed in seeds) {
            val source = seed.source
            val domainOrdinal = source.domain.ordinal
            if (domainOrdinal < lastDomainOrdinal) {
                return fail()
            }
            if (domainOrdinal > lastDomainOrdinal) {
                if (!flushPending()) return fail()
                lastDomainOrdinal = domainOrdinal
                lastGeneration = 0L
            }
            if (source.generationValue <= lastGeneration) {
                return fail()
            }

            val shardId = SemanticShardLayout.shardFor(source)
            val activeShard = currentShard
            if (activeShard != null && shardId != activeShard) {
                if (!isNextShard(activeShard, shardId)) return fail()
                if (!flushPending()) return fail()
            }
            currentShard = shardId
            lastGeneration = source.generationValue

            val copiedValues = seed.vector.copyValues()
            val copiedVector = try {
                SemanticEmbeddingVector(copiedValues)
            } finally {
                copiedValues.fill(0f)
            }
            pending += SemanticIndexSeed(source, copiedVector)
            if (pending.size > SemanticShardLayout.ENTRIES_PER_SHARD) {
                return fail()
            }
        }
        return true
    }

    fun finish(
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ): SemanticShardManifest? {
        if (failed || !flushPending()) {
            clear()
            return null
        }
        val manifest = try {
            SemanticShardManifest(
                version = SemanticShardManifest.CURRENT_VERSION,
                model = model,
                authoritative = authoritative,
                entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
                shards = descriptors.toList()
            )
        } catch (_: IllegalArgumentException) {
            failed = true
            return null
        }
        if (!store.writeManifest(manifest)) {
            failed = true
            return null
        }
        return manifest
    }

    fun clear() {
        pending.forEach { it.vector.clear() }
        pending.clear()
    }

    private fun flushPending(): Boolean {
        if (pending.isEmpty()) {
            currentShard = null
            return true
        }
        val shardId = currentShard ?: return false
        val descriptor = try {
            store.writeShard(
                SemanticShardCheckpoint(
                    version = SemanticShardCheckpoint.CURRENT_VERSION,
                    shardId = shardId,
                    seeds = pending.toList()
                )
            )
        } catch (_: IllegalArgumentException) {
            null
        }
        pending.forEach { it.vector.clear() }
        pending.clear()
        currentShard = null
        if (descriptor == null) return false
        descriptors += descriptor
        return true
    }

    private fun isNextShard(
        previous: SemanticShardId,
        next: SemanticShardId
    ): Boolean {
        if (next.domain.ordinal < previous.domain.ordinal) return false
        if (next.domain != previous.domain) return true
        return next.ordinal > previous.ordinal
    }

    private fun fail(): Boolean {
        failed = true
        clear()
        return false
    }
}
