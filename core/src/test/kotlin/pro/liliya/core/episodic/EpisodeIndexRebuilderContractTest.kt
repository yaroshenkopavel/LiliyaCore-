package pro.liliya.core.episodic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentEntityId

class EpisodeIndexRebuilderContractTest {
    @Test
    fun stable_source_writes_complete_manifest_after_projection() {
        val checkpoint = EpisodeIndexSourceCheckpoint(revision = 10L, highWatermark = 2L, entryCount = 2L)
        val source = FakeSource(
            checkpoints = ArrayDeque(listOf(checkpoint, checkpoint)),
            pages = ArrayDeque(
                listOf(
                    EpisodePageResult.Loaded(
                        entries = listOf(snapshot("episode-1", 1L), snapshot("episode-2", 2L)),
                        nextCursor = null
                    )
                )
            )
        )
        val index = FakeIndexStore()
        val rebuiltAt = Instant.parse("2026-09-19T13:00:00Z")

        val result = assertIs<EpisodeIndexRebuildResult.Complete>(
            EpisodeIndexRebuilder(source, index).rebuild(rebuiltAt, pageSize = 2)
        )
        assertEquals(checkpoint, result.source)
        assertEquals(2, result.episodesScanned)
        assertEquals(8, result.projectedEntries)
        assertEquals(8, result.indexedEntries)
        assertEquals(checkpoint, index.lastManifest?.source)
        assertEquals(rebuiltAt, index.lastManifest?.rebuiltAt)
    }

    @Test
    fun source_drift_during_rebuild_never_writes_complete_manifest() {
        val start = EpisodeIndexSourceCheckpoint(revision = 10L, highWatermark = 1L, entryCount = 1L)
        val end = start.copy(revision = 11L, highWatermark = 2L, entryCount = 2L)
        val source = FakeSource(
            checkpoints = ArrayDeque(listOf(start, end)),
            pages = ArrayDeque(
                listOf(
                    EpisodePageResult.Loaded(
                        entries = listOf(snapshot("episode-1", 1L)),
                        nextCursor = null
                    )
                )
            )
        )
        val index = FakeIndexStore()

        val result = assertIs<EpisodeIndexRebuildResult.Incomplete>(
            EpisodeIndexRebuilder(source, index).rebuild(Instant.parse("2026-09-19T13:00:00Z"))
        )
        assertEquals("episodic source changed during index rebuild", result.reason)
        assertNull(index.lastManifest)
    }

    @Test
    fun projection_failure_never_writes_complete_manifest() {
        val checkpoint = EpisodeIndexSourceCheckpoint(revision = 10L, highWatermark = 1L, entryCount = 1L)
        val source = FakeSource(
            checkpoints = ArrayDeque(listOf(checkpoint)),
            pages = ArrayDeque(
                listOf(
                    EpisodePageResult.Loaded(
                        entries = listOf(snapshot("episode-1", 1L)),
                        nextCursor = null
                    )
                )
            )
        )
        val index = FakeIndexStore(failProjection = true)

        val result = assertIs<EpisodeIndexRebuildResult.Incomplete>(
            EpisodeIndexRebuilder(source, index).rebuild(Instant.parse("2026-09-19T13:00:00Z"))
        )
        assertEquals(0, result.episodesScanned)
        assertNull(index.lastManifest)
    }

    private fun snapshot(id: String, generation: Long): EpisodeSnapshot = EpisodeSnapshot(
        record = EpisodeRecord(
            id = EpisodeId(id),
            evidence = listOf(
                RawEvidenceReference(
                    RawEvidenceNamespace("conversation-v3"),
                    RawEvidenceId("$id-evidence")
                )
            ),
            description = "episode $id",
            observedAt = Instant.parse("2026-09-19T10:00:00Z").plusSeconds(generation),
            eventAt = Instant.parse("2026-09-19T09:59:00Z").plusSeconds(generation),
            derivedAt = Instant.parse("2026-09-19T10:01:00Z").plusSeconds(generation)
        ),
        generation = generation
    )

    private class FakeSource(
        private val checkpoints: ArrayDeque<EpisodeIndexSourceCheckpoint>,
        private val pages: ArrayDeque<EpisodePageResult>
    ) : EpisodicIndexSource {
        override fun indexSourceCheckpoint(): EpisodeIndexSourceCheckpoint? =
            if (checkpoints.isEmpty()) null else checkpoints.removeFirst()

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): EpisodePageResult = if (pages.isEmpty()) EpisodePageResult.Empty else pages.removeFirst()
    }

    private class FakeIndexStore(
        private val failProjection: Boolean = false
    ) : EpisodicIndexProjectionStore {
        var lastManifest: EpisodeIndexManifest? = null

        override fun project(snapshot: EpisodeSnapshot): EpisodeIndexProjectionResult {
            val projected = EpisodeIndexProjector.project(snapshot)
            return if (failProjection) {
                EpisodeIndexProjectionResult.Incomplete(
                    projected = projected.size,
                    indexed = 0,
                    alreadyIndexed = 0,
                    failedEntryId = projected.firstOrNull()?.id
                        ?: EpisodeIndexEntryId("synthetic-failure"),
                    reason = "synthetic projection failure"
                )
            } else {
                EpisodeIndexProjectionResult.Complete(
                    projected = projected.size,
                    indexed = projected.size,
                    alreadyIndexed = 0
                )
            }
        }

        override fun writeManifest(manifest: EpisodeIndexManifest): EpisodeIndexManifestResult {
            lastManifest = manifest
            return EpisodeIndexManifestResult.Loaded(manifest, 1L)
        }

        override fun completeness(source: EpisodeIndexSourceCheckpoint): EpisodeIndexCompleteness =
            if (lastManifest?.source == source) EpisodeIndexCompleteness.COMPLETE
            else EpisodeIndexCompleteness.UNKNOWN
    }
}
