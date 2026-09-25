package pro.liliya.android.semanticprovider

/**
 * Bounded initial rebuild writer for v3 segmented manifests.
 *
 * At most one semantic shard plus one bounded manifest descriptor segment are retained in memory.
 * The v3 root is published only after authoritative metadata stability is verified by the caller.
 */
internal class SemanticShardRebuildWriterV3(
    private val shardStore: SemanticShardStore,
    manifestStore: SemanticShardManifestV3Store,
    model: SemanticCheckpointModelBinding = SemanticCheckpointModelBinding.production()
) {
    private val manifestWriter = SemanticShardManifestV3PublicationWriter(
        store = manifestStore,
        model = model
    )
    private val pending = ArrayList<SemanticIndexSeed>(
        SemanticShardLayout.ENTRIES_PER_SHARD.toInt()
    )

    private var currentShard: SemanticShardId? = null
    private var lastDomainOrdinal: Int = -1
    private var lastGeneration: Long = 0L
    private var failed: Boolean = false

    fun append(seeds: List<SemanticIndexSeed>): Boolean {
        if (failed) return false

        for (seed in seeds) {
            val source = seed.source
            val domainOrdinal = source.domain.ordinal
            if (domainOrdinal < lastDomainOrdinal) return fail()
            if (domainOrdinal > lastDomainOrdinal) {
                if (!flushPending()) return fail()
                lastDomainOrdinal = domainOrdinal
                lastGeneration = 0L
            }
            if (source.generationValue <= lastGeneration) return fail()

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
            if (pending.size > SemanticShardLayout.ENTRIES_PER_SHARD) return fail()
        }
        return true
    }

    fun finish(
        authoritative: SemanticAuthoritativeMetadataCheckpoint,
        beforeCommit: (SemanticShardManifestRootV3) -> Boolean = { true }
    ): SemanticShardManifestRootV3? {
        if (failed || !flushPending()) {
            clear()
            return null
        }
        return manifestWriter.finish(authoritative, beforeCommit)
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
            shardStore.writeShard(
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
        return manifestWriter.append(descriptor)
    }

    private fun isNextShard(previous: SemanticShardId, next: SemanticShardId): Boolean {
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
