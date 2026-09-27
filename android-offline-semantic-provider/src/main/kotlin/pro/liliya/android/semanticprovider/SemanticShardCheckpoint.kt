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

private fun compareShardIds(left: SemanticShardId, right: SemanticShardId): Int {
    val domain = left.domain.compareTo(right.domain)
    return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
}

private fun entityIdentity(source: SemanticIndexSourceReference): String = when (source) {
    is SemanticIndexSourceReference.Memory -> "M:" + source.id.value
    is SemanticIndexSourceReference.Knowledge -> "K:" + source.id.value
}

internal data class SemanticShardDescriptor(
    val shardId: SemanticShardId,
    val entryCount: Int,
    val blobSha256: String
) {
    init {
        require(entryCount in 1..SemanticShardLayout.ENTRIES_PER_SHARD.toInt())
        require(SHA256.matches(blobSha256))
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class SemanticShardManifest(
    val version: Int,
    val model: SemanticCheckpointModelBinding,
    val authoritative: SemanticAuthoritativeMetadataCheckpoint,
    val entriesPerShard: Long,
    val shards: List<SemanticShardDescriptor>
) {
    init {
        require(version == CURRENT_VERSION)
        require(entriesPerShard == SemanticShardLayout.ENTRIES_PER_SHARD)
        require(shards.zipWithNext().all { (left, right) ->
            compareShardIds(left.shardId, right.shardId) < 0
        }) { "semantic shard manifest descriptors must be unique and sorted" }
    }

    fun matches(
        expectedModel: SemanticCheckpointModelBinding,
        expectedAuthoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Boolean =
        model == expectedModel &&
            authoritative == expectedAuthoritative &&
            entriesPerShard == SemanticShardLayout.ENTRIES_PER_SHARD

    companion object {
        const val CURRENT_VERSION = 2
    }
}

internal data class SemanticShardCheckpoint(
    val version: Int,
    val shardId: SemanticShardId,
    val seeds: List<SemanticIndexSeed>
) {
    init {
        require(version == CURRENT_VERSION)
        require(seeds.isNotEmpty())
        require(seeds.size <= SemanticShardLayout.ENTRIES_PER_SHARD)
        require(seeds.all { SemanticShardLayout.shardFor(it.source) == shardId })
        val identities = HashSet<String>(seeds.size)
        require(seeds.all { identities.add(entityIdentity(it.source)) }) {
            "semantic shard contains duplicate entity identity"
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal sealed interface SemanticShardManifestDecodeResult {
    data class Decoded(val manifest: SemanticShardManifest) : SemanticShardManifestDecodeResult
    data object Corrupt : SemanticShardManifestDecodeResult
    data class Incompatible(val reason: String) : SemanticShardManifestDecodeResult
}

internal sealed interface SemanticShardDecodeResult {
    data class Decoded(val checkpoint: SemanticShardCheckpoint) : SemanticShardDecodeResult
    data object Corrupt : SemanticShardDecodeResult
    data class Incompatible(val reason: String) : SemanticShardDecodeResult
}

internal object SemanticShardCheckpointCodec {
    private const val MANIFEST_MAGIC = 0x4C534D32 // LSM2
    private const val SHARD_MAGIC = 0x4C535331 // LSS1
    private const val DIGEST_BYTES = 32
    private const val MAX_STRING_BYTES = 65_536

    fun encodeShard(
        checkpoint: SemanticShardCheckpoint
    ): AndroidOfflineSemanticCheckpointBlob {
        val body = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(SHARD_MAGIC)
                out.writeInt(SemanticShardCheckpoint.CURRENT_VERSION)
                writeShardId(out, checkpoint.shardId)
                out.writeInt(checkpoint.seeds.size)
                checkpoint.seeds.forEach { seed ->
                    writeSource(out, seed.source)
                    val values = seed.vector.copyValues()
                    try {
                        values.forEach(out::writeFloat)
                    } finally {
                        values.fill(0f)
                    }
                }
            }
            buffer.toByteArray()
        }
        return wrapWithDigest(body)
    }

    fun decodeShard(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticShardDecodeResult {
        val body = unwrapAndVerify(blob) ?: return SemanticShardDecodeResult.Corrupt
        try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != SHARD_MAGIC) return SemanticShardDecodeResult.Corrupt
            val version = input.readInt()
            if (version != SemanticShardCheckpoint.CURRENT_VERSION) {
                return SemanticShardDecodeResult.Incompatible(
                    "unsupported semantic shard checkpoint version"
                )
            }
            val shardId = readShardId(input)
            val count = input.readInt()
            if (count !in 1..SemanticShardLayout.ENTRIES_PER_SHARD.toInt()) {
                return SemanticShardDecodeResult.Corrupt
            }
            val seeds = ArrayList<SemanticIndexSeed>(count)
            var retain = false
            try {
                repeat(count) {
                    val source = readSource(input) ?: return SemanticShardDecodeResult.Corrupt
                    if (SemanticShardLayout.shardFor(source) != shardId) {
                        return SemanticShardDecodeResult.Corrupt
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
                if (input.read() != -1) return SemanticShardDecodeResult.Corrupt
                val checkpoint = try {
                    SemanticShardCheckpoint(version, shardId, seeds)
                } catch (_: IllegalArgumentException) {
                    return SemanticShardDecodeResult.Corrupt
                }
                retain = true
                return SemanticShardDecodeResult.Decoded(checkpoint)
            } finally {
                if (!retain) seeds.forEach { it.vector.clear() }
            }
        } catch (_: Exception) {
            return SemanticShardDecodeResult.Corrupt
        } finally {
            body.fill(0)
        }
    }

    fun shardDigest(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): String {
        val bytes = blob.copyBytes()
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
        }
    }

    fun encodeManifest(
        manifest: SemanticShardManifest
    ): AndroidOfflineSemanticCheckpointBlob {
        val body = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MANIFEST_MAGIC)
                out.writeInt(SemanticShardManifest.CURRENT_VERSION)
                writeModel(out, manifest.model)
                writeMetadata(out, manifest.authoritative)
                out.writeLong(manifest.entriesPerShard)
                out.writeInt(manifest.shards.size)
                manifest.shards.forEach { descriptor ->
                    writeShardId(out, descriptor.shardId)
                    out.writeInt(descriptor.entryCount)
                    writeString(out, descriptor.blobSha256)
                }
            }
            buffer.toByteArray()
        }
        return wrapWithDigest(body)
    }

    fun decodeManifest(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticShardManifestDecodeResult {
        val body = unwrapAndVerify(blob) ?: return SemanticShardManifestDecodeResult.Corrupt
        try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != MANIFEST_MAGIC) return SemanticShardManifestDecodeResult.Corrupt
            val version = input.readInt()
            if (version != SemanticShardManifest.CURRENT_VERSION) {
                return SemanticShardManifestDecodeResult.Incompatible(
                    "unsupported semantic shard manifest version"
                )
            }
            val model = readModel(input)
            val authoritative = readMetadata(input)
            val entriesPerShard = input.readLong()
            if (entriesPerShard != SemanticShardLayout.ENTRIES_PER_SHARD) {
                return SemanticShardManifestDecodeResult.Incompatible(
                    "semantic shard layout width mismatch"
                )
            }
            val shardCount = input.readInt()
            if (
                shardCount < 0 ||
                shardCount.toLong() * MIN_DESCRIPTOR_BYTES.toLong() > body.size.toLong()
            ) {
                return SemanticShardManifestDecodeResult.Corrupt
            }
            val descriptors = ArrayList<SemanticShardDescriptor>()
            repeat(shardCount) {
                val descriptor = try {
                    SemanticShardDescriptor(
                        shardId = readShardId(input),
                        entryCount = input.readInt(),
                        blobSha256 = readString(input)
                    )
                } catch (_: IllegalArgumentException) {
                    return SemanticShardManifestDecodeResult.Corrupt
                }
                descriptors += descriptor
            }
            if (input.read() != -1) return SemanticShardManifestDecodeResult.Corrupt
            return try {
                SemanticShardManifestDecodeResult.Decoded(
                    SemanticShardManifest(
                        version = version,
                        model = model,
                        authoritative = authoritative,
                        entriesPerShard = entriesPerShard,
                        shards = descriptors
                    )
                )
            } catch (_: IllegalArgumentException) {
                SemanticShardManifestDecodeResult.Corrupt
            }
        } catch (_: Exception) {
            return SemanticShardManifestDecodeResult.Corrupt
        } finally {
            body.fill(0)
        }
    }

    private fun wrapWithDigest(
        body: ByteArray
    ): AndroidOfflineSemanticCheckpointBlob {
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

    private fun unwrapAndVerify(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): ByteArray? {
        val encoded = blob.copyBytes()
        try {
            if (encoded.size <= DIGEST_BYTES) return null
            val bodySize = encoded.size - DIGEST_BYTES
            val body = encoded.copyOfRange(0, bodySize)
            val expected = encoded.copyOfRange(bodySize, encoded.size)
            val actual = MessageDigest.getInstance("SHA-256").digest(body)
            return try {
                if (MessageDigest.isEqual(expected, actual)) body else {
                    body.fill(0)
                    null
                }
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
        } finally {
            encoded.fill(0)
        }
    }

    private fun writeShardId(out: DataOutputStream, shardId: SemanticShardId) {
        out.writeByte(
            when (shardId.domain) {
                SemanticIndexDomain.MEMORY -> 1
                SemanticIndexDomain.KNOWLEDGE -> 2
            }
        )
        out.writeLong(shardId.ordinal)
    }

    private fun readShardId(input: DataInputStream): SemanticShardId {
        val domain = when (input.readUnsignedByte()) {
            1 -> SemanticIndexDomain.MEMORY
            2 -> SemanticIndexDomain.KNOWLEDGE
            else -> throw IllegalArgumentException("invalid semantic shard domain")
        }
        return SemanticShardId(domain, input.readLong())
    }

    private fun writeSource(out: DataOutputStream, source: SemanticIndexSourceReference) {
        when (source) {
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
    }

    private fun readSource(input: DataInputStream): SemanticIndexSourceReference? {
        val domain = input.readUnsignedByte()
        val id = readString(input)
        val generation = input.readLong()
        if (generation <= 0L) return null
        return when (domain) {
            1 -> SemanticIndexSourceReference.Memory(
                MemoryRecordId(id),
                MemoryGeneration(generation)
            )
            2 -> SemanticIndexSourceReference.Knowledge(
                KnowledgeItemId(id),
                KnowledgeGeneration(generation)
            )
            else -> null
        }
    }

    private fun writeModel(out: DataOutputStream, model: SemanticCheckpointModelBinding) {
        writeString(out, model.profileId)
        out.writeLong(model.profileGeneration)
        writeString(out, model.upstreamModelRepository)
        writeString(out, model.upstreamModelRevision)
        writeString(out, model.conversionArtifactRepository)
        writeString(out, model.conversionArtifactRevision)
        writeString(out, model.conversionToolRevision)
        writeString(out, model.modelFileName)
        writeString(out, model.modelFormat)
        out.writeLong(model.modelExpectedSizeBytes)
        writeString(out, model.modelSha256)
        writeString(out, model.tokenizerFileName)
        out.writeLong(model.tokenizerExpectedSizeBytes)
        writeString(out, model.tokenizerSha256)
        writeString(out, model.architecture)
        out.writeInt(model.embeddingDimension)
        writeString(out, model.poolingType)
        writeString(out, model.normalizationRule)
        writeString(out, model.tokenizerProfileId)
        writeString(out, model.runtimeId)
        writeString(out, model.runtimeVersion)
        writeString(out, model.extensionsId)
        writeString(out, model.extensionsVersion)
    }

    private fun readModel(input: DataInputStream): SemanticCheckpointModelBinding =
        SemanticCheckpointModelBinding(
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

    private fun writeMetadata(
        out: DataOutputStream,
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ) {
        out.writeLong(authoritative.memory.revision)
        out.writeLong(authoritative.memory.highWatermark)
        out.writeLong(authoritative.memory.entryCount)
        out.writeLong(authoritative.knowledge.revision)
        out.writeLong(authoritative.knowledge.highWatermark)
        out.writeLong(authoritative.knowledge.entryCount)
    }

    private fun readMetadata(
        input: DataInputStream
    ): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = pro.liliya.core.persistence.PersistentBackendMetadata(
                input.readLong(),
                input.readLong(),
                input.readLong()
            ),
            knowledge = pro.liliya.core.persistence.PersistentBackendMetadata(
                input.readLong(),
                input.readLong(),
                input.readLong()
            )
        )

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size in 1..MAX_STRING_BYTES)
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

    // domain byte + ordinal + entry count + digest string length + 64 SHA-256 chars.
    // Serialized-record lower bound used only to reject impossible allocation counts.
    private const val MIN_DESCRIPTOR_BYTES = 1 + 8 + 4 + 4 + 64
}
