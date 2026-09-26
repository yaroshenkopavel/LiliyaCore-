package pro.liliya.android.semanticprovider

internal interface SemanticShardActiveIndex {
    fun rank(
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): SemanticShardRankResult

    fun add(
        source: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticShardMutationResult

    fun replace(
        expected: SemanticIndexSourceReference,
        replacement: SemanticIndexSourceReference,
        replacementVector: SemanticEmbeddingVector
    ): SemanticShardMutationResult

    fun remove(source: SemanticIndexSourceReference): SemanticShardMutationResult

    fun persistManifest(
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Boolean

    fun entryCount(): Long
}