package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.PriorityQueue

internal data class SemanticClaimVectorShardDescriptor(
    val indexGeneration: Long,
    val ordinal: Long,
    val entryCount: Int,
    val blobSha256: String
) {
    init {
        require(indexGeneration > 0L)
        require(ordinal >= 0L)
        require(entryCount in 1..SemanticClaimVectorProjectionManifest.MAX_SHARD_ENTRIES)
        require(SHA256.matches(blobSha256))
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class SemanticClaimVectorRoutingLeafEntry(
    val descriptor: SemanticClaimVectorShardDescriptor,
    val envelope: SemanticShardRoutingEnvelope
)

internal data class SemanticClaimVectorRoutingChildEntry(
    val nodeSha256: String,
    val envelope: SemanticShardRoutingEnvelope
) {
    init { require(SHA256.matches(nodeSha256)) }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal sealed interface SemanticClaimVectorRoutingNode {
    val version: Int
    val level: Int
    val envelope: SemanticShardRoutingEnvelope

    data class Leaf(
        override val version: Int = CURRENT_VERSION,
        override val level: Int = 0,
        val entries: List<SemanticClaimVectorRoutingLeafEntry>,
        override val envelope: SemanticShardRoutingEnvelope = mergeLeaf(entries)
    ) : SemanticClaimVectorRoutingNode {
        init {
            require(version == CURRENT_VERSION)
            require(level == 0)
            require(entries.isNotEmpty())
            require(entries.size <= FANOUT)
            require(entries.zipWithNext().all { (left, right) ->
                left.descriptor.ordinal < right.descriptor.ordinal
            })
        }
    }

    data class Internal(
        override val version: Int = CURRENT_VERSION,
        override val level: Int,
        val children: List<SemanticClaimVectorRoutingChildEntry>,
        override val envelope: SemanticShardRoutingEnvelope = mergeChildren(children)
    ) : SemanticClaimVectorRoutingNode {
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

        private fun mergeLeaf(
            entries: List<SemanticClaimVectorRoutingLeafEntry>
        ): SemanticShardRoutingEnvelope {
            require(entries.isNotEmpty())
            var result = entries.first().envelope
            for (index in 1 until entries.size) result = result.merged(entries[index].envelope)
            return result
        }

        private fun mergeChildren(
            entries: List<SemanticClaimVectorRoutingChildEntry>
        ): SemanticShardRoutingEnvelope {
            require(entries.isNotEmpty())
            var result = entries.first().envelope
            for (index in 1 until entries.size) result = result.merged(entries[index].envelope)
            return result
        }
    }
}

internal data class SemanticClaimVectorRoutingNodeRef(
    val sha256: String,
    val level: Int,
    val envelope: SemanticShardRoutingEnvelope
)

internal sealed interface SemanticClaimVectorRoutingNodeLoadResult {
    data object Missing : SemanticClaimVectorRoutingNodeLoadResult
    data class Loaded(val node: SemanticClaimVectorRoutingNode) :
        SemanticClaimVectorRoutingNodeLoadResult
    data object Corrupt : SemanticClaimVectorRoutingNodeLoadResult
    data class Incompatible(val reason: String) : SemanticClaimVectorRoutingNodeLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimVectorRoutingNodeLoadResult
}

internal object SemanticClaimVectorRoutingCodec {
    private const val MAGIC = 0x4C43524E // LCRN
    private const val TYPE_LEAF = 1
    private const val TYPE_INTERNAL = 2
    private const val MAX_STRING_BYTES = 128

    fun encode(node: SemanticClaimVectorRoutingNode): AndroidOfflineSemanticCheckpointBlob =
        AndroidOfflineSemanticCheckpointBlob(
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(node.version)
                    out.writeInt(
                        when (node) {
                            is SemanticClaimVectorRoutingNode.Leaf -> TYPE_LEAF
                            is SemanticClaimVectorRoutingNode.Internal -> TYPE_INTERNAL
                        }
                    )
                    out.writeInt(node.level)
                    writeEnvelope(out, node.envelope)
                    when (node) {
                        is SemanticClaimVectorRoutingNode.Leaf -> {
                            out.writeInt(node.entries.size)
                            node.entries.forEach { entry ->
                                val descriptor = entry.descriptor
                                out.writeLong(descriptor.indexGeneration)
                                out.writeLong(descriptor.ordinal)
                                out.writeInt(descriptor.entryCount)
                                writeString(out, descriptor.blobSha256)
                                writeEnvelope(out, entry.envelope)
                            }
                        }
                        is SemanticClaimVectorRoutingNode.Internal -> {
                            out.writeInt(node.children.size)
                            node.children.forEach { child ->
                                writeString(out, child.nodeSha256)
                                writeEnvelope(out, child.envelope)
                            }
                        }
                    }
                }
                buffer.toByteArray()
            }
        )

    fun decode(blob: AndroidOfflineSemanticCheckpointBlob): SemanticClaimVectorRoutingNodeLoadResult {
        val bytes = blob.copyBytes()
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) {
                    return SemanticClaimVectorRoutingNodeLoadResult.Incompatible(
                        "semantic claim vector routing node magic mismatch"
                    )
                }
                val version = input.readInt()
                if (version != SemanticClaimVectorRoutingNode.CURRENT_VERSION) {
                    return SemanticClaimVectorRoutingNodeLoadResult.Incompatible(
                        "semantic claim vector routing node version mismatch"
                    )
                }
                val type = input.readInt()
                val level = input.readInt()
                val storedEnvelope = readEnvelope(input)
                val count = input.readInt()
                if (count !in 1..SemanticClaimVectorRoutingNode.FANOUT) {
                    return SemanticClaimVectorRoutingNodeLoadResult.Corrupt
                }

                val node: SemanticClaimVectorRoutingNode = when (type) {
                    TYPE_LEAF -> {
                        if (level != 0) return SemanticClaimVectorRoutingNodeLoadResult.Corrupt
                        val entries = ArrayList<SemanticClaimVectorRoutingLeafEntry>(count)
                        repeat(count) {
                            entries += SemanticClaimVectorRoutingLeafEntry(
                                descriptor = SemanticClaimVectorShardDescriptor(
                                    indexGeneration = input.readLong(),
                                    ordinal = input.readLong(),
                                    entryCount = input.readInt(),
                                    blobSha256 = readString(input)
                                ),
                                envelope = readEnvelope(input)
                            )
                        }
                        SemanticClaimVectorRoutingNode.Leaf(entries = entries)
                    }
                    TYPE_INTERNAL -> {
                        if (level <= 0) return SemanticClaimVectorRoutingNodeLoadResult.Corrupt
                        val children = ArrayList<SemanticClaimVectorRoutingChildEntry>(count)
                        repeat(count) {
                            children += SemanticClaimVectorRoutingChildEntry(
                                nodeSha256 = readString(input),
                                envelope = readEnvelope(input)
                            )
                        }
                        SemanticClaimVectorRoutingNode.Internal(
                            level = level,
                            children = children
                        )
                    }
                    else -> return SemanticClaimVectorRoutingNodeLoadResult.Corrupt
                }

                if (input.available() != 0 || !sameEnvelope(storedEnvelope, node.envelope)) {
                    SemanticClaimVectorRoutingNodeLoadResult.Corrupt
                } else {
                    SemanticClaimVectorRoutingNodeLoadResult.Loaded(node)
                }
            }
        } catch (_: Exception) {
            SemanticClaimVectorRoutingNodeLoadResult.Corrupt
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

    private fun writeEnvelope(out: DataOutputStream, envelope: SemanticShardRoutingEnvelope) {
        val min = envelope.copyMinimum()
        val max = envelope.copyMaximum()
        try {
            min.forEach(out::writeFloat)
            max.forEach(out::writeFloat)
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

    private fun sameEnvelope(
        left: SemanticShardRoutingEnvelope,
        right: SemanticShardRoutingEnvelope
    ): Boolean {
        val leftMin = left.copyMinimum()
        val leftMax = left.copyMaximum()
        val rightMin = right.copyMinimum()
        val rightMax = right.copyMaximum()
        return try {
            leftMin.contentEquals(rightMin) && leftMax.contentEquals(rightMax)
        } finally {
            leftMin.fill(0f)
            leftMax.fill(0f)
            rightMin.fill(0f)
            rightMax.fill(0f)
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
}

internal class SemanticClaimVectorRoutingStore(
    private val storage: AndroidOfflineSemanticShardStorage
) {
    fun writeNode(node: SemanticClaimVectorRoutingNode): SemanticClaimVectorRoutingNodeRef? {
        val blob = SemanticClaimVectorRoutingCodec.encode(node)
        val sha = SemanticClaimVectorRoutingCodec.digest(blob)
        val key = AndroidOfflineSemanticShardStorageKey.forClaimVectorRoutingNode(sha)
        return when (storage.write(key, blob)) {
            AndroidOfflineSemanticShardStorageWriteResult.Written ->
                SemanticClaimVectorRoutingNodeRef(sha, node.level, node.envelope)
            is AndroidOfflineSemanticShardStorageWriteResult.Failed -> null
        }
    }

    fun readNode(sha256: String): SemanticClaimVectorRoutingNodeLoadResult {
        val key = try {
            AndroidOfflineSemanticShardStorageKey.forClaimVectorRoutingNode(sha256)
        } catch (_: Exception) {
            return SemanticClaimVectorRoutingNodeLoadResult.Corrupt
        }
        val blob = when (val read = storage.read(key)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                return SemanticClaimVectorRoutingNodeLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                return SemanticClaimVectorRoutingNodeLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> read.blob
        }
        if (SemanticClaimVectorRoutingCodec.digest(blob) != sha256) {
            return SemanticClaimVectorRoutingNodeLoadResult.Corrupt
        }
        return SemanticClaimVectorRoutingCodec.decode(blob)
    }
}

internal class SemanticClaimVectorRoutingBuilder(
    private val store: SemanticClaimVectorRoutingStore
) {
    private val leafEntries =
        ArrayList<SemanticClaimVectorRoutingLeafEntry>(SemanticClaimVectorRoutingNode.FANOUT)
    private val levelBuffers = ArrayList<ArrayList<SemanticClaimVectorRoutingNodeRef>>()
    private var previousOrdinal: Long? = null
    private var failed = false

    fun append(
        descriptor: SemanticClaimVectorShardDescriptor,
        envelope: SemanticShardRoutingEnvelope
    ): Boolean {
        if (failed) return false
        val previous = previousOrdinal
        if (previous != null && descriptor.ordinal <= previous) {
            failed = true
            return false
        }
        previousOrdinal = descriptor.ordinal
        leafEntries += SemanticClaimVectorRoutingLeafEntry(descriptor, envelope)
        if (leafEntries.size == SemanticClaimVectorRoutingNode.FANOUT && !flushLeaf()) {
            failed = true
            return false
        }
        return true
    }

    fun finish(): String? {
        if (failed) return null
        if (leafEntries.isNotEmpty() && !flushLeaf()) return null
        if (levelBuffers.all { it.isEmpty() }) return null

        while (true) {
            val nonEmpty = levelBuffers.indices.filter { levelBuffers[it].isNotEmpty() }
            val totalRefs = nonEmpty.sumOf { levelBuffers[it].size }
            if (totalRefs == 1) {
                return levelBuffers[nonEmpty.single()].single().sha256
            }
            val level = nonEmpty.firstOrNull() ?: return null
            val refs = levelBuffers[level].toList()
            levelBuffers[level].clear()
            if (!writeParent(level, refs)) return null
        }
    }

    private fun flushLeaf(): Boolean {
        if (leafEntries.isEmpty()) return true
        val node = try {
            SemanticClaimVectorRoutingNode.Leaf(entries = leafEntries.toList())
        } catch (_: Exception) {
            return false
        }
        val ref = store.writeNode(node) ?: return false
        leafEntries.clear()
        return addNodeRef(ref)
    }

    private fun addNodeRef(ref: SemanticClaimVectorRoutingNodeRef): Boolean {
        while (levelBuffers.size <= ref.level) {
            levelBuffers.add(ArrayList(SemanticClaimVectorRoutingNode.FANOUT))
        }
        val buffer = levelBuffers[ref.level]
        buffer += ref
        if (buffer.size < SemanticClaimVectorRoutingNode.FANOUT) return true
        val refs = buffer.toList()
        buffer.clear()
        return writeParent(ref.level, refs)
    }

    private fun writeParent(
        childLevel: Int,
        refs: List<SemanticClaimVectorRoutingNodeRef>
    ): Boolean {
        if (refs.isEmpty() || refs.size > SemanticClaimVectorRoutingNode.FANOUT) return false
        if (refs.any { it.level != childLevel }) return false
        val node = try {
            SemanticClaimVectorRoutingNode.Internal(
                level = childLevel + 1,
                children = refs.map {
                    SemanticClaimVectorRoutingChildEntry(
                        nodeSha256 = it.sha256,
                        envelope = it.envelope
                    )
                }
            )
        } catch (_: Exception) {
            return false
        }
        return addNodeRef(store.writeNode(node) ?: return false)
    }
}

internal data class SemanticClaimVectorRoutingPolicy(
    val maxCandidates: Int = 128,
    val maxRoutingNodeReads: Int = 256,
    val maxShardReads: Int = 64
) {
    init {
        require(maxCandidates in 1..128)
        require(maxRoutingNodeReads in 1..4096)
        require(maxShardReads in 1..1024)
    }
}

internal sealed interface SemanticClaimVectorRoutingQueryResult {
    data class Routed(
        val candidates: List<OfflineSemanticClaimVectorCandidate>,
        val routingNodeReads: Int,
        val shardReads: Int,
        val truncated: Boolean
    ) : SemanticClaimVectorRoutingQueryResult

    data object Missing : SemanticClaimVectorRoutingQueryResult
    data object Corrupt : SemanticClaimVectorRoutingQueryResult
    data class Incompatible(val reason: String) : SemanticClaimVectorRoutingQueryResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimVectorRoutingQueryResult
}

internal class SemanticClaimVectorRoutingQuery(
    private val projectionStore: SemanticClaimVectorProjectionStore,
    private val routingStore: SemanticClaimVectorRoutingStore
) {
    private sealed interface FrontierItem {
        val upperBound: Double
        val tieKey: String

        data class Node(
            val sha256: String,
            val expectedLevel: Int?,
            override val upperBound: Double
        ) : FrontierItem {
            override val tieKey: String = "N:" + sha256
        }

        data class Shard(
            val descriptor: SemanticClaimVectorShardDescriptor,
            override val upperBound: Double
        ) : FrontierItem {
            override val tieKey: String =
                "S:" + descriptor.ordinal + ":" + descriptor.blobSha256
        }
    }

    private data class Ranked(
        val candidate: OfflineSemanticClaimVectorCandidate
    )

    fun rank(
        manifest: SemanticClaimVectorProjectionManifest,
        query: SemanticEmbeddingVector,
        policy: SemanticClaimVectorRoutingPolicy = SemanticClaimVectorRoutingPolicy()
    ): SemanticClaimVectorRoutingQueryResult {
        if (manifest.state != SemanticClaimVectorProjectionState.COMPLETE) {
            return SemanticClaimVectorRoutingQueryResult.Missing
        }
        if (manifest.indexedEntryCount == 0L) {
            return SemanticClaimVectorRoutingQueryResult.Routed(
                emptyList(), 0, 0, false
            )
        }
        val root = manifest.routingRootSha256
            ?: return SemanticClaimVectorRoutingQueryResult.Corrupt

        val frontier = PriorityQueue<FrontierItem>(
            compareByDescending<FrontierItem> { it.upperBound }
                .thenBy { it.tieKey }
        )
        frontier += FrontierItem.Node(root, null, Double.POSITIVE_INFINITY)

        val bestFirst = Comparator<Ranked> { left, right ->
            compareCandidates(left.candidate, right.candidate)
        }
        val worstFirst = bestFirst.reversed()
        val top = PriorityQueue(policy.maxCandidates, worstFirst)

        var routingReads = 0
        var shardReads = 0
        var truncated = false

        while (frontier.isNotEmpty()) {
            val threshold = if (top.size == policy.maxCandidates) {
                top.peek()?.candidate?.similarity ?: Double.NEGATIVE_INFINITY
            } else {
                Double.NEGATIVE_INFINITY
            }
            val next = frontier.peek() ?: break
            if (top.size == policy.maxCandidates && next.upperBound < threshold) break

            when (val item = frontier.poll()) {
                is FrontierItem.Node -> {
                    if (routingReads >= policy.maxRoutingNodeReads) {
                        truncated = true
                        break
                    }
                    routingReads += 1
                    val node = when (val loaded = routingStore.readNode(item.sha256)) {
                        SemanticClaimVectorRoutingNodeLoadResult.Missing ->
                            return SemanticClaimVectorRoutingQueryResult.Corrupt
                        SemanticClaimVectorRoutingNodeLoadResult.Corrupt ->
                            return SemanticClaimVectorRoutingQueryResult.Corrupt
                        is SemanticClaimVectorRoutingNodeLoadResult.Incompatible ->
                            return SemanticClaimVectorRoutingQueryResult.Incompatible(loaded.reason)
                        is SemanticClaimVectorRoutingNodeLoadResult.Failed ->
                            return SemanticClaimVectorRoutingQueryResult.Failed(
                                loaded.reason, loaded.throwable
                            )
                        is SemanticClaimVectorRoutingNodeLoadResult.Loaded -> loaded.node
                    }
                    if (item.expectedLevel != null && node.level != item.expectedLevel) {
                        return SemanticClaimVectorRoutingQueryResult.Corrupt
                    }
                    when (node) {
                        is SemanticClaimVectorRoutingNode.Leaf -> {
                            node.entries.forEach { entry ->
                                val bound = entry.envelope.upperBound(query)
                                if (!bound.isFinite()) {
                                    return SemanticClaimVectorRoutingQueryResult.Corrupt
                                }
                                frontier += FrontierItem.Shard(entry.descriptor, bound)
                            }
                        }
                        is SemanticClaimVectorRoutingNode.Internal -> {
                            node.children.forEach { child ->
                                val bound = child.envelope.upperBound(query)
                                if (!bound.isFinite()) {
                                    return SemanticClaimVectorRoutingQueryResult.Corrupt
                                }
                                frontier += FrontierItem.Node(
                                    sha256 = child.nodeSha256,
                                    expectedLevel = node.level - 1,
                                    upperBound = bound
                                )
                            }
                        }
                    }
                }
                is FrontierItem.Shard -> {
                    if (shardReads >= policy.maxShardReads) {
                        truncated = true
                        break
                    }
                    shardReads += 1
                    val descriptor = item.descriptor
                    if (descriptor.indexGeneration != manifest.indexGeneration) {
                        return SemanticClaimVectorRoutingQueryResult.Corrupt
                    }
                    val shard = when (
                        val loaded = projectionStore.readShard(
                            descriptor.indexGeneration,
                            descriptor.ordinal,
                            descriptor.blobSha256
                        )
                    ) {
                        SemanticClaimVectorProjectionShardLoadResult.Missing ->
                            return SemanticClaimVectorRoutingQueryResult.Corrupt
                        SemanticClaimVectorProjectionShardLoadResult.Corrupt ->
                            return SemanticClaimVectorRoutingQueryResult.Corrupt
                        is SemanticClaimVectorProjectionShardLoadResult.Incompatible ->
                            return SemanticClaimVectorRoutingQueryResult.Incompatible(loaded.reason)
                        is SemanticClaimVectorProjectionShardLoadResult.Failed ->
                            return SemanticClaimVectorRoutingQueryResult.Failed(
                                loaded.reason, loaded.throwable
                            )
                        is SemanticClaimVectorProjectionShardLoadResult.Loaded -> loaded.shard
                    }
                    try {
                        if (shard.entries.size != descriptor.entryCount) {
                            return SemanticClaimVectorRoutingQueryResult.Corrupt
                        }
                        shard.entries.forEach { entry ->
                            val ranked = Ranked(
                                OfflineSemanticClaimVectorCandidate(
                                    reference = entry.reference,
                                    similarity = query.dot(entry.vector)
                                )
                            )
                            if (top.size < policy.maxCandidates) {
                                top.add(ranked)
                            } else if (bestFirst.compare(ranked, top.peek()) < 0) {
                                top.poll()
                                top.add(ranked)
                            }
                        }
                    } finally {
                        shard.entries.forEach { it.vector.clear() }
                    }
                }
            }
        }

        return SemanticClaimVectorRoutingQueryResult.Routed(
            candidates = top.toList().sortedWith(bestFirst).map { it.candidate },
            routingNodeReads = routingReads,
            shardReads = shardReads,
            truncated = truncated
        )
    }

    private fun compareCandidates(
        left: OfflineSemanticClaimVectorCandidate,
        right: OfflineSemanticClaimVectorCandidate
    ): Int {
        val similarity = right.similarity.compareTo(left.similarity)
        if (similarity != 0) return similarity
        val leftId = left.reference.claimId.value.toByteArray(Charsets.UTF_8)
        val rightId = right.reference.claimId.value.toByteArray(Charsets.UTF_8)
        val id = try {
            compareUtf8(leftId, rightId)
        } finally {
            leftId.fill(0)
            rightId.fill(0)
        }
        if (id != 0) return id
        return left.reference.version.value.compareTo(right.reference.version.value)
    }

    private fun compareUtf8(left: ByteArray, right: ByteArray): Int {
        val size = minOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left[index].toInt() and 0xff
            val b = right[index].toInt() and 0xff
            if (a != b) return a.compareTo(b)
        }
        return left.size.compareTo(right.size)
    }
}