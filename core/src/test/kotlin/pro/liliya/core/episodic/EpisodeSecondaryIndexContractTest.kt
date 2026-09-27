package pro.liliya.core.episodic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EpisodeSecondaryIndexContractTest {
    @Test
    fun projection_is_deterministic_deduplicates_namespaces_and_omits_null_event() {
        val snapshot = EpisodeSnapshot(
            record = EpisodeRecord(
                id = EpisodeId("episode-1"),
                evidence = listOf(
                    RawEvidenceReference(RawEvidenceNamespace("conversation-v3"), RawEvidenceId("chunk-1")),
                    RawEvidenceReference(RawEvidenceNamespace("action-log"), RawEvidenceId("action-1")),
                    RawEvidenceReference(RawEvidenceNamespace("conversation-v3"), RawEvidenceId("chunk-2"))
                ),
                description = "sensitive description must not be copied into index",
                observedAt = Instant.parse("2026-09-19T10:00:00Z"),
                eventAt = null,
                derivedAt = Instant.parse("2026-09-19T10:01:00Z"),
                extraction = EpisodeExtractionProvenance(
                    extractorId = "episode-extractor",
                    extractorVersion = "1",
                    extractedAt = Instant.parse("2026-09-19T10:01:00Z")
                )
            ),
            generation = 7L
        )

        val first = EpisodeIndexProjector.project(snapshot)
        val second = EpisodeIndexProjector.project(snapshot)
        assertEquals(first, second)

        val temporal = first.filterIsInstance<EpisodeTemporalIndexEntry>()
        assertEquals(
            setOf(EpisodeTemporalAxis.DERIVED, EpisodeTemporalAxis.OBSERVED),
            temporal.map { it.axis }.toSet()
        )
        assertFalse(temporal.any { it.axis == EpisodeTemporalAxis.EVENT })

        val provenance = first.filterIsInstance<EpisodeProvenanceIndexEntry>()
        assertEquals(
            listOf("action-log", "conversation-v3"),
            provenance.map { it.namespace.value }
        )
        assertTrue(first.all { it.episodeId == snapshot.record.id })
        assertTrue(first.all { it.episodeGeneration == snapshot.generation })
        assertEquals(first.map { it.id }.toSet().size, first.size)
    }

    @Test
    fun event_projection_is_added_when_event_time_exists() {
        val eventAt = Instant.parse("2026-09-19T09:59:00Z")
        val snapshot = EpisodeSnapshot(
            record = EpisodeRecord(
                id = EpisodeId("episode-event"),
                evidence = listOf(
                    RawEvidenceReference(RawEvidenceNamespace("conversation-v3"), RawEvidenceId("chunk-1"))
                ),
                description = "event episode",
                observedAt = Instant.parse("2026-09-19T10:00:00Z"),
                eventAt = eventAt,
                derivedAt = Instant.parse("2026-09-19T10:01:00Z")
            ),
            generation = 3L
        )

        val event = assertIs<EpisodeTemporalIndexEntry>(
            EpisodeIndexProjector.project(snapshot)
                .single { it is EpisodeTemporalIndexEntry && it.axis == EpisodeTemporalAxis.EVENT }
        )
        assertEquals(eventAt, event.indexedAt)
        assertEquals(eventAt, event.sortAt)
    }
}
