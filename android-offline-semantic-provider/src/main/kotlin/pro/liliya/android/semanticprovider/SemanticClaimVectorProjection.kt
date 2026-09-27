package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import pro.liliya.core.semantic.SemanticClaimId
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVersion
import pro.liliya.core.semantic.SemanticClaimVersionReference

internal enum class SemanticClaimVectorProjectionState {
    INCOMPLETE,
    COMPLETE
}

internal data class SemanticClaimVectorProjectionManifest(
    val version: Int = CURRENT_VERSION,
    val profileId: String,
    val profileGeneration: Long,
    val indexGeneration: Long,
    val source: SemanticClaimSourceCheckpoint,
    val state: SemanticClaimVectorProjectionState,
    val shardEntryLimit: Int,
    val shardCount: Long,
    val indexedEntryCount: Long,
    val routingRootSha256: String? = null
) {
    init {
        require(version == CURRENT_VERSION)
        require(profileId.isNotBlank() && profileId.length <= MAX_PROFILE_ID_LENGTH)
        require(profileGeneration > 0L)
        require(indexGeneration > 0L)
        require(shardEntryLimit in 1..MAX_SHARD_ENTRIES)
        require(shardCount >= 0L)
        require(indexedEntryCount >= 0L)
        require((indexedEntryCount == 0L) == (shardCount == 0L))
        require(routingRootSha256 == null || SHA256.matches(routingRootSha256))
        if (state == SemanticClaimVectorProjectionState.COMPLETE && indexedEntryCount > 0L) {
            require(routingRootSha256 != null)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_PROFILE_ID_LENGTH = 128
        const val MAX_SHARD_ENTRIES = 128
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class SemanticClaimVectorProjectionEntry(
    val reference: SemanticClaimVersionReference,
    val vector: SemanticEmbeddingVector
) {
    override fun toString(): String =
        "SemanticClaimVectorProjectionEntry(reference=$reference, vector=<redacted:384>)"
}

internal data class SemanticClaimVectorProjectionShard(
    val version: Int = CURRENT_VERSION,
    val indexGeneration: Long,
    val ordinal: Long,
    val entries: List<SemanticClaimVectorProjectionEntry>
) {
    init {
        require(version == CURRENT_VERSION)
        require(indexGeneration > 0L)
        require(ordinal >= 0L)
        require(entries.isNotEmpty())
        require(entries.size <= SemanticClaimVectorProjectionManifest.MAX_SHARD_ENTRIES)
        require(entries.map { it.reference }.distinct().size == entries.size)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal sealed interface SemanticClaimVectorProjectionManifestDecodeResult {
    data class Decoded(val manifest: SemanticClaimVectorProjectionManifest) :
        SemanticClaimVectorProjectionManifestDecodeResult
    data object Corrupt : SemanticClaimVectorProjectionManifestDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimVectorProjectionManifestDecodeResult
}

internal sealed interface SemanticClaimVectorProjectionShardDecodeResult {
    data class Decoded(val shard: SemanticClaimVectorProjectionShard) :
        SemanticClaimVectorProjectionShardDecodeResult
    data object Corrupt : SemanticClaimVectorProjectionShardDecodeResult
    data class Incompatible(val reason: String) : SemanticClaimVectorProjectionShardDecodeResult
}

internal object SemanticClaimVectorProjectionCodec {
    private const val MANIFEST_MAGIC = 0x4C435631 // LCV1
    private const val SHARD_MAGIC = 0x4C435331 // LCS1
    private const val MAX_STRING_BYTES = 512

    fun encodeManifest(
        manifest: SemanticClaimVectorProjectionManifest
    ): AndroidOfflineSemanticCheckpointBlob =
        AndroidOfflineSemanticCheckpointBlob(
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MANIFEST_MAGIC)
                    out.writeInt(manifest.version)
                    writeString(out, manifest.profileId)
                    out.writeLong(manifest.profileGeneration)
                    out.writeLong(manifest.indexGeneration)
                    out.writeLong(manifest.source.revision)
                    out.writeLong(manifest.source.highWatermark)
                    out.writeLong(manifest.source.entryCount)
                    out.writeInt(manifest.state.ordinal)
                    out.writeInt(manifest.shardEntryLimit)
                    out.writeLong(manifest.shardCount)
                    out.writeLong(manifest.indexedEntryCount)
                    out.writeBoolean(manifest.routingRootSha256 != null)
                    manifest.routingRootSha256?.let { writeString(out, it) }
                }
                buffer.toByteArray()
            }
        )

    fun decodeManifest(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticClaimVectorProjectionManifestDecodeResult {
        val bytes = blob.copyBytes()
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MANIFEST_MAGIC) {
                    return SemanticClaimVectorProjectionManifestDecodeResult.Incompatible(
                        "semantic claim vector manifest magic mismatch"
                    )
                }
                val version = input.readInt()
                if (version != SemanticClaimVectorProjectionManifest.CURRENT_VERSION) {
                    return SemanticClaimVectorProjectionManifestDecodeResult.Incompatible(
                        "semantic claim vector manifest version mismatch"
                    )
                }
                val manifest = SemanticClaimVectorProjectionManifest(
                    version = version,
                    profileId = readString(input),
                    profileGeneration = input.readLong(),
                    indexGeneration = input.readLong(),
                    source = SemanticClaimSourceCheckpoint(
                        revision = input.readLong(),
                        highWatermark = input.readLong(),
                        entryCount = input.readLong()
                    ),
                    state = enumValue(
                        SemanticClaimVectorProjectionState.entries,
                        input.readInt()
                    ) ?: return SemanticClaimVectorProjectionManifestDecodeResult.Corrupt,
                    shardEntryLimit = input.readInt(),
                    shardCount = input.readLong(),
                    indexedEntryCount = input.readLong(),
                    routingRootSha256 = if (input.readBoolean()) readString(input) else null
                )
                if (input.available() != 0) {
                    SemanticClaimVectorProjectionManifestDecodeResult.Corrupt
                } else {
                    SemanticClaimVectorProjectionManifestDecodeResult.Decoded(manifest)
                }
            }
        } catch (_: Exception) {
            SemanticClaimVectorProjectionManifestDecodeResult.Corrupt
        } finally {
            bytes.fill(0)
        }
    }

    fun encodeShard(
        shard: SemanticClaimVectorProjectionShard
    ): AndroidOfflineSemanticCheckpointBlob =
        AndroidOfflineSemanticCheckpointBlob(
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(SHARD_MAGIC)
                    out.writeInt(shard.version)
                    out.writeLong(shard.indexGeneration)
                    out.writeLong(shard.ordinal)
                    out.writeInt(shard.entries.size)
                    shard.entries.forEach { entry ->
                        writeString(out, entry.reference.claimId.value)
                        out.writeLong(entry.reference.version.value)
                        val values = entry.vector.copyValues()
                        try {
                            values.forEach(out::writeFloat)
                        } finally {
                            values.fill(0f)
                        }
                    }
                }
                buffer.toByteArray()
            }
        )

    fun decodeShard(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticClaimVectorProjectionShardDecodeResult {
        val bytes = blob.copyBytes()
        val vectors = ArrayList<SemanticEmbeddingVector>()
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != SHARD_MAGIC) {
                    return SemanticClaimVectorProjectionShardDecodeResult.Incompatible(
                        "semantic claim vector shard magic mismatch"
                    )
                }
                val version = input.readInt()
                if (version != SemanticClaimVectorProjectionShard.CURRENT_VERSION) {
                    return SemanticClaimVectorProjectionShardDecodeResult.Incompatible(
                        "semantic claim vector shard version mismatch"
                    )
                }
                val generation = input.readLong()
                val ordinal = input.readLong()
                val count = input.readInt()
                if (count !in 1..SemanticClaimVectorProjectionManifest.MAX_SHARD_ENTRIES) {
                    return SemanticClaimVectorProjectionShardDecodeResult.Corrupt
                }
                val entries = ArrayList<SemanticClaimVectorProjectionEntry>(count)
                repeat(count) {
                    val reference = SemanticClaimVersionReference(
                        claimId = SemanticClaimId(readString(input)),
                        version = SemanticClaimVersion(input.readLong())
                    )
                    val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
                    for (index in values.indices) values[index] = input.readFloat()
                    val vector = SemanticEmbeddingVector(values)
                    values.fill(0f)
                    vectors += vector
                    entries += SemanticClaimVectorProjectionEntry(reference, vector)
                }
                if (input.available() != 0) {
                    vectors.forEach { it.clear() }
                    SemanticClaimVectorProjectionShardDecodeResult.Corrupt
                } else {
                    SemanticClaimVectorProjectionShardDecodeResult.Decoded(
                        SemanticClaimVectorProjectionShard(
                            version = version,
                            indexGeneration = generation,
                            ordinal = ordinal,
                            entries = entries
                        )
                    )
                }
            }
        } catch (_: Exception) {
            vectors.forEach { it.clear() }
            SemanticClaimVectorProjectionShardDecodeResult.Corrupt
        } finally {
            bytes.fill(0)
        }
    }

    fun digest(blob: AndroidOfflineSemanticCheckpointBlob): String {
        val bytes = blob.copyBytes()
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_STRING_BYTES)
        try {
            out.writeInt(bytes.size)
            out.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> enumValue(values: List<T>, ordinal: Int): T? =
        values.getOrNull(ordinal)
}

internal sealed interface SemanticClaimVectorProjectionManifestLoadResult {
    data object Missing : SemanticClaimVectorProjectionManifestLoadResult
    data class Loaded(val manifest: SemanticClaimVectorProjectionManifest) :
        SemanticClaimVectorProjectionManifestLoadResult
    data object Corrupt : SemanticClaimVectorProjectionManifestLoadResult
    data class Incompatible(val reason: String) : SemanticClaimVectorProjectionManifestLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimVectorProjectionManifestLoadResult
}

internal sealed interface SemanticClaimVectorProjectionShardLoadResult {
    data object Missing : SemanticClaimVectorProjectionShardLoadResult
    data class Loaded(val shard: SemanticClaimVectorProjectionShard) :
        SemanticClaimVectorProjectionShardLoadResult
    data object Corrupt : SemanticClaimVectorProjectionShardLoadResult
    data class Incompatible(val reason: String) : SemanticClaimVectorProjectionShardLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimVectorProjectionShardLoadResult
}

internal class SemanticClaimVectorProjectionStore(
    private val storage: AndroidOfflineSemanticShardStorage
) {
    fun loadManifest(): SemanticClaimVectorProjectionManifestLoadResult =
        when (val read = storage.read(AndroidOfflineSemanticShardStorageKey.CLAIM_VECTOR_V1_MANIFEST)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticClaimVectorProjectionManifestLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticClaimVectorProjectionManifestLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded ->
                when (val decoded = SemanticClaimVectorProjectionCodec.decodeManifest(read.blob)) {
                    is SemanticClaimVectorProjectionManifestDecodeResult.Decoded ->
                        SemanticClaimVectorProjectionManifestLoadResult.Loaded(decoded.manifest)
                    SemanticClaimVectorProjectionManifestDecodeResult.Corrupt ->
                        SemanticClaimVectorProjectionManifestLoadResult.Corrupt
                    is SemanticClaimVectorProjectionManifestDecodeResult.Incompatible ->
                        SemanticClaimVectorProjectionManifestLoadResult.Incompatible(decoded.reason)
                }
        }

    fun writeManifest(manifest: SemanticClaimVectorProjectionManifest): Boolean =
        storage.write(
            AndroidOfflineSemanticShardStorageKey.CLAIM_VECTOR_V1_MANIFEST,
            SemanticClaimVectorProjectionCodec.encodeManifest(manifest)
        ) is AndroidOfflineSemanticShardStorageWriteResult.Written

    fun writeShard(shard: SemanticClaimVectorProjectionShard): String? {
        val blob = SemanticClaimVectorProjectionCodec.encodeShard(shard)
        val digest = SemanticClaimVectorProjectionCodec.digest(blob)
        val key = AndroidOfflineSemanticShardStorageKey.forClaimVectorShard(
            shard.indexGeneration,
            shard.ordinal,
            digest
        )
        return when (storage.write(key, blob)) {
            AndroidOfflineSemanticShardStorageWriteResult.Written -> digest
            is AndroidOfflineSemanticShardStorageWriteResult.Failed -> null
        }
    }

    fun readShard(
        indexGeneration: Long,
        ordinal: Long,
        digest: String
    ): SemanticClaimVectorProjectionShardLoadResult {
        val key = AndroidOfflineSemanticShardStorageKey.forClaimVectorShard(
            indexGeneration,
            ordinal,
            digest
        )
        val blob = when (val read = storage.read(key)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                return SemanticClaimVectorProjectionShardLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                return SemanticClaimVectorProjectionShardLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> read.blob
        }
        if (SemanticClaimVectorProjectionCodec.digest(blob) != digest) {
            return SemanticClaimVectorProjectionShardLoadResult.Corrupt
        }
        return when (val decoded = SemanticClaimVectorProjectionCodec.decodeShard(blob)) {
            is SemanticClaimVectorProjectionShardDecodeResult.Decoded -> {
                val shard = decoded.shard
                if (shard.indexGeneration != indexGeneration || shard.ordinal != ordinal) {
                    shard.entries.forEach { it.vector.clear() }
                    SemanticClaimVectorProjectionShardLoadResult.Corrupt
                } else {
                    SemanticClaimVectorProjectionShardLoadResult.Loaded(shard)
                }
            }
            SemanticClaimVectorProjectionShardDecodeResult.Corrupt ->
                SemanticClaimVectorProjectionShardLoadResult.Corrupt
            is SemanticClaimVectorProjectionShardDecodeResult.Incompatible ->
                SemanticClaimVectorProjectionShardLoadResult.Incompatible(decoded.reason)
        }
    }
}