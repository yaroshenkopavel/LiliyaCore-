package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardManifestV3ContractTest {
    @Test
    fun root_blob_size_is_constant_as_logical_manifest_counts_grow() {
        val first = root(segmentCount = 1L, descriptorCount = 1L)
        val huge = root(segmentCount = 10_000_000L, descriptorCount = 2_560_000_000L)

        val firstBlob = SemanticShardManifestV3Codec.encodeRoot(first)
        val hugeBlob = SemanticShardManifestV3Codec.encodeRoot(huge)

        assertEquals(firstBlob.copyBytes().size, hugeBlob.copyBytes().size)
        assertTrue(hugeBlob.copyBytes().size < 4_096)
        assertEquals(
            huge,
            assertIs<SemanticShardManifestRootV3DecodeResult.Decoded>(
                SemanticShardManifestV3Codec.decodeRoot(hugeBlob)
            ).root
        )
    }

    @Test
    fun bounded_segment_round_trip_preserves_sorted_descriptors_and_binding() {
        val segment = SemanticShardManifestSegment(
            publicationId = "1".repeat(32),
            ordinal = 7L,
            shards = listOf(
                descriptor(SemanticIndexDomain.MEMORY, 1L, "a"),
                descriptor(SemanticIndexDomain.MEMORY, 2L, "b"),
                descriptor(SemanticIndexDomain.KNOWLEDGE, 0L, "c")
            )
        )

        val blob = SemanticShardManifestV3Codec.encodeSegment(segment)
        val decoded = assertIs<SemanticShardManifestSegmentDecodeResult.Decoded>(
            SemanticShardManifestV3Codec.decodeSegment(blob)
        ).segment

        assertEquals(segment, decoded)
        assertEquals(64, SemanticShardManifestV3Codec.digest(blob).length)
    }

    @Test
    fun segment_rejects_more_than_bounded_descriptor_limit() {
        val descriptors = (0..SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT).map { ordinal ->
            descriptor(SemanticIndexDomain.MEMORY, ordinal.toLong(), "d")
        }

        assertFailsWith<IllegalArgumentException> {
            SemanticShardManifestSegment(
                publicationId = "2".repeat(32),
                ordinal = 0L,
                shards = descriptors
            )
        }
    }

    @Test
    fun segment_rejects_unsorted_descriptors() {
        assertFailsWith<IllegalArgumentException> {
            SemanticShardManifestSegment(
                publicationId = "3".repeat(32),
                ordinal = 0L,
                shards = listOf(
                    descriptor(SemanticIndexDomain.KNOWLEDGE, 0L, "e"),
                    descriptor(SemanticIndexDomain.MEMORY, 0L, "f")
                )
            )
        }
    }

    @Test
    fun root_and_segment_tamper_fail_closed() {
        val rootBlob = SemanticShardManifestV3Codec.encodeRoot(root(1L, 1L))
        assertIs<SemanticShardManifestRootV3DecodeResult.Corrupt>(
            SemanticShardManifestV3Codec.decodeRoot(tamper(rootBlob))
        )

        val segmentBlob = SemanticShardManifestV3Codec.encodeSegment(
            SemanticShardManifestSegment(
                publicationId = "4".repeat(32),
                ordinal = 0L,
                shards = listOf(descriptor(SemanticIndexDomain.MEMORY, 0L, "1"))
            )
        )
        assertIs<SemanticShardManifestSegmentDecodeResult.Corrupt>(
            SemanticShardManifestV3Codec.decodeSegment(tamper(segmentBlob))
        )
    }

    private fun root(segmentCount: Long, descriptorCount: Long) =
        SemanticShardManifestRootV3(
            model = SemanticCheckpointModelBinding.production(),
            authoritative = SemanticAuthoritativeMetadataCheckpoint(
                memory = PersistentBackendMetadata(7L, 9_000L, 8_500L),
                knowledge = PersistentBackendMetadata(4L, 7_000L, 6_800L)
            ),
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            descriptorsPerSegment = SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT,
            publicationId = "a".repeat(32),
            manifestBindingSha256 = "f".repeat(64),
            segmentCount = segmentCount,
            shardDescriptorCount = descriptorCount
        )

    private fun descriptor(
        domain: SemanticIndexDomain,
        ordinal: Long,
        digestSeed: String
    ) = SemanticShardDescriptor(
        shardId = SemanticShardId(domain, ordinal),
        entryCount = 1,
        blobSha256 = digestSeed.repeat(64).take(64)
    )

    private fun tamper(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): AndroidOfflineSemanticCheckpointBlob {
        val bytes = blob.copyBytes()
        bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 0x01).toByte()
        return AndroidOfflineSemanticCheckpointBlob(bytes)
    }
}
