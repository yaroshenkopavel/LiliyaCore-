package pro.liliya.core.episodic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

class EpisodicMemoryPersistentCodecContractTest {
    private val episode = EpisodeRecord(
        id = EpisodeId("episode-codec"),
        evidence = listOf(
            RawEvidenceReference(
                RawEvidenceNamespace("conversation-v3"),
                RawEvidenceId("session-7:chunk-2")
            )
        ),
        description = "user completed a bounded project step",
        observedAt = Instant.parse("2026-09-19T12:00:00Z"),
        eventAt = Instant.parse("2026-09-19T11:58:00Z"),
        derivedAt = Instant.parse("2026-09-19T12:01:00Z")
    )

    @Test
    fun versioned_codec_round_trips_exact_episode_and_provenance() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        val decoded = assertIs<EpisodePersistentDecodeResult.Decoded>(
            EpisodicMemoryPersistentCodec.decode(encoded)
        )
        assertEquals(episode, decoded.record)
    }

    @Test
    fun wrong_schema_or_version_is_incompatible_not_silently_accepted() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        assertIs<EpisodePersistentDecodeResult.Incompatible>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    PersistentSchemaId("other-domain"),
                    encoded.schemaVersion,
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )
        assertIs<EpisodePersistentDecodeResult.Incompatible>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    PersistentSchemaVersion(2),
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )
    }

    @Test
    fun identity_time_or_payload_corruption_fails_closed() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        assertIs<EpisodePersistentDecodeResult.Corrupt>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    pro.liliya.core.persistence.PersistentEntityId("wrong-id"),
                    encoded.schemaId,
                    encoded.schemaVersion,
                    encoded.payload,
                    encoded.createdAt
                )
            )
        )
        assertIs<EpisodePersistentDecodeResult.Corrupt>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    encoded.payload,
                    encoded.createdAt.plusSeconds(1)
                )
            )
        )
        val damaged = encoded.payload.copyBytes().also { it[0] = (it[0].toInt() xor 0x7f).toByte() }
        assertIs<EpisodePersistentDecodeResult.Corrupt>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    PersistentPayload(damaged),
                    encoded.createdAt
                )
            )
        )
    }
}
