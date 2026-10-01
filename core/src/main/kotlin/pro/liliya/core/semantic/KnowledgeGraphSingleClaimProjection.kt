package pro.liliya.core.semantic

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import pro.liliya.core.retrieval.compareUtf8

@JvmInline
value class KnowledgeGraphNodeId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

@JvmInline
value class KnowledgeGraphEdgeId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

enum class KnowledgeGraphNodeKind {
    SUBJECT_ENTITY,
    CLAIM_VERSION,
    OBJECT_ENTITY,
    OBJECT_LITERAL
}

enum class KnowledgeGraphEdgeKind {
    SUBJECT_TO_CLAIM,
    CLAIM_TO_OBJECT
}

enum class KnowledgeGraphLiteralKind {
    TEXT,
    NUMBER,
    BOOLEAN
}

sealed interface KnowledgeGraphNode {
    val id: KnowledgeGraphNodeId
    val kind: KnowledgeGraphNodeKind

    data class Entity(
        override val id: KnowledgeGraphNodeId,
        override val kind: KnowledgeGraphNodeKind,
        val reference: SemanticEntityReference
    ) : KnowledgeGraphNode {
        init {
            require(
                kind == KnowledgeGraphNodeKind.SUBJECT_ENTITY ||
                    kind == KnowledgeGraphNodeKind.OBJECT_ENTITY
            )
        }
    }

    data class ClaimVersion(
        override val id: KnowledgeGraphNodeId,
        val reference: SemanticClaimVersionReference,
        val predicate: String,
        val temporal: SemanticClaimTemporalState,
        val provenance: SemanticClaimProvenance
    ) : KnowledgeGraphNode {
        override val kind: KnowledgeGraphNodeKind = KnowledgeGraphNodeKind.CLAIM_VERSION

        init {
            require(predicate.isNotBlank())
        }
    }

    data class Literal(
        override val id: KnowledgeGraphNodeId,
        val literalKind: KnowledgeGraphLiteralKind,
        val canonicalValue: String
    ) : KnowledgeGraphNode {
        override val kind: KnowledgeGraphNodeKind = KnowledgeGraphNodeKind.OBJECT_LITERAL

        init {
            require(canonicalValue.isNotBlank())
        }
    }
}

data class KnowledgeGraphEdge(
    val id: KnowledgeGraphEdgeId,
    val kind: KnowledgeGraphEdgeKind,
    val source: KnowledgeGraphNodeId,
    val target: KnowledgeGraphNodeId,
    val claimReference: SemanticClaimVersionReference
) {
    init {
        require(source != target || kind == KnowledgeGraphEdgeKind.CLAIM_TO_OBJECT)
    }
}

data class KnowledgeGraphFragment(
    val sourceClaim: SemanticClaimVersionReference,
    val nodes: List<KnowledgeGraphNode>,
    val edges: List<KnowledgeGraphEdge>,
    val advisoryOnly: Boolean = true
) {
    init {
        require(nodes.size in 2..3)
        require(edges.size == 2)
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(edges.map { it.id }.distinct().size == edges.size)
        require(advisoryOnly)
    }
}

object KnowledgeGraphSingleClaimProjector {
    const val MAX_NODES_PER_CLAIM = 3
    const val MAX_EDGES_PER_CLAIM = 2

    fun project(record: SemanticClaimRecord): KnowledgeGraphFragment {
        val reference = SemanticClaimVersionReference(record.id, record.version)
        val subjectId = KnowledgeGraphIds.entity(record.identity.subject)
        val claimId = KnowledgeGraphIds.claim(reference)
        val objectNode = objectNode(record.objectValue)

        val subjectNode = KnowledgeGraphNode.Entity(
            id = subjectId,
            kind = KnowledgeGraphNodeKind.SUBJECT_ENTITY,
            reference = record.identity.subject
        )
        val claimNode = KnowledgeGraphNode.ClaimVersion(
            id = claimId,
            reference = reference,
            predicate = record.identity.predicate,
            temporal = record.temporal,
            provenance = record.provenance
        )

        val nodesById = linkedMapOf<KnowledgeGraphNodeId, KnowledgeGraphNode>()
        nodesById[subjectNode.id] = subjectNode
        nodesById[claimNode.id] = claimNode

        when (objectNode) {
            is KnowledgeGraphNode.Entity -> {
                val existing = nodesById[objectNode.id]
                if (existing == null) {
                    nodesById[objectNode.id] = objectNode
                } else if (existing is KnowledgeGraphNode.Entity) {
                    nodesById[objectNode.id] = existing.copy(
                        kind = KnowledgeGraphNodeKind.SUBJECT_ENTITY
                    )
                }
            }
            is KnowledgeGraphNode.Literal -> nodesById[objectNode.id] = objectNode
            is KnowledgeGraphNode.ClaimVersion -> error("object node must not be a claim node")
        }

        val subjectEdge = KnowledgeGraphEdge(
            id = KnowledgeGraphIds.edge(
                subjectId,
                KnowledgeGraphEdgeKind.SUBJECT_TO_CLAIM,
                claimId,
                reference
            ),
            kind = KnowledgeGraphEdgeKind.SUBJECT_TO_CLAIM,
            source = subjectId,
            target = claimId,
            claimReference = reference
        )
        val objectEdge = KnowledgeGraphEdge(
            id = KnowledgeGraphIds.edge(
                claimId,
                KnowledgeGraphEdgeKind.CLAIM_TO_OBJECT,
                objectNode.id,
                reference
            ),
            kind = KnowledgeGraphEdgeKind.CLAIM_TO_OBJECT,
            source = claimId,
            target = objectNode.id,
            claimReference = reference
        )

        val nodes = nodesById.values.sortedWith { left, right ->
            compareUtf8(left.id.value, right.id.value)
        }
        val edges = listOf(subjectEdge, objectEdge).sortedWith { left, right ->
            compareUtf8(left.id.value, right.id.value)
        }

        check(nodes.size <= MAX_NODES_PER_CLAIM)
        check(edges.size <= MAX_EDGES_PER_CLAIM)

        return KnowledgeGraphFragment(
            sourceClaim = reference,
            nodes = nodes,
            edges = edges
        )
    }

    private fun objectNode(value: SemanticClaimObject): KnowledgeGraphNode =
        when (value) {
            is SemanticClaimObject.Entity -> KnowledgeGraphNode.Entity(
                id = KnowledgeGraphIds.entity(value.reference),
                kind = KnowledgeGraphNodeKind.OBJECT_ENTITY,
                reference = value.reference
            )
            is SemanticClaimObject.Text -> KnowledgeGraphNode.Literal(
                id = KnowledgeGraphIds.literal(
                    KnowledgeGraphLiteralKind.TEXT,
                    value.value
                ),
                literalKind = KnowledgeGraphLiteralKind.TEXT,
                canonicalValue = value.value
            )
            is SemanticClaimObject.Number -> KnowledgeGraphNode.Literal(
                id = KnowledgeGraphIds.literal(
                    KnowledgeGraphLiteralKind.NUMBER,
                    value.canonical
                ),
                literalKind = KnowledgeGraphLiteralKind.NUMBER,
                canonicalValue = value.canonical
            )
            is SemanticClaimObject.BooleanValue -> {
                val canonical = if (value.value) "true" else "false"
                KnowledgeGraphNode.Literal(
                    id = KnowledgeGraphIds.literal(
                        KnowledgeGraphLiteralKind.BOOLEAN,
                        canonical
                    ),
                    literalKind = KnowledgeGraphLiteralKind.BOOLEAN,
                    canonicalValue = canonical
                )
            }
        }
}

private object KnowledgeGraphIds {
    fun entity(reference: SemanticEntityReference): KnowledgeGraphNodeId =
        nodeId("entity", reference.namespace, reference.id)

    fun claim(reference: SemanticClaimVersionReference): KnowledgeGraphNodeId =
        nodeId(
            "claim-version",
            reference.claimId.value,
            reference.version.value.toString()
        )

    fun literal(
        kind: KnowledgeGraphLiteralKind,
        canonicalValue: String
    ): KnowledgeGraphNodeId =
        nodeId("literal", kind.name, canonicalValue)

    fun edge(
        source: KnowledgeGraphNodeId,
        kind: KnowledgeGraphEdgeKind,
        target: KnowledgeGraphNodeId,
        claim: SemanticClaimVersionReference
    ): KnowledgeGraphEdgeId {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, "knowledge-graph-edge-v1")
        put(digest, source.value)
        put(digest, kind.name)
        put(digest, target.value)
        put(digest, claim.claimId.value)
        put(digest, claim.version.value.toString())
        return KnowledgeGraphEdgeId("kg-edge-" + digest.hex())
    }

    private fun nodeId(vararg parts: String): KnowledgeGraphNodeId {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, "knowledge-graph-node-v1")
        parts.forEach { put(digest, it) }
        return KnowledgeGraphNodeId("kg-node-" + digest.hex())
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun MessageDigest.hex(): String =
        digest().joinToString("") { "%02x".format(it) }
}