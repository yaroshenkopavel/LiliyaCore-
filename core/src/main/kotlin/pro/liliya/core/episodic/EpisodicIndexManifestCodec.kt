package pro.liliya.core.episodic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface EpisodeIndexManifestDecodeResult {
    data class Decoded(val manifest: EpisodeIndexManifest) : EpisodeIndexManifestDecodeResult
    data object Corrupt : EpisodeIndexManifestDecodeResult
    data class Incompatible(val reason: String) : EpisodeIndexManifestDecodeResult
}

internal object EpisodicIndexManifestCodec {
    val entityId = PersistentEntityId("episodic-index-manifest")
    val schemaId = PersistentSchemaId("episodic-index-manifest")
    val schemaVersion = PersistentSchemaVersion(1)
    private const val MAGIC = 0x45494D31 // EIM1

    fun encode(manifest: EpisodeIndexManifest): PersistentRecord {
        val payload = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeLong(manifest.source.revision)
                data.writeLong(manifest.source.highWatermark)
                data.writeLong(manifest.source.entryCount)
                data.writeLong(manifest.rebuiltAt.epochSecond)
                data.writeInt(manifest.rebuiltAt.nano)
            }
            output.toByteArray()
        }
        return PersistentRecord(
            id = entityId,
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(payload),
            createdAt = manifest.rebuiltAt
        )
    }

    fun decode(record: PersistentRecord): EpisodeIndexManifestDecodeResult {
        if (record.id != entityId || record.schemaId != schemaId || record.schemaVersion != schemaVersion) {
            return EpisodeIndexManifestDecodeResult.Incompatible("episodic index manifest identity/schema mismatch")
        }
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) return EpisodeIndexManifestDecodeResult.Corrupt
            val source = EpisodeIndexSourceCheckpoint(
                revision = data.readLong(),
                highWatermark = data.readLong(),
                entryCount = data.readLong()
            )
            val rebuiltAt = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
            if (input.available() != 0 || record.createdAt != rebuiltAt) {
                return EpisodeIndexManifestDecodeResult.Corrupt
            }
            EpisodeIndexManifestDecodeResult.Decoded(EpisodeIndexManifest(source, rebuiltAt))
        } catch (_: EOFException) {
            EpisodeIndexManifestDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            EpisodeIndexManifestDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            EpisodeIndexManifestDecodeResult.Corrupt
        }
    }
}
