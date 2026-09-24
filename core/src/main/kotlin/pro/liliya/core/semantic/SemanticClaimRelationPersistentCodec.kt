package pro.liliya.core.semantic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface SemanticClaimRelationDecodeResult {
    data class Decoded(val relation: SemanticClaimRelation) : SemanticClaimRelationDecodeResult
    data object Corrupt : SemanticClaimRelationDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimRelationDecodeResult
}

internal object SemanticClaimRelationPersistentCodec {
    val schemaId = PersistentSchemaId("semantic-claim-relation")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x53435231

    fun encode(relation: SemanticClaimRelation): PersistentRecord {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeString(relation.type.name)
            data.writeVersionReference(relation.source)
            data.writeVersionReference(relation.target)
            data.writeLong(relation.recordedAt.epochSecond)
            data.writeInt(relation.recordedAt.nano)
        }
        return PersistentRecord(
            id = PersistentEntityId(relationId(relation)),
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(output.toByteArray()),
            createdAt = relation.recordedAt
        )
    }

    fun decode(record: PersistentRecord): SemanticClaimRelationDecodeResult {
        if (record.schemaId != schemaId) {
            return SemanticClaimRelationDecodeResult.Incompatible("semantic claim relation schema id mismatch")
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimRelationDecodeResult.Incompatible("semantic claim relation schema version mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return SemanticClaimRelationDecodeResult.Corrupt
            val relation = SemanticClaimRelation(
                type = SemanticClaimRelationType.valueOf(data.readString(input)),
                source = data.readVersionReference(input),
                target = data.readVersionReference(input),
                recordedAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
            )
            if (input.available() != 0 ||
                record.id.value != relationId(relation) ||
                record.createdAt != relation.recordedAt
            ) {
                return SemanticClaimRelationDecodeResult.Corrupt
            }
            SemanticClaimRelationDecodeResult.Decoded(relation)
        } catch (_: EOFException) {
            SemanticClaimRelationDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            SemanticClaimRelationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            SemanticClaimRelationDecodeResult.Corrupt
        }
    }

    fun relationId(relation: SemanticClaimRelation): String {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, relation.type.name)
        put(digest, relation.source.claimId.value)
        put(digest, relation.source.version.value.toString())
        put(digest, relation.target.claimId.value)
        put(digest, relation.target.version.value.toString())
        return "claim-relation-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun DataOutputStream.writeVersionReference(reference: SemanticClaimVersionReference) {
        writeString(reference.claimId.value)
        writeLong(reference.version.value)
    }

    private fun DataInputStream.readVersionReference(input: ByteArrayInputStream) =
        SemanticClaimVersionReference(
            claimId = SemanticClaimId(readString(input)),
            version = SemanticClaimVersion(readLong())
        )

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

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
}
