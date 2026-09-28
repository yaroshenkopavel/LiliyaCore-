package pro.liliya.core.semantic

enum class KnowledgeGraphProjectionState {
    INCOMPLETE,
    COMPLETE
}

data class KnowledgeGraphProjectionPolicy(
    val version: Int = CURRENT_VERSION,
    val claimPageSize: Int = DEFAULT_CLAIM_PAGE_SIZE
) {
    init {
        require(version == CURRENT_VERSION)
        require(claimPageSize in 1..MAX_CLAIM_PAGE_SIZE)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_CLAIM_PAGE_SIZE = 128
        const val MAX_CLAIM_PAGE_SIZE = 128
    }
}

data class KnowledgeGraphProjectionManifest(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val source: SemanticClaimSourceCheckpoint,
    val state: KnowledgeGraphProjectionState,
    val policyVersion: Int,
    val claimPageSize: Int,
    val projectedClaimCount: Long,
    val projectedNodeCount: Long,
    val projectedEdgeCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <= 64)
        require(policyVersion == KnowledgeGraphProjectionPolicy.CURRENT_VERSION)
        require(claimPageSize in 1..KnowledgeGraphProjectionPolicy.MAX_CLAIM_PAGE_SIZE)
        require(projectedClaimCount >= 0L)
        require(projectedNodeCount >= 0L)
        require(projectedEdgeCount >= 0L)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class KnowledgeGraphStoredNode(
    val id: KnowledgeGraphNodeId,
    val kind: KnowledgeGraphNodeKind
)

data class KnowledgeGraphStoredEdge(
    val id: KnowledgeGraphEdgeId,
    val kind: KnowledgeGraphEdgeKind,
    val source: KnowledgeGraphNodeId,
    val target: KnowledgeGraphNodeId,
    val claimReference: SemanticClaimVersionReference
)

data class KnowledgeGraphStoredFragment(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val sourceClaim: SemanticClaimVersionReference,
    val nodes: List<KnowledgeGraphStoredNode>,
    val edges: List<KnowledgeGraphStoredEdge>,
    val advisoryOnly: Boolean = true
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <= 64)
        require(nodes.size in 2..KnowledgeGraphSingleClaimProjector.MAX_NODES_PER_CLAIM)
        require(edges.size == KnowledgeGraphSingleClaimProjector.MAX_EDGES_PER_CLAIM)
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(edges.map { it.id }.distinct().size == edges.size)
        require(edges.all { it.claimReference == sourceClaim })
        require(advisoryOnly)
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun from(buildEpoch: String, fragment: KnowledgeGraphFragment): KnowledgeGraphStoredFragment =
            KnowledgeGraphStoredFragment(
                buildEpoch = buildEpoch,
                sourceClaim = fragment.sourceClaim,
                nodes = fragment.nodes.map { KnowledgeGraphStoredNode(it.id, it.kind) },
                edges = fragment.edges.map {
                    KnowledgeGraphStoredEdge(
                        id = it.id,
                        kind = it.kind,
                        source = it.source,
                        target = it.target,
                        claimReference = it.claimReference
                    )
                },
                advisoryOnly = fragment.advisoryOnly
            )
    }
}

sealed interface KnowledgeGraphProjectionWriteResult {
    data object Written : KnowledgeGraphProjectionWriteResult
    data class Rejected(val reason: String) : KnowledgeGraphProjectionWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        KnowledgeGraphProjectionWriteResult
}

sealed interface KnowledgeGraphProjectionManifestLoadResult {
    data object Missing : KnowledgeGraphProjectionManifestLoadResult
    data class Loaded(val manifest: KnowledgeGraphProjectionManifest) :
        KnowledgeGraphProjectionManifestLoadResult
    data object Corrupt : KnowledgeGraphProjectionManifestLoadResult
    data class Incompatible(val reason: String) : KnowledgeGraphProjectionManifestLoadResult
    data class EncryptionUnavailable(val category: String) : KnowledgeGraphProjectionManifestLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        KnowledgeGraphProjectionManifestLoadResult
}

sealed interface KnowledgeGraphProjectionFragmentLoadResult {
    data object Missing : KnowledgeGraphProjectionFragmentLoadResult
    data class Loaded(val fragment: KnowledgeGraphStoredFragment) :
        KnowledgeGraphProjectionFragmentLoadResult
    data object Corrupt : KnowledgeGraphProjectionFragmentLoadResult
    data class Incompatible(val reason: String) : KnowledgeGraphProjectionFragmentLoadResult
    data class EncryptionUnavailable(val category: String) : KnowledgeGraphProjectionFragmentLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        KnowledgeGraphProjectionFragmentLoadResult
}


internal interface KnowledgeGraphProjectionReader {
    fun readManifest(): KnowledgeGraphProjectionManifestLoadResult
    fun readFragment(
        reference: SemanticClaimVersionReference
    ): KnowledgeGraphProjectionFragmentLoadResult
}
