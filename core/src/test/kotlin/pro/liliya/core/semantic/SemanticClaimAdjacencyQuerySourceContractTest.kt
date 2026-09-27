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

class SemanticClaimAdjacencyQuerySourceContractTest {
    private val checkpoint = SemanticClaimSourceCheckpoint(10, 20, 30)
    private val epoch = "adjacency-contract"

    @Test
    fun policy_hard_limits_match_stage_m_bounded_dod() {
        assertEquals(128, SemanticClaimAdjacencyPolicy.MAX_RELATIONS_PER_SEED)
        assertEquals(2048, SemanticClaimAdjacencyPolicy.MAX_WORKING_SET)
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimAdjacencyPolicy(maxRelationsPerSeed = 129)
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticClaimAdjacencyPolicy(maxWorkingSet = 2049)
        }
    }

    @Test
    fun one_hop_expansion_preserves_relation_order_and_direction() {
        val a = reference("a")
        val b = reference("b")
        val c = reference("c")
        val canonical = FakeCanonical(
            checkpoint,
            mapOf(b to claim(b), c to claim(c))
        )
        val index = FakeIndex(
            manifest = completeManifest(),
            roots = mapOf(
                a to SemanticClaimAdjacencyRoot(buildEpoch = epoch, reference = a, pageCount = 1, entryCount = 2)
            ),
            pages = mapOf(
                a to mapOf(
                    0L to SemanticClaimAdjacencyPage(
                        buildEpoch = epoch,
                        reference = a,
                        ordinal = 0,
                        entries = listOf(
                            SemanticClaimAdjacencyEntry(
                                b,
                                SemanticClaimRelationType.CONTRADICTS,
                                SemanticClaimAdjacencyDirection.SYMMETRIC,
                                Instant.parse("2026-09-27T10:00:00Z")
                            ),
                            SemanticClaimAdjacencyEntry(
                                c,
                                SemanticClaimRelationType.SUPERSEDES,
                                SemanticClaimAdjacencyDirection.OUTGOING,
                                Instant.parse("2026-09-27T10:01:00Z")
                            )
                        )
                    )
                )
            )
        )

        val result = assertIs<SemanticClaimAdjacencyQueryResult.Expanded>(
            SemanticClaimAdjacencyQuerySource(canonical, index).expand(
                listOf(seed(a, 0.9))
            )
        )

        assertEquals(listOf(b, c), result.candidates.map { it.reference })
        assertEquals(
            listOf(
                SemanticClaimAdjacencyDirection.SYMMETRIC,
                SemanticClaimAdjacencyDirection.OUTGOING
            ),
            result.candidates.map { it.direction }
        )
        assertEquals(2, result.audit.relationEntriesScanned)
        assertEquals(2, result.audit.returnedCount)
        assertTrue(result.audit.advisoryOnly)
        assertEquals(SemanticClaimAdjacencyQueryStatus.EXPANDED, result.audit.status)
    }

    @Test
    fun seeds_and_duplicate_neighbors_are_suppressed_deterministically() {
        val a = reference("a")
        val b = reference("b")
        val c = reference("c")
        val canonical = FakeCanonical(checkpoint, mapOf(c to claim(c)))
        val index = FakeIndex(
            manifest = completeManifest(),
            roots = mapOf(
                a to SemanticClaimAdjacencyRoot(buildEpoch = epoch, reference = a, pageCount = 1, entryCount = 2),
                b to SemanticClaimAdjacencyRoot(buildEpoch = epoch, reference = b, pageCount = 1, entryCount = 1)
            ),
            pages = mapOf(
                a to mapOf(
                    0L to page(
                        a,
                        entry(b, SemanticClaimAdjacencyDirection.SYMMETRIC),
                        entry(c, SemanticClaimAdjacencyDirection.OUTGOING)
                    )
                ),
                b to mapOf(
                    0L to page(
                        b,
                        entry(c, SemanticClaimAdjacencyDirection.INCOMING)
                    )
                )
            )
        )

        val result = assertIs<SemanticClaimAdjacencyQueryResult.Expanded>(
            SemanticClaimAdjacencyQuerySource(canonical, index).expand(
                listOf(seed(a, 0.9), seed(b, 0.8))
            )
        )
        assertEquals(listOf(c), result.candidates.map { it.reference })
        assertEquals(2, result.audit.duplicateSuppressed)
        assertEquals(3, result.audit.relationEntriesScanned)
    }

    @Test
    fun relation_scan_budget_is_explicit_and_truncated() {
        val a = reference("a")
        val b = reference("b")
        val c = reference("c")
        val policy = SemanticClaimAdjacencyPolicy(
            pageEntries = 1,
            maxSeeds = 2,
            maxRelationsPerSeed = 1,
            maxWorkingSet = 4,
            maxReturnedCandidates = 2
        )
        val canonical = FakeCanonical(checkpoint, mapOf(b to claim(b)))
        val index = FakeIndex(
            manifest = completeManifest(pageEntries = 1),
            roots = mapOf(
                a to SemanticClaimAdjacencyRoot(buildEpoch = epoch, reference = a, pageCount = 2, entryCount = 2)
            ),
            pages = mapOf(
                a to mapOf(
                    0L to SemanticClaimAdjacencyPage(
                        buildEpoch = epoch,
                        reference = a,
                        ordinal = 0,
                        entries = listOf(entry(b, SemanticClaimAdjacencyDirection.OUTGOING))
                    ),
                    1L to SemanticClaimAdjacencyPage(
                        buildEpoch = epoch,
                        reference = a,
                        ordinal = 1,
                        entries = listOf(entry(c, SemanticClaimAdjacencyDirection.OUTGOING))
                    )
                )
            )
        )

        val result = assertIs<SemanticClaimAdjacencyQueryResult.Expanded>(
            SemanticClaimAdjacencyQuerySource(canonical, index, policy).expand(
                listOf(seed(a, 1.0))
            )
        )
        assertEquals(listOf(b), result.candidates.map { it.reference })
        assertEquals(1, result.audit.relationEntriesScanned)
        assertTrue(result.audit.truncated)
        assertEquals(1, index.pageReads)
    }

    @Test
    fun stale_manifest_fails_before_relation_or_canonical_reads() {
        val a = reference("a")
        val canonical = FakeCanonical(checkpoint, emptyMap())
        val stale = completeManifest().copy(
            source = checkpoint.copy(revision = checkpoint.revision - 1)
        )
        val index = FakeIndex(stale, emptyMap(), emptyMap())

        val result = assertIs<SemanticClaimAdjacencyQueryResult.FallbackRequired>(
            SemanticClaimAdjacencyQuerySource(canonical, index).expand(
                listOf(seed(a, 1.0))
            )
        )
        assertTrue(result.reason.contains("stale"))
        assertEquals(SemanticClaimAdjacencyQueryStatus.FALLBACK_REQUIRED, result.audit.status)
        assertEquals(checkpoint, result.audit.source)
        assertEquals(0, index.rootReads)
        assertEquals(0, canonical.readCalls)
    }

    @Test
    fun missing_expanded_canonical_claim_fails_safe_without_partial_success() {
        val a = reference("a")
        val b = reference("b")
        val c = reference("c")
        val canonical = FakeCanonical(checkpoint, mapOf(b to claim(b)))
        val index = FakeIndex(
            completeManifest(),
            roots = mapOf(a to SemanticClaimAdjacencyRoot(buildEpoch = epoch, reference = a, pageCount = 1, entryCount = 2)),
            pages = mapOf(
                a to mapOf(
                    0L to page(
                        a,
                        entry(b, SemanticClaimAdjacencyDirection.OUTGOING),
                        entry(c, SemanticClaimAdjacencyDirection.OUTGOING)
                    )
                )
            )
        )

        val result = assertIs<SemanticClaimAdjacencyQueryResult.FallbackRequired>(
            SemanticClaimAdjacencyQuerySource(canonical, index).expand(
                listOf(seed(a, 1.0))
            )
        )
        assertTrue(result.reason.contains("missing"))
        assertEquals(2, canonical.readCalls)
    }

    @Test
    fun adjacency_codec_round_trips_manifest_root_and_page() {
        val a = reference("a")
        val b = reference("b")
        val manifest = completeManifest()
        assertEquals(
            manifest,
            assertIs<SemanticClaimAdjacencyManifestDecodeResult.Decoded>(
                SemanticClaimAdjacencyIndexCodec.decodeManifest(
                    SemanticClaimAdjacencyIndexCodec.encodeManifest(manifest)
                )
            ).manifest
        )
        val root = SemanticClaimAdjacencyRoot(buildEpoch = epoch, reference = a, pageCount = 1, entryCount = 1)
        assertEquals(
            root,
            assertIs<SemanticClaimAdjacencyRootDecodeResult.Decoded>(
                SemanticClaimAdjacencyIndexCodec.decodeRoot(
                    SemanticClaimAdjacencyIndexCodec.encodeRoot(root)
                )
            ).root
        )
        val page = page(a, entry(b, SemanticClaimAdjacencyDirection.SYMMETRIC))
        assertEquals(
            page,
            assertIs<SemanticClaimAdjacencyPageDecodeResult.Decoded>(
                SemanticClaimAdjacencyIndexCodec.decodePage(
                    SemanticClaimAdjacencyIndexCodec.encodePage(page)
                )
            ).page
        )
    }

    private fun completeManifest(pageEntries: Int = 128) =
        SemanticClaimAdjacencyManifest(
            buildEpoch = epoch,
            source = checkpoint,
            state = SemanticClaimAdjacencyIndexState.COMPLETE,
            policyVersion = SemanticClaimAdjacencyPolicy.CURRENT_VERSION,
            pageEntries = pageEntries,
            indexedRelationCount = 2,
            adjacencyEntryCount = 4
        )

    private fun reference(id: String) =
        SemanticClaimVersionReference(
            SemanticClaimId("claim-" + id),
            SemanticClaimVersion(1)
        )

    private fun seed(reference: SemanticClaimVersionReference, score: Double) =
        HybridFusedCandidate(
            id = SemanticClaimRetrievalCandidateIdentity.encode(reference),
            fusedScore = score,
            contributions = listOf(
                HybridRankContribution(
                    channelId = RetrievalChannelId("test"),
                    rank = 1,
                    reciprocalContribution = score
                )
            )
        )

    private fun entry(
        neighbor: SemanticClaimVersionReference,
        direction: SemanticClaimAdjacencyDirection
    ) = SemanticClaimAdjacencyEntry(
        neighbor = neighbor,
        relationType = SemanticClaimRelationType.SUPERSEDES,
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

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointCalls += 1
            return checkpoint
        }

        override fun readExact(
            reference: SemanticClaimVersionReference
        ): SemanticClaimReadResult {
            readCalls += 1
            val record = records[reference] ?: return SemanticClaimReadResult.Missing
            return SemanticClaimReadResult.Found(record)
        }
    }

    private class FakeIndex(
        private val manifest: SemanticClaimAdjacencyManifest,
        private val roots: Map<SemanticClaimVersionReference, SemanticClaimAdjacencyRoot>,
        private val pages: Map<
            SemanticClaimVersionReference,
            Map<Long, SemanticClaimAdjacencyPage>
        >
    ) : SemanticClaimAdjacencyIndexReader {
        var rootReads = 0
        var pageReads = 0

        override fun readManifest(): SemanticClaimAdjacencyManifestLoadResult =
            SemanticClaimAdjacencyManifestLoadResult.Loaded(manifest)

        override fun readRoot(
            reference: SemanticClaimVersionReference
        ): SemanticClaimAdjacencyRootLoadResult {
            rootReads += 1
            val root = roots[reference] ?: return SemanticClaimAdjacencyRootLoadResult.Missing
            return SemanticClaimAdjacencyRootLoadResult.Loaded(root)
        }

        override fun readPage(
            reference: SemanticClaimVersionReference,
            ordinal: Long
        ): SemanticClaimAdjacencyPageLoadResult {
            pageReads += 1
            val page = pages[reference]?.get(ordinal)
                ?: return SemanticClaimAdjacencyPageLoadResult.Missing
            return SemanticClaimAdjacencyPageLoadResult.Loaded(page)
        }
    }
}