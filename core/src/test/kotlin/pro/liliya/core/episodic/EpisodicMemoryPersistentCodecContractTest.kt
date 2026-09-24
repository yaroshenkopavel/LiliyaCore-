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
                    PersistentSchemaVersion(99),
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

    @Test
    fun schema_v3_round_trips_structured_context_without_truth_promotion() {
        val contextual = episode.copy(
            context = EpisodeStructuredContext(
                entities = listOf(
                    EpisodeEntityReference("person", "user", "actor"),
                    EpisodeEntityReference("project", "LiliyaCore")
                ),
                task = EpisodeContextReference("task", "episodic-v0.6"),
                goal = EpisodeContextReference("goal", "lifelong-memory"),
                tags = setOf("memory", "bounded"),
                interval = EpisodeTimeInterval(
                    startInclusive = Instant.parse("2026-09-19T11:57:00Z"),
                    endExclusive = Instant.parse("2026-09-19T12:00:00Z")
                ),
                significance = EpisodeSignificance.HIGH,
                extractionConfidence = EpisodeExtractionConfidence.MEDIUM,
                links = listOf(
                    EpisodeLink(EpisodeLinkType.RELATED, EpisodeId("episode-prior"))
                )
            )
        )
        val encoded = EpisodicMemoryPersistentCodec.encode(contextual)
        assertEquals(PersistentSchemaVersion(3), encoded.schemaVersion)
        val decoded = assertIs<EpisodePersistentDecodeResult.Decoded>(
            EpisodicMemoryPersistentCodec.decode(encoded)
        )
        assertEquals(contextual, decoded.record)
    }

    @Test
    fun legacy_v2_payload_remains_readable_with_empty_structured_context() {
        val extracted = episode.copy(
            extraction = EpisodeExtractionProvenance(
                extractorId = "legacy-extractor",
                extractorVersion = "2.0.0",
                extractedAt = episode.derivedAt
            )
        )
        val encoded = EpisodicMemoryPersistentCodec.encode(extracted)
        val decoded = assertIs<EpisodePersistentDecodeResult.Decoded>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    PersistentSchemaVersion(2),
                    PersistentPayload(legacyV2Payload(extracted)),
                    encoded.createdAt
                )
            )
        )
        assertEquals(extracted.copy(context = EpisodeStructuredContext.EMPTY), decoded.record)
    }

    @Test
    fun self_link_is_rejected_by_episode_model() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            episode.copy(
                context = EpisodeStructuredContext(
                    links = listOf(
                        EpisodeLink(EpisodeLinkType.RELATED, episode.id)
                    )
                )
            )
        }
    }

    @Test
    fun legacy_v1_payload_remains_readable_without_extraction_provenance() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        val legacyPayload = legacyV1Payload(episode)
        val decoded = assertIs<EpisodePersistentDecodeResult.Decoded>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    PersistentSchemaVersion(1),
                    PersistentPayload(legacyPayload),
                    encoded.createdAt
                )
            )
        )
        assertEquals(episode.copy(extraction = null), decoded.record)
    }


    private fun legacyV2Payload(record: EpisodeRecord): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            fun writeString(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            fun writeInstant(value: Instant) {
                data.writeLong(value.epochSecond)
                data.writeInt(value.nano)
            }
            data.writeInt(0x45505331)
            writeString(record.id.value)
            data.writeInt(record.evidence.size)
            record.evidence.forEach { reference ->
                writeString(reference.namespace.value)
                writeString(reference.id.value)
            }
            writeString(record.description)
            writeInstant(record.observedAt)
            data.writeBoolean(record.eventAt != null)
            record.eventAt?.let(::writeInstant)
            writeInstant(record.derivedAt)
            data.writeBoolean(record.extraction != null)
            record.extraction?.let {
                writeString(it.extractorId)
                writeString(it.extractorVersion)
                writeInstant(it.extractedAt)
            }
        }
        return output.toByteArray()
    }

    private fun legacyV1Payload(record: EpisodeRecord): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            fun writeString(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            fun writeInstant(value: Instant) {
                data.writeLong(value.epochSecond)
                data.writeInt(value.nano)
            }
            data.writeInt(0x45505331)
            writeString(record.id.value)
            data.writeInt(record.evidence.size)
            record.evidence.forEach { reference ->
                writeString(reference.namespace.value)
                writeString(reference.id.value)
            }
            writeString(record.description)
            writeInstant(record.observedAt)
            data.writeBoolean(record.eventAt != null)
            record.eventAt?.let(::writeInstant)
            writeInstant(record.derivedAt)
        }
        return output.toByteArray()
    }

}
