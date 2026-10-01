package pro.liliya.core.semantic

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

internal sealed interface SemanticClaimQueryManifestDecodeResult {
    data class Decoded(val manifest: SemanticClaimQueryIndexManifest) :
        SemanticClaimQueryManifestDecodeResult
    data object Corrupt : SemanticClaimQueryManifestDecodeResult
    data class Incompatible(val reason: String) :
        SemanticClaimQueryManifestDecodeResult
}

internal sealed interface SemanticClaimQueryRootDecodeResult {
    data class Decoded(val root: SemanticClaimQueryGroupRoot) :
        SemanticClaimQueryRootDecodeResult
    data object Corrupt : SemanticClaimQueryRootDecodeResult
    data class Incompatible(val reason: String) :
        SemanticClaimQueryRootDecodeResult
}

internal sealed interface SemanticClaimQueryPageDecodeResult {
    data class Decoded(val page: SemanticClaimQueryPage) :
        SemanticClaimQueryPageDecodeResult
    data object Corrupt : SemanticClaimQueryPageDecodeResult
    data class Incompatible(val reason: String) :
        SemanticClaimQueryPageDecodeResult
}

internal object SemanticClaimQueryIndexCodec {
    val manifestSchemaId = PersistentSchemaId("semantic-claim-query-manifest")
    val rootSchemaId = PersistentSchemaId("semantic-claim-query-group-root")
    val pageSchemaId = PersistentSchemaId("semantic-claim-query-group-page")
    val schemaVersion = PersistentSchemaVersion(1)
    val manifestEntityId = PersistentEntityId("semantic-claim-query-manifest")

    private const val MANIFEST_MAGIC = 0x5343514D // SCQM
    private const val ROOT_MAGIC = 0x53435152 // SCQR
    private const val PAGE_MAGIC = 0x53435150 // SCQP
    private const val MAX_STRING_BYTES = 256

    fun rootEntityId(
        conflictGroupId: SemanticClaimConflictGroupId
    ): PersistentEntityId =
        PersistentEntityId("semantic-claim-query-root:" + conflictGroupId.value)

    fun pageEntityId(
        conflictGroupId: SemanticClaimConflictGroupId,
        ordinal: Long
    ): PersistentEntityId {
        require(ordinal >= 0L)
        return PersistentEntityId(
            "semantic-claim-query-page:" + conflictGroupId.value + ":" + ordinal
        )
    }

    fun encodeManifest(
        manifest: SemanticClaimQueryIndexManifest
    ): PersistentRecord =
        PersistentRecord(
            id = manifestEntityId,
            schemaId = manifestSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(MANIFEST_MAGIC)
                        data.writeInt(manifest.version)
                        data.writeString(manifest.buildEpoch)
                        data.writeLong(manifest.source.revision)
                        data.writeLong(manifest.source.highWatermark)
                        data.writeLong(manifest.source.entryCount)
                        data.writeInt(manifest.state.ordinal)
                    }
                    output.toByteArray()
                }
            ),
            createdAt = Instant.EPOCH
        )

    fun decodeManifest(
        record: PersistentRecord
    ): SemanticClaimQueryManifestDecodeResult {
        if (record.id != manifestEntityId || record.schemaId != manifestSchemaId) {
            return SemanticClaimQueryManifestDecodeResult.Incompatible(
                "semantic claim query manifest identity/schema mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimQueryManifestDecodeResult.Incompatible(
                "semantic claim query manifest schema version mismatch"
            )
        }
        return decode(record) { data, input ->
            if (data.readInt() != MANIFEST_MAGIC) return@decode null
            val version = data.readInt()
            if (version != SemanticClaimQueryIndexManifest.CURRENT_VERSION) {
                throw IncompatibleVersion()
            }
            val epoch = data.readString(input)
            val source = SemanticClaimSourceCheckpoint(
                revision = data.readLong(),
                highWatermark = data.readLong(),
                entryCount = data.readLong()
            )
            val stateOrdinal = data.readInt()
            val state = SemanticClaimQueryIndexState.entries
                .getOrNull(stateOrdinal) ?: return@decode null
            if (input.available() != 0) return@decode null
            SemanticClaimQueryIndexManifest(version, epoch, source, state)
        }.toManifestResult()
    }

    fun encodeRoot(root: SemanticClaimQueryGroupRoot): PersistentRecord =
        PersistentRecord(
            id = rootEntityId(root.conflictGroupId),
            schemaId = rootSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(ROOT_MAGIC)
                        data.writeInt(root.version)
                        data.writeString(root.buildEpoch)
                        data.writeString(root.conflictGroupId.value)
                        data.writeLong(root.pageCount)
                        data.writeLong(root.entryCount)
                    }
                    output.toByteArray()
                }
            ),
            createdAt = Instant.EPOCH
        )

    fun decodeRoot(record: PersistentRecord): SemanticClaimQueryRootDecodeResult {
        if (record.schemaId != rootSchemaId) {
            return SemanticClaimQueryRootDecodeResult.Incompatible(
                "semantic claim query root schema mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimQueryRootDecodeResult.Incompatible(
                "semantic claim query root schema version mismatch"
            )
        }
        return decode(record) { data, input ->
            if (data.readInt() != ROOT_MAGIC) return@decode null
            val version = data.readInt()
            if (version != SemanticClaimQueryGroupRoot.CURRENT_VERSION) {
                throw IncompatibleVersion()
            }
            val root = SemanticClaimQueryGroupRoot(
                version = version,
                buildEpoch = data.readString(input),
                conflictGroupId = SemanticClaimConflictGroupId(data.readString(input)),
                pageCount = data.readLong(),
                entryCount = data.readLong()
            )
            if (record.id != rootEntityId(root.conflictGroupId)) return@decode null
            if (input.available() != 0) return@decode null
            root
        }.toRootResult()
    }

    fun encodePage(page: SemanticClaimQueryPage): PersistentRecord =
        PersistentRecord(
            id = pageEntityId(page.conflictGroupId, page.ordinal),
            schemaId = pageSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(PAGE_MAGIC)
                        data.writeInt(page.version)
                        data.writeString(page.buildEpoch)
                        data.writeString(page.conflictGroupId.value)
                        data.writeLong(page.ordinal)
                        data.writeInt(page.entries.size)
                        page.entries.forEach { reference ->
                            data.writeString(reference.claimId.value)
                            data.writeLong(reference.version.value)
                        }
                    }
                    output.toByteArray()
                }
            ),
            createdAt = Instant.EPOCH
        )

    fun decodePage(record: PersistentRecord): SemanticClaimQueryPageDecodeResult {
        if (record.schemaId != pageSchemaId) {
            return SemanticClaimQueryPageDecodeResult.Incompatible(
                "semantic claim query page schema mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimQueryPageDecodeResult.Incompatible(
                "semantic claim query page schema version mismatch"
            )
        }
        return decode(record) { data, input ->
            if (data.readInt() != PAGE_MAGIC) return@decode null
            val version = data.readInt()
            if (version != SemanticClaimQueryPage.CURRENT_VERSION) {
                throw IncompatibleVersion()
            }
            val epoch = data.readString(input)
            val group = SemanticClaimConflictGroupId(data.readString(input))
            val ordinal = data.readLong()
            val count = data.readInt()
            if (count !in 1..SemanticClaimQueryPage.MAX_ENTRIES) return@decode null
            val entries = ArrayList<SemanticClaimVersionReference>(count)
            repeat(count) {
                entries += SemanticClaimVersionReference(
                    claimId = SemanticClaimId(data.readString(input)),
                    version = SemanticClaimVersion(data.readLong())
                )
            }
            val page = SemanticClaimQueryPage(
                version = version,
                buildEpoch = epoch,
                conflictGroupId = group,
                ordinal = ordinal,
                entries = entries
            )
            if (record.id != pageEntityId(group, ordinal)) return@decode null
            if (input.available() != 0) return@decode null
            page
        }.toPageResult()
    }

    private fun <T> decode(
        record: PersistentRecord,
        block: (DataInputStream, ByteArrayInputStream) -> T?
    ): Decoded<T> {
        val bytes = record.payload.copyBytes()
        return try {
            val input = ByteArrayInputStream(bytes)
            val data = DataInputStream(input)
            Decoded.Value(block(data, input))
        } catch (_: IncompatibleVersion) {
            Decoded.Incompatible
        } catch (_: EOFException) {
            Decoded.Corrupt
        } catch (_: IllegalArgumentException) {
            Decoded.Corrupt
        } catch (_: RuntimeException) {
            Decoded.Corrupt
        } finally {
            bytes.fill(0)
        }
    }

    private sealed interface Decoded<out T> {
        data class Value<T>(val value: T?) : Decoded<T>
        data object Corrupt : Decoded<Nothing>
        data object Incompatible : Decoded<Nothing>
    }

    private class IncompatibleVersion : RuntimeException()

    private fun Decoded<SemanticClaimQueryIndexManifest>.toManifestResult() =
        when (this) {
            is Decoded.Value -> value?.let {
                SemanticClaimQueryManifestDecodeResult.Decoded(it)
            } ?: SemanticClaimQueryManifestDecodeResult.Corrupt
            Decoded.Corrupt -> SemanticClaimQueryManifestDecodeResult.Corrupt
            Decoded.Incompatible ->
                SemanticClaimQueryManifestDecodeResult.Incompatible(
                    "unsupported semantic claim query manifest version"
                )
        }

    private fun Decoded<SemanticClaimQueryGroupRoot>.toRootResult() =
        when (this) {
            is Decoded.Value -> value?.let {
                SemanticClaimQueryRootDecodeResult.Decoded(it)
            } ?: SemanticClaimQueryRootDecodeResult.Corrupt
            Decoded.Corrupt -> SemanticClaimQueryRootDecodeResult.Corrupt
            Decoded.Incompatible ->
                SemanticClaimQueryRootDecodeResult.Incompatible(
                    "unsupported semantic claim query root version"
                )
        }

    private fun Decoded<SemanticClaimQueryPage>.toPageResult() =
        when (this) {
            is Decoded.Value -> value?.let {
                SemanticClaimQueryPageDecodeResult.Decoded(it)
            } ?: SemanticClaimQueryPageDecodeResult.Corrupt
            Decoded.Corrupt -> SemanticClaimQueryPageDecodeResult.Corrupt
            Decoded.Incompatible ->
                SemanticClaimQueryPageDecodeResult.Incompatible(
                    "unsupported semantic claim query page version"
                )
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MAX_STRING_BYTES)
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val size = readInt()
        if (size !in 1..MAX_STRING_BYTES || size > input.available()) {
            throw EOFException()
        }
        val bytes = ByteArray(size)
        readFully(bytes)
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }
}
