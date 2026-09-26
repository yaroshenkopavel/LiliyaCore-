package pro.liliya.core.episodic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentEntityId

class EpisodeIndexedRetrievalContractTest {
    private val checkpoint = EpisodeIndexSourceCheckpoint(7, 11, 3)
    private val base = Instant.parse("2026-09-19T10:00:00Z")

    @Test
    fun incomplete_index_requires_fallback_without_reading_index_or_episode() {
        val source = FakeSource(checkpoint)
        val repo = FakeRepository()
        val index = FakeIndex(EpisodeIndexCompleteness.INCOMPLETE)
        val coordinator = EpisodeIndexedRetrievalCoordinator(source, repo, index)

        val result = coordinator.temporal(
            EpisodeIndexedTemporalQuery(
                axis = EpisodeTemporalAxis.OBSERVED,
                window = EpisodeTimeWindow(startInclusive = base.minusSeconds(60))
            )
        )

        assertIs<EpisodeIndexedRetrievalResult.FallbackRequired>(result)
        assertEquals(0, index.temporalCalls)
        assertEquals(0, repo.lookupCalls)
    }

    @Test
    fun complete_temporal_index_returns_exact_generation_verified_episode_and_stable_cursor() {
        val episodeA = episode("episode-a", generation = 2, observedAt = base)
        val episodeB = episode("episode-b", generation = 3, observedAt = base.plusSeconds(1))
        val repo = FakeRepository(episodeA, episodeB)
        val entries = listOf(
            temporalEntry(episodeA, EpisodeTemporalAxis.OBSERVED, episodeA.record.observedAt),
            temporalEntry(episodeB, EpisodeTemporalAxis.OBSERVED, episodeB.record.observedAt)
        )
        val index = FakeIndex(EpisodeIndexCompleteness.COMPLETE, temporalEntries = entries)
        val coordinator = EpisodeIndexedRetrievalCoordinator(FakeSource(checkpoint), repo, index)

        val first = assertIs<EpisodeIndexedRetrievalResult.Loaded>(
            coordinator.temporal(
                EpisodeIndexedTemporalQuery(
                    axis = EpisodeTemporalAxis.OBSERVED,
                    window = EpisodeTimeWindow(startInclusive = base.minusSeconds(1)),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 1,
                    maxScannedIndexEntries = 1
                )
            )
        )
        assertEquals(listOf("episode-a"), first.entries.map { it.record.id.value })
        assertFalse(first.exhausted)
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(entries.first().id, cursor.indexEntryId)
        assertEquals(entries.first().sortAt, cursor.indexedAt)

        val second = assertIs<EpisodeIndexedRetrievalResult.Loaded>(
            coordinator.temporal(
                EpisodeIndexedTemporalQuery(
                    axis = EpisodeTemporalAxis.OBSERVED,
                    window = EpisodeTimeWindow(startInclusive = base.minusSeconds(1)),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 2,
                    maxScannedIndexEntries = 2,
                    cursorExclusive = cursor
                )
            )
        )
        assertEquals(listOf("episode-b"), second.entries.map { it.record.id.value })
        assertTrue(second.exhausted)
    }

    @Test
    fun stale_generation_or_missing_episode_never_returns_partial_index_truth() {
        val episode = episode("episode-stale", generation = 5, observedAt = base)
        val stale = EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.OBSERVED,
            indexedAt = base,
            episodeId = episode.record.id,
            episodeGeneration = 4
        )
        val mismatch = EpisodeIndexedRetrievalCoordinator(
            FakeSource(checkpoint),
            FakeRepository(episode),
            FakeIndex(EpisodeIndexCompleteness.COMPLETE, temporalEntries = listOf(stale))
        )
        assertIs<EpisodeIndexedRetrievalResult.FallbackRequired>(
            mismatch.temporal(
                EpisodeIndexedTemporalQuery(
                    axis = EpisodeTemporalAxis.OBSERVED,
                    window = EpisodeTimeWindow(startInclusive = base.minusSeconds(1))
                )
            )
        )

        val missing = EpisodeIndexedRetrievalCoordinator(
            FakeSource(checkpoint),
            FakeRepository(),
            FakeIndex(EpisodeIndexCompleteness.COMPLETE, temporalEntries = listOf(stale.copy(episodeGeneration = 4)))
        )
        assertIs<EpisodeIndexedRetrievalResult.FallbackRequired>(
            missing.temporal(
                EpisodeIndexedTemporalQuery(
                    axis = EpisodeTemporalAxis.OBSERVED,
                    window = EpisodeTimeWindow(startInclusive = base.minusSeconds(1))
                )
            )
        )
    }

    @Test
    fun provenance_query_filters_namespace_then_exact_verifies_episode() {
        val episode = episode("episode-provenance", generation = 9, observedAt = base)
        val wrong = EpisodeProvenanceIndexEntry(
            namespace = RawEvidenceNamespace("action-log"),
            episodeDerivedAt = episode.record.derivedAt,
            episodeId = episode.record.id,
            episodeGeneration = episode.generation
        )
        val right = EpisodeProvenanceIndexEntry(
            namespace = RawEvidenceNamespace("conversation-v3"),
            episodeDerivedAt = episode.record.derivedAt,
            episodeId = episode.record.id,
            episodeGeneration = episode.generation
        )
        val coordinator = EpisodeIndexedRetrievalCoordinator(
            FakeSource(checkpoint),
            FakeRepository(episode),
            FakeIndex(EpisodeIndexCompleteness.COMPLETE, provenanceEntries = listOf(wrong, right))
        )

        val result = assertIs<EpisodeIndexedRetrievalResult.Loaded>(
            coordinator.provenance(
                EpisodeIndexedProvenanceQuery(
                    namespace = RawEvidenceNamespace("conversation-v3"),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 5,
                    maxScannedIndexEntries = 5
                )
            )
        )
        assertEquals(listOf("episode-provenance"), result.entries.map { it.record.id.value })
        assertEquals(2, result.scannedIndexEntries)
    }

    private fun episode(id: String, generation: Long, observedAt: Instant): EpisodeSnapshot {
        val derivedAt = observedAt.plusSeconds(10)
        return EpisodeSnapshot(
            record = EpisodeRecord(
                id = EpisodeId(id),
                evidence = listOf(
                    RawEvidenceReference(
                        RawEvidenceNamespace("conversation-v3"),
                        RawEvidenceId("$id-evidence")
                    )
                ),
                description = "episode $id",
                observedAt = observedAt,
                eventAt = observedAt.plusSeconds(2),
                derivedAt = derivedAt,
                extraction = EpisodeExtractionProvenance("test", "1", derivedAt)
            ),
            generation = generation
        )
    }

    private fun temporalEntry(
        snapshot: EpisodeSnapshot,
        axis: EpisodeTemporalAxis,
        at: Instant
    ) = EpisodeTemporalIndexEntry(axis, at, snapshot.record.id, snapshot.generation)

    private class FakeSource(
        private val checkpoint: EpisodeIndexSourceCheckpoint?
    ) : EpisodicIndexSource {
        override fun indexSourceCheckpoint(): EpisodeIndexSourceCheckpoint? = checkpoint
        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): EpisodePageResult = EpisodePageResult.Empty
    }

    private class FakeRepository(
        vararg snapshots: EpisodeSnapshot
    ) : EpisodicMemoryRepository {
        private val entries = snapshots.associateBy { it.record.id }
        var lookupCalls = 0
        override fun store(record: EpisodeRecord): EpisodeStoreResult = error("unused")
        override fun lookup(id: EpisodeId): EpisodeLookupResult {
            lookupCalls += 1
            return entries[id]?.let(EpisodeLookupResult::Found) ?: EpisodeLookupResult.Missing
        }
    }

    private class FakeIndex(
        private val completeness: EpisodeIndexCompleteness,
        private val temporalEntries: List<EpisodeIndexEntry> = emptyList(),
        private val provenanceEntries: List<EpisodeIndexEntry> = emptyList()
    ) : EpisodicIndexReadStore {
        var temporalCalls = 0

        override fun completeness(source: EpisodeIndexSourceCheckpoint): EpisodeIndexCompleteness = completeness

        override fun temporalPage(
            axis: EpisodeTemporalAxis,
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): EpisodeIndexPageResult {
            temporalCalls += 1
            return page(temporalEntries, limit, order, cursorExclusive)
        }

        override fun provenancePage(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): EpisodeIndexPageResult = page(provenanceEntries, limit, order, cursorExclusive)

        private fun page(
            source: List<EpisodeIndexEntry>,
            limit: Int,
            order: PersistentBackendPageOrder,
            cursor: PersistentBackendPageCursor?
        ): EpisodeIndexPageResult {
            val sorted = source.sortedWith(compareBy<EpisodeIndexEntry>({ it.sortAt }, { it.id.value }))
                .let { if (order == PersistentBackendPageOrder.OLDEST_FIRST) it else it.asReversed() }
            val filtered = sorted.filter { entry ->
                cursor == null || when (order) {
                    PersistentBackendPageOrder.OLDEST_FIRST ->
                        entry.sortAt > cursor.createdAt ||
                            (entry.sortAt == cursor.createdAt && entry.id.value > cursor.entityId.value)
                    PersistentBackendPageOrder.NEWEST_FIRST ->
                        entry.sortAt < cursor.createdAt ||
                            (entry.sortAt == cursor.createdAt && entry.id.value < cursor.entityId.value)
                }
            }
            val entries = filtered.take(limit)
            if (entries.isEmpty()) return EpisodeIndexPageResult.Empty
            val next = if (filtered.size > limit) {
                val last = entries.last()
                PersistentBackendPageCursor(last.sortAt, PersistentEntityId(last.id.value))
            } else null
            return EpisodeIndexPageResult.Loaded(entries, next)
        }
    }
}
