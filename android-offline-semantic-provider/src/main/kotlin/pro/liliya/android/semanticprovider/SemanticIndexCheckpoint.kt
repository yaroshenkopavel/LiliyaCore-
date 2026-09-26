package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

data class AndroidOfflineSemanticAuthoritativeMetadata(
    val memoryRevision: Long,
    val memoryHighWatermark: Long,
    val memoryEntryCount: Long,
    val knowledgeRevision: Long,
    val knowledgeHighWatermark: Long,
    val knowledgeEntryCount: Long
) {
    init {
        require(memoryRevision >= 0L)
        require(memoryHighWatermark >= 0L)
        require(memoryEntryCount >= 0L)
        require(knowledgeRevision >= 0L)
        require(knowledgeHighWatermark >= 0L)
        require(knowledgeEntryCount >= 0L)
    }
}

fun interface AndroidOfflineSemanticAuthoritativeMetadataSource {
    fun snapshot(): AndroidOfflineSemanticAuthoritativeMetadata?
}

class AndroidOfflineSemanticCheckpointBlob(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.isNotEmpty())
        require(value.size <= MAX_BYTES)
    }

    fun copyBytes(): ByteArray = value.copyOf()
    val size: Int get() = value.size

    override fun toString(): String =
        "AndroidOfflineSemanticCheckpointBlob(<redacted:" + value.size + " bytes>)"

    companion object {
        const val MAX_BYTES: Int = 64 * 1024 * 1024
    }
}

sealed interface AndroidOfflineSemanticCheckpointStorageReadResult {
    data object Missing : AndroidOfflineSemanticCheckpointStorageReadResult
    data class Loaded(
        val blob: AndroidOfflineSemanticCheckpointBlob
    ) : AndroidOfflineSemanticCheckpointStorageReadResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticCheckpointStorageReadResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

sealed interface AndroidOfflineSemanticCheckpointStorageWriteResult {
    data object Written : AndroidOfflineSemanticCheckpointStorageWriteResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidOfflineSemanticCheckpointStorageWriteResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

interface AndroidOfflineSemanticCheckpointStorage {
    fun read(): AndroidOfflineSemanticCheckpointStorageReadResult
    fun write(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): AndroidOfflineSemanticCheckpointStorageWriteResult
}

internal data class SemanticCheckpointModelBinding(
    val profileId: String,
    val profileGeneration: Long,
    val upstreamModelRepository: String,
    val upstreamModelRevision: String,
    val conversionArtifactRepository: String,
    val conversionArtifactRevision: String,
    val conversionToolRevision: String,
    val modelFileName: String,
    val modelFormat: String,
    val modelExpectedSizeBytes: Long,
    val modelSha256: String,
    val tokenizerFileName: String,
    val tokenizerExpectedSizeBytes: Long,
    val tokenizerSha256: String,
    val architecture: String,
    val embeddingDimension: Int,
    val poolingType: String,
    val normalizationRule: String,
    val tokenizerProfileId: String,
    val runtimeId: String,
    val runtimeVersion: String,
    val extensionsId: String,
    val extensionsVersion: String
) {
    init {
        require(profileId.isNotBlank())
        require(profileGeneration > 0L)
        require(upstreamModelRepository.isNotBlank())
        require(upstreamModelRevision.isNotBlank())
        require(conversionArtifactRepository.isNotBlank())
        require(conversionArtifactRevision.isNotBlank())
        require(conversionToolRevision.isNotBlank())
        require(modelFileName.isNotBlank())
        require(modelFormat.isNotBlank())
        require(modelExpectedSizeBytes > 0L)
        require(SHA256.matches(modelSha256))
        require(tokenizerFileName.isNotBlank())
        require(tokenizerExpectedSizeBytes > 0L)
        require(SHA256.matches(tokenizerSha256))
        require(architecture.isNotBlank())
        require(embeddingDimension == SemanticEmbeddingVector.DIMENSION)
        require(poolingType.isNotBlank())
        require(normalizationRule.isNotBlank())
        require(tokenizerProfileId.isNotBlank())
        require(runtimeId.isNotBlank())
        require(runtimeVersion.isNotBlank())
        require(extensionsId.isNotBlank())
        require(extensionsVersion.isNotBlank())
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun production(): SemanticCheckpointModelBinding {
            val identity = productionSemanticModelIdentity()
            val provenance = identity.conversionProvenance as? SemanticConversionProvenance.Reproducible
                ?: error("production semantic checkpoint requires reproducible conversion provenance")
            return SemanticCheckpointModelBinding(
                profileId = identity.profileId,
                profileGeneration = identity.profileGeneration.value,
                upstreamModelRepository = identity.upstreamModelRepository,
                upstreamModelRevision = identity.upstreamModelRevision,
                conversionArtifactRepository = provenance.artifactRepository,
                conversionArtifactRevision = provenance.artifactRevision,
                conversionToolRevision = provenance.conversionToolRevision,
                modelFileName = identity.modelFileName,
                modelFormat = identity.modelFormat.name,
                modelExpectedSizeBytes = identity.expectedSizeBytes,
                modelSha256 = identity.expectedSha256,
                tokenizerFileName = identity.tokenizerFileName,
                tokenizerExpectedSizeBytes = identity.tokenizerExpectedSizeBytes,
                tokenizerSha256 = identity.tokenizerExpectedSha256,
                architecture = identity.architecture.name,
                embeddingDimension = identity.embeddingDimension,
                poolingType = identity.poolingType.name,
                normalizationRule = identity.normalizationRule.name,
                tokenizerProfileId = identity.tokenizerProfileId,
                runtimeId = identity.runtimeIdentity.runtimeId,
                runtimeVersion = identity.runtimeIdentity.runtimeVersion,
                extensionsId = identity.runtimeIdentity.extensionsId,
                extensionsVersion = identity.runtimeIdentity.extensionsVersion
            )
        }
    }
}

internal data class SemanticAuthoritativeMetadataCheckpoint(
    val memory: PersistentBackendMetadata,
    val knowledge: PersistentBackendMetadata
)

internal fun AndroidOfflineSemanticAuthoritativeMetadata.toInternal():
    SemanticAuthoritativeMetadataCheckpoint =
    SemanticAuthoritativeMetadataCheckpoint(
        memory = PersistentBackendMetadata(
            revision = memoryRevision,
            highWatermark = memoryHighWatermark,
            entryCount = memoryEntryCount
        ),
        knowledge = PersistentBackendMetadata(
            revision = knowledgeRevision,
            highWatermark = knowledgeHighWatermark,
            entryCount = knowledgeEntryCount
        )
    )

internal fun interface SemanticAuthoritativeMetadataSource {
    fun snapshot(): SemanticAuthoritativeMetadataCheckpoint?
}

internal data class SemanticIndexCheckpoint(
    val version: Int,
    val model: SemanticCheckpointModelBinding,
    val authoritative: SemanticAuthoritativeMetadataCheckpoint,
    val seeds: List<SemanticIndexSeed>
) {
    init {
        require(version == CURRENT_VERSION)
        require(seeds.size <= MAX_ENTRIES)
    }

    fun matches(
        expectedModel: SemanticCheckpointModelBinding,
        expectedAuthoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Boolean =
        model == expectedModel && authoritative == expectedAuthoritative

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ENTRIES = 20_000
    }
}

internal sealed interface SemanticCheckpointReadResult {
    data object Missing : SemanticCheckpointReadResult
    data class Loaded(val checkpoint: SemanticIndexCheckpoint) : SemanticCheckpointReadResult
    data object Corrupt : SemanticCheckpointReadResult
    data class Incompatible(val reason: String) : SemanticCheckpointReadResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticCheckpointReadResult
}

internal sealed interface SemanticCheckpointWriteResult {
    data object Written : SemanticCheckpointWriteResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticCheckpointWriteResult
}

internal interface SemanticCheckpointStore {
    fun read(): SemanticCheckpointReadResult
    fun write(checkpoint: SemanticIndexCheckpoint): SemanticCheckpointWriteResult
}

internal class OpaqueSemanticCheckpointStore(
    private val storage: AndroidOfflineSemanticCheckpointStorage
) : SemanticCheckpointStore {
    override fun read(): SemanticCheckpointReadResult =
        when (val read = storage.read()) {
            AndroidOfflineSemanticCheckpointStorageReadResult.Missing ->
                SemanticCheckpointReadResult.Missing
            is AndroidOfflineSemanticCheckpointStorageReadResult.Loaded ->
                SemanticCheckpointCodec.decode(read.blob)
            is AndroidOfflineSemanticCheckpointStorageReadResult.Failed ->
                SemanticCheckpointReadResult.Failed(read.reason, read.throwable)
        }

    override fun write(
        checkpoint: SemanticIndexCheckpoint
    ): SemanticCheckpointWriteResult {
        val blob = try {
            SemanticCheckpointCodec.encode(checkpoint)
        } catch (failure: Exception) {
            return SemanticCheckpointWriteResult.Failed(
                "semantic checkpoint encoding failed",
                failure
            )
        }
        return when (val written = storage.write(blob)) {
            AndroidOfflineSemanticCheckpointStorageWriteResult.Written ->
                SemanticCheckpointWriteResult.Written
            is AndroidOfflineSemanticCheckpointStorageWriteResult.Failed ->
                SemanticCheckpointWriteResult.Failed(
                    written.reason,
                    written.throwable
                )
        }
    }
}

internal object SemanticCheckpointCodec {
    private const val MAGIC = 0x4C534350
    private const val DIGEST_BYTES = 32
    private const val MAX_STRING_BYTES = 65_536

    fun encode(checkpoint: SemanticIndexCheckpoint): AndroidOfflineSemanticCheckpointBlob {
        val bodyOut = ByteArrayOutputStream()
        DataOutputStream(bodyOut).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(SemanticIndexCheckpoint.CURRENT_VERSION)
            writeString(out, checkpoint.model.profileId)
            out.writeLong(checkpoint.model.profileGeneration)
            writeString(out, checkpoint.model.upstreamModelRepository)
            writeString(out, checkpoint.model.upstreamModelRevision)
            writeString(out, checkpoint.model.conversionArtifactRepository)
            writeString(out, checkpoint.model.conversionArtifactRevision)
            writeString(out, checkpoint.model.conversionToolRevision)
            writeString(out, checkpoint.model.modelFileName)
            writeString(out, checkpoint.model.modelFormat)
            out.writeLong(checkpoint.model.modelExpectedSizeBytes)
            writeString(out, checkpoint.model.modelSha256)
            writeString(out, checkpoint.model.tokenizerFileName)
            out.writeLong(checkpoint.model.tokenizerExpectedSizeBytes)
            writeString(out, checkpoint.model.tokenizerSha256)
            writeString(out, checkpoint.model.architecture)
            out.writeInt(checkpoint.model.embeddingDimension)
            writeString(out, checkpoint.model.poolingType)
            writeString(out, checkpoint.model.normalizationRule)
            writeString(out, checkpoint.model.tokenizerProfileId)
            writeString(out, checkpoint.model.runtimeId)
            writeString(out, checkpoint.model.runtimeVersion)
            writeString(out, checkpoint.model.extensionsId)
            writeString(out, checkpoint.model.extensionsVersion)
            writeMetadata(out, checkpoint.authoritative.memory)
            writeMetadata(out, checkpoint.authoritative.knowledge)
            out.writeInt(checkpoint.seeds.size)
            checkpoint.seeds.forEach { seed ->
                when (val source = seed.source) {
                    is SemanticIndexSourceReference.Memory -> {
                        out.writeByte(1)
                        writeString(out, source.id.value)
                        out.writeLong(source.generation.value)
                    }
                    is SemanticIndexSourceReference.Knowledge -> {
                        out.writeByte(2)
                        writeString(out, source.id.value)
                        out.writeLong(source.generation.value)
                    }
                }
                val values = seed.vector.copyValues()
                try {
                    values.forEach(out::writeFloat)
                } finally {
                    values.fill(0f)
                }
            }
        }

        val body = bodyOut.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        val encoded = ByteArrayOutputStream(body.size + digest.size).use { output ->
            output.write(body)
            output.write(digest)
            output.toByteArray()
        }
        body.fill(0)
        digest.fill(0)
        return try {
            AndroidOfflineSemanticCheckpointBlob(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    fun decode(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticCheckpointReadResult {
        val encoded = blob.copyBytes()
        try {
            if (encoded.size <= DIGEST_BYTES) return SemanticCheckpointReadResult.Corrupt
            val bodySize = encoded.size - DIGEST_BYTES
            val body = encoded.copyOfRange(0, bodySize)
            val expected = encoded.copyOfRange(bodySize, encoded.size)
            val actual = MessageDigest.getInstance("SHA-256").digest(body)
            try {
                if (!MessageDigest.isEqual(expected, actual)) {
                    return SemanticCheckpointReadResult.Corrupt
                }
                val input = DataInputStream(ByteArrayInputStream(body))
                if (input.readInt() != MAGIC) return SemanticCheckpointReadResult.Corrupt
                val version = input.readInt()
                if (version != SemanticIndexCheckpoint.CURRENT_VERSION) {
                    return SemanticCheckpointReadResult.Incompatible(
                        "unsupported semantic checkpoint version"
                    )
                }

                val model = SemanticCheckpointModelBinding(
                    profileId = readString(input),
                    profileGeneration = input.readLong(),
                    upstreamModelRepository = readString(input),
                    upstreamModelRevision = readString(input),
                    conversionArtifactRepository = readString(input),
                    conversionArtifactRevision = readString(input),
                    conversionToolRevision = readString(input),
                    modelFileName = readString(input),
                    modelFormat = readString(input),
                    modelExpectedSizeBytes = input.readLong(),
                    modelSha256 = readString(input),
                    tokenizerFileName = readString(input),
                    tokenizerExpectedSizeBytes = input.readLong(),
                    tokenizerSha256 = readString(input),
                    architecture = readString(input),
                    embeddingDimension = input.readInt(),
                    poolingType = readString(input),
                    normalizationRule = readString(input),
                    tokenizerProfileId = readString(input),
                    runtimeId = readString(input),
                    runtimeVersion = readString(input),
                    extensionsId = readString(input),
                    extensionsVersion = readString(input)
                )
                val authoritative = SemanticAuthoritativeMetadataCheckpoint(
                    memory = readMetadata(input),
                    knowledge = readMetadata(input)
                )
                val count = input.readInt()
                if (count < 0 || count > SemanticIndexCheckpoint.MAX_ENTRIES) {
                    return SemanticCheckpointReadResult.Corrupt
                }
                val seeds = ArrayList<SemanticIndexSeed>(count)
                var retainSeeds = false
                try {
                    repeat(count) {
                        val domain = input.readUnsignedByte()
                        val sourceId = readString(input)
                        val generation = input.readLong()
                        if (generation <= 0L) return SemanticCheckpointReadResult.Corrupt
                        val source = when (domain) {
                            1 -> SemanticIndexSourceReference.Memory(
                                MemoryRecordId(sourceId),
                                MemoryGeneration(generation)
                            )
                            2 -> SemanticIndexSourceReference.Knowledge(
                                KnowledgeItemId(sourceId),
                                KnowledgeGeneration(generation)
                            )
                            else -> return SemanticCheckpointReadResult.Corrupt
                        }
                        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
                        for (index in values.indices) values[index] = input.readFloat()
                        val vector = try {
                            SemanticEmbeddingVector(values)
                        } finally {
                            values.fill(0f)
                        }
                        seeds += SemanticIndexSeed(source, vector)
                    }
                    if (input.read() != -1) return SemanticCheckpointReadResult.Corrupt
                    val checkpoint = SemanticIndexCheckpoint(
                        version = version,
                        model = model,
                        authoritative = authoritative,
                        seeds = seeds
                    )
                    retainSeeds = true
                    return SemanticCheckpointReadResult.Loaded(checkpoint)
                } finally {
                    if (!retainSeeds) {
                        seeds.forEach { seed -> seed.vector.clear() }
                    }
                }
            } finally {
                body.fill(0)
                expected.fill(0)
                actual.fill(0)
            }
        } catch (_: IllegalArgumentException) {
            return SemanticCheckpointReadResult.Corrupt
        } catch (_: Exception) {
            return SemanticCheckpointReadResult.Corrupt
        } finally {
            encoded.fill(0)
        }
    }

    private fun writeMetadata(
        out: DataOutputStream,
        metadata: PersistentBackendMetadata
    ) {
        out.writeLong(metadata.revision)
        out.writeLong(metadata.highWatermark)
        out.writeLong(metadata.entryCount)
    }

    private fun readMetadata(input: DataInputStream): PersistentBackendMetadata =
        PersistentBackendMetadata(
            revision = input.readLong(),
            highWatermark = input.readLong(),
            entryCount = input.readLong()
        )

    private fun writeString(
        out: DataOutputStream,
        value: String
    ) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
        bytes.fill(0)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
