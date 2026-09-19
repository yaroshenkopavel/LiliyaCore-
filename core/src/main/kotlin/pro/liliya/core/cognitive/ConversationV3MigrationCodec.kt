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

internal data object ConversationV3MixedModeMarker

internal data class ConversationV3MigrationLock(
    val sessionId: CognitiveConversationSessionId
)

internal data class ConversationV3TruncatedRootBoundary(
    val sessionId: CognitiveConversationSessionId,
    val firstRetainedSequence: Long,
    val sourceLegacyEntityId: PersistentEntityId
)

internal sealed interface ConversationV3MigrationDecodeResult<out T> {
    data class Decoded<T>(val value: T) : ConversationV3MigrationDecodeResult<T>
    data object Corrupt : ConversationV3MigrationDecodeResult<Nothing>
    data class Incompatible(val reason: String) : ConversationV3MigrationDecodeResult<Nothing>
}

/**
 * Explicit migration-only metadata.
 *
 * Native-v3 format semantics remain unchanged. Mixed mode and truncated legacy roots use separate
 * authenticated encrypted records so old v3 stores stay backwards compatible and lost v1 history
 * is never fabricated.
 */
internal object ConversationV3MigrationCodec {
    val MIXED_MARKER_ID = PersistentEntityId("conversation-v3-mixed-mode-marker")
    val MIXED_MARKER_SCHEMA_ID =
        PersistentSchemaId("cognitive-conversation-v3-mixed-mode")
    val MIGRATION_LOCK_SCHEMA_ID =
        PersistentSchemaId("cognitive-conversation-v3-migration-lock")
    val TRUNCATED_ROOT_SCHEMA_ID =
        PersistentSchemaId("cognitive-conversation-v3-truncated-root")
    val SCHEMA_VERSION = PersistentSchemaVersion(1)

    private const val MIXED_MAGIC = 0x434D5831 // CMX1
    private const val MIGRATION_LOCK_MAGIC = 0x434D4C31 // CML1
    private const val TRUNCATED_ROOT_MAGIC = 0x43545231 // CTR1
    private const val MAX_STRING_BYTES = 65_536

    fun truncatedRootId(
        sessionId: CognitiveConversationSessionId
    ): PersistentEntityId =
        PersistentEntityId(
            "conversation-v3-truncated-root-" + digest(sessionId.value)
        )

    fun encodeMixedMarker(persistedAt: Instant): PersistentRecord {
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MIXED_MAGIC)
                data.writeInt(SCHEMA_VERSION.value)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = MIXED_MARKER_ID,
            schemaId = MIXED_MARKER_SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = PersistentPayload(payload),
            createdAt = persistedAt
        )
    }

    fun decodeMixedMarker(
        record: PersistentRecord
    ): ConversationV3MigrationDecodeResult<ConversationV3MixedModeMarker> {
        if (record.id != MIXED_MARKER_ID ||
            record.schemaId != MIXED_MARKER_SCHEMA_ID ||
            record.schemaVersion != SCHEMA_VERSION
        ) {
            return ConversationV3MigrationDecodeResult.Incompatible(
                "conversation v3 mixed-mode marker schema mismatch"
            )
        }

        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MIXED_MAGIC ||
                data.readInt() != SCHEMA_VERSION.value ||
                input.available() != 0
            ) {
                ConversationV3MigrationDecodeResult.Corrupt
            } else {
                ConversationV3MigrationDecodeResult.Decoded(
                    ConversationV3MixedModeMarker
                )
            }
        } catch (_: EOFException) {
            ConversationV3MigrationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationV3MigrationDecodeResult.Corrupt
        }
    }

    fun migrationLockId(
        sessionId: CognitiveConversationSessionId
    ): PersistentEntityId =
        PersistentEntityId(
            "conversation-v3-migration-lock-" + digest(sessionId.value)
        )

    fun encodeMigrationLock(
        lock: ConversationV3MigrationLock,
        persistedAt: Instant
    ): PersistentRecord {
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MIGRATION_LOCK_MAGIC)
                data.writeString(lock.sessionId.value)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = migrationLockId(lock.sessionId),
            schemaId = MIGRATION_LOCK_SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = PersistentPayload(payload),
            createdAt = persistedAt
        )
    }

    fun decodeMigrationLock(
        record: PersistentRecord
    ): ConversationV3MigrationDecodeResult<ConversationV3MigrationLock> {
        if (record.schemaId != MIGRATION_LOCK_SCHEMA_ID ||
            record.schemaVersion != SCHEMA_VERSION
        ) {
            return ConversationV3MigrationDecodeResult.Incompatible(
                "conversation v3 migration-lock schema mismatch"
            )
        }

        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MIGRATION_LOCK_MAGIC) {
                return ConversationV3MigrationDecodeResult.Corrupt
            }
            val sessionId = CognitiveConversationSessionId(data.readString(input))
            if (input.available() != 0 ||
                record.id != migrationLockId(sessionId)
            ) {
                ConversationV3MigrationDecodeResult.Corrupt
            } else {
                ConversationV3MigrationDecodeResult.Decoded(
                    ConversationV3MigrationLock(sessionId)
                )
            }
        } catch (_: EOFException) {
            ConversationV3MigrationDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationV3MigrationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationV3MigrationDecodeResult.Corrupt
        }
    }

    fun encodeTruncatedRoot(
        boundary: ConversationV3TruncatedRootBoundary,
        persistedAt: Instant
    ): PersistentRecord {
        require(boundary.firstRetainedSequence > 1L) {
            "truncated root must begin after sequence 1"
        }
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(TRUNCATED_ROOT_MAGIC)
                data.writeString(boundary.sessionId.value)
                data.writeLong(boundary.firstRetainedSequence)
                data.writeString(boundary.sourceLegacyEntityId.value)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = truncatedRootId(boundary.sessionId),
            schemaId = TRUNCATED_ROOT_SCHEMA_ID,
            schemaVersion = SCHEMA_VERSION,
            payload = PersistentPayload(payload),
            createdAt = persistedAt
        )
    }

    fun decodeTruncatedRoot(
        record: PersistentRecord
    ): ConversationV3MigrationDecodeResult<ConversationV3TruncatedRootBoundary> {
        if (record.schemaId != TRUNCATED_ROOT_SCHEMA_ID ||
            record.schemaVersion != SCHEMA_VERSION
        ) {
            return ConversationV3MigrationDecodeResult.Incompatible(
                "conversation v3 truncated-root schema mismatch"
            )
        }

        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != TRUNCATED_ROOT_MAGIC) {
                return ConversationV3MigrationDecodeResult.Corrupt
            }

            val sessionId = CognitiveConversationSessionId(data.readString(input))
            val firstRetainedSequence = data.readLong()
            val sourceLegacyEntityId =
                PersistentEntityId(data.readString(input))

            if (firstRetainedSequence <= 1L ||
                input.available() != 0 ||
                record.id != truncatedRootId(sessionId)
            ) {
                ConversationV3MigrationDecodeResult.Corrupt
            } else {
                ConversationV3MigrationDecodeResult.Decoded(
                    ConversationV3TruncatedRootBoundary(
                        sessionId = sessionId,
                        firstRetainedSequence = firstRetainedSequence,
                        sourceLegacyEntityId = sourceLegacyEntityId
                    )
                )
            }
        } catch (_: EOFException) {
            ConversationV3MigrationDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            ConversationV3MigrationDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            ConversationV3MigrationDecodeResult.Corrupt
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
}
