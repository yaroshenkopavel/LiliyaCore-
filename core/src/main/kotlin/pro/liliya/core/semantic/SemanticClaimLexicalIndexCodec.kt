package pro.liliya.core.semantic

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

internal sealed interface SemanticClaimLexicalManifestDecodeResult {
    data class Decoded(val manifest: SemanticClaimLexicalManifest) :
        SemanticClaimLexicalManifestDecodeResult
    data object Corrupt : SemanticClaimLexicalManifestDecodeResult
    data class Incompatible(val reason: String) :
        SemanticClaimLexicalManifestDecodeResult
}

internal sealed interface SemanticClaimLexicalRootDecodeResult {
    data class Decoded(val root: SemanticClaimLexicalTokenRoot) :
        SemanticClaimLexicalRootDecodeResult
    data object Corrupt : SemanticClaimLexicalRootDecodeResult
    data class Incompatible(val reason: String) :
        SemanticClaimLexicalRootDecodeResult
}

internal sealed interface SemanticClaimLexicalPageDecodeResult {
    data class Decoded(val page: SemanticClaimLexicalPostingPage) :
        SemanticClaimLexicalPageDecodeResult
    data object Corrupt : SemanticClaimLexicalPageDecodeResult
    data class Incompatible(val reason: String) :
        SemanticClaimLexicalPageDecodeResult
}

internal object SemanticClaimLexicalIndexCodec {
    val manifestSchemaId = PersistentSchemaId("semantic-claim-lexical-manifest")
    val rootSchemaId = PersistentSchemaId("semantic-claim-lexical-token-root")
    val pageSchemaId = PersistentSchemaId("semantic-claim-lexical-posting-page")
    val schemaVersion = PersistentSchemaVersion(1)
    val manifestEntityId = PersistentEntityId("semantic-claim-lexical-manifest")

    private const val MANIFEST_MAGIC = 0x534C584D // SLXM
    private const val ROOT_MAGIC = 0x534C5852 // SLXR
    private const val PAGE_MAGIC = 0x534C5850 // SLXP
    private const val MAX_STRING_BYTES = 512

    fun tokenDigest(token: String): String {
        val bytes = token.toByteArray(StandardCharsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
        return try {
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    append(HEX[(byte.toInt() ushr 4) and 0x0F])
                    append(HEX[byte.toInt() and 0x0F])
                }
            }
        } finally {
            digest.fill(0)
        }
    }

    fun rootEntityId(token: String): PersistentEntityId =
        PersistentEntityId("semantic-claim-lexical-root:" + tokenDigest(token))

    fun pageEntityId(token: String, ordinal: Long): PersistentEntityId {
        require(ordinal >= 0L)
        return PersistentEntityId(
            "semantic-claim-lexical-page:" + tokenDigest(token) + ":" + ordinal
        )
    }

    fun encodeManifest(manifest: SemanticClaimLexicalManifest): PersistentRecord =
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
                        data.writeInt(manifest.policyVersion)
                        data.writeInt(manifest.tokenizerVersion)
                        data.writeInt(manifest.postingPageEntries)
                        data.writeDouble(manifest.bm25K1)
                        data.writeDouble(manifest.bm25B)
                        data.writeLong(manifest.indexedDocumentCount)
                        data.writeLong(manifest.totalIndexedTokenCount)
                        data.writeLong(manifest.truncatedDocumentCount)
                    }
                    output.toByteArray()
                }
            ),
            createdAt = Instant.EPOCH
        )

    fun decodeManifest(record: PersistentRecord): SemanticClaimLexicalManifestDecodeResult {
        if (record.id != manifestEntityId || record.schemaId != manifestSchemaId) {
            return SemanticClaimLexicalManifestDecodeResult.Incompatible(
                "semantic lexical manifest identity/schema mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimLexicalManifestDecodeResult.Incompatible(
                "semantic lexical manifest schema version mismatch"
            )
        }
        return decode(record) { data, input ->
            if (data.readInt() != MANIFEST_MAGIC) return@decode null
            val version = data.readInt()
            if (version != SemanticClaimLexicalManifest.CURRENT_VERSION) {
                throw IncompatibleVersion()
            }
            val epoch = data.readString(input)
            val source = SemanticClaimSourceCheckpoint(
                data.readLong(), data.readLong(), data.readLong()
            )
            val state = SemanticClaimLexicalIndexState.entries
                .getOrNull(data.readInt()) ?: return@decode null
            val policyVersion = data.readInt()
            val tokenizerVersion = data.readInt()
            val manifest = SemanticClaimLexicalManifest(
                version = version,
                buildEpoch = epoch,
                source = source,
                state = state,
                policyVersion = policyVersion,
                tokenizerVersion = tokenizerVersion,
                postingPageEntries = data.readInt(),
                bm25K1 = data.readDouble(),
                bm25B = data.readDouble(),
                indexedDocumentCount = data.readLong(),
                totalIndexedTokenCount = data.readLong(),
                truncatedDocumentCount = data.readLong()
            )
            if (input.available() != 0) return@decode null
            manifest
        }.toManifestResult()
    }

    fun encodeRoot(root: SemanticClaimLexicalTokenRoot): PersistentRecord =
        PersistentRecord(
            id = rootEntityId(root.token),
            schemaId = rootSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(ROOT_MAGIC)
                        data.writeInt(root.version)
                        data.writeString(root.buildEpoch)
                        data.writeString(root.token)
                        data.writeLong(root.pageCount)
                        data.writeLong(root.postingCount)
                    }
                    output.toByteArray()
                }
            ),
            createdAt = Instant.EPOCH
        )

    fun decodeRoot(record: PersistentRecord): SemanticClaimLexicalRootDecodeResult {
        if (record.schemaId != rootSchemaId) {
            return SemanticClaimLexicalRootDecodeResult.Incompatible(
                "semantic lexical token root schema mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimLexicalRootDecodeResult.Incompatible(
                "semantic lexical token root schema version mismatch"
            )
        }
        return decode(record) { data, input ->
            if (data.readInt() != ROOT_MAGIC) return@decode null
            val version = data.readInt()
            if (version != SemanticClaimLexicalTokenRoot.CURRENT_VERSION) {
                throw IncompatibleVersion()
            }
            val root = SemanticClaimLexicalTokenRoot(
                version = version,
                buildEpoch = data.readString(input),
                token = data.readString(input),
                pageCount = data.readLong(),
                postingCount = data.readLong()
            )
            if (record.id != rootEntityId(root.token)) return@decode null
            if (input.available() != 0) return@decode null
            root
        }.toRootResult()
    }

    fun encodePage(page: SemanticClaimLexicalPostingPage): PersistentRecord =
        PersistentRecord(
            id = pageEntityId(page.token, page.ordinal),
            schemaId = pageSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(PAGE_MAGIC)
                        data.writeInt(page.version)
                        data.writeString(page.buildEpoch)
                        data.writeString(page.token)
                        data.writeLong(page.ordinal)
                        data.writeInt(page.entries.size)
                        page.entries.forEach { posting ->
                            data.writeString(posting.reference.claimId.value)
                            data.writeLong(posting.reference.version.value)
                            data.writeInt(posting.termFrequency)
                            data.writeInt(posting.documentLength)
                        }
                    }
                    output.toByteArray()
                }
            ),
            createdAt = Instant.EPOCH
        )

    fun decodePage(record: PersistentRecord): SemanticClaimLexicalPageDecodeResult {
        if (record.schemaId != pageSchemaId) {
            return SemanticClaimLexicalPageDecodeResult.Incompatible(
                "semantic lexical posting page schema mismatch"
            )
        }
        if (record.schemaVersion != schemaVersion) {
            return SemanticClaimLexicalPageDecodeResult.Incompatible(
                "semantic lexical posting page schema version mismatch"
            )
        }
        return decode(record) { data, input ->
            if (data.readInt() != PAGE_MAGIC) return@decode null
            val version = data.readInt()
            if (version != SemanticClaimLexicalPostingPage.CURRENT_VERSION) {
                throw IncompatibleVersion()
            }
            val epoch = data.readString(input)
            val token = data.readString(input)
            val ordinal = data.readLong()
            val count = data.readInt()
            if (count !in 1..SemanticClaimLexicalPolicy.MAX_POSTING_PAGE_ENTRIES) {
                return@decode null
            }
            val entries = ArrayList<SemanticClaimLexicalPosting>(count)
            repeat(count) {
                entries += SemanticClaimLexicalPosting(
                    reference = SemanticClaimVersionReference(
                        claimId = SemanticClaimId(data.readString(input)),
                        version = SemanticClaimVersion(data.readLong())
                    ),
                    termFrequency = data.readInt(),
                    documentLength = data.readInt()
                )
            }
            val page = SemanticClaimLexicalPostingPage(
                version = version,
                buildEpoch = epoch,
                token = token,
                ordinal = ordinal,
                entries = entries
            )
            if (record.id != pageEntityId(token, ordinal)) return@decode null
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

    private fun Decoded<SemanticClaimLexicalManifest>.toManifestResult() =
        when (this) {
            is Decoded.Value -> value?.let {
                SemanticClaimLexicalManifestDecodeResult.Decoded(it)
            } ?: SemanticClaimLexicalManifestDecodeResult.Corrupt
            Decoded.Corrupt -> SemanticClaimLexicalManifestDecodeResult.Corrupt
            Decoded.Incompatible ->
                SemanticClaimLexicalManifestDecodeResult.Incompatible(
                    "unsupported semantic lexical manifest version"
                )
        }

    private fun Decoded<SemanticClaimLexicalTokenRoot>.toRootResult() =
        when (this) {
            is Decoded.Value -> value?.let {
                SemanticClaimLexicalRootDecodeResult.Decoded(it)
            } ?: SemanticClaimLexicalRootDecodeResult.Corrupt
            Decoded.Corrupt -> SemanticClaimLexicalRootDecodeResult.Corrupt
            Decoded.Incompatible ->
                SemanticClaimLexicalRootDecodeResult.Incompatible(
                    "unsupported semantic lexical root version"
                )
        }

    private fun Decoded<SemanticClaimLexicalPostingPage>.toPageResult() =
        when (this) {
            is Decoded.Value -> value?.let {
                SemanticClaimLexicalPageDecodeResult.Decoded(it)
            } ?: SemanticClaimLexicalPageDecodeResult.Corrupt
            Decoded.Corrupt -> SemanticClaimLexicalPageDecodeResult.Corrupt
            Decoded.Incompatible ->
                SemanticClaimLexicalPageDecodeResult.Incompatible(
                    "unsupported semantic lexical page version"
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

    private const val HEX = "0123456789abcdef"
}
