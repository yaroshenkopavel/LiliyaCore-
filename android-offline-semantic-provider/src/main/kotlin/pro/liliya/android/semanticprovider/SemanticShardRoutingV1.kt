package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal class SemanticShardRoutingEnvelope private constructor(
    private val minimum: FloatArray,
    private val maximum: FloatArray
) {
    init {
        require(minimum.size == SemanticEmbeddingVector.DIMENSION)
        require(maximum.size == SemanticEmbeddingVector.DIMENSION)
        for (index in minimum.indices) {
            require(minimum[index].isFinite() && maximum[index].isFinite())
            require(minimum[index] <= maximum[index])
        }
    }

    fun upperBound(query: SemanticEmbeddingVector): Double {
        val values = query.copyValues()
        try {
            var sum = 0.0
            for (index in values.indices) {
                val q = values[index].toDouble()
                val bound = if (q >= 0.0) maximum[index] else minimum[index]
                sum += q * bound.toDouble()
            }
            // Conservative guard against floating-point accumulation roundoff. The exact scorer
            // uses the same Float components promoted to Double, so this only widens the bound.
            return sum + UPPER_BOUND_EPSILON
        } finally {
            values.fill(0f)
        }
    }

    fun merged(other: SemanticShardRoutingEnvelope): SemanticShardRoutingEnvelope {
        val min = FloatArray(SemanticEmbeddingVector.DIMENSION)
        val max = FloatArray(SemanticEmbeddingVector.DIMENSION)
        for (index in min.indices) {
            min[index] = kotlin.math.min(minimum[index], other.minimum[index])
            max[index] = kotlin.math.max(maximum[index], other.maximum[index])
        }
        return SemanticShardRoutingEnvelope(min, max)
    }

    fun copyMinimum(): FloatArray = minimum.copyOf()
    fun copyMaximum(): FloatArray = maximum.copyOf()

    companion object {
        private const val UPPER_BOUND_EPSILON = 1e-12

        fun fromSeeds(seeds: List<SemanticIndexSeed>): SemanticShardRoutingEnvelope {
            require(seeds.isNotEmpty())
            val min = FloatArray(SemanticEmbeddingVector.DIMENSION) { Float.POSITIVE_INFINITY }
            val max = FloatArray(SemanticEmbeddingVector.DIMENSION) { Float.NEGATIVE_INFINITY }
            for (seed in seeds) {
                val values = seed.vector.copyValues()
                try {
                    for (index in values.indices) {
                        if (values[index] < min[index]) min[index] = values[index]
                        if (values[index] > max[index]) max[index] = values[index]
                    }
                } finally {
                    values.fill(0f)
                }
            }
            return SemanticShardRoutingEnvelope(min, max)
        }

        fun fromBounds(minimum: FloatArray, maximum: FloatArray): SemanticShardRoutingEnvelope =
            SemanticShardRoutingEnvelope(minimum.copyOf(), maximum.copyOf())
    }
}

internal data class SemanticShardRoutingLeafEntry(
    val descriptor: SemanticShardDescriptor,
    val envelope: SemanticShardRoutingEnvelope
)

internal data class SemanticShardRoutingChildEntry(
    val nodeSha256: String,
    val envelope: SemanticShardRoutingEnvelope
) {
    init {
        require(SHA256.matches(nodeSha256))
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal sealed interface SemanticShardRoutingNode {
    val version: Int
    val level: Int
    val envelope: SemanticShardRoutingEnvelope

    data class Leaf(
        override val version: Int = CURRENT_VERSION,
        override val level: Int = 0,
        val entries: List<SemanticShardRoutingLeafEntry>,
        override val envelope: SemanticShardRoutingEnvelope = mergeLeaf(entries)
    ) : SemanticShardRoutingNode {
        init {
            require(version == CURRENT_VERSION)
            require(level == 0)
            require(entries.isNotEmpty())
            require(entries.size <= FANOUT)
            require(entries.zipWithNext().all { (left, right) ->
                compareShardIds(left.descriptor.shardId, right.descriptor.shardId) < 0
            })
        }
    }

    data class Internal(
        override val version: Int = CURRENT_VERSION,
        override val level: Int,
        val children: List<SemanticShardRoutingChildEntry>,
        override val envelope: SemanticShardRoutingEnvelope = mergeChildren(children)
    ) : SemanticShardRoutingNode {
        init {
            require(version == CURRENT_VERSION)
            require(level > 0)
            require(children.isNotEmpty())
            require(children.size <= FANOUT)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val FANOUT = 16

        private fun mergeLeaf(entries: List<SemanticShardRoutingLeafEntry>): SemanticShardRoutingEnvelope {
            require(entries.isNotEmpty())
            var envelope = entries.first().envelope
            for (index in 1 until entries.size) envelope = envelope.merged(entries[index].envelope)
            return envelope
        }

        private fun mergeChildren(entries: List<SemanticShardRoutingChildEntry>): SemanticShardRoutingEnvelope {
            require(entries.isNotEmpty())
            var envelope = entries.first().envelope
            for (index in 1 until entries.size) envelope = envelope.merged(entries[index].envelope)
            return envelope
        }

        private fun compareShardIds(left: SemanticShardId, right: SemanticShardId): Int {
            val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
            return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
        }
    }
}

internal data class SemanticShardRoutingDomainRoot(
    val rootNodeSha256: String?,
    val depth: Int,
    val shardCount: Long
) {
    init {
        require(shardCount >= 0L)
        require((shardCount == 0L) == (rootNodeSha256 == null))
        require((shardCount == 0L) == (depth == 0))
        if (rootNodeSha256 != null) require(SHA256.matches(rootNodeSha256))
        if (shardCount > 0L) require(depth > 0)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        val EMPTY = SemanticShardRoutingDomainRoot(null, 0, 0L)
    }
}

internal data class SemanticShardRoutingRoot(
    val version: Int = CURRENT_VERSION,
    val manifestPublicationId: String,
    val manifestBindingSha256: String,
    val memory: SemanticShardRoutingDomainRoot,
    val knowledge: SemanticShardRoutingDomainRoot,
    val fanout: Int = SemanticShardRoutingNode.FANOUT
) {
    init {
        require(version == CURRENT_VERSION)
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(manifestPublicationId))
        require(SHA256.matches(manifestBindingSha256))
        require(fanout == SemanticShardRoutingNode.FANOUT)
    }

    val shardCount: Long
        get() = Math.addExact(memory.shardCount, knowledge.shardCount)

    fun domain(domain: SemanticIndexDomain): SemanticShardRoutingDomainRoot =
        when (domain) {
            SemanticIndexDomain.MEMORY -> memory
            SemanticIndexDomain.KNOWLEDGE -> knowledge
        }

    fun matches(manifest: SemanticShardManifestRootV3): Boolean =
        manifestPublicationId == manifest.publicationId &&
            manifestBindingSha256 == manifest.manifestBindingSha256 &&
            shardCount == manifest.shardDescriptorCount

    companion object {
        const val CURRENT_VERSION = 1
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal sealed interface SemanticShardRoutingRootDecodeResult {
    data class Decoded(val root: SemanticShardRoutingRoot) : SemanticShardRoutingRootDecodeResult
    data object Corrupt : SemanticShardRoutingRootDecodeResult
    data class Incompatible(val reason: String) : SemanticShardRoutingRootDecodeResult
}

internal sealed interface SemanticShardRoutingNodeDecodeResult {
    data class Decoded(val node: SemanticShardRoutingNode) : SemanticShardRoutingNodeDecodeResult
    data object Corrupt : SemanticShardRoutingNodeDecodeResult
    data class Incompatible(val reason: String) : SemanticShardRoutingNodeDecodeResult
}

internal object SemanticShardRoutingCodec {
    private const val ROOT_MAGIC = 0x4C535231 // LSR1
    private const val NODE_MAGIC = 0x4C534E31 // LSN1
    private const val DIGEST_BYTES = 32
    private const val MAX_STRING_BYTES = 128

    fun encodeRoot(root: SemanticShardRoutingRoot): AndroidOfflineSemanticCheckpointBlob =
        wrap(ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(ROOT_MAGIC)
                out.writeInt(root.version)
                writeString(out, root.manifestPublicationId)
                writeString(out, root.manifestBindingSha256)
                writeDomainRoot(out, root.memory)
                writeDomainRoot(out, root.knowledge)
                out.writeInt(root.fanout)
            }
            buffer.toByteArray()
        })

    fun decodeRoot(blob: AndroidOfflineSemanticCheckpointBlob): SemanticShardRoutingRootDecodeResult {
        val body = unwrap(blob) ?: return SemanticShardRoutingRootDecodeResult.Corrupt
        return try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != ROOT_MAGIC) return SemanticShardRoutingRootDecodeResult.Corrupt
            val version = input.readInt()
            if (version != SemanticShardRoutingRoot.CURRENT_VERSION) {
                return SemanticShardRoutingRootDecodeResult.Incompatible("unsupported semantic routing root version")
            }
            val root = SemanticShardRoutingRoot(
                version = version,
                manifestPublicationId = readString(input),
                manifestBindingSha256 = readString(input),
                memory = readDomainRoot(input),
                knowledge = readDomainRoot(input),
                fanout = input.readInt()
            )
            if (input.read() != -1) return SemanticShardRoutingRootDecodeResult.Corrupt
            SemanticShardRoutingRootDecodeResult.Decoded(root)
        } catch (_: Exception) {
            SemanticShardRoutingRootDecodeResult.Corrupt
        } finally {
            body.fill(0)
        }
    }

    fun encodeNode(node: SemanticShardRoutingNode): AndroidOfflineSemanticCheckpointBlob =
        wrap(ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(NODE_MAGIC)
                out.writeInt(node.version)
                out.writeInt(node.level)
                when (node) {
                    is SemanticShardRoutingNode.Leaf -> {
                        out.writeByte(1)
                        out.writeInt(node.entries.size)
                        node.entries.forEach { entry ->
                            writeShardDescriptor(out, entry.descriptor)
                            writeEnvelope(out, entry.envelope)
                        }
                    }
                    is SemanticShardRoutingNode.Internal -> {
                        out.writeByte(2)
                        out.writeInt(node.children.size)
                        node.children.forEach { child ->
                            writeString(out, child.nodeSha256)
                            writeEnvelope(out, child.envelope)
                        }
                    }
                }
            }
            buffer.toByteArray()
        })

    fun decodeNode(blob: AndroidOfflineSemanticCheckpointBlob): SemanticShardRoutingNodeDecodeResult {
        val body = unwrap(blob) ?: return SemanticShardRoutingNodeDecodeResult.Corrupt
        return try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != NODE_MAGIC) return SemanticShardRoutingNodeDecodeResult.Corrupt
            val version = input.readInt()
            if (version != SemanticShardRoutingNode.CURRENT_VERSION) {
                return SemanticShardRoutingNodeDecodeResult.Incompatible("unsupported semantic routing node version")
            }
            val level = input.readInt()
            val kind = input.readUnsignedByte()
            val count = input.readInt()
            if (count !in 1..SemanticShardRoutingNode.FANOUT) {
                return SemanticShardRoutingNodeDecodeResult.Corrupt
            }
            val node = when (kind) {
                1 -> {
                    if (level != 0) return SemanticShardRoutingNodeDecodeResult.Corrupt
                    val entries = ArrayList<SemanticShardRoutingLeafEntry>(count)
                    repeat(count) {
                        entries += SemanticShardRoutingLeafEntry(
                            descriptor = readShardDescriptor(input),
                            envelope = readEnvelope(input)
                        )
                    }
                    SemanticShardRoutingNode.Leaf(
                        version = version,
                        level = level,
                        entries = entries
                    )
                }
                2 -> {
                    if (level <= 0) return SemanticShardRoutingNodeDecodeResult.Corrupt
                    val children = ArrayList<SemanticShardRoutingChildEntry>(count)
                    repeat(count) {
                        children += SemanticShardRoutingChildEntry(
                            nodeSha256 = readString(input),
                            envelope = readEnvelope(input)
                        )
                    }
                    SemanticShardRoutingNode.Internal(
                        version = version,
                        level = level,
                        children = children
                    )
                }
                else -> return SemanticShardRoutingNodeDecodeResult.Corrupt
            }
            if (input.read() != -1) return SemanticShardRoutingNodeDecodeResult.Corrupt
            SemanticShardRoutingNodeDecodeResult.Decoded(node)
        } catch (_: Exception) {
            SemanticShardRoutingNodeDecodeResult.Corrupt
        } finally {
            body.fill(0)
        }
    }

    fun nodeDigest(blob: AndroidOfflineSemanticCheckpointBlob): String {
        val bytes = blob.copyBytes()
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
        return try {
            digest.joinToString("") { "%02x".format(it) }
        } finally {
            digest.fill(0)
        }
    }

    private fun writeEnvelope(out: DataOutputStream, envelope: SemanticShardRoutingEnvelope) {
        val min = envelope.copyMinimum()
        val max = envelope.copyMaximum()
        try {
            for (value in min) out.writeFloat(value)
            for (value in max) out.writeFloat(value)
        } finally {
            min.fill(0f)
            max.fill(0f)
        }
    }

    private fun readEnvelope(input: DataInputStream): SemanticShardRoutingEnvelope {
        val min = FloatArray(SemanticEmbeddingVector.DIMENSION)
        val max = FloatArray(SemanticEmbeddingVector.DIMENSION)
        for (index in min.indices) min[index] = input.readFloat()
        for (index in max.indices) max[index] = input.readFloat()
        return SemanticShardRoutingEnvelope.fromBounds(min, max)
    }

    private fun writeShardDescriptor(out: DataOutputStream, descriptor: SemanticShardDescriptor) {
        out.writeByte(if (descriptor.shardId.domain == SemanticIndexDomain.MEMORY) 1 else 2)
        out.writeLong(descriptor.shardId.ordinal)
        out.writeInt(descriptor.entryCount)
        writeString(out, descriptor.blobSha256)
    }

    private fun readShardDescriptor(input: DataInputStream): SemanticShardDescriptor {
        val domain = when (input.readUnsignedByte()) {
            1 -> SemanticIndexDomain.MEMORY
            2 -> SemanticIndexDomain.KNOWLEDGE
            else -> throw IllegalArgumentException("invalid semantic routing shard domain")
        }
        return SemanticShardDescriptor(
            shardId = SemanticShardId(domain, input.readLong()),
            entryCount = input.readInt(),
            blobSha256 = readString(input)
        )
    }

    private fun wrap(body: ByteArray): AndroidOfflineSemanticCheckpointBlob {
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        val all = ByteArray(body.size + digest.size)
        body.copyInto(all)
        digest.copyInto(all, body.size)
        body.fill(0)
        digest.fill(0)
        return try {
            AndroidOfflineSemanticCheckpointBlob(all)
        } finally {
            all.fill(0)
        }
    }

    private fun unwrap(blob: AndroidOfflineSemanticCheckpointBlob): ByteArray? {
        val all = blob.copyBytes()
        try {
            if (all.size <= DIGEST_BYTES) return null
            val bodySize = all.size - DIGEST_BYTES
            val body = all.copyOfRange(0, bodySize)
            val expected = all.copyOfRange(bodySize, all.size)
            val actual = MessageDigest.getInstance("SHA-256").digest(body)
            return try {
                if (MessageDigest.isEqual(expected, actual)) {
                    body
                } else {
                    body.fill(0)
                    null
                }
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
        } finally {
            all.fill(0)
        }
    }

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

    private fun writeDomainRoot(
        out: DataOutputStream,
        root: SemanticShardRoutingDomainRoot
    ) {
        writeNullableString(out, root.rootNodeSha256)
        out.writeInt(root.depth)
        out.writeLong(root.shardCount)
    }

    private fun readDomainRoot(input: DataInputStream): SemanticShardRoutingDomainRoot =
        SemanticShardRoutingDomainRoot(
            rootNodeSha256 = readNullableString(input),
            depth = input.readInt(),
            shardCount = input.readLong()
        )

    private fun writeNullableString(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) writeString(out, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null
}
