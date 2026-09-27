package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardRoutingV1ContractTest {
    @Test
    fun envelope_upper_bound_never_underestimates_exact_similarity() {
        val first = vector(0, 1f)
        val second = normalizedPair(0.6f, -0.8f)
        val query = normalizedPair(-0.8f, 0.6f)
        try {
            val envelope = SemanticShardRoutingEnvelope.fromSeeds(
                listOf(
                    seed(1L, first),
                    seed(2L, second)
                )
            )
            val bound = envelope.upperBound(query)
            assertTrue(bound + 1e-12 >= query.dot(first))
            assertTrue(bound + 1e-12 >= query.dot(second))
        } finally {
            first.clear()
            second.clear()
            query.clear()
        }
    }

    @Test
    fun root_size_is_constant_for_huge_logical_shard_count() {
        val small = SemanticShardRoutingRoot(
            manifestPublicationId = "a".repeat(32),
            manifestBindingSha256 = "b".repeat(64),
            memory = SemanticShardRoutingDomainRoot("c".repeat(64), 1, 1L),
            knowledge = SemanticShardRoutingDomainRoot.EMPTY
        )
        val huge = small.copy(
            memory = SemanticShardRoutingDomainRoot("c".repeat(64), 12, 9_000_000_000L)
        )

        val smallBlob = SemanticShardRoutingCodec.encodeRoot(small)
        val hugeBlob = SemanticShardRoutingCodec.encodeRoot(huge)
        assertEquals(smallBlob.copyBytes().size, hugeBlob.copyBytes().size)
        assertTrue(hugeBlob.copyBytes().size < 1024)
        assertEquals(
            huge,
            assertIs<SemanticShardRoutingRootDecodeResult.Decoded>(
                SemanticShardRoutingCodec.decodeRoot(hugeBlob)
            ).root
        )
    }

    @Test
    fun max_fanout_leaf_stays_bounded_and_round_trips() {
        val envelope = onePointEnvelope()
        val entries = (0 until SemanticShardRoutingNode.FANOUT).map { ordinal ->
            SemanticShardRoutingLeafEntry(
                descriptor = descriptor(ordinal.toLong()),
                envelope = envelope
            )
        }
        val node = SemanticShardRoutingNode.Leaf(entries = entries)
        val blob = SemanticShardRoutingCodec.encodeNode(node)
        assertTrue(blob.copyBytes().size < 64 * 1024)

        val decoded = assertIs<SemanticShardRoutingNodeDecodeResult.Decoded>(
            SemanticShardRoutingCodec.decodeNode(blob)
        ).node
        val leaf = assertIs<SemanticShardRoutingNode.Leaf>(decoded)
        assertEquals(entries.map { it.descriptor }, leaf.entries.map { it.descriptor })
    }

    @Test
    fun root_binding_matches_only_exact_manifest_publication() {
        val manifest = manifestRoot("d".repeat(32))
        val routing = SemanticShardRoutingRoot(
            manifestPublicationId = manifest.publicationId,
            manifestBindingSha256 = manifest.manifestBindingSha256,
            memory = SemanticShardRoutingDomainRoot(
                rootNodeSha256 = "e".repeat(64),
                depth = 1,
                shardCount = manifest.shardDescriptorCount
            ),
            knowledge = SemanticShardRoutingDomainRoot.EMPTY
        )
        assertTrue(routing.matches(manifest))
        assertEquals(
            false,
            routing.matches(manifestRoot("f".repeat(32)))
        )
    }

    @Test
    fun tampered_root_and_node_fail_closed() {
        val root = SemanticShardRoutingRoot(
            manifestPublicationId = "1".repeat(32),
            manifestBindingSha256 = "2".repeat(64),
            memory = SemanticShardRoutingDomainRoot("3".repeat(64), 1, 1L),
            knowledge = SemanticShardRoutingDomainRoot.EMPTY
        )
        assertIs<SemanticShardRoutingRootDecodeResult.Corrupt>(
            SemanticShardRoutingCodec.decodeRoot(tamper(SemanticShardRoutingCodec.encodeRoot(root)))
        )

        val leaf = SemanticShardRoutingNode.Leaf(
            entries = listOf(
                SemanticShardRoutingLeafEntry(descriptor(0L), onePointEnvelope())
            )
        )
        assertIs<SemanticShardRoutingNodeDecodeResult.Corrupt>(
            SemanticShardRoutingCodec.decodeNode(tamper(SemanticShardRoutingCodec.encodeNode(leaf)))
        )
    }

    @Test
    fun leaf_requires_strictly_sorted_unique_shards() {
        val envelope = onePointEnvelope()
        try {
            SemanticShardRoutingNode.Leaf(
                entries = listOf(
                    SemanticShardRoutingLeafEntry(descriptor(1L), envelope),
                    SemanticShardRoutingLeafEntry(descriptor(0L), envelope)
                )
            )
            throw AssertionError("unsorted leaf must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun manifestRoot(publicationId: String): SemanticShardManifestRootV3 {
        val model = SemanticCheckpointModelBinding.production()
        val authoritative = SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(2L, 1L, 1L),
            knowledge = PersistentBackendMetadata(1L, 0L, 0L)
        )
        return SemanticShardManifestRootV3(
            model = model,
            authoritative = authoritative,
            entriesPerShard = SemanticShardLayout.ENTRIES_PER_SHARD,
            descriptorsPerSegment = SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT,
            publicationId = publicationId,
            manifestBindingSha256 = SemanticShardManifestV3Codec.manifestBindingSha256(
                publicationId,
                model,
                authoritative
            ),
            segmentCount = 1L,
            shardDescriptorCount = 1L
        )
    }

    private fun descriptor(ordinal: Long) =
        SemanticShardDescriptor(
            shardId = SemanticShardId(SemanticIndexDomain.MEMORY, ordinal),
            entryCount = 1,
            blobSha256 = ordinal.toString(16).padStart(64, '0').takeLast(64)
        )

    private fun onePointEnvelope(): SemanticShardRoutingEnvelope {
        val v = vector(0, 1f)
        return try {
            SemanticShardRoutingEnvelope.fromSeeds(listOf(seed(1L, v)))
        } finally {
            v.clear()
        }
    }

    private fun seed(generation: Long, vector: SemanticEmbeddingVector) =
        SemanticIndexSeed(
            SemanticIndexSourceReference.Memory(
                MemoryRecordId("memory-" + generation),
                MemoryGeneration(generation)
            ),
            vector
        )

    private fun vector(axis: Int, value: Float): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[axis] = value
        return try {
            SemanticEmbeddingVector(values)
        } finally {
            values.fill(0f)
        }
    }

    private fun normalizedPair(first: Float, second: Float): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[0] = first
        values[1] = second
        return try {
            SemanticEmbeddingVector(values)
        } finally {
            values.fill(0f)
        }
    }

    private fun tamper(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): AndroidOfflineSemanticCheckpointBlob {
        val bytes = blob.copyBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        return AndroidOfflineSemanticCheckpointBlob(bytes)
    }
}
