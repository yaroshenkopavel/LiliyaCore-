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

internal sealed interface SemanticClaimAdjacencyManifestDecodeResult {
    data class Decoded(val manifest: SemanticClaimAdjacencyManifest) :
        SemanticClaimAdjacencyManifestDecodeResult
    data object Corrupt : SemanticClaimAdjacencyManifestDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyManifestDecodeResult
}

internal sealed interface SemanticClaimAdjacencyRootDecodeResult {
    data class Decoded(val root: SemanticClaimAdjacencyRoot) :
        SemanticClaimAdjacencyRootDecodeResult
    data object Corrupt : SemanticClaimAdjacencyRootDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyRootDecodeResult
}

internal sealed interface SemanticClaimAdjacencyPageDecodeResult {
    data class Decoded(val page: SemanticClaimAdjacencyPage) :
        SemanticClaimAdjacencyPageDecodeResult
    data object Corrupt : SemanticClaimAdjacencyPageDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyPageDecodeResult
}

internal object SemanticClaimAdjacencyIndexCodec {
    private const val MANIFEST_MAGIC = 0x5343414D // SCAM
    private const val ROOT_MAGIC = 0x53434152 // SCAR
    private const val PAGE_MAGIC = 0x53434150 // SCAP
    val schemaId = PersistentSchemaId("semantic-claim-adjacency-index")
    private val schemaVersion = PersistentSchemaVersion(1)
    val manifestEntityId = PersistentEntityId("semantic-claim-adjacency-manifest-v1")

    fun rootEntityId(reference: SemanticClaimVersionReference): PersistentEntityId =
        PersistentEntityId("semantic-claim-adjacency-root-" + referenceDigest(reference))

    fun pageEntityId(
        reference: SemanticClaimVersionReference,
        ordinal: Long
    ): PersistentEntityId {
        require(ordinal >= 0L)
        return PersistentEntityId(
            "semantic-claim-adjacency-page-" + referenceDigest(reference) + "-" + ordinal
        )
    }

    fun encodeManifest(manifest: SemanticClaimAdjacencyManifest): PersistentRecord =
        record(manifestEntityId, manifest.buildEpoch) { out ->
            out.writeInt(MANIFEST_MAGIC)
            out.writeInt(manifest.version)
            out.writeString(manifest.buildEpoch)
            out.writeCheckpoint(manifest.source)
            out.writeInt(manifest.state.ordinal)
            out.writeInt(manifest.policyVersion)
            out.writeInt(manifest.pageEntries)
            out.writeLong(manifest.indexedRelationCount)
            out.writeLong(manifest.adjacencyEntryCount)
        }

    fun decodeManifest(record: PersistentRecord): SemanticClaimAdjacencyManifestDecodeResult {
        if (record.id != manifestEntityId || record.schemaId != schemaId) {
            return SemanticClaimAdjacencyManifestDecodeResult.Incompatible(
                "semantic adjacency manifest identity mismatch"
            )
        }
        return decode(record) { input, bytes ->
            if (input.readInt() != MANIFEST_MAGIC) return@decode null
            val version = input.readInt()
            if (version != SemanticClaimAdjacencyManifest.CURRENT_VERSION) {
                throw IncompatibleException("semantic adjacency manifest version mismatch")
            }
            SemanticClaimAdjacencyManifest(
                version = version,
                buildEpoch = input.readString(bytes),
                source = input.readCheckpoint(),
                state = SemanticClaimAdjacencyIndexState.entries.getOrNull(input.readInt())
                    ?: return@decode null,
                policyVersion = input.readInt(),
                pageEntries = input.readInt(),
                indexedRelationCount = input.readLong(),
                adjacencyEntryCount = input.readLong()
            )
        }.toManifestResult()
    }

    fun encodeRoot(root: SemanticClaimAdjacencyRoot): PersistentRecord =
        record(rootEntityId(root.reference), root.buildEpoch) { out ->
            out.writeInt(ROOT_MAGIC)
            out.writeInt(root.version)
            out.writeString(root.buildEpoch)
            out.writeReference(root.reference)
            out.writeLong(root.pageCount)
            out.writeLong(root.entryCount)
        }

    fun decodeRoot(record: PersistentRecord): SemanticClaimAdjacencyRootDecodeResult =
        decode(record) { input, bytes ->
            if (record.schemaId != schemaId || input.readInt() != ROOT_MAGIC) return@decode null
            val version = input.readInt()
            if (version != SemanticClaimAdjacencyRoot.CURRENT_VERSION) {
                throw IncompatibleException("semantic adjacency root version mismatch")
            }
            val root = SemanticClaimAdjacencyRoot(
                version = version,
                buildEpoch = input.readString(bytes),
                reference = input.readReference(bytes),
                pageCount = input.readLong(),
                entryCount = input.readLong()
            )
            if (record.id != rootEntityId(root.reference)) return@decode null
            root
        }.toRootResult()

    fun encodePage(page: SemanticClaimAdjacencyPage): PersistentRecord =
        record(pageEntityId(page.reference, page.ordinal), page.buildEpoch) { out ->
            out.writeInt(PAGE_MAGIC)
            out.writeInt(page.version)
            out.writeString(page.buildEpoch)
            out.writeReference(page.reference)
            out.writeLong(page.ordinal)
            out.writeInt(page.entries.size)
            page.entries.forEach { entry ->
                out.writeReference(entry.neighbor)
                out.writeInt(entry.relationType.ordinal)
                out.writeInt(entry.direction.ordinal)
                out.writeLong(entry.recordedAt.epochSecond)
                out.writeInt(entry.recordedAt.nano)
            }
        }

    fun decodePage(record: PersistentRecord): SemanticClaimAdjacencyPageDecodeResult =
        decode(record) { input, bytes ->
            if (record.schemaId != schemaId || input.readInt() != PAGE_MAGIC) return@decode null
            val version = input.readInt()
            if (version != SemanticClaimAdjacencyPage.CURRENT_VERSION) {
                throw IncompatibleException("semantic adjacency page version mismatch")
            }
            val epoch = input.readString(bytes)
            val reference = input.readReference(bytes)
            val ordinal = input.readLong()
            val count = input.readInt()
            if (count !in 1..SemanticClaimAdjacencyPolicy.MAX_PAGE_ENTRIES) return@decode null
            val entries = ArrayList<SemanticClaimAdjacencyEntry>(count)
            repeat(count) {
                val neighbor = input.readReference(bytes)
                val relationType = SemanticClaimRelationType.entries.getOrNull(input.readInt())
                    ?: return@decode null
                val direction = SemanticClaimAdjacencyDirection.entries.getOrNull(input.readInt())
                    ?: return@decode null
                val recordedAt = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())
                entries += SemanticClaimAdjacencyEntry(
                    neighbor = neighbor,
                    relationType = relationType,
                    direction = direction,
                    recordedAt = recordedAt
                )
            }
            val page = SemanticClaimAdjacencyPage(
                version = version,
                buildEpoch = epoch,
                reference = reference,
                ordinal = ordinal,
                entries = entries
            )
            if (record.id != pageEntityId(reference, ordinal)) return@decode null
            page
        }.toPageResult()

    private class IncompatibleException(message: String) : RuntimeException(message)

    private data class Decoded<T>(val value: T?, val incompatible: String?)

    private fun <T> decode(
        record: PersistentRecord,
        block: (DataInputStream, ByteArrayInputStream) -> T?
    ): Decoded<T> {
        if (record.schemaVersion != schemaVersion) {
            return Decoded(null, "semantic adjacency schema version mismatch")
        }
        val payload = record.payload.copyBytes()
        return try {
            val bytes = ByteArrayInputStream(payload)
            val input = DataInputStream(bytes)
            val value = block(input, bytes)
            if (value == null || bytes.available() != 0) Decoded(null, null)
            else Decoded(value, null)
        } catch (error: IncompatibleException) {
            Decoded(null, error.message)
        } catch (_: EOFException) {
            Decoded(null, null)
        } catch (_: IllegalArgumentException) {
            Decoded(null, null)
        } catch (_: RuntimeException) {
            Decoded(null, null)
        } finally {
            payload.fill(0)
        }
    }

    private fun record(
        id: PersistentEntityId,
        epoch: String,
        writer: (DataOutputStream) -> Unit
    ): PersistentRecord {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(writer)
        return PersistentRecord(
            id = id,
            schemaId = schemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(output.toByteArray()),
            createdAt = stableInstant(epoch)
        )
    }

    private fun stableInstant(epoch: String): Instant {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(epoch.toByteArray(StandardCharsets.UTF_8))
        val seconds = ByteBuffer.wrap(digest.copyOfRange(0, 8)).long and Long.MAX_VALUE
        return Instant.ofEpochSecond(seconds % 4_102_444_800L)
    }

    private fun referenceDigest(reference: SemanticClaimVersionReference): String {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, reference.claimId.value)
        put(digest, reference.version.value.toString())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= 1024)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(source: ByteArrayInputStream): String {
        val length = readInt()
        if (length !in 1..1024 || length > source.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeReference(reference: SemanticClaimVersionReference) {
        writeString(reference.claimId.value)
        writeLong(reference.version.value)
    }

    private fun DataInputStream.readReference(source: ByteArrayInputStream) =
        SemanticClaimVersionReference(
            SemanticClaimId(readString(source)),
            SemanticClaimVersion(readLong())
        )

    private fun DataOutputStream.writeCheckpoint(value: SemanticClaimSourceCheckpoint) {
        writeLong(value.revision)
        writeLong(value.highWatermark)
        writeLong(value.entryCount)
    }

    private fun DataInputStream.readCheckpoint() =
        SemanticClaimSourceCheckpoint(readLong(), readLong(), readLong())

    private fun Decoded<SemanticClaimAdjacencyManifest>.toManifestResult() =
        when {
            incompatible != null -> SemanticClaimAdjacencyManifestDecodeResult.Incompatible(incompatible)
            value != null -> SemanticClaimAdjacencyManifestDecodeResult.Decoded(value)
            else -> SemanticClaimAdjacencyManifestDecodeResult.Corrupt
        }

    private fun Decoded<SemanticClaimAdjacencyRoot>.toRootResult() =
        when {
            incompatible != null -> SemanticClaimAdjacencyRootDecodeResult.Incompatible(incompatible)
            value != null -> SemanticClaimAdjacencyRootDecodeResult.Decoded(value)
            else -> SemanticClaimAdjacencyRootDecodeResult.Corrupt
        }

    private fun Decoded<SemanticClaimAdjacencyPage>.toPageResult() =
        when {
            incompatible != null -> SemanticClaimAdjacencyPageDecodeResult.Incompatible(incompatible)
            value != null -> SemanticClaimAdjacencyPageDecodeResult.Decoded(value)
            else -> SemanticClaimAdjacencyPageDecodeResult.Corrupt
        }
}