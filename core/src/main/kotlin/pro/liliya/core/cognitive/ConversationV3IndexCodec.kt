package pro.liliya.core.cognitive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal data object ConversationV3FormatMarker

internal data class ConversationV3SessionHead(
    val sessionId: CognitiveConversationSessionId,
    val lastSequence: Long,
    val latestChunkId: PersistentEntityId?
)

internal data class ConversationV3LinkedChunk(
    val snapshot: CognitiveConversationContextSnapshot,
    val previousChunkId: PersistentEntityId?
)

internal sealed interface ConversationV3DecodeResult<out T> {
    data class Decoded<T>(val value: T) : ConversationV3DecodeResult<T>
    data object Corrupt : ConversationV3DecodeResult<Nothing>
    data class Incompatible(val reason: String) : ConversationV3DecodeResult<Nothing>
}

/**
 * Plaintext codec used only before/after EncryptedPersistentRecordStore authentication.
 * Session ids and transcript remain inside the encrypted envelope. Durable entity ids are hashes.
 */
internal object ConversationV3IndexCodec {
    val MARKER_ID = PersistentEntityId("conversation-v3-format-marker")
    val MARKER_SCHEMA_ID = PersistentSchemaId("cognitive-conversation-v3-format")
    val HEAD_SCHEMA_ID = PersistentSchemaId("cognitive-conversation-session-head")
    val CHUNK_SCHEMA_ID = PersistentSchemaId("cognitive-conversation-linked-chunk")
    val SCHEMA_VERSION = PersistentSchemaVersion(3)

    private const val MARKER_MAGIC = 0x43483330 // CH30
    private const val HEAD_MAGIC = 0x43483331 // CH31
    private const val CHUNK_MAGIC = 0x43483332 // CH32
    private const val MAX_STRING_BYTES = 65_536

    fun encodeMarker(persistedAt: Instant): PersistentRecord {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MARKER_MAGIC)
                data.writeInt(SCHEMA_VERSION.value)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = MARKER_ID,
            schemaId = MARKER_SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun decodeMarker(record: PersistentRecord): ConversationV3DecodeResult<ConversationV3FormatMarker> {
        if (record.id != MARKER_ID ||
            record.schemaId != MARKER_SCHEMA_ID ||
            record.schemaVersion != SCHEMA_VERSION
        ) {
            return ConversationV3DecodeResult.Incompatible("conversation v3 marker schema mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MARKER_MAGIC ||
                data.readInt() != SCHEMA_VERSION.value ||
                input.available() != 0
            ) {
                ConversationV3DecodeResult.Corrupt
            } else {
                ConversationV3DecodeResult.Decoded(ConversationV3FormatMarker)
            }
        } catch (_: EOFException) {
            ConversationV3DecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationV3DecodeResult.Corrupt
        }
    }

    fun headId(sessionId: CognitiveConversationSessionId): PersistentEntityId =
        PersistentEntityId("conversation-head-" + digest(sessionId.value))

    fun chunkId(
        sessionId: CognitiveConversationSessionId,
        firstSequence: Long
    ): PersistentEntityId {
        require(firstSequence > 0L)
        return PersistentEntityId(
            "conversation-v3-chunk-" + digest(sessionId.value + ":" + firstSequence)
        )
    }

    fun encodeHead(
        head: ConversationV3SessionHead,
        persistedAt: Instant
    ): PersistentRecord {
        require(head.lastSequence >= 0L)
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(HEAD_MAGIC)
                data.writeString(head.sessionId.value)
                data.writeLong(head.lastSequence)
                data.writeNullableId(head.latestChunkId)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = headId(head.sessionId),
            schemaId = HEAD_SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun decodeHead(record: PersistentRecord): ConversationV3DecodeResult<ConversationV3SessionHead> {
        if (record.schemaId != HEAD_SCHEMA_ID || record.schemaVersion != SCHEMA_VERSION) {
            return ConversationV3DecodeResult.Incompatible("conversation v3 head schema mismatch")
        }
        return try {
            val raw = record.payload.copyBytes()
            val input = ByteArrayInputStream(raw)
            val data = DataInputStream(input)
            if (data.readInt() != HEAD_MAGIC) return ConversationV3DecodeResult.Corrupt
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            val lastSequence = data.readLong()
            if (lastSequence < 0L) return ConversationV3DecodeResult.Corrupt
            val latest = data.readNullableId(input)
            if (input.available() != 0) return ConversationV3DecodeResult.Corrupt
            if (record.id != headId(sessionId)) return ConversationV3DecodeResult.Corrupt
            if ((lastSequence == 0L) != (latest == null)) return ConversationV3DecodeResult.Corrupt
            ConversationV3DecodeResult.Decoded(
                ConversationV3SessionHead(sessionId, lastSequence, latest)
            )
        } catch (_: EOFException) {
            ConversationV3DecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationV3DecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationV3DecodeResult.Corrupt
        }
    }

    fun encodeChunk(
        chunk: ConversationV3LinkedChunk,
        persistedAt: Instant
    ): PersistentRecord {
        require(chunk.snapshot.messages.size in 1..2)
        val first = chunk.snapshot.messages.first().sequence.value
        require(first > 0L)
        require((first == 1L) == (chunk.previousChunkId == null)) {
            "conversation v3 predecessor boundary mismatch"
        }
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(CHUNK_MAGIC)
                data.writeString(chunk.snapshot.sessionId.value)
                data.writeNullableId(chunk.previousChunkId)
                data.writeInt(chunk.snapshot.messages.size)
                for (message in chunk.snapshot.messages) {
                    data.writeLong(message.sequence.value)
                    data.writeInt(message.role.ordinal)
                    data.writeString(message.content)
                }
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = chunkId(chunk.snapshot.sessionId, first),
            schemaId = CHUNK_SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = PersistentPayload(bytes),
            createdAt = persistedAt
        )
    }

    fun decodeChunk(
        record: PersistentRecord
    ): ConversationV3DecodeResult<ConversationV3LinkedChunk> {
        if (record.schemaId != CHUNK_SCHEMA_ID || record.schemaVersion != SCHEMA_VERSION) {
            return ConversationV3DecodeResult.Incompatible("conversation v3 chunk schema mismatch")
        }
        return try {
            val raw = record.payload.copyBytes()
            val input = ByteArrayInputStream(raw)
            val data = DataInputStream(input)
            if (data.readInt() != CHUNK_MAGIC) return ConversationV3DecodeResult.Corrupt
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            val previous = data.readNullableId(input)
            val count = data.readInt()
            if (count !in 1..2) return ConversationV3DecodeResult.Corrupt
            val messages = ArrayList<CognitiveConversationContextMessage>(count)
            repeat(count) {
                val sequence = data.readLong()
                val role = CognitiveConversationRole.entries.getOrNull(data.readInt())
                    ?: return ConversationV3DecodeResult.Corrupt
                val content = data.readString(input)
                messages += CognitiveConversationContextMessage(
                    CognitiveConversationSequence(sequence),
                    role,
                    content
                )
            }
            if (input.available() != 0) return ConversationV3DecodeResult.Corrupt
            val first = messages.first().sequence.value
            if (first <= 0L ||
                first > Long.MAX_VALUE - (count - 1L) ||
                messages.last().sequence.value != first + count - 1L
            ) {
                return ConversationV3DecodeResult.Corrupt
            }
            if ((first == 1L) != (previous == null)) {
                return ConversationV3DecodeResult.Corrupt
            }
            if (record.id != chunkId(sessionId, first)) return ConversationV3DecodeResult.Corrupt
            ConversationV3DecodeResult.Decoded(
                ConversationV3LinkedChunk(
                    CognitiveConversationContextSnapshot(sessionId, messages),
                    previous
                )
            )
        } catch (_: EOFException) {
            ConversationV3DecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationV3DecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationV3DecodeResult.Corrupt
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length <= 0 || length > MAX_STRING_BYTES || length > input.available()) {
            throw EOFException()
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableId(id: PersistentEntityId?) {
        writeBoolean(id != null)
        if (id != null) writeString(id.value)
    }

    private fun DataInputStream.readNullableId(input: ByteArrayInputStream): PersistentEntityId? =
        if (!readBoolean()) null else PersistentEntityId(readString(input))
}
