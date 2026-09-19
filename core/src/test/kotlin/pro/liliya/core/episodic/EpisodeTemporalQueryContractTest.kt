package pro.liliya.core.episodic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.encryption.EncryptedPersistentRecordPageResult
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentRecordSnapshot

class EpisodeTemporalQueryContractTest {
    @Test
    fun malformed_windows_and_budgets_fail_closed_at_construction() {
        assertFailsWith<IllegalArgumentException> {
            EpisodeTimeWindow()
        }
        assertFailsWith<IllegalArgumentException> {
            EpisodeTimeWindow(
                startInclusive = Instant.parse("2026-09-19T10:00:00Z"),
                endExclusive = Instant.parse("2026-09-19T09:00:00Z")
            )
        }
        val window = EpisodeTimeWindow(
            startInclusive = Instant.parse("2026-09-19T09:00:00Z")
        )
        assertFailsWith<IllegalArgumentException> {
            EpisodeTemporalQuery(
                axis = EpisodeTemporalAxis.DERIVED,
                window = window,
                limit = 0
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EpisodeTemporalQuery(
                axis = EpisodeTemporalAxis.DERIVED,
                window = window,
                maxScannedEntries = EpisodeTemporalQuery.MAX_SCAN_BUDGET + 1
            )
        }
    }

    @Test
    fun equal_derived_times_are_stable_by_episode_id_across_cursor_pages() {
        val at = Instant.parse("2026-09-19T10:00:00Z")
        val loader = loader(
            episode("episode-b", at, at, at, "conversation-v3"),
            episode("episode-a", at, at, at, "conversation-v3"),
            episode("episode-c", at.plusSeconds(1), at, at.plusSeconds(1), "conversation-v3")
        )

        val first = assertIs<EpisodeTemporalQueryResult.Loaded>(
            EpisodeTemporalQueryExecutor.execute(
                EpisodeTemporalQuery(
                    axis = EpisodeTemporalAxis.DERIVED,
                    window = EpisodeTimeWindow(startInclusive = at),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 1,
                    maxScannedEntries = 1
                ),
                loader
            )
        )
        assertEquals(listOf("episode-a"), first.entries.map { it.record.id.value })
        assertFalse(first.exhausted)
        val cursor = assertNotNull(first.nextCursor)
        assertEquals("episode-a", cursor.episodeId.value)

        val second = assertIs<EpisodeTemporalQueryResult.Loaded>(
            EpisodeTemporalQueryExecutor.execute(
                EpisodeTemporalQuery(
                    axis = EpisodeTemporalAxis.DERIVED,
                    window = EpisodeTimeWindow(startInclusive = at),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 2,
                    maxScannedEntries = 2,
                    cursorExclusive = cursor
                ),
                loader
            )
        )
        assertEquals(listOf("episode-b", "episode-c"), second.entries.map { it.record.id.value })
    }

    @Test
    fun event_query_excludes_null_event_and_applies_provenance_namespace_filter() {
        val derived = Instant.parse("2026-09-19T12:00:00Z")
        val event = Instant.parse("2026-09-19T10:00:00Z")
        val loader = loader(
            episode("episode-null", derived, derived, null, "conversation-v3"),
            episode("episode-wrong-ns", derived.plusSeconds(1), derived, event, "action-log"),
            episode("episode-match", derived.plusSeconds(2), derived, event.plusSeconds(60), "conversation-v3")
        )
        val result = assertIs<EpisodeTemporalQueryResult.Loaded>(
            EpisodeTemporalQueryExecutor.execute(
                EpisodeTemporalQuery(
                    axis = EpisodeTemporalAxis.EVENT,
                    window = EpisodeTimeWindow(
                        startInclusive = event,
                        endExclusive = event.plusSeconds(120)
                    ),
                    evidenceNamespace = RawEvidenceNamespace("conversation-v3"),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 10,
                    maxScannedEntries = 10
                ),
                loader
            )
        )
        assertEquals(listOf("episode-match"), result.entries.map { it.record.id.value })
        assertTrue(result.exhausted)
        assertEquals(3, result.scannedEntries)
    }

    @Test
    fun zero_match_scan_budget_returns_continuation_without_full_archive_scan() {
        val base = Instant.parse("2026-09-19T08:00:00Z")
        val loader = loader(
            episode("episode-1", base, base, base, "action-log"),
            episode("episode-2", base.plusSeconds(1), base, base, "action-log"),
            episode("episode-3", base.plusSeconds(2), base, base, "conversation-v3")
        )
        val result = assertIs<EpisodeTemporalQueryResult.Loaded>(
            EpisodeTemporalQueryExecutor.execute(
                EpisodeTemporalQuery(
                    axis = EpisodeTemporalAxis.OBSERVED,
                    window = EpisodeTimeWindow(startInclusive = base),
                    evidenceNamespace = RawEvidenceNamespace("conversation-v3"),
                    order = EpisodeTemporalOrder.OLDEST_FIRST,
                    limit = 5,
                    maxScannedEntries = 2
                ),
                loader
            )
        )
        assertTrue(result.entries.isEmpty())
        assertFalse(result.exhausted)
        assertEquals(2, result.scannedEntries)
        assertEquals("episode-2", assertNotNull(result.nextCursor).episodeId.value)
    }

    private fun episode(
        id: String,
        derivedAt: Instant,
        observedAt: Instant,
        eventAt: Instant?,
        namespace: String
    ): EpisodeRecord = EpisodeRecord(
        id = EpisodeId(id),
        evidence = listOf(
            RawEvidenceReference(
                RawEvidenceNamespace(namespace),
                RawEvidenceId("$id-evidence")
            )
        ),
        description = "episode $id",
        observedAt = observedAt,
        eventAt = eventAt,
        derivedAt = derivedAt,
        extraction = EpisodeExtractionProvenance(
            extractorId = "temporal-test",
            extractorVersion = "1",
            extractedAt = derivedAt
        )
    )

    private fun loader(
        vararg episodes: EpisodeRecord
    ): (PersistentBackendPageRequest) -> EncryptedPersistentRecordPageResult {
        val snapshots = episodes.mapIndexed { index, episode ->
            PersistentRecordSnapshot(
                record = EpisodicMemoryPersistentCodec.encode(episode),
                generation = PersistentGeneration(index.toLong() + 1L)
            )
        }
        return { request ->
            val ordered = snapshots.sortedWith(
                compareBy<PersistentRecordSnapshot>(
                    { it.record.createdAt },
                    { it.record.id.value }
                )
            ).let {
                if (request.order == PersistentBackendPageOrder.OLDEST_FIRST) it else it.asReversed()
            }
            val filtered = ordered.filter { snapshot ->
                val cursor = request.cursorExclusive ?: return@filter true
                afterCursor(snapshot, cursor, request.order)
            }
            val entries = filtered.take(request.limit)
            val next = if (filtered.size > request.limit && entries.isNotEmpty()) {
                val last = entries.last().record
                PersistentBackendPageCursor(last.createdAt, last.id)
            } else {
                null
            }
            if (entries.isEmpty()) {
                EncryptedPersistentRecordPageResult.Empty
            } else {
                EncryptedPersistentRecordPageResult.Loaded(entries, next)
            }
        }
    }

    private fun afterCursor(
        snapshot: PersistentRecordSnapshot,
        cursor: PersistentBackendPageCursor,
        order: PersistentBackendPageOrder
    ): Boolean = when (order) {
        PersistentBackendPageOrder.OLDEST_FIRST ->
            snapshot.record.createdAt > cursor.createdAt ||
                (snapshot.record.createdAt == cursor.createdAt &&
                    snapshot.record.id.value > cursor.entityId.value)

        PersistentBackendPageOrder.NEWEST_FIRST ->
            snapshot.record.createdAt < cursor.createdAt ||
                (snapshot.record.createdAt == cursor.createdAt &&
                    snapshot.record.id.value < cursor.entityId.value)
    }
}
