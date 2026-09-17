package pro.liliya.core.personality

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface PersonalityPersistentDecodeResult {
    data class Decoded(val profile: PersonalityProfile) : PersonalityPersistentDecodeResult
    data object Corrupt : PersonalityPersistentDecodeResult
    data class Incompatible(val reason: String) : PersonalityPersistentDecodeResult
}

internal object PersonalityPersistentRecordCodec {
    val schemaId = PersistentSchemaId("personality-profile")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x50525331
    private const val TARGET_SELF = 1
    private const val MAX_ATTRIBUTE_COUNT = 4096

    fun encode(profile: PersonalityProfile): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeString(profile.id.value)
                when (val target = profile.target) {
                    is PersonalityTarget.Self -> {
                        data.writeInt(TARGET_SELF)
                        data.writeString(target.identityId.value)
                        data.writeLong(target.generation.value)
                    }
                }
                data.writeInt(profile.attributes.size)
                profile.attributes.forEach { attribute ->
                    data.writeString(attribute.key.value)
                    data.writeString(attribute.value.value)
                }
                data.writeString(profile.provenance.sourceId.value)
                val reference = profile.provenance.sourceReference
                data.writeBoolean(reference != null)
                if (reference != null) data.writeString(reference.value)
                data.writeLong(profile.createdAt.epochSecond)
                data.writeInt(profile.createdAt.nano)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = entityId(profile.id),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(bytes),
            createdAt = profile.createdAt
        )
    }

    fun decode(record: PersistentRecord): PersonalityPersistentDecodeResult {
        if (record.schemaId != schemaId) {
            return PersonalityPersistentDecodeResult.Incompatible("persistent personality schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return PersonalityPersistentDecodeResult.Incompatible("persistent personality schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return PersonalityPersistentDecodeResult.Corrupt
            val id = PersonalityProfileId(data.readString(input))
            val target = when (data.readInt()) {
                TARGET_SELF -> PersonalityTarget.Self(
                    identityId = SelfIdentityId(data.readString(input)),
                    generation = SelfGeneration(data.readLong())
                )
                else -> return PersonalityPersistentDecodeResult.Corrupt
            }
            val attributeCount = data.readInt()
            if (attributeCount <= 0 || attributeCount > MAX_ATTRIBUTE_COUNT) {
                return PersonalityPersistentDecodeResult.Corrupt
            }
            val attributes = ArrayList<PersonalityAttribute>(attributeCount)
            repeat(attributeCount) {
                attributes += PersonalityAttribute(
                    PersonalityAttributeKey(data.readString(input)),
                    PersonalityAttributeValue(data.readString(input))
                )
            }
            val sourceId = PersonalitySourceId(data.readString(input))
            val sourceReference = if (data.readBoolean()) PersonalitySourceReference(data.readString(input)) else null
            val createdAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
            if (input.available() != 0) return PersonalityPersistentDecodeResult.Corrupt
            if (record.id != entityId(id) || record.createdAt != createdAt) return PersonalityPersistentDecodeResult.Corrupt
            PersonalityPersistentDecodeResult.Decoded(
                PersonalityProfile(
                    id = id,
                    target = target,
                    attributes = attributes,
                    provenance = PersonalityProvenance(sourceId, sourceReference),
                    createdAt = createdAt
                )
            )
        } catch (_: EOFException) {
            PersonalityPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            PersonalityPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            PersonalityPersistentDecodeResult.Corrupt
        }
    }

    private fun entityId(id: PersonalityProfileId): PersistentEntityId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PersistentEntityId("personality-$digest")
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
}
