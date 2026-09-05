package pro.liliya.android.semanticprovider

internal data class SemanticIndexSeed(
    val source: SemanticIndexSourceReference,
    val vector: SemanticEmbeddingVector
) {
    override fun toString(): String = "SemanticIndexSeed(source=$source, vector=<redacted:384>)"
}

internal sealed interface SemanticIndexRebuildResult {
    data class Published(val entryCount: Int) : SemanticIndexRebuildResult
    data object DuplicateOrConflictingIdentity : SemanticIndexRebuildResult
    data object CapacityRejected : SemanticIndexRebuildResult
}

/**
 * Owns the currently published advisory semantic index.
 *
 * Rebuild is transactional at the publication boundary: a complete replacement index is built
 * off to the side and becomes visible only after every seed has been accepted. Any duplicate,
 * generation conflict or capacity failure leaves the previously published index untouched.
 */
internal class SemanticIndexPublication(
    private val profileGeneration: SemanticProfileGeneration,
    private val limits: SemanticFlatIndexLimits = SemanticFlatIndexLimits()
) {
    @Volatile
    private var published: SemanticFlatIndex = newIndex()

    @Synchronized
    fun rebuild(seeds: List<SemanticIndexSeed>): SemanticIndexRebuildResult {
        val replacement = newIndex()
        for (seed in seeds) {
            when (replacement.addExact(seed.source, seed.vector)) {
                SemanticIndexAddResult.Indexed -> Unit
                SemanticIndexAddResult.CapacityRejected ->
                    return SemanticIndexRebuildResult.CapacityRejected
                SemanticIndexAddResult.DuplicateExact,
                SemanticIndexAddResult.EntityAlreadyIndexed ->
                    return SemanticIndexRebuildResult.DuplicateOrConflictingIdentity
            }
        }
        published = replacement
        return SemanticIndexRebuildResult.Published(seeds.size)
    }

    @Synchronized
    fun addExact(
        source: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticIndexAddResult = published.addExact(source, vector)

    @Synchronized
    fun replaceExact(
        expected: SemanticIndexSourceReference,
        replacement: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticIndexReplaceResult = published.replaceExact(expected, replacement, vector)

    @Synchronized
    fun removeExact(source: SemanticIndexSourceReference): SemanticIndexRemoveResult =
        published.removeExact(source)

    fun rank(
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): List<SemanticRankedCandidate> = published.rank(domain, query, maxCandidates)

    fun size(domain: SemanticIndexDomain? = null): Int = published.size(domain)

    private fun newIndex(): SemanticFlatIndex = SemanticFlatIndex(profileGeneration, limits)
}
