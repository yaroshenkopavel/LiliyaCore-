package pro.liliya.core.episodic

import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

class EpisodicIndexPersistentCodecContractTest {
    @Test
    fun temporal_and_provenance_entries_round_trip_with_created_at_as_index_sort_time() {
        val temporal = EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.OBSERVED,
            indexedAt = Instant.parse("2026-09-19T10:00:00Z"),
            episodeId = EpisodeId("episode-1"),
            episodeGeneration = 4L
        )
        val provenance = EpisodeProvenanceIndexEntry(
            namespace = RawEvidenceNamespace("conversation-v3"),
            episodeDerivedAt = Instant.parse("2026-09-19T10:01:00Z"),
            episodeId = EpisodeId("episode-1"),
            episodeGeneration = 4L
        )

        listOf<EpisodeIndexEntry>(temporal, provenance).forEach { entry ->
            val encoded = EpisodicIndexPersistentCodec.encode(entry)
            assertEquals(entry.sortAt, encoded.createdAt)
            val decoded = assertIs<EpisodeIndexDecodeResult.Decoded>(
                EpisodicIndexPersistentCodec.decode(encoded)
            )
            assertEquals(entry, decoded.entry)
        }
    }

    @Test
    fun index_payload_does_not_copy_episode_description() {
        val entry = EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.DERIVED,
            indexedAt = Instant.parse("2026-09-19T10:01:00Z"),
            episodeId = EpisodeId("episode-private"),
            episodeGeneration = 9L
        )
        val encoded = EpisodicIndexPersistentCodec.encode(entry)
        val payload = String(encoded.payload.copyBytes(), StandardCharsets.ISO_8859_1)
        assertFalse(payload.contains("sensitive description"))
    }

    @Test
    fun id_time_schema_or_payload_tampering_fails_closed() {
        val entry = EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.EVENT,
            indexedAt = Instant.parse("2026-09-19T09:59:00Z"),
            episodeId = EpisodeId("episode-1"),
            episodeGeneration = 2L
        )
        val encoded = EpisodicIndexPersistentCodec.encode(entry)

        assertIs<EpisodeIndexDecodeResult.Corrupt>(
            EpisodicIndexPersistentCodec.decode(
                PersistentRecord(
                    id = PersistentEntityId("wrong-index-id"),
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    payload = encoded.payload,
                    createdAt = encoded.createdAt
                )
            )
        )
        assertIs<EpisodeIndexDecodeResult.Corrupt>(
            EpisodicIndexPersistentCodec.decode(
                PersistentRecord(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    payload = encoded.payload,
                    createdAt = encoded.createdAt.plusSeconds(1)
                )
            )
        )
        assertIs<EpisodeIndexDecodeResult.Incompatible>(
            EpisodicIndexPersistentCodec.decode(
                PersistentRecord(
                    id = encoded.id,
                    schemaId = PersistentSchemaId("wrong-schema"),
                    schemaVersion = encoded.schemaVersion,
                    payload = encoded.payload,
                    createdAt = encoded.createdAt
                )
            )
        )
        assertIs<EpisodeIndexDecodeResult.Incompatible>(
            EpisodicIndexPersistentCodec.decode(
                PersistentRecord(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = PersistentSchemaVersion(99),
                    payload = encoded.payload,
                    createdAt = encoded.createdAt
                )
            )
        )
        val damaged = encoded.payload.copyBytes().also { it[0] = (it[0].toInt() xor 0x7f).toByte() }
        assertIs<EpisodeIndexDecodeResult.Corrupt>(
            EpisodicIndexPersistentCodec.decode(
                PersistentRecord(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    payload = PersistentPayload(damaged),
                    createdAt = encoded.createdAt
                )
            )
        )
    }
}
