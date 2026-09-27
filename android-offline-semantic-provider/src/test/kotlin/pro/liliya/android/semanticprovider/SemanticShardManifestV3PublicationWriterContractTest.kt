package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardManifestV3PublicationWriterContractTest {
    @Test
    fun publication_is_bounded_segmented_and_commits_root_last() {
        val storage = RecordingStorage()
        val store = SemanticShardManifestV3Store(storage)
        val writer = SemanticShardManifestV3PublicationWriter(
            store = store,
            publicationId = "1".repeat(32)
        )

        repeat(600) { ordinal ->
            assertTrue(writer.append(descriptor(ordinal.toLong())))
            assertTrue(
                writer.bufferedDescriptorCount() <
                    SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT
            )
        }

        val root = assertNotNull(writer.finish(authoritative()))
        assertEquals(3L, root.segmentCount)
        assertEquals(600L, root.shardDescriptorCount)
        assertEquals("manifest-v3-root", storage.writeOrder.last())

        val loadedRoot = assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
            store.loadRoot()
        ).root
        assertEquals(root, loadedRoot)
        assertEquals(256, segment(store, root, 0L).shards.size)
        assertEquals(256, segment(store, root, 1L).shards.size)
        assertEquals(88, segment(store, root, 2L).shards.size)
    }

    @Test
    fun missing_or_publication_mismatched_segment_fails_closed() {
        val storage = RecordingStorage()
        val store = SemanticShardManifestV3Store(storage)
        val root = root("2".repeat(32), 1L, 1L)
        assertTrue(store.writeRoot(root))

        assertIs<SemanticShardManifestSegmentLoadResult.Missing>(
            store.readSegment(root, 0L)
        )

        val wrong = SemanticShardManifestSegment(
            publicationId = "3".repeat(32),
            ordinal = 0L,
            shards = listOf(descriptor(0L))
        )
        val wrongBlob = SemanticShardManifestV3Codec.encodeSegment(wrong)
        storage.forcePut(
            AndroidOfflineSemanticShardStorageKey.forManifestSegment(root.publicationId, 0L),
            wrongBlob
        )
        assertIs<SemanticShardManifestSegmentLoadResult.Corrupt>(
            store.readSegment(root, 0L)
        )
    }

    @Test
    fun root_binding_rejects_authoritative_metadata_tamper() {
        val storage = RecordingStorage()
        val model = SemanticCheckpointModelBinding.production()
        val authoritative = authoritative()
        val publicationId = "4".repeat(32)
        val valid = root(publicationId, 0L, 0L, model, authoritative)
        val tampered = valid.copy(
            authoritative = authoritative.copy(
                memory = authoritative.memory.copy(revision = authoritative.memory.revision + 1L)
            )
        )
        storage.forcePut(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ROOT,
            SemanticShardManifestV3Codec.encodeRoot(tampered)
        )

        assertIs<SemanticShardManifestRootV3LoadResult.Corrupt>(
            SemanticShardManifestV3Store(storage).loadRoot()
        )
    }

    private fun segment(
        store: SemanticShardManifestV3Store,
        root: SemanticShardManifestRootV3,
        ordinal: Long
    ): SemanticShardManifestSegment =
        assertIs<SemanticShardManifestSegmentLoadResult.Loaded>(
            store.readSegment(root, ordinal)
        ).segment

    private fun root(
        publicationId: String,
        segmentCount: Long,
        descriptorCount: Long,
        model: SemanticCheckpointModelBinding = SemanticCheckpointModelBinding.production(),
        authoritative: SemanticAuthoritativeMetadataCheckpoint = authoritative()
    ): SemanticShardManifestRootV3 = SemanticShardManifestRootV3(
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
        segmentCount = segmentCount,
        shardDescriptorCount = descriptorCount
    )

    private fun descriptor(ordinal: Long): SemanticShardDescriptor = SemanticShardDescriptor(
        shardId = SemanticShardId(SemanticIndexDomain.MEMORY, ordinal),
        entryCount = 1,
        blobSha256 = "a".repeat(64)
    )

    private fun authoritative() = SemanticAuthoritativeMetadataCheckpoint(
        memory = PersistentBackendMetadata(7L, 9_000L, 8_500L),
        knowledge = PersistentBackendMetadata(4L, 7_000L, 6_800L)
    )

    private class RecordingStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()
        val writeOrder = ArrayList<String>()

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult =
            blobs[key.value]?.let {
                AndroidOfflineSemanticShardStorageReadResult.Loaded(
                    AndroidOfflineSemanticCheckpointBlob(it.copyBytes())
                )
            } ?: AndroidOfflineSemanticShardStorageReadResult.Missing

        override fun write(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ): AndroidOfflineSemanticShardStorageWriteResult {
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            writeOrder += key.value
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }

        fun forcePut(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ) {
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
        }
    }
}