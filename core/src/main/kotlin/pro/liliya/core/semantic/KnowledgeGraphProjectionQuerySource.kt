package pro.liliya.core.semantic

internal interface KnowledgeGraphProjectionCanonicalSource {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint
    fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult
}

private class RepositoryKnowledgeGraphProjectionCanonicalSource(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : KnowledgeGraphProjectionCanonicalSource {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun readExact(
        reference: SemanticClaimVersionReference
    ): SemanticClaimReadResult =
        repository.readExact(reference)
}

sealed interface KnowledgeGraphProjectionQueryResult {
    data class Available(
        val fragment: KnowledgeGraphStoredFragment,
        val canonicalRecord: SemanticClaimRecord
    ) : KnowledgeGraphProjectionQueryResult

    data object Missing : KnowledgeGraphProjectionQueryResult
    data class RebuildRequired(val reason: String) : KnowledgeGraphProjectionQueryResult
    data class EncryptionUnavailable(val category: String) : KnowledgeGraphProjectionQueryResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        KnowledgeGraphProjectionQueryResult
}

class KnowledgeGraphProjectionQuerySource internal constructor(
    private val source: KnowledgeGraphProjectionCanonicalSource,
    private val reader: KnowledgeGraphProjectionReader
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        store: EncryptedPersistentKnowledgeGraphProjectionStore
    ) : this(
        source = RepositoryKnowledgeGraphProjectionCanonicalSource(repository),
        reader = store
    )

    fun exactClaim(
        reference: SemanticClaimVersionReference
    ): KnowledgeGraphProjectionQueryResult {
        val manifest = when (val loaded = reader.readManifest()) {
            KnowledgeGraphProjectionManifestLoadResult.Missing ->
                return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                    "knowledge graph projection manifest is missing"
                )
            KnowledgeGraphProjectionManifestLoadResult.Corrupt ->
                return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                    "knowledge graph projection manifest is corrupt"
                )
            is KnowledgeGraphProjectionManifestLoadResult.Incompatible ->
                return KnowledgeGraphProjectionQueryResult.RebuildRequired(loaded.reason)
            is KnowledgeGraphProjectionManifestLoadResult.EncryptionUnavailable ->
                return KnowledgeGraphProjectionQueryResult.EncryptionUnavailable(
                    loaded.category
                )
            is KnowledgeGraphProjectionManifestLoadResult.Failed ->
                return KnowledgeGraphProjectionQueryResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is KnowledgeGraphProjectionManifestLoadResult.Loaded -> loaded.manifest
        }

        if (manifest.state != KnowledgeGraphProjectionState.COMPLETE) {
            return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                "knowledge graph projection is incomplete"
            )
        }

        val before = source.sourceCheckpoint()
        if (before != manifest.source) {
            return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                "knowledge graph projection source checkpoint is stale"
            )
        }

        val stored = when (val loaded = reader.readFragment(manifest.buildEpoch, reference)) {
            KnowledgeGraphProjectionFragmentLoadResult.Missing ->
                return KnowledgeGraphProjectionQueryResult.Missing
            KnowledgeGraphProjectionFragmentLoadResult.Corrupt ->
                return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                    "knowledge graph projection fragment is corrupt"
                )
            is KnowledgeGraphProjectionFragmentLoadResult.Incompatible ->
                return KnowledgeGraphProjectionQueryResult.RebuildRequired(loaded.reason)
            is KnowledgeGraphProjectionFragmentLoadResult.EncryptionUnavailable ->
                return KnowledgeGraphProjectionQueryResult.EncryptionUnavailable(
                    loaded.category
                )
            is KnowledgeGraphProjectionFragmentLoadResult.Failed ->
                return KnowledgeGraphProjectionQueryResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is KnowledgeGraphProjectionFragmentLoadResult.Loaded -> loaded.fragment
        }

        if (stored.buildEpoch != manifest.buildEpoch) {
            return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                "knowledge graph projection fragment build epoch is stale"
            )
        }

        val canonical = when (val read = source.readExact(reference)) {
            SemanticClaimReadResult.Missing ->
                return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                    "canonical semantic claim is missing"
                )
            SemanticClaimReadResult.Corrupt ->
                return KnowledgeGraphProjectionQueryResult.Failed(
                    "canonical semantic claim is corrupt"
                )
            is SemanticClaimReadResult.Incompatible ->
                return KnowledgeGraphProjectionQueryResult.Failed(read.reason)
            is SemanticClaimReadResult.EncryptionUnavailable ->
                return KnowledgeGraphProjectionQueryResult.EncryptionUnavailable(
                    read.category.toString()
                )
            is SemanticClaimReadResult.Failed ->
                return KnowledgeGraphProjectionQueryResult.Failed(
                    read.reason,
                    read.throwable
                )
            is SemanticClaimReadResult.Found -> read.record
        }

        val after = source.sourceCheckpoint()
        if (after != manifest.source) {
            return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                "semantic claim source drifted during graph revalidation"
            )
        }

        val projected = KnowledgeGraphStoredFragment.from(
            manifest.buildEpoch,
            KnowledgeGraphSingleClaimProjector.project(canonical)
        )
        if (projected != stored) {
            return KnowledgeGraphProjectionQueryResult.RebuildRequired(
                "stored knowledge graph fragment does not match canonical projection"
            )
        }

        return KnowledgeGraphProjectionQueryResult.Available(
            fragment = stored,
            canonicalRecord = canonical
        )
    }
}
