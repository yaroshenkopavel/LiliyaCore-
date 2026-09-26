package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardManifestV3PublicationIntentContractTest {
    @Test
    fun publication_intent_is_written_before_manifest_segment() {
        val storage = RecordingStorage()
        val store = SemanticShardManifestV3Store(storage)
        val writer = SemanticShardManifestV3PublicationWriter(
            store = store,
            publicationId = "1".repeat(32)
        )

        repeat(SemanticShardManifestRootV3.DESCRIPTORS_PER_SEGMENT) { ordinal ->
            assertTrue(writer.append(descriptor(ordinal.toLong())))
        }

        val intentIndex = storage.writeOrder.indexOf(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_PUBLICATION_INTENT.value
        )
        val segmentIndex = storage.writeOrder.indexOf(
            AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                "1".repeat(32),
                0L
            ).value
        )
        assertTrue(intentIndex >= 0)
        assertTrue(segmentIndex > intentIndex)
    }

    @Test
    fun interrupted_before_root_commit_reclaims_staged_manifest_segments() {
        val storage = RecordingStorage()
        val store = SemanticShardManifestV3Store(storage)
        val writer = SemanticShardManifestV3PublicationWriter(
            store = store,
            publicationId = "2".repeat(32)
        )

        repeat(300) { ordinal ->
            assertTrue(writer.append(descriptor(ordinal.toLong())))
        }
        // 256 descriptors flushed segment 0; segment 1 remains buffered and has not been written.
        val segment0 = AndroidOfflineSemanticShardStorageKey.forManifestSegment(
            "2".repeat(32),
            0L
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(storage.read(segment0))
        assertIs<SemanticShardManifestV3PublicationIntentReadResult.Loaded>(
            SemanticShardManifestV3PublicationIntentStore(storage).read()
        )

        val gc = SemanticShardManifestV3PublicationIntentGarbageCollector(
            storage = storage,
            manifestStore = store
        )
        assertIs<SemanticShardManifestV3PublicationIntentGcResult.Completed>(gc.resumeIfSafe())
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(storage.read(segment0))
        assertIs<SemanticShardManifestV3PublicationIntentReadResult.Missing>(
            SemanticShardManifestV3PublicationIntentStore(storage).read()
        )
    }

    @Test
    fun committed_root_preserves_its_segments_if_intent_cleanup_was_interrupted() {
        val storage = RecordingStorage()
        val store = SemanticShardManifestV3Store(storage)
        val publicationId = "3".repeat(32)
        val descriptors = listOf(descriptor(0L))
        val segment = SemanticShardManifestSegment(
            publicationId = publicationId,
            ordinal = 0L,
            shards = descriptors
        )
        assertTrue(
            SemanticShardManifestV3PublicationIntentStore(storage)
                .recordSegmentBeforeWrite(publicationId, 0L)
        )
        assertTrue(store.writeSegment(segment))

        val root = root(publicationId, segmentCount = 1L, descriptorCount = 1L)
        assertTrue(store.writeRoot(root))

        val gc = SemanticShardManifestV3PublicationIntentGarbageCollector(
            storage = storage,
            manifestStore = store
        )
        assertIs<SemanticShardManifestV3PublicationIntentGcResult.Completed>(gc.resumeIfSafe())
        assertIs<SemanticShardManifestSegmentLoadResult.Loaded>(store.readSegment(root, 0L))
        assertIs<SemanticShardManifestV3PublicationIntentReadResult.Missing>(
            SemanticShardManifestV3PublicationIntentStore(storage).read()
        )
    }

    @Test
    fun unresolved_publication_intent_blocks_different_publication() {
        val storage = RecordingStorage()
        val intentStore = SemanticShardManifestV3PublicationIntentStore(storage)
        assertTrue(intentStore.recordSegmentBeforeWrite("4".repeat(32), 0L))

        assertEquals(
            false,
            intentStore.recordSegmentBeforeWrite("5".repeat(32), 0L)
        )
        val intent = assertIs<SemanticShardManifestV3PublicationIntentReadResult.Loaded>(
            intentStore.read()
        ).intent
        assertEquals("4".repeat(32), intent.publicationId)
        assertEquals(1L, intent.manifestSegmentCount)
    }

    private fun root(
        publicationId: String,
        segmentCount: Long,
        descriptorCount: Long
    ): SemanticShardManifestRootV3 {
        val model = SemanticCheckpointModelBinding.production()
        val authoritative = metadata(descriptorCount)
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
            segmentCount = segmentCount,
            shardDescriptorCount = descriptorCount
        )
    }

    private fun descriptor(ordinal: Long): SemanticShardDescriptor =
        SemanticShardDescriptor(
            shardId = SemanticShardId(SemanticIndexDomain.MEMORY, ordinal),
            entryCount = 1,
            blobSha256 = "a".repeat(64)
        )

    private fun metadata(entries: Long) = SemanticAuthoritativeMetadataCheckpoint(
        memory = PersistentBackendMetadata(
            revision = entries + 1L,
            highWatermark = entries,
            entryCount = entries
        ),
        knowledge = PersistentBackendMetadata(1L, 0L, 0L)
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

        override fun delete(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageDeleteResult =
            if (blobs.remove(key.value) != null) {
                AndroidOfflineSemanticShardStorageDeleteResult.Deleted
            } else {
                AndroidOfflineSemanticShardStorageDeleteResult.Missing
            }
    }
}
