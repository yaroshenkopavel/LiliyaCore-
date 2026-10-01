package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.retrieval.HybridFusedCandidate
import pro.liliya.core.retrieval.HybridRankContribution
import pro.liliya.core.retrieval.RetrievalChannelId

class SemanticClaimGraphTraversalSourceContractTest {
    private val checkpoint = SemanticClaimSourceCheckpoint(20, 30, 40)
    private val epoch = "graph-traversal-contract"

    @Test
    fun two_hop_traversal_preserves_depth_parent_and_first_discovery_provenance() {
        val a = ref("a")
        val b = ref("b")
        val c = ref("c")
        val d = ref("d")
        val canonical = FakeCanonical(
            checkpoint,
            mapOf(b to claim(b), c to claim(c), d to claim(d))
        )
        val index = FakeIndex(
            manifest(),
            roots = mapOf(
                a to root(a, 2),
                b to root(b, 1),
                c to root(c, 1)
            ),
            pages = mapOf(
                a to mapOf(0L to page(a, edge(c), edge(b))),
                b to mapOf(0L to page(b, edge(d))),
                c to mapOf(0L to page(c, edge(d)))
            )
        )

        val result = assertIs<SemanticClaimGraphTraversalResult.Traversed>(
            SemanticClaimGraphTraversalSource(canonical, index).traverse(
                listOf(seed(a))
            )
        )

        assertEquals(listOf(b, c, d), result.candidates.map { it.reference })
        assertEquals(listOf(1, 1, 2), result.candidates.map { it.depth })
        val dCandidate = result.candidates.last()
        assertEquals(
            SemanticClaimRetrievalCandidateIdentity.encode(b),
            dCandidate.parentCandidateId
        )
        assertEquals(3, result.audit.returnedCount)
        assertEquals(2, result.audit.depthReached)
        assertEquals(SemanticClaimGraphTraversalStatus.TRAVERSED, result.audit.status)
        assertTrue(result.audit.advisoryOnly)
    }

    @Test
    fun cycles_and_seed_revisits_are_suppressed_deterministically() {
        val a = ref("a")
        val b = ref("b")
        val canonical = FakeCanonical(checkpoint, mapOf(b to claim(b)))
        val index = FakeIndex(
            manifest(),
            roots = mapOf(a to root(a, 1), b to root(b, 1)),
            pages = mapOf(
                a to mapOf(0L to page(a, edge(b))),
                b to mapOf(0L to page(b, edge(a)))
            )
        )

        val result = assertIs<SemanticClaimGraphTraversalResult.Traversed>(
            SemanticClaimGraphTraversalSource(canonical, index).traverse(listOf(seed(a)))
        )

        assertEquals(listOf(b), result.candidates.map { it.reference })
        assertEquals(1, result.audit.duplicatesOrCyclesSuppressed)
        assertEquals(2, result.audit.relationEntriesScanned)
        assertEquals(2, result.audit.visitedCount)
    }

    @Test
    fun traversal_never_goes_beyond_depth_two() {
        val a = ref("a")
        val b = ref("b")
        val c = ref("c")
        val d = ref("d")
        val canonical = FakeCanonical(
            checkpoint,
            mapOf(b to claim(b), c to claim(c), d to claim(d))
        )
        val index = FakeIndex(
            manifest(),
            roots = mapOf(a to root(a, 1), b to root(b, 1), c to root(c, 1)),
            pages = mapOf(
                a to mapOf(0L to page(a, edge(b))),
                b to mapOf(0L to page(b, edge(c))),
                c to mapOf(0L to page(c, edge(d)))
            )
        )

        val result = assertIs<SemanticClaimGraphTraversalResult.Traversed>(
            SemanticClaimGraphTraversalSource(canonical, index).traverse(listOf(seed(a)))
        )

        assertEquals(listOf(b, c), result.candidates.map { it.reference })
        assertEquals(2, result.audit.depthReached)
        assertEquals(2, index.pageReads)
        assertEquals(2, canonical.readCalls)
    }

    @Test
    fun source_drift_fails_safe_after_exact_revalidation() {
        val a = ref("a")
        val b = ref("b")
        val canonical = FakeCanonical(checkpoint, mapOf(b to claim(b))).apply {
            driftOnFinalCheckpoint = true
        }
        val index = FakeIndex(
            manifest(),
            roots = mapOf(a to root(a, 1)),
            pages = mapOf(a to mapOf(0L to page(a, edge(b))))
        )

        val result = assertIs<SemanticClaimGraphTraversalResult.FallbackRequired>(
            SemanticClaimGraphTraversalSource(canonical, index).traverse(listOf(seed(a)))
        )

        assertTrue(result.reason.contains("source changed"))
        assertEquals(SemanticClaimGraphTraversalStatus.FALLBACK_REQUIRED, result.audit.status)
        assertEquals(1, canonical.readCalls)
    }

    @Test
    fun policy_hard_limits_match_stage_n_dod() {
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimGraphTraversalPolicy(maxDepth = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimGraphTraversalPolicy(maxFrontier = 257)
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimGraphTraversalPolicy(maxVisited = 2049)
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimGraphTraversalPolicy(maxReturnedCandidates = 129)
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimGraphTraversalPolicy(maxRelationsPerNode = 129)
        }
    }

    private fun manifest() = SemanticClaimAdjacencyManifest(
        buildEpoch = epoch,
        source = checkpoint,
        state = SemanticClaimAdjacencyIndexState.COMPLETE,
        policyVersion = SemanticClaimAdjacencyPolicy.CURRENT_VERSION,
        pageEntries = 128,
        indexedRelationCount = 4,
        adjacencyEntryCount = 8
    )

    private fun ref(id: String) = SemanticClaimVersionReference(
        SemanticClaimId("claim-" + id),
        SemanticClaimVersion(1)
    )

    private fun root(ref: SemanticClaimVersionReference, count: Long) =
        SemanticClaimAdjacencyRoot(
            buildEpoch = epoch,
            reference = ref,
            pageCount = 1,
            entryCount = count
        )

    private fun edge(
        neighbor: SemanticClaimVersionReference,
        type: SemanticClaimRelationType = SemanticClaimRelationType.SUPERSEDES,
        direction: SemanticClaimAdjacencyDirection = SemanticClaimAdjacencyDirection.OUTGOING
    ) = SemanticClaimAdjacencyEntry(
        neighbor = neighbor,
        relationType = type,
        direction = direction,
        recordedAt = Instant.parse("2026-09-27T10:00:00Z")
    )

    private fun page(
        reference: SemanticClaimVersionReference,
        vararg entries: SemanticClaimAdjacencyEntry
    ) = SemanticClaimAdjacencyPage(
        buildEpoch = epoch,
        reference = reference,
        ordinal = 0,
        entries = entries.toList()
    )

    private fun seed(reference: SemanticClaimVersionReference) = HybridFusedCandidate(
        id = SemanticClaimRetrievalCandidateIdentity.encode(reference),
        fusedScore = 1.0,
        contributions = listOf(
            HybridRankContribution(
                channelId = RetrievalChannelId("test"),
                rank = 1,
                reciprocalContribution = 1.0
            )
        )
    )

    private fun claim(reference: SemanticClaimVersionReference): SemanticClaimRecord {
        val observed = Instant.parse("2026-09-27T00:00:00Z")
        return SemanticClaimRecord(
            id = reference.claimId,
            version = reference.version,
            identity = SemanticClaimIdentity(
                SemanticEntityReference("user", "self"),
                "predicate-" + reference.claimId.value
            ),
            objectValue = SemanticClaimObject.Text("value"),
            temporal = SemanticClaimTemporalState(observedAt = observed),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-" + reference.claimId.value)),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "test",
                    extractorVersion = "1",
                    extractedAt = observed
                )
            )
        )
    }

    private class FakeCanonical(
        private val checkpoint: SemanticClaimSourceCheckpoint,
        private val records: Map<SemanticClaimVersionReference, SemanticClaimRecord>
    ) : SemanticClaimAdjacencyCanonicalReader {
        var readCalls = 0
        var checkpointCalls = 0
        var driftOnFinalCheckpoint = false

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointCalls += 1
            return if (driftOnFinalCheckpoint && checkpointCalls > 1) {
                checkpoint.copy(revision = checkpoint.revision + 1)
            } else checkpoint
        }

        override fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult {
            readCalls += 1
            return records[reference]?.let { SemanticClaimReadResult.Found(it) }
                ?: SemanticClaimReadResult.Missing
        }
    }

    private class FakeIndex(
        private val manifest: SemanticClaimAdjacencyManifest,
        private val roots: Map<SemanticClaimVersionReference, SemanticClaimAdjacencyRoot>,
        private val pages: Map<SemanticClaimVersionReference, Map<Long, SemanticClaimAdjacencyPage>>
    ) : SemanticClaimAdjacencyIndexReader {
        var pageReads = 0

        override fun readManifest() =
            SemanticClaimAdjacencyManifestLoadResult.Loaded(manifest)

        override fun readRoot(reference: SemanticClaimVersionReference) =
            roots[reference]?.let { SemanticClaimAdjacencyRootLoadResult.Loaded(it) }
                ?: SemanticClaimAdjacencyRootLoadResult.Missing

        override fun readPage(
            reference: SemanticClaimVersionReference,
            ordinal: Long
        ): SemanticClaimAdjacencyPageLoadResult {
            pageReads += 1
            return pages[reference]?.get(ordinal)?.let {
                SemanticClaimAdjacencyPageLoadResult.Loaded(it)
            } ?: SemanticClaimAdjacencyPageLoadResult.Missing
        }
    }
}