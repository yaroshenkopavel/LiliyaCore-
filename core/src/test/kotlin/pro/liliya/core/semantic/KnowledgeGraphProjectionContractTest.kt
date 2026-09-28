package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.persistence.PersistentBackendPageCursor

class KnowledgeGraphProjectionContractTest {
    @Test
    fun rebuild_is_bounded_and_publishes_complete_only_after_stable_checkpoint() {
        val checkpoint = SemanticClaimSourceCheckpoint(7L, 20L, 2L)
        val first = claim("claim-1", SemanticClaimObject.Entity(SemanticEntityReference("city", "lviv")))
        val second = claim("claim-2", SemanticClaimObject.Text("tea"))
        val source = FakeRebuildSource(
            checkpoints = ArrayDeque(listOf(checkpoint, checkpoint)),
            pages = ArrayDeque(
                listOf(
                    SemanticClaimPageResult.Loaded(
                        records = listOf(first, second),
                        nextCursor = null
                    )
                )
            )
        )
        val writer = FakeWriter()

        val result = KnowledgeGraphProjectionRebuilder(
            source = source,
            writer = writer,
            epochFactory = { "epoch-1" }
        ).rebuild()

        val rebuilt = assertIs<KnowledgeGraphProjectionRebuildResult.Rebuilt>(result)
        assertEquals(KnowledgeGraphProjectionState.COMPLETE, rebuilt.manifest.state)
        assertEquals(2L, rebuilt.manifest.projectedClaimCount)
        assertEquals(6L, rebuilt.manifest.projectedNodeCount)
        assertEquals(4L, rebuilt.manifest.projectedEdgeCount)
        assertEquals(
            listOf(
                KnowledgeGraphProjectionState.INCOMPLETE,
                KnowledgeGraphProjectionState.COMPLETE
            ),
            writer.manifests.map { it.state }
        )
        assertEquals(2, writer.fragments.size)
        assertEquals(
            listOf(KnowledgeGraphProjectionPolicy.DEFAULT_CLAIM_PAGE_SIZE),
            source.requestedLimits
        )
        assertTrue(writer.fragments.all { it.buildEpoch == "epoch-1" })
    }

    @Test
    fun source_drift_never_publishes_complete_generation() {
        val started = SemanticClaimSourceCheckpoint(7L, 20L, 1L)
        val ended = SemanticClaimSourceCheckpoint(8L, 21L, 2L)
        val source = FakeRebuildSource(
            checkpoints = ArrayDeque(listOf(started, ended)),
            pages = ArrayDeque(
                listOf(
                    SemanticClaimPageResult.Loaded(
                        records = listOf(claim("claim-1", SemanticClaimObject.Text("tea"))),
                        nextCursor = null
                    )
                )
            )
        )
        val writer = FakeWriter()

        val result = KnowledgeGraphProjectionRebuilder(
            source = source,
            writer = writer,
            epochFactory = { "epoch-drift" }
        ).rebuild()

        val drift = assertIs<KnowledgeGraphProjectionRebuildResult.SourceDrift>(result)
        assertEquals(started, drift.started)
        assertEquals(ended, drift.ended)
        assertEquals(
            listOf(KnowledgeGraphProjectionState.INCOMPLETE),
            writer.manifests.map { it.state }
        )
    }

    @Test
    fun exact_query_revalidates_canonical_claim_and_returns_traceable_fragment() {
        val record = claim("claim-query", SemanticClaimObject.Text("tea"))
        val reference = SemanticClaimVersionReference(record.id, record.version)
        val checkpoint = SemanticClaimSourceCheckpoint(9L, 30L, 1L)
        val fragment = KnowledgeGraphStoredFragment.from(
            "epoch-query",
            KnowledgeGraphSingleClaimProjector.project(record)
        )
        val source = FakeCanonicalSource(checkpoint, SemanticClaimReadResult.Found(record))
        val reader = FakeReader(
            manifest = completeManifest("epoch-query", checkpoint, 1L, 3L, 2L),
            fragment = fragment
        )

        val result = KnowledgeGraphProjectionQuerySource(source, reader).exactClaim(reference)

        val available = assertIs<KnowledgeGraphProjectionQueryResult.Available>(result)
        assertEquals(record, available.canonicalRecord)
        assertEquals(reference, available.fragment.sourceClaim)
        assertEquals(2, source.checkpointReads)
        assertEquals(1, source.exactReads)
    }

    @Test
    fun stale_manifest_checkpoint_fails_before_fragment_or_canonical_read() {
        val record = claim("claim-stale", SemanticClaimObject.Text("tea"))
        val reference = SemanticClaimVersionReference(record.id, record.version)
        val indexed = SemanticClaimSourceCheckpoint(1L, 1L, 1L)
        val current = SemanticClaimSourceCheckpoint(2L, 2L, 2L)
        val source = FakeCanonicalSource(current, SemanticClaimReadResult.Found(record))
        val reader = FakeReader(
            manifest = completeManifest("epoch-stale", indexed, 1L, 3L, 2L),
            fragment = KnowledgeGraphStoredFragment.from(
                "epoch-stale",
                KnowledgeGraphSingleClaimProjector.project(record)
            )
        )

        val result = KnowledgeGraphProjectionQuerySource(source, reader).exactClaim(reference)

        assertIs<KnowledgeGraphProjectionQueryResult.RebuildRequired>(result)
        assertEquals(0, reader.fragmentReads)
        assertEquals(0, source.exactReads)
    }

    @Test
    fun stored_fragment_mismatch_requires_rebuild_instead_of_returning_graph_truth() {
        val record = claim("claim-mismatch", SemanticClaimObject.Text("tea"))
        val reference = SemanticClaimVersionReference(record.id, record.version)
        val checkpoint = SemanticClaimSourceCheckpoint(4L, 4L, 1L)
        val correct = KnowledgeGraphStoredFragment.from(
            "epoch-mismatch",
            KnowledgeGraphSingleClaimProjector.project(record)
        )
        val wrong = correct.copy(
            nodes = correct.nodes.mapIndexed { index, node ->
                if (index == 0) {
                    node.copy(
                        id = KnowledgeGraphNodeId(
                            "kg-node-ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                        )
                    )
                } else {
                    node
                }
            }
        )
        val source = FakeCanonicalSource(checkpoint, SemanticClaimReadResult.Found(record))
        val reader = FakeReader(
            manifest = completeManifest("epoch-mismatch", checkpoint, 1L, 3L, 2L),
            fragment = wrong
        )

        val result = KnowledgeGraphProjectionQuerySource(source, reader).exactClaim(reference)

        assertIs<KnowledgeGraphProjectionQueryResult.RebuildRequired>(result)
    }

    @Test
    fun fragment_entity_id_is_stable_across_rebuild_epochs_for_same_claim_version() {
        val record = claim("claim-epoch-key", SemanticClaimObject.Text("tea"))
        val reference = SemanticClaimVersionReference(record.id, record.version)

        val first = KnowledgeGraphProjectionCodec.fragmentEntityId(reference)
        val second = KnowledgeGraphProjectionCodec.fragmentEntityId(reference)

        assertEquals(first, second)
    }
    @Test
    fun projection_codec_round_trips_manifest_and_fragment() {
        val record = claim("claim-codec", SemanticClaimObject.BooleanValue(true))
        val checkpoint = SemanticClaimSourceCheckpoint(3L, 5L, 1L)
        val manifest = completeManifest("epoch-codec", checkpoint, 1L, 3L, 2L)
        val fragment = KnowledgeGraphStoredFragment.from(
            "epoch-codec",
            KnowledgeGraphSingleClaimProjector.project(record)
        )

        val manifestDecoded = KnowledgeGraphProjectionCodec.decodeManifest(
            KnowledgeGraphProjectionCodec.encodeManifest(manifest)
        )
        val fragmentDecoded = KnowledgeGraphProjectionCodec.decodeFragment(
            KnowledgeGraphProjectionCodec.encodeFragment(fragment)
        )

        assertEquals(
            manifest,
            assertIs<KnowledgeGraphProjectionManifestDecodeResult.Decoded>(
                manifestDecoded
            ).manifest
        )
        assertEquals(
            fragment,
            assertIs<KnowledgeGraphProjectionFragmentDecodeResult.Decoded>(
                fragmentDecoded
            ).fragment
        )
    }

    private fun completeManifest(
        epoch: String,
        checkpoint: SemanticClaimSourceCheckpoint,
        claims: Long,
        nodes: Long,
        edges: Long
    ) = KnowledgeGraphProjectionManifest(
        buildEpoch = epoch,
        source = checkpoint,
        state = KnowledgeGraphProjectionState.COMPLETE,
        policyVersion = KnowledgeGraphProjectionPolicy.CURRENT_VERSION,
        claimPageSize = KnowledgeGraphProjectionPolicy.DEFAULT_CLAIM_PAGE_SIZE,
        projectedClaimCount = claims,
        projectedNodeCount = nodes,
        projectedEdgeCount = edges
    )

    private fun claim(
        id: String,
        objectValue: SemanticClaimObject
    ): SemanticClaimRecord {
        val observed = Instant.parse("2026-09-28T00:00:00Z")
        return SemanticClaimRecord(
            id = SemanticClaimId(id),
            version = SemanticClaimVersion(1L),
            identity = SemanticClaimIdentity(
                subject = SemanticEntityReference("person", "user"),
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

    private class FakeRebuildSource(
        private val checkpoints: ArrayDeque<SemanticClaimSourceCheckpoint>,
        private val pages: ArrayDeque<SemanticClaimPageResult>
    ) : KnowledgeGraphProjectionClaimSource {
        val requestedLimits = mutableListOf<Int>()

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
            checkpoints.removeFirst()

        override fun claimPage(
            limit: Int,
            cursorExclusive: PersistentBackendPageCursor?
        ): SemanticClaimPageResult {
            requestedLimits += limit
            return pages.removeFirst()
        }
    }

    private class FakeWriter : KnowledgeGraphProjectionWriter {
        val manifests = mutableListOf<KnowledgeGraphProjectionManifest>()
        val fragments = mutableListOf<KnowledgeGraphStoredFragment>()

        override fun writeManifest(
            manifest: KnowledgeGraphProjectionManifest
        ): KnowledgeGraphProjectionWriteResult {
            manifests += manifest
            return KnowledgeGraphProjectionWriteResult.Written
        }

        override fun writeFragment(
            fragment: KnowledgeGraphStoredFragment
        ): KnowledgeGraphProjectionWriteResult {
            fragments += fragment
            return KnowledgeGraphProjectionWriteResult.Written
        }
    }

    private class FakeCanonicalSource(
        private val checkpoint: SemanticClaimSourceCheckpoint,
        private val read: SemanticClaimReadResult
    ) : KnowledgeGraphProjectionCanonicalSource {
        var checkpointReads = 0
        var exactReads = 0

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointReads += 1
            return checkpoint
        }

        override fun readExact(
            reference: SemanticClaimVersionReference
        ): SemanticClaimReadResult {
            exactReads += 1
            return read
        }
    }

    private class FakeReader(
        manifest: KnowledgeGraphProjectionManifest,
        private val fragment: KnowledgeGraphStoredFragment
    ) : KnowledgeGraphProjectionReader {
        private val manifestResult =
            KnowledgeGraphProjectionManifestLoadResult.Loaded(manifest)
        var fragmentReads = 0

        override fun readManifest(): KnowledgeGraphProjectionManifestLoadResult =
            manifestResult

        override fun readFragment(
            reference: SemanticClaimVersionReference
        ): KnowledgeGraphProjectionFragmentLoadResult {
            fragmentReads += 1
            return KnowledgeGraphProjectionFragmentLoadResult.Loaded(fragment)
        }
    }
}
