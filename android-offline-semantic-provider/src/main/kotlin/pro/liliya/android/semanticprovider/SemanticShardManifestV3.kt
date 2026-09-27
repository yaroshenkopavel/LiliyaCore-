package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import pro.liliya.core.persistence.PersistentBackendMetadata

internal data class SemanticShardManifestRootV3(
    val version: Int = CURRENT_VERSION,
    val model: SemanticCheckpointModelBinding,
    val authoritative: SemanticAuthoritativeMetadataCheckpoint,
    val entriesPerShard: Long,
    val descriptorsPerSegment: Int,
    val publicationId: String,
    val manifestBindingSha256: String,
    val segmentCount: Long,
    val shardDescriptorCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(entriesPerShard == SemanticShardLayout.ENTRIES_PER_SHARD)
        require(descriptorsPerSegment == DESCRIPTORS_PER_SEGMENT)
        require(PUBLICATION_ID.matches(publicationId))
        require(SHA256.matches(manifestBindingSha256))
        require(segmentCount >= 0L)
        require(shardDescriptorCount >= 0L)
        require((segmentCount == 0L) == (shardDescriptorCount == 0L))
        if (segmentCount > 0L) {
            require(shardDescriptorCount > 0L)
            require(shardDescriptorCount <= segmentCount * descriptorsPerSegment.toLong())
        }
    }

    fun matches(
        expectedModel: SemanticCheckpointModelBinding,
        expectedAuthoritative: SemanticAuthoritativeMetadataCheckpoint
    ): Boolean =
        model == expectedModel &&
            authoritative == expectedAuthoritative &&
            entriesPerShard == SemanticShardLayout.ENTRIES_PER_SHARD &&
            descriptorsPerSegment == DESCRIPTORS_PER_SEGMENT

    companion object {
        const val CURRENT_VERSION = 3
        const val DESCRIPTORS_PER_SEGMENT = 256
        internal val PUBLICATION_ID = Regex("[0-9a-f]{32}")
        internal val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class SemanticShardManifestSegment(
    val version: Int = CURRENT_VERSION,
    val publicationId: String,
    val ordinal: Long,
    val shards: List<SemanticShardDescriptor>
) {
    init {
        require(version == CURRENT_VERSION)
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
        require(ordinal >= 0L)
        require(shards.isNotEmpty())
        require(shards.size <= SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT)
        require(shards.zipWithNext().all { (left, right) ->
            compareIds(left.shardId, right.shardId) < 0
        }) { "semantic manifest segment descriptors must be unique and sorted" }
    }

    companion object {
        const val CURRENT_VERSION = 1
        private fun compareIds(left: SemanticShardId, right: SemanticShardId): Int {
            val domain = left.domain.compareTo(right.domain)
            return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
        }
    }
}

internal sealed interface SemanticShardManifestRootV3DecodeResult {
    data class Decoded(val root: SemanticShardManifestRootV3) :
        SemanticShardManifestRootV3DecodeResult
    data object Corrupt : SemanticShardManifestRootV3DecodeResult
    data class Incompatible(val reason: String) : SemanticShardManifestRootV3DecodeResult
}

internal sealed interface SemanticShardManifestSegmentDecodeResult {
    data class Decoded(val segment: SemanticShardManifestSegment) :
        SemanticShardManifestSegmentDecodeResult
    data object Corrupt : SemanticShardManifestSegmentDecodeResult
    data class Incompatible(val reason: String) : SemanticShardManifestSegmentDecodeResult
}

internal object SemanticShardManifestV3Codec {
    private const val ROOT_MAGIC = 0x4C534D33 // LSM3
    private const val SEGMENT_MAGIC = 0x4C534733 // LSG3
    private const val DIGEST_BYTES = 32
    private const val MAX_STRING_BYTES = 65_536

    fun encodeRoot(root: SemanticShardManifestRootV3): AndroidOfflineSemanticCheckpointBlob =
        wrapWithDigest(ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(ROOT_MAGIC)
                out.writeInt(root.version)
                writeModel(out, root.model)
                writeMetadata(out, root.authoritative)
                out.writeLong(root.entriesPerShard)
                out.writeInt(root.descriptorsPerSegment)
                writeString(out, root.publicationId)
                writeString(out, root.manifestBindingSha256)
                out.writeLong(root.segmentCount)
                out.writeLong(root.shardDescriptorCount)
            }
            buffer.toByteArray()
        })

    fun decodeRoot(blob: AndroidOfflineSemanticCheckpointBlob): SemanticShardManifestRootV3DecodeResult {
        val body = unwrapAndVerify(blob) ?: return SemanticShardManifestRootV3DecodeResult.Corrupt
        try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != ROOT_MAGIC) return SemanticShardManifestRootV3DecodeResult.Corrupt
            val version = input.readInt()
            if (version != SemanticShardManifestRootV3.CURRENT_VERSION) {
                return SemanticShardManifestRootV3DecodeResult.Incompatible(
                    "unsupported semantic manifest root version"
                )
            }
            val root = SemanticShardManifestRootV3(
                version = version,
                model = readModel(input),
                authoritative = readMetadata(input),
                entriesPerShard = input.readLong(),
                descriptorsPerSegment = input.readInt(),
                publicationId = readString(input),
                manifestBindingSha256 = readString(input),
                segmentCount = input.readLong(),
                shardDescriptorCount = input.readLong()
            )
            if (input.read() != -1) return SemanticShardManifestRootV3DecodeResult.Corrupt
            return SemanticShardManifestRootV3DecodeResult.Decoded(root)
        } catch (_: Exception) {
            return SemanticShardManifestRootV3DecodeResult.Corrupt
        } finally {
            body.fill(0)
        }
    }

    fun encodeSegment(segment: SemanticShardManifestSegment): AndroidOfflineSemanticCheckpointBlob =
        wrapWithDigest(ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(SEGMENT_MAGIC)
                out.writeInt(segment.version)
                writeString(out, segment.publicationId)
                out.writeLong(segment.ordinal)
                out.writeInt(segment.shards.size)
                segment.shards.forEach { descriptor ->
                    writeShardId(out, descriptor.shardId)
                    out.writeInt(descriptor.entryCount)
                    writeString(out, descriptor.blobSha256)
                }
            }
            buffer.toByteArray()
        })

    fun decodeSegment(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticShardManifestSegmentDecodeResult {
        val body = unwrapAndVerify(blob) ?: return SemanticShardManifestSegmentDecodeResult.Corrupt
        try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != SEGMENT_MAGIC) {
                return SemanticShardManifestSegmentDecodeResult.Corrupt
            }
            val version = input.readInt()
            if (version != SemanticShardManifestSegment.CURRENT_VERSION) {
                return SemanticShardManifestSegmentDecodeResult.Incompatible(
                    "unsupported semantic manifest segment version"
                )
            }
            val publicationId = readString(input)
            val ordinal = input.readLong()
            val count = input.readInt()
            if (count !in 1..SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT) {
                return SemanticShardManifestSegmentDecodeResult.Corrupt
            }
            val shards = ArrayList<SemanticShardDescriptor>(count)
            repeat(count) {
                shards += SemanticShardDescriptor(
                    shardId = readShardId(input),
                    entryCount = input.readInt(),
                    blobSha256 = readString(input)
                )
            }
            if (input.read() != -1) return SemanticShardManifestSegmentDecodeResult.Corrupt
            return SemanticShardManifestSegmentDecodeResult.Decoded(
                SemanticShardManifestSegment(
                    version = version,
                    publicationId = publicationId,
                    ordinal = ordinal,
                    shards = shards
                )
            )
        } catch (_: Exception) {
            return SemanticShardManifestSegmentDecodeResult.Corrupt
        } finally {
            body.fill(0)
        }
    }

    fun digest(blob: AndroidOfflineSemanticCheckpointBlob): String {
        val bytes = blob.copyBytes()
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
        }
    }

    fun manifestBindingSha256(
        publicationId: String,
        model: SemanticCheckpointModelBinding,
        authoritative: SemanticAuthoritativeMetadataCheckpoint
    ): String {
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(ROOT_MAGIC)
                writeString(out, publicationId)
                writeModel(out, model)
                writeMetadata(out, authoritative)
                out.writeLong(SemanticShardLayout.ENTRIES_PER_SHARD)
                out.writeInt(SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT)
            }
            buffer.toByteArray()
        }
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun wrapWithDigest(body: ByteArray): AndroidOfflineSemanticCheckpointBlob {
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

    private fun unwrapAndVerify(blob: AndroidOfflineSemanticCheckpointBlob): ByteArray? {
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
        out.writeByte(if (shardId.domain == SemanticIndexDomain.MEMORY) 1 else 2)
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

    private fun writeNullableSha(out: DataOutputStream, sha: String?) {
        out.writeBoolean(sha != null)
        if (sha != null) writeString(out, sha)
    }

    private fun readNullableSha(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

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

    private fun readMetadata(input: DataInputStream): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(input.readLong(), input.readLong(), input.readLong()),
            knowledge = PersistentBackendMetadata(input.readLong(), input.readLong(), input.readLong())
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
}
