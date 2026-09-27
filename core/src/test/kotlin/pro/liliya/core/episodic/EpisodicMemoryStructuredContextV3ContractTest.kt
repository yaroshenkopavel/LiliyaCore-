package pro.liliya.core.episodic

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaVersion

class EpisodicMemoryStructuredContextV3ContractTest {
    private val extraction = EpisodeExtractionProvenance(
        extractorId = "episode-extractor",
        extractorVersion = "2.0.0",
        extractedAt = Instant.parse("2026-09-24T18:00:00Z")
    )

    private val context = EpisodeStructuredContext(
        entities = listOf(
            EpisodeEntityReference("person", "user", "actor"),
            EpisodeEntityReference("project", "LiliyaCore", "subject")
        ),
        task = EpisodeContextReference("task", "episodic-v0.6"),
        goal = EpisodeContextReference("goal", "lifelong-memory-foundation"),
        tags = setOf("memory", "episodic"),
        interval = EpisodeTimeInterval(
            startInclusive = Instant.parse("2026-09-24T17:55:00Z"),
            endExclusive = Instant.parse("2026-09-24T18:00:00Z")
        ),
        significance = EpisodeSignificance.HIGH,
        extractionConfidence = EpisodeExtractionConfidence.HIGH,
        links = listOf(
            EpisodeLink(EpisodeLinkType.RELATED, EpisodeId("episode-related"))
        )
    )

    private val episode = EpisodeRecord(
        id = EpisodeId("episode-v3"),
        evidence = listOf(
            RawEvidenceReference(
                RawEvidenceNamespace("conversation-v3"),
                RawEvidenceId("session-1:chunk-8")
            )
        ),
        description = "structured episodic context persisted",
        observedAt = Instant.parse("2026-09-24T17:59:00Z"),
        eventAt = Instant.parse("2026-09-24T17:58:00Z"),
        derivedAt = extraction.extractedAt,
        extraction = extraction,
        context = context
    )

    @Test
    fun schema_v3_round_trips_exact_structured_context() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        assertEquals(PersistentSchemaVersion(3), encoded.schemaVersion)
        val decoded = assertIs<EpisodePersistentDecodeResult.Decoded>(
            EpisodicMemoryPersistentCodec.decode(encoded)
        )
        assertEquals(episode, decoded.record)
    }

    @Test
    fun legacy_v2_remains_readable_with_empty_structured_context() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        val legacy = PersistentRecord(
            encoded.id,
            encoded.schemaId,
            PersistentSchemaVersion(2),
            PersistentPayload(legacyV2Payload(episode)),
            encoded.createdAt
        )
        val decoded = assertIs<EpisodePersistentDecodeResult.Decoded>(
            EpisodicMemoryPersistentCodec.decode(legacy)
        )
        assertEquals(episode.copy(context = EpisodeStructuredContext.EMPTY), decoded.record)
    }

    @Test
    fun structured_context_changes_deterministic_episode_identity() {
        val base = EpisodeCandidate(
            evidence = episode.evidence,
            description = episode.description,
            observedAt = episode.observedAt,
            eventAt = episode.eventAt,
            extraction = extraction,
            context = context
        )
        val materializer = EpisodeMaterializer(
            repository = NoOpRepository(),
            evidenceVerifier = { RawEvidenceVerificationResult.Present(it) }
        )
        val one = materializer.deterministicId(base)
        val two = materializer.deterministicId(
            base.copy(
                context = context.copy(
                    goal = EpisodeContextReference("goal", "different-goal")
                )
            )
        )
        assertNotEquals(one, two)
    }

    @Test
    fun malformed_v3_structured_context_fails_closed() {
        val encoded = EpisodicMemoryPersistentCodec.encode(episode)
        val bytes = encoded.payload.copyBytes()
        val truncated = bytes.copyOf(bytes.size - 1)
        assertIs<EpisodePersistentDecodeResult.Corrupt>(
            EpisodicMemoryPersistentCodec.decode(
                PersistentRecord(
                    encoded.id,
                    encoded.schemaId,
                    encoded.schemaVersion,
                    PersistentPayload(truncated),
                    encoded.createdAt
                )
            )
        )
    }

    @Test
    fun self_link_is_rejected_by_episode_model() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            episode.copy(
                context = context.copy(
                    links = listOf(
                        EpisodeLink(EpisodeLinkType.RELATED, episode.id)
                    )
                )
            )
        }
    }

    private fun legacyV2Payload(record: EpisodeRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
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
            record.evidence.forEach {
                writeString(it.namespace.value)
                writeString(it.id.value)
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

    private class NoOpRepository : EpisodicMemoryRepository {
        override fun store(record: EpisodeRecord): EpisodeStoreResult =
            error("not used")

        override fun lookup(id: EpisodeId): EpisodeLookupResult =
            EpisodeLookupResult.Missing
    }
}
