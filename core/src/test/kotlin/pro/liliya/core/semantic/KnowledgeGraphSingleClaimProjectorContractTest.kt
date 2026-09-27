package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference

class KnowledgeGraphSingleClaimProjectorContractTest {
    @Test
    fun entity_object_projects_three_traceable_nodes_and_two_edges() {
        val subject = SemanticEntityReference("person", "user")
        val target = SemanticEntityReference("city", "lviv")
        val claim = claim(
            subject = subject,
            objectValue = SemanticClaimObject.Entity(target)
        )

        val fragment = KnowledgeGraphSingleClaimProjector.project(claim)

        assertEquals(3, fragment.nodes.size)
        assertEquals(2, fragment.edges.size)
        assertEquals(
            SemanticClaimVersionReference(claim.id, claim.version),
            fragment.sourceClaim
        )
        assertTrue(fragment.advisoryOnly)

        val claimNode = fragment.nodes.filterIsInstance<KnowledgeGraphNode.ClaimVersion>().single()
        assertEquals(claim.identity.predicate, claimNode.predicate)
        assertEquals(claim.temporal, claimNode.temporal)
        assertEquals(claim.provenance, claimNode.provenance)

        val entityNodes = fragment.nodes.filterIsInstance<KnowledgeGraphNode.Entity>()
        assertEquals(setOf(subject, target), entityNodes.map { it.reference }.toSet())
        assertEquals(
            setOf(
                KnowledgeGraphEdgeKind.SUBJECT_TO_CLAIM,
                KnowledgeGraphEdgeKind.CLAIM_TO_OBJECT
            ),
            fragment.edges.map { it.kind }.toSet()
        )
    }

    @Test
    fun text_number_and_boolean_literals_use_typed_canonical_identity() {
        val text = KnowledgeGraphSingleClaimProjector.project(
            claim(objectValue = SemanticClaimObject.Text("42"))
        ).literal()
        val number = KnowledgeGraphSingleClaimProjector.project(
            claim(objectValue = SemanticClaimObject.Number("42"))
        ).literal()
        val bool = KnowledgeGraphSingleClaimProjector.project(
            claim(objectValue = SemanticClaimObject.BooleanValue(true))
        ).literal()

        assertEquals(KnowledgeGraphLiteralKind.TEXT, text.literalKind)
        assertEquals("42", text.canonicalValue)
        assertEquals(KnowledgeGraphLiteralKind.NUMBER, number.literalKind)
        assertEquals("42", number.canonicalValue)
        assertEquals(KnowledgeGraphLiteralKind.BOOLEAN, bool.literalKind)
        assertEquals("true", bool.canonicalValue)
        assertNotEquals(text.id, number.id)
        assertNotEquals(number.id, bool.id)
    }

    @Test
    fun projection_is_deterministic_and_node_edge_order_is_stable() {
        val record = claim(
            subject = SemanticEntityReference("персона", "пользователь"),
            objectValue = SemanticClaimObject.Entity(
                SemanticEntityReference("город", "Львов")
            )
        )

        val first = KnowledgeGraphSingleClaimProjector.project(record)
        val second = KnowledgeGraphSingleClaimProjector.project(record)

        assertEquals(first, second)
        assertEquals(
            first.nodes.map { it.id.value }.sortedWith(::compareUtf8ForTest),
            first.nodes.map { it.id.value }
        )
        assertEquals(
            first.edges.map { it.id.value }.sortedWith(::compareUtf8ForTest),
            first.edges.map { it.id.value }
        )
    }

    @Test
    fun exact_claim_version_changes_claim_and_edge_identity_but_not_entity_identity() {
        val firstRecord = claim(version = 1)
        val secondRecord = claim(version = 2)

        val first = KnowledgeGraphSingleClaimProjector.project(firstRecord)
        val second = KnowledgeGraphSingleClaimProjector.project(secondRecord)

        val firstSubject = first.nodes.filterIsInstance<KnowledgeGraphNode.Entity>().single()
        val secondSubject = second.nodes.filterIsInstance<KnowledgeGraphNode.Entity>().single()
        assertEquals(firstSubject.id, secondSubject.id)

        val firstClaim = first.nodes.filterIsInstance<KnowledgeGraphNode.ClaimVersion>().single()
        val secondClaim = second.nodes.filterIsInstance<KnowledgeGraphNode.ClaimVersion>().single()
        assertNotEquals(firstClaim.id, secondClaim.id)
        assertTrue(first.edges.map { it.id }.toSet().intersect(second.edges.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun self_referencing_entity_claim_deduplicates_entity_node_and_stays_bounded() {
        val same = SemanticEntityReference("person", "user")
        val fragment = KnowledgeGraphSingleClaimProjector.project(
            claim(
                subject = same,
                objectValue = SemanticClaimObject.Entity(same)
            )
        )

        assertEquals(2, fragment.nodes.size)
        assertEquals(2, fragment.edges.size)
        assertEquals(1, fragment.nodes.filterIsInstance<KnowledgeGraphNode.Entity>().size)
        assertTrue(fragment.nodes.size <= KnowledgeGraphSingleClaimProjector.MAX_NODES_PER_CLAIM)
        assertTrue(fragment.edges.size <= KnowledgeGraphSingleClaimProjector.MAX_EDGES_PER_CLAIM)
    }

    @Test
    fun provenance_contains_references_only_and_preserves_extraction_identity() {
        val record = claim()
        val fragment = KnowledgeGraphSingleClaimProjector.project(record)
        val node = fragment.nodes.filterIsInstance<KnowledgeGraphNode.ClaimVersion>().single()

        assertEquals(listOf(EpisodeId("episode-1")), node.provenance.episodes)
        assertEquals(
            listOf(
                RawEvidenceReference(
                    RawEvidenceNamespace("chat"),
                    RawEvidenceId("message-1")
                )
            ),
            node.provenance.rawEvidence
        )
        assertEquals("extractor", node.provenance.extraction.extractorId)
        assertEquals("1", node.provenance.extraction.extractorVersion)
        assertEquals(SemanticEvidenceStrength.HIGH, node.provenance.evidenceStrength)
    }

    private fun KnowledgeGraphFragment.literal() =
        nodes.filterIsInstance<KnowledgeGraphNode.Literal>().single()

    private fun claim(
        subject: SemanticEntityReference = SemanticEntityReference("person", "user"),
        objectValue: SemanticClaimObject = SemanticClaimObject.Text("loves tea"),
        version: Long = 1
    ): SemanticClaimRecord {
        val observed = Instant.parse("2026-09-28T00:00:00Z")
        return SemanticClaimRecord(
            id = SemanticClaimId("claim-fixed"),
            version = SemanticClaimVersion(version),
            identity = SemanticClaimIdentity(
                subject = subject,
                predicate = "prefers"
            ),
            objectValue = objectValue,
            temporal = SemanticClaimTemporalState(
                observedAt = observed,
                validFrom = observed
            ),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-1")),
                rawEvidence = listOf(
                    RawEvidenceReference(
                        RawEvidenceNamespace("chat"),
                        RawEvidenceId("message-1")
                    )
                ),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "extractor",
                    extractorVersion = "1",
                    extractedAt = observed
                ),
                evidenceStrength = SemanticEvidenceStrength.HIGH
            )
        )
    }

    private fun compareUtf8ForTest(left: String, right: String): Int {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        val limit = minOf(a.size, b.size)
        for (index in 0 until limit) {
            val ai = a[index].toInt() and 0xff
            val bi = b[index].toInt() and 0xff
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }
}