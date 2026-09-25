package pro.liliya.android.semanticprovider

import java.util.UUID

/**
 * Bounded-memory publisher for segmented semantic manifests.
 *
 * Descriptor segments are durable before the root. The root is the commit point.
 */
internal class SemanticShardManifestV3PublicationWriter(
    private val store: SemanticShardManifestV3Store,
    private val model: SemanticCheckpointModelBinding = SemanticCheckpointModelBinding.production(),
    internal val publicationId: String = UUID.randomUUID().toString().replace("-", "")
) {
    private val pending = ArrayList<SemanticShardDescriptor>(
        SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT
    )
    private var lastShardId: SemanticShardId? = null
    private var nextSegmentOrdinal: Long = 0L
    private var descriptorCount: Long = 0L
    private var failed: Boolean = false
    private var finished: Boolean = false

    init {
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
    }

    fun append(descriptor: SemanticShardDescriptor): Boolean {
        if (failed || finished) return false
        val previous = lastShardId
        if (previous != null && compareIds(previous, descriptor.shardId) >= 0) {
            failed = true
            return false
        }
        pending += descriptor
        lastShardId = descriptor.shardId
        descriptorCount += 1L
        if (pending.size == SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT) {
            if (!flushSegment()) {
                failed = true
                return false
            }
        }
        return true
    }

    fun finish(
        authoritative: SemanticAuthoritativeMetadataCheckpoint,
        beforeCommit: (SemanticShardManifestRootV3) -> Boolean = { true }
    ): SemanticShardManifestRootV3? {
        if (failed || finished) return null
        if (!flushSegment()) {
            failed = true
            return null
        }
        val binding = SemanticShardManifestV3Codec.manifestBindingSha256(
            publicationId,
            model,
            authoritative
        )
        val root = try {
            SemanticShardManifestRootV3(
                model = model,
                authoritative = authoritative,
                entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
                descriptorsPerSegment = SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT,
                publicationId = publicationId,
                manifestBindingSha256 = binding,
                segmentCount = nextSegmentOrdinal,
                shardDescriptorCount = descriptorCount
            )
        } catch (_: IllegalArgumentException) {
            failed = true
            return null
        }
        if (!beforeCommit(root) || !store.writeRoot(root)) {
            failed = true
            return null
        }
        finished = true
        return root
    }

    internal fun bufferedDescriptorCount(): Int = pending.size

    private fun flushSegment(): Boolean {
        if (pending.isEmpty()) return true
        val segment = try {
            SemanticShardManifestSegment(
                publicationId = publicationId,
                ordinal = nextSegmentOrdinal,
                shards = pending.toList()
            )
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (!store.writeSegment(segment)) return false
        pending.clear()
        nextSegmentOrdinal += 1L
        return true
    }

    private fun compareIds(left: SemanticShardId, right: SemanticShardId): Int {
        val domain = left.domain.ordinal.compareTo(right.domain.ordinal)
        return if (domain != 0) domain else left.ordinal.compareTo(right.ordinal)
    }
}