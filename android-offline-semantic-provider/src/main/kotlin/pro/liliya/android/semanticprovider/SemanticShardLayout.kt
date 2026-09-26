package pro.liliya.android.semanticprovider

internal data class SemanticShardId(
    val domain: SemanticIndexDomain,
    val ordinal: Long
) {
    init {
        require(ordinal >= 0L) { "semantic shard ordinal must not be negative" }
    }

    override fun toString(): String =
        "SemanticShardId(domain=$domain, ordinal=$ordinal)"
}

/**
 * Deterministic unbounded shard layout over authoritative source generations.
 *
 * A shard is bounded to [ENTRIES_PER_SHARD] possible generation values per domain. Shard count is
 * not capped: higher durable generations create higher shard ordinals rather than rejecting the
 * lifetime corpus.
 */
internal object SemanticShardLayout {
    const val ENTRIES_PER_SHARD: Long = 2_048L

    fun shardFor(source: SemanticIndexSourceReference): SemanticShardId =
        shardFor(source.domain, source.generationValue)

    fun shardFor(
        domain: SemanticIndexDomain,
        generation: Long
    ): SemanticShardId {
        require(generation > 0L) { "semantic source generation must be positive" }
        return SemanticShardId(
            domain = domain,
            ordinal = (generation - 1L) / ENTRIES_PER_SHARD
        )
    }

    fun firstGeneration(shard: SemanticShardId): Long =
        Math.addExact(
            Math.multiplyExact(shard.ordinal, ENTRIES_PER_SHARD),
            1L
        )

    fun lastGeneration(shard: SemanticShardId): Long =
        Math.multiplyExact(
            Math.addExact(shard.ordinal, 1L),
            ENTRIES_PER_SHARD
        )
}
