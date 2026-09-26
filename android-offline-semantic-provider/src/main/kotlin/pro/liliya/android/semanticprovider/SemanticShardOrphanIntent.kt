package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

internal enum class SemanticShardOrphanIntentMode {
    ORDERED_REBUILD,
    BOUNDED_MUTATIONS
}

internal enum class SemanticShardOrphanIntentPhase {
    TRACKING,
    RECLAIMED
}

internal data class SemanticShardOrphanIntentRoot(
    val version: Int = CURRENT_VERSION,
    val publicationId: String,
    val mode: SemanticShardOrphanIntentMode,
    val segmentCount: Long,
    val phase: SemanticShardOrphanIntentPhase = SemanticShardOrphanIntentPhase.TRACKING
) {
    init {
        require(version == CURRENT_VERSION)
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
        require(segmentCount > 0L)
        if (mode == SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS) {
            require(segmentCount <= MAX_BOUNDED_SEGMENTS)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DESCRIPTORS_PER_SEGMENT = 256
        const val MAX_BOUNDED_DESCRIPTORS = 1_024
        const val MAX_BOUNDED_SEGMENTS =
            MAX_BOUNDED_DESCRIPTORS / DESCRIPTORS_PER_SEGMENT
    }
}

internal data class SemanticShardOrphanIntentSegment(
    val version: Int = CURRENT_VERSION,
    val publicationId: String,
    val ordinal: Long,
    val descriptors: List<SemanticShardDescriptor>
) {
    init {
        require(version == CURRENT_VERSION)
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
        require(ordinal >= 0L)
        require(descriptors.isNotEmpty())
        require(descriptors.size <= SemanticShardOrphanIntentRoot.DESCRIPTORS_PER_SEGMENT)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal sealed interface SemanticShardOrphanIntentRootLoadResult {
    data object Missing : SemanticShardOrphanIntentRootLoadResult
    data class Loaded(val root: SemanticShardOrphanIntentRoot) :
        SemanticShardOrphanIntentRootLoadResult
    data object Corrupt : SemanticShardOrphanIntentRootLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardOrphanIntentRootLoadResult
}

internal sealed interface SemanticShardOrphanIntentSegmentLoadResult {
    data object Missing : SemanticShardOrphanIntentSegmentLoadResult
    data class Loaded(val segment: SemanticShardOrphanIntentSegment) :
        SemanticShardOrphanIntentSegmentLoadResult
    data object Corrupt : SemanticShardOrphanIntentSegmentLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardOrphanIntentSegmentLoadResult
}

internal object SemanticShardOrphanIntentCodec {
    private const val ROOT_MAGIC = 0x4C534F52 // LSOR
    private const val SEGMENT_MAGIC = 0x4C534F53 // LSOS
    private const val DIGEST_BYTES = 32

    fun encodeRoot(root: SemanticShardOrphanIntentRoot): AndroidOfflineSemanticCheckpointBlob =
        wrap(ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(ROOT_MAGIC)
                out.writeInt(root.version)
                writeString(out, root.publicationId)
                out.writeInt(root.mode.ordinal)
                out.writeLong(root.segmentCount)
                out.writeInt(root.phase.ordinal)
            }
            buffer.toByteArray()
        })

    fun decodeRoot(blob: AndroidOfflineSemanticCheckpointBlob): SemanticShardOrphanIntentRoot? {
        val body = unwrap(blob) ?: return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != ROOT_MAGIC) return null
            val version = input.readInt()
            if (version != SemanticShardOrphanIntentRoot.CURRENT_VERSION) return null
            val publicationId = readString(input)
            val mode = SemanticShardOrphanIntentMode.entries.getOrNull(input.readInt())
                ?: return null
            val segmentCount = input.readLong()
            val phase = SemanticShardOrphanIntentPhase.entries.getOrNull(input.readInt())
                ?: return null
            val root = SemanticShardOrphanIntentRoot(
                version = version,
                publicationId = publicationId,
                mode = mode,
                segmentCount = segmentCount,
                phase = phase
            )
            if (input.read() != -1) return null
            root
        } catch (_: Exception) {
            null
        } finally {
            body.fill(0)
        }
    }

    fun encodeSegment(
        segment: SemanticShardOrphanIntentSegment
    ): AndroidOfflineSemanticCheckpointBlob =
        wrap(ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(SEGMENT_MAGIC)
                out.writeInt(segment.version)
                writeString(out, segment.publicationId)
                out.writeLong(segment.ordinal)
                out.writeInt(segment.descriptors.size)
                segment.descriptors.forEach { descriptor ->
                    out.writeByte(
                        if (descriptor.shardId.domain == SemanticIndexDomain.MEMORY) 1 else 2
                    )
                    out.writeLong(descriptor.shardId.ordinal)
                    out.writeInt(descriptor.entryCount)
                    writeString(out, descriptor.blobSha256)
                }
            }
            buffer.toByteArray()
        })

    fun decodeSegment(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticShardOrphanIntentSegment? {
        val body = unwrap(blob) ?: return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != SEGMENT_MAGIC) return null
            val version = input.readInt()
            if (version != SemanticShardOrphanIntentSegment.CURRENT_VERSION) return null
            val publicationId = readString(input)
            val ordinal = input.readLong()
            val count = input.readInt()
            if (count !in 1..SemanticShardOrphanIntentRoot.DESCRIPTORS_PER_SEGMENT) {
                return null
            }
            val descriptors = ArrayList<SemanticShardDescriptor>(count)
            repeat(count) {
                val domain = when (input.readUnsignedByte()) {
                    1 -> SemanticIndexDomain.MEMORY
                    2 -> SemanticIndexDomain.KNOWLEDGE
                    else -> return null
                }
                descriptors += SemanticShardDescriptor(
                    shardId = SemanticShardId(domain, input.readLong()),
                    entryCount = input.readInt(),
                    blobSha256 = readString(input)
                )
            }
            if (input.read() != -1) return null
            SemanticShardOrphanIntentSegment(
                version = version,
                publicationId = publicationId,
                ordinal = ordinal,
                descriptors = descriptors
            )
        } catch (_: Exception) {
            null
        } finally {
            body.fill(0)
        }
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
        require(bytes.isNotEmpty() && bytes.size <= 128)
        out.writeInt(bytes.size)
        out.write(bytes)
        bytes.fill(0)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 1..128)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}

internal class SemanticShardOrphanIntentStore(
    private val storage: AndroidOfflineSemanticShardStorage
) {
    fun loadRoot(): SemanticShardOrphanIntentRootLoadResult =
        when (val read = storage.read(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ORPHAN_INTENT_ROOT)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardOrphanIntentRootLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardOrphanIntentRootLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> {
                val decoded = SemanticShardOrphanIntentCodec.decodeRoot(read.blob)
                if (decoded == null) {
                    SemanticShardOrphanIntentRootLoadResult.Corrupt
                } else {
                    SemanticShardOrphanIntentRootLoadResult.Loaded(decoded)
                }
            }
        }

    fun writeRoot(root: SemanticShardOrphanIntentRoot): Boolean =
        storage.write(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ORPHAN_INTENT_ROOT,
            SemanticShardOrphanIntentCodec.encodeRoot(root)
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written

    fun readSegment(
        root: SemanticShardOrphanIntentRoot,
        ordinal: Long
    ): SemanticShardOrphanIntentSegmentLoadResult {
        if (ordinal !in 0 until root.segmentCount) {
            return SemanticShardOrphanIntentSegmentLoadResult.Corrupt
        }
        val key = AndroidOfflineSemanticShardStorageKey.forOrphanIntentSegment(
            root.publicationId,
            ordinal
        )
        return when (val read = storage.read(key)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardOrphanIntentSegmentLoadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardOrphanIntentSegmentLoadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> {
                val decoded = SemanticShardOrphanIntentCodec.decodeSegment(read.blob)
                if (
                    decoded == null ||
                    decoded.publicationId != root.publicationId ||
                    decoded.ordinal != ordinal
                ) {
                    SemanticShardOrphanIntentSegmentLoadResult.Corrupt
                } else {
                    SemanticShardOrphanIntentSegmentLoadResult.Loaded(decoded)
                }
            }
        }
    }

    fun writeSegment(segment: SemanticShardOrphanIntentSegment): Boolean =
        storage.write(
            AndroidOfflineSemanticShardStorageKey.forOrphanIntentSegment(
                segment.publicationId,
                segment.ordinal
            ),
            SemanticShardOrphanIntentCodec.encodeSegment(segment)
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written

    fun clear(root: SemanticShardOrphanIntentRoot): Boolean {
        for (ordinal in 0 until root.segmentCount) {
            when (
                storage.delete(
                    AndroidOfflineSemanticShardStorageKey.forOrphanIntentSegment(
                        root.publicationId,
                        ordinal
                    )
                )
            ) {
                AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
                AndroidOfflineSemanticShardStorageDeleteResult.Missing -> Unit
                AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
                is AndroidOfflineSemanticShardStorageDeleteResult.Failed -> return false
            }
        }
        return when (
            storage.delete(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ORPHAN_INTENT_ROOT)
        ) {
            AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
            AndroidOfflineSemanticShardStorageDeleteResult.Missing -> true
            AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
            is AndroidOfflineSemanticShardStorageDeleteResult.Failed -> false
        }
    }
}

internal class SemanticShardOrphanIntentTracker(
    private val store: SemanticShardOrphanIntentStore,
    private val mode: SemanticShardOrphanIntentMode,
    private val publicationId: String = UUID.randomUUID().toString().replace("-", "")
) {
    private val pending = ArrayList<SemanticShardDescriptor>(
        SemanticShardOrphanIntentRoot.DESCRIPTORS_PER_SEGMENT
    )
    private var segmentOrdinal = 0L
    private var initialized = false
    private var failed = false
    private var lastOrderedId: SemanticShardId? = null

    init {
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
    }

    fun record(descriptor: SemanticShardDescriptor): Boolean {
        if (failed) return false
        if (!initialized) {
            when (store.loadRoot()) {
                SemanticShardOrphanIntentRootLoadResult.Missing -> Unit
                SemanticShardOrphanIntentRootLoadResult.Corrupt,
                is SemanticShardOrphanIntentRootLoadResult.Failed,
                is SemanticShardOrphanIntentRootLoadResult.Loaded -> {
                    // Never overwrite an unresolved intent epoch. Losing that root would make
                    // previously written content-addressed blobs undiscoverable to crash GC.
                    failed = true
                    return false
                }
            }
        }
        if (mode == SemanticShardOrphanIntentMode.ORDERED_REBUILD) {
            val previous = lastOrderedId
            if (previous != null && compareIds(previous, descriptor.shardId) >= 0) {
                failed = true
                return false
            }
        }

        if (pending.size == SemanticShardOrphanIntentRoot.DESCRIPTORS_PER_SEGMENT) {
            val nextOrdinal = segmentOrdinal + 1L
            if (
                mode == SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS &&
                nextOrdinal >= SemanticShardOrphanIntentRoot.MAX_BOUNDED_SEGMENTS
            ) {
                failed = true
                return false
            }
            val nextSegment = SemanticShardOrphanIntentSegment(
                publicationId = publicationId,
                ordinal = nextOrdinal,
                descriptors = listOf(descriptor)
            )
            if (!store.writeSegment(nextSegment)) {
                failed = true
                return false
            }
            val nextRoot = SemanticShardOrphanIntentRoot(
                publicationId = publicationId,
                mode = mode,
                segmentCount = nextOrdinal + 1L
            )
            if (!store.writeRoot(nextRoot)) {
                failed = true
                return false
            }
            pending.clear()
            pending += descriptor
            segmentOrdinal = nextOrdinal
            initialized = true
            lastOrderedId = descriptor.shardId
            return true
        }

        val nextPending = ArrayList<SemanticShardDescriptor>(pending.size + 1)
        nextPending += pending
        nextPending += descriptor
        val segment = SemanticShardOrphanIntentSegment(
            publicationId = publicationId,
            ordinal = segmentOrdinal,
            descriptors = nextPending
        )
        if (!store.writeSegment(segment)) {
            failed = true
            return false
        }
        if (!initialized) {
            val root = SemanticShardOrphanIntentRoot(
                publicationId = publicationId,
                mode = mode,
                segmentCount = 1L
            )
            if (!store.writeRoot(root)) {
                failed = true
                return false
            }
            initialized = true
        }
        pending.clear()
        pending += nextPending
        lastOrderedId = descriptor.shardId
        return true
    }

    fun clearCommittedIntents(): Boolean {
        if (!initialized) return true
        val reclaimed = SemanticShardOrphanIntentRoot(
            publicationId = publicationId,
            mode = mode,
            segmentCount = segmentOrdinal + 1L,
            phase = SemanticShardOrphanIntentPhase.RECLAIMED
        )
        if (!store.writeRoot(reclaimed)) return false
        return store.clear(reclaimed)
    }

    private fun compareIds(left: SemanticShardId, right: SemanticShardId): Int {
        val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
        return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
    }
}
