package pro.liliya.core.episodic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface EpisodePersistentDecodeResult {
    data class Decoded(val record: EpisodeRecord) : EpisodePersistentDecodeResult
    data object Corrupt : EpisodePersistentDecodeResult
    data class Incompatible(val reason: String) : EpisodePersistentDecodeResult
}

internal object EpisodicMemoryPersistentCodec {
    val schemaId = PersistentSchemaId("episodic-memory-record")
    val schemaVersion = PersistentSchemaVersion(3)
    private val legacySchemaVersionV1 = PersistentSchemaVersion(1)
    private val legacySchemaVersionV2 = PersistentSchemaVersion(2)
    private const val MAGIC = 0x45505331
    private const val MAX_EVIDENCE_REFERENCES = 256
    private const val MAX_ENTITY_REFERENCES = 256
    private const val MAX_TAGS = 256
    private const val MAX_LINKS = 256

    fun encode(record: EpisodeRecord): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(record.id.value)
                data.writeInt(record.evidence.size)
                record.evidence.forEach { reference ->
                    data.writeString(reference.namespace.value)
                    data.writeString(reference.id.value)
                }
                data.writeString(record.description)
                data.writeInstant(record.observedAt)
                data.writeBoolean(record.eventAt != null)
                record.eventAt?.let { data.writeInstant(it) }
                data.writeInstant(record.derivedAt)
                data.writeBoolean(record.extraction != null)
                record.extraction?.let { extraction ->
                    data.writeString(extraction.extractorId)
                    data.writeString(extraction.extractorVersion)
                    data.writeInstant(extraction.extractedAt)
                }
                data.writeStructuredContext(record.context)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = PersistentEntityId(record.id.value),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = record.derivedAt
        )
    }

    fun decode(record: PersistentRecord): EpisodePersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return EpisodePersistentDecodeResult.Incompatible("episodic schema id mismatch")
        }
        if (
            record.schemaVersion != legacySchemaVersionV1 &&
            record.schemaVersion != legacySchemaVersionV2 &&
            record.schemaVersion != schemaVersion
        ) {
            return EpisodePersistentDecodeResult.Incompatible("episodic schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return EpisodePersistentDecodeResult.Corrupt
            val id = EpisodeId(data.readString(input))
            val evidenceCount = data.readInt()
            if (evidenceCount !in 1..MAX_EVIDENCE_REFERENCES) {
                return EpisodePersistentDecodeResult.Corrupt
            }
            val evidence = List(evidenceCount) {
                RawEvidenceReference(
                    RawEvidenceNamespace(data.readString(input)),
                    RawEvidenceId(data.readString(input))
                )
            }
            val description = data.readString(input)
            val observedAt = data.readInstant()
            val eventAt = if (data.readBoolean()) data.readInstant() else null
            val derivedAt = data.readInstant()
            val extraction = if (record.schemaVersion != legacySchemaVersionV1) {
                if (data.readBoolean()) {
                    EpisodeExtractionProvenance(
                        extractorId = data.readString(input),
                        extractorVersion = data.readString(input),
                        extractedAt = data.readInstant()
                    )
                } else {
                    null
                }
            } else {
                null
            }
            val context = if (record.schemaVersion == schemaVersion) {
                data.readStructuredContext(input)
            } else {
                EpisodeStructuredContext.EMPTY
            }
            if (input.available() != 0) return EpisodePersistentDecodeResult.Corrupt
            if (record.id.value != id.value || record.createdAt != derivedAt) {
                return EpisodePersistentDecodeResult.Corrupt
            }
            EpisodePersistentDecodeResult.Decoded(
                EpisodeRecord(
                    id = id,
                    evidence = evidence,
                    description = description,
                    observedAt = observedAt,
                    eventAt = eventAt,
                    derivedAt = derivedAt,
                    extraction = extraction,
                    context = context
                )
            )
        } catch (_: EOFException) {
            EpisodePersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            EpisodePersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            EpisodePersistentDecodeResult.Corrupt
        }
    }

    private fun DataOutputStream.writeStructuredContext(context: EpisodeStructuredContext) {
        writeInt(context.entities.size)
        context.entities.forEach { entity ->
            writeString(entity.namespace)
            writeString(entity.id)
            writeBoolean(entity.role != null)
            entity.role?.let { role -> writeString(role) }
        }

        writeBoolean(context.task != null)
        context.task?.let {
            writeString(it.namespace)
            writeString(it.id)
        }

        writeBoolean(context.goal != null)
        context.goal?.let {
            writeString(it.namespace)
            writeString(it.id)
        }

        val tags = context.tags.sorted()
        writeInt(tags.size)
        tags.forEach { tag -> writeString(tag) }

        writeBoolean(context.interval != null)
        context.interval?.let {
            writeInstant(it.startInclusive)
            writeBoolean(it.endExclusive != null)
            it.endExclusive?.let { end -> writeInstant(end) }
        }

        writeBoolean(context.significance != null)
        context.significance?.let { writeString(it.name) }

        writeBoolean(context.extractionConfidence != null)
        context.extractionConfidence?.let { writeString(it.name) }

        writeInt(context.links.size)
        context.links.forEach {
            writeString(it.type.name)
            writeString(it.target.value)
        }
    }

    private fun DataInputStream.readStructuredContext(
        input: ByteArrayInputStream
    ): EpisodeStructuredContext {
        val entityCount = readInt()
        if (entityCount !in 0..MAX_ENTITY_REFERENCES) throw EOFException()
        val entities = List(entityCount) {
            EpisodeEntityReference(
                namespace = readString(input),
                id = readString(input),
                role = if (readBoolean()) readString(input) else null
            )
        }

        val task = if (readBoolean()) {
            EpisodeContextReference(readString(input), readString(input))
        } else {
            null
        }

        val goal = if (readBoolean()) {
            EpisodeContextReference(readString(input), readString(input))
        } else {
            null
        }

        val tagCount = readInt()
        if (tagCount !in 0..MAX_TAGS) throw EOFException()
        val tags = LinkedHashSet<String>()
        repeat(tagCount) {
            if (!tags.add(readString(input))) {
                throw IllegalArgumentException("duplicate episode context tag")
            }
        }

        val interval = if (readBoolean()) {
            EpisodeTimeInterval(
                startInclusive = readInstant(),
                endExclusive = if (readBoolean()) readInstant() else null
            )
        } else {
            null
        }

        val significance = if (readBoolean()) {
            EpisodeSignificance.valueOf(readString(input))
        } else {
            null
        }

        val confidence = if (readBoolean()) {
            EpisodeExtractionConfidence.valueOf(readString(input))
        } else {
            null
        }

        val linkCount = readInt()
        if (linkCount !in 0..MAX_LINKS) throw EOFException()
        val links = List(linkCount) {
            EpisodeLink(
                type = EpisodeLinkType.valueOf(readString(input)),
                target = EpisodeId(readString(input))
            )
        }

        return EpisodeStructuredContext(
            entities = entities,
            task = task,
            goal = goal,
            tags = tags,
            interval = interval,
            significance = significance,
            extractionConfidence = confidence,
            links = links
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 0 || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant =
        Instant.ofEpochSecond(readLong(), readInt().toLong())
}
