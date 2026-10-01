package pro.liliya.core.semantic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentEntityId

class SemanticClaimAdjacencyIndexRebuilderContractTest {
    private val checkpoint = SemanticClaimSourceCheckpoint(11, 22, 33)

    @Test
    fun rebuild_streams_relations_and_publishes_complete_only_after_source_recheck() {
        val a = reference("a")
        val b = reference("b")
        val c = reference("c")
        val relations = listOf(
            SemanticClaimRelation(
                type = SemanticClaimRelationType.CONTRADICTS,
                source = a,
                target = b,
                recordedAt = Instant.parse("2026-09-27T10:00:00Z")
            ),
            SemanticClaimRelation(
                type = SemanticClaimRelationType.SUPERSEDES,
                source = c,
                target = a,
                recordedAt = Instant.parse("2026-09-27T10:01:00Z")
            )
        )
        val source = FakeSource(checkpoint, relations)
        val writer = FakeWriter()

        val result = assertIs<SemanticClaimAdjacencyRebuildResult.Complete>(
            SemanticClaimAdjacencyIndexRebuilder(
                source = source,
                index = writer,
                policy = SemanticClaimAdjacencyPolicy(pageEntries = 2),
                epochFactory = { "epoch-1" }
            ).rebuild()
        )

        assertEquals(2L, result.indexedRelations)
        assertEquals(4L, result.adjacencyEntries)
        assertEquals(listOf(512), source.requestedLimits)
        assertEquals(2, source.checkpointCalls)
        assertEquals(
            listOf(
                SemanticClaimAdjacencyIndexState.INCOMPLETE,
                SemanticClaimAdjacencyIndexState.COMPLETE
            ),
            writer.manifests.map { it.state }
        )
        assertEquals(4L, writer.manifests.last().adjacencyEntryCount)

        val aEntries = writer.allEntries(a)
        assertEquals(2, aEntries.size)
        assertEquals(
            listOf(
                SemanticClaimAdjacencyDirection.SYMMETRIC,
                SemanticClaimAdjacencyDirection.INCOMING
            ),
            aEntries.map { it.direction }
        )
        assertEquals(listOf(b, c), aEntries.map { it.neighbor })
        assertEquals(1, writer.allEntries(b).size)
        assertEquals(1, writer.allEntries(c).size)
    }

    @Test
    fun source_drift_never_publishes_complete_manifest() {
        val a = reference("a")
        val b = reference("b")
        val source = FakeSource(
            checkpoint,
            listOf(
                SemanticClaimRelation(
                    SemanticClaimRelationType.CONTRADICTS,
                    a,
                    b,
                    Instant.parse("2026-09-27T10:00:00Z")
                )
            )
        ).apply {
            driftOnFinalCheckpoint = true
        }
        val writer = FakeWriter()

        val result = assertIs<SemanticClaimAdjacencyRebuildResult.SourceDrift>(
            SemanticClaimAdjacencyIndexRebuilder(
                source = source,
                index = writer,
                epochFactory = { "epoch-drift" }
            ).rebuild()
        )

        assertEquals(checkpoint, result.started)
        assertTrue(result.ended.revision > result.started.revision)
        assertEquals(
            listOf(SemanticClaimAdjacencyIndexState.INCOMPLETE),
            writer.manifests.map { it.state }
        )
    }

    @Test
    fun relation_page_failure_leaves_index_incomplete() {
        val source = object : SemanticClaimAdjacencyRelationSource {
            var checkpointCalls = 0

            override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
                checkpointCalls += 1
                return checkpoint
            }

            override fun relationPage(
                limit: Int,
                cursorExclusive: PersistentBackendPageCursor?
            ): SemanticRelationPageResult =
                SemanticRelationPageResult.Corrupt
        }
        val writer = FakeWriter()

        val result = assertIs<SemanticClaimAdjacencyRebuildResult.Failed>(
            SemanticClaimAdjacencyIndexRebuilder(
                source = source,
                index = writer,
                epochFactory = { "epoch-fail" }
            ).rebuild()
        )

        assertTrue(result.reason.contains("corrupt"))
        assertEquals(1, source.checkpointCalls)
        assertEquals(
            listOf(SemanticClaimAdjacencyIndexState.INCOMPLETE),
            writer.manifests.map { it.state }
        )
    }

    private fun reference(id: String) =
        SemanticClaimVersionReference(
            SemanticClaimId("claim-" + id),
            SemanticClaimVersion(1)
        )

    private class FakeSource(
        private val checkpoint: SemanticClaimSourceCheckpoint,
        private val relations: List<SemanticClaimRelation>
    ) : SemanticClaimAdjacencyRelationSource {
        var checkpointCalls = 0
        var driftOnFinalCheckpoint = false
        val requestedLimits = mutableListOf<Int>()
        private var served = false

        override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint {
            checkpointCalls += 1
            return if (driftOnFinalCheckpoint && checkpointCalls > 1) {
                checkpoint.copy(revision = checkpoint.revision + 1)
            } else {
                checkpoint
            }
        }

        override fun relationPage(
            limit: Int,
            cursorExclusive: PersistentBackendPageCursor?
        ): SemanticRelationPageResult {
            requestedLimits += limit
            if (served || relations.isEmpty()) return SemanticRelationPageResult.Empty
            served = true
            return SemanticRelationPageResult.Loaded(
                relations = relations,
                nextCursor = null
            )
        }
    }

    private class FakeWriter : SemanticClaimAdjacencyIndexWriter {
        val manifests = mutableListOf<SemanticClaimAdjacencyManifest>()
        private val roots = LinkedHashMap<
            SemanticClaimVersionReference,
            SemanticClaimAdjacencyRoot
        >()
        private val pages = LinkedHashMap<
            Pair<SemanticClaimVersionReference, Long>,
            SemanticClaimAdjacencyPage
        >()

        override fun readRoot(
            reference: SemanticClaimVersionReference
        ): SemanticClaimAdjacencyRootLoadResult =
            roots[reference]?.let {
                SemanticClaimAdjacencyRootLoadResult.Loaded(it)
            } ?: SemanticClaimAdjacencyRootLoadResult.Missing

        override fun readPage(
            reference: SemanticClaimVersionReference,
            ordinal: Long
        ): SemanticClaimAdjacencyPageLoadResult =
            pages[reference to ordinal]?.let {
                SemanticClaimAdjacencyPageLoadResult.Loaded(it)
            } ?: SemanticClaimAdjacencyPageLoadResult.Missing

        override fun writeManifest(
            manifest: SemanticClaimAdjacencyManifest
        ): SemanticClaimAdjacencyWriteResult {
            manifests += manifest
            return SemanticClaimAdjacencyWriteResult.Written
        }

        override fun writeRoot(
            root: SemanticClaimAdjacencyRoot
        ): SemanticClaimAdjacencyWriteResult {
            roots[root.reference] = root
            return SemanticClaimAdjacencyWriteResult.Written
        }

        override fun writePage(
            page: SemanticClaimAdjacencyPage
        ): SemanticClaimAdjacencyWriteResult {
            pages[page.reference to page.ordinal] = page
            return SemanticClaimAdjacencyWriteResult.Written
        }

        fun allEntries(
            reference: SemanticClaimVersionReference
        ): List<SemanticClaimAdjacencyEntry> =
            pages.entries
                .filter { it.key.first == reference }
                .sortedBy { it.key.second }
                .flatMap { it.value.entries }
    }
}