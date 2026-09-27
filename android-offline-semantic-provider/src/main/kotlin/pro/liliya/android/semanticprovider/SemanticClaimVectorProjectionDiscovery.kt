package pro.liliya.android.semanticprovider

internal fun interface SemanticClaimVectorQueryEmbeddingPort {
    fun embedQuery(text: String): OfflineSemanticSharedEmbeddingResult
}

internal class OfflineSemanticClaimVectorProjectionDiscovery(
    private val embedding: SemanticClaimVectorQueryEmbeddingPort,
    private val projectionStore: SemanticClaimVectorProjectionStore,
    private val routingStore: SemanticClaimVectorRoutingStore,
    private val maxRoutingNodeReads: Int = 256,
    private val maxShardReads: Int = 64
) : OfflineSemanticClaimVectorDiscoveryPort {
    override fun discover(
        text: String,
        maxCandidates: Int
    ): OfflineSemanticClaimVectorDiscoveryResult {
        if (maxCandidates !in 1..128) {
            return OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
                SemanticProviderFailureKind.REQUEST_REJECTED,
                "semantic claim vector candidate limit is invalid"
            )
        }

        val manifest = when (val loaded = projectionStore.loadManifest()) {
            SemanticClaimVectorProjectionManifestLoadResult.Missing ->
                return unavailable("semantic claim vector projection manifest is missing")
            SemanticClaimVectorProjectionManifestLoadResult.Corrupt ->
                return unavailable("semantic claim vector projection manifest is corrupt")
            is SemanticClaimVectorProjectionManifestLoadResult.Incompatible ->
                return unavailable(loaded.reason)
            is SemanticClaimVectorProjectionManifestLoadResult.Failed ->
                return OfflineSemanticClaimVectorDiscoveryResult.Failed(
                    loaded.reason,
                    loaded.throwable?.javaClass?.name
                )
            is SemanticClaimVectorProjectionManifestLoadResult.Loaded -> loaded.manifest
        }

        if (manifest.state != SemanticClaimVectorProjectionState.COMPLETE) {
            return unavailable("semantic claim vector projection is incomplete")
        }
        if (
            manifest.profileId != SemanticModelProfileV01.PROFILE_ID ||
            manifest.profileGeneration != SemanticModelProfileV01.PROFILE_GENERATION.value
        ) {
            return unavailable("semantic claim vector projection profile is incompatible")
        }

        val queryVector = when (val embedded = embedding.embedQuery(text)) {
            is OfflineSemanticSharedEmbeddingResult.Embedded -> embedded.vector
            OfflineSemanticSharedEmbeddingResult.Busy ->
                return OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
                    SemanticProviderFailureKind.BUSY,
                    "semantic embedding session is busy"
                )
            OfflineSemanticSharedEmbeddingResult.NotReady ->
                return unavailable("semantic embedding session is not ready")
            OfflineSemanticSharedEmbeddingResult.ResourceRejected ->
                return OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
                    SemanticProviderFailureKind.RESOURCE_REJECTED,
                    "semantic claim vector query exceeds resource bounds"
                )
            OfflineSemanticSharedEmbeddingResult.RequestRejected ->
                return OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
                    SemanticProviderFailureKind.REQUEST_REJECTED,
                    "semantic claim vector query is invalid"
                )
            OfflineSemanticSharedEmbeddingResult.SessionFailed ->
                return OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
                    SemanticProviderFailureKind.SESSION_FAILED,
                    "semantic embedding session failed"
                )
            OfflineSemanticSharedEmbeddingResult.OperationFailed ->
                return OfflineSemanticClaimVectorDiscoveryResult.Failed(
                    "semantic claim vector embedding operation failed"
                )
        }

        return try {
            when (
                val routed = SemanticClaimVectorRoutingQuery(
                    projectionStore = projectionStore,
                    routingStore = routingStore
                ).rank(
                    manifest = manifest,
                    query = queryVector,
                    policy = SemanticClaimVectorRoutingPolicy(
                        maxCandidates = maxCandidates,
                        maxRoutingNodeReads = maxRoutingNodeReads,
                        maxShardReads = maxShardReads
                    )
                )
            ) {
                SemanticClaimVectorRoutingQueryResult.Missing ->
                    unavailable("semantic claim vector routing is missing")
                SemanticClaimVectorRoutingQueryResult.Corrupt ->
                    unavailable("semantic claim vector routing or shard is corrupt")
                is SemanticClaimVectorRoutingQueryResult.Incompatible ->
                    unavailable(routed.reason)
                is SemanticClaimVectorRoutingQueryResult.Failed ->
                    OfflineSemanticClaimVectorDiscoveryResult.Failed(
                        routed.reason,
                        routed.throwable?.javaClass?.name
                    )
                is SemanticClaimVectorRoutingQueryResult.Routed ->
                    OfflineSemanticClaimVectorDiscoveryResult.Ranked(
                        identity = OfflineSemanticClaimVectorIdentity(
                            profileId = manifest.profileId,
                            profileGeneration = manifest.profileGeneration,
                            indexGeneration = manifest.indexGeneration,
                            source = manifest.source
                        ),
                        candidates = routed.candidates,
                        truncated = routed.truncated
                    )
            }
        } finally {
            queryVector.clear()
        }
    }

    private fun unavailable(reason: String): OfflineSemanticClaimVectorDiscoveryResult.Unavailable =
        OfflineSemanticClaimVectorDiscoveryResult.Unavailable(
            SemanticProviderFailureKind.INDEX_UNAVAILABLE,
            reason
        )

    companion object {
        fun fromProvider(
            provider: OfflineSemanticProviderComposition,
            storage: AndroidOfflineSemanticShardStorage,
            maxRoutingNodeReads: Int = 256,
            maxShardReads: Int = 64
        ): OfflineSemanticClaimVectorProjectionDiscovery {
            val projectionStore = SemanticClaimVectorProjectionStore(storage)
            return OfflineSemanticClaimVectorProjectionDiscovery(
                embedding = SemanticClaimVectorQueryEmbeddingPort(provider::embedSharedClaimQuery),
                projectionStore = projectionStore,
                routingStore = SemanticClaimVectorRoutingStore(storage),
                maxRoutingNodeReads = maxRoutingNodeReads,
                maxShardReads = maxShardReads
            )
        }
    }
}