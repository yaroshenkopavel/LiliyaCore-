package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardOrphanIntentContractTest {
    @Test
    fun intent_is_durable_before_content_addressed_shard_write() {
        val storage = RecordingDeletableStorage()
        val tracker = SemanticShardOrphanIntentTracker(
            store = SemanticShardOrphanIntentStore(storage),
            mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS,
            publicationId = "1".repeat(32)
        )
        val shardStore = shardStore(storage)

        val descriptor = writeShard(
            shardStore = shardStore,
            tracker = tracker,
            generation = 1L,
            value = 1f
        )

        val shardKey = key(descriptor).value
        val segmentIndex = storage.writeOrder.indexOf(
            AndroidOfflineSemanticShardStorageKey.forOrphanIntentSegment(
                "1".repeat(32),
                0L
            ).value
        )
        val rootIndex = storage.writeOrder.indexOf(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_ORPHAN_INTENT_ROOT.value
        )
        val shardIndex = storage.writeOrder.indexOf(shardKey)

        assertTrue(segmentIndex >= 0)
        assertTrue(rootIndex > segmentIndex)
        assertTrue(shardIndex > rootIndex)
    }

    @Test
    fun restart_before_root_commit_reclaims_unreferenced_replacement_only() {
        val storage = RecordingDeletableStorage()
        val manifestStore = SemanticShardManifestV3Store(storage)
        val shardStore = shardStore(storage)

        val old = writeShardWithoutIntent(shardStore, generation = 1L, value = 1f)
        val current = publish(manifestStore, "2".repeat(32), listOf(old))

        val tracker = SemanticShardOrphanIntentTracker(
            store = SemanticShardOrphanIntentStore(storage),
            mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS,
            publicationId = "3".repeat(32)
        )
        val replacement = writeShard(
            shardStore = shardStore,
            tracker = tracker,
            generation = 1L,
            value = 2f
        )

        val gc = SemanticShardOrphanIntentGarbageCollector(
            storage = storage,
            manifestStore = manifestStore,
            shardStore = shardStore
        )
        assertIs<SemanticShardOrphanIntentGcResult.Completed>(gc.resumeIfSafe())

        assertEquals(
            current.publicationId,
            assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
                manifestStore.loadRoot()
            ).root.publicationId
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(storage.read(key(old)))
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            storage.read(key(replacement))
        )
        assertIs<SemanticShardOrphanIntentRootLoadResult.Missing>(
            SemanticShardOrphanIntentStore(storage).loadRoot()
        )
    }

    @Test
    fun committed_latest_replacement_preserves_latest_and_reclaims_superseded_intent() {
        val storage = RecordingDeletableStorage()
        val manifestStore = SemanticShardManifestV3Store(storage)
        val shardStore = shardStore(storage)

        val initial = writeShardWithoutIntent(shardStore, generation = 1L, value = 1f)
        publish(manifestStore, "4".repeat(32), listOf(initial))

        val tracker = SemanticShardOrphanIntentTracker(
            store = SemanticShardOrphanIntentStore(storage),
            mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS,
            publicationId = "5".repeat(32)
        )
        val first = writeShard(shardStore, tracker, generation = 1L, value = 2f)
        val latest = writeShard(shardStore, tracker, generation = 1L, value = 3f)
        publish(manifestStore, "6".repeat(32), listOf(latest))

        val gc = SemanticShardOrphanIntentGarbageCollector(
            storage = storage,
            manifestStore = manifestStore,
            shardStore = shardStore
        )
        assertIs<SemanticShardOrphanIntentGcResult.Completed>(gc.resumeIfSafe())

        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(storage.read(key(first)))
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(storage.read(key(latest)))
    }

    @Test
    fun ordered_rebuild_intents_stream_across_multiple_segments_without_lifetime_cap() {
        val storage = RecordingDeletableStorage()
        val manifestStore = SemanticShardManifestV3Store(storage)
        val shardStore = shardStore(storage)
        val tracker = SemanticShardOrphanIntentTracker(
            store = SemanticShardOrphanIntentStore(storage),
            mode = SemanticShardOrphanIntentMode.ORDERED_REBUILD,
            publicationId = "7".repeat(32)
        )

        val descriptors = (0 until 600).map { ordinal ->
            descriptor(ordinal.toLong(), (ordinal % 16).toString(16))
        }
        descriptors.forEach { assertTrue(tracker.record(it)) }
        val root = publish(manifestStore, "8".repeat(32), descriptors)

        val intentRoot = assertIs<SemanticShardOrphanIntentRootLoadResult.Loaded>(
            SemanticShardOrphanIntentStore(storage).loadRoot()
        ).root
        assertEquals(3L, intentRoot.segmentCount)

        val gc = SemanticShardOrphanIntentGarbageCollector(
            storage = storage,
            manifestStore = manifestStore,
            shardStore = shardStore
        )
        assertIs<SemanticShardOrphanIntentGcResult.Completed>(gc.resumeIfSafe())
        assertEquals(
            root.publicationId,
            assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
                manifestStore.loadRoot()
            ).root.publicationId
        )
        assertIs<SemanticShardOrphanIntentRootLoadResult.Missing>(
            SemanticShardOrphanIntentStore(storage).loadRoot()
        )
    }

    @Test
    fun unresolved_intent_epoch_cannot_be_overwritten_by_new_tracker() {
        val storage = RecordingDeletableStorage()
        val first = SemanticShardOrphanIntentTracker(
            store = SemanticShardOrphanIntentStore(storage),
            mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS,
            publicationId = "9".repeat(32)
        )
        assertTrue(first.record(descriptor(0L, "a")))

        val second = SemanticShardOrphanIntentTracker(
            store = SemanticShardOrphanIntentStore(storage),
            mode = SemanticShardOrphanIntentMode.BOUNDED_MUTATIONS,
            publicationId = "a".repeat(32)
        )
        assertEquals(false, second.record(descriptor(1L, "b")))

        val root = assertIs<SemanticShardOrphanIntentRootLoadResult.Loaded>(
            SemanticShardOrphanIntentStore(storage).loadRoot()
        ).root
        assertEquals("9".repeat(32), root.publicationId)
        assertEquals(SemanticShardOrphanIntentPhase.TRACKING, root.phase)
    }

    @Test
    fun reclaimed_phase_resumes_metadata_cleanup_after_partial_segment_deletion() {
        val storage = RecordingDeletableStorage()
        val intentStore = SemanticShardOrphanIntentStore(storage)
        val tracker = SemanticShardOrphanIntentTracker(
            store = intentStore,
            mode = SemanticShardOrphanIntentMode.ORDERED_REBUILD,
            publicationId = "b".repeat(32)
        )

        repeat(300) { ordinal ->
            assertTrue(tracker.record(descriptor(ordinal.toLong(), "c")))
        }
        val trackingRoot = assertIs<SemanticShardOrphanIntentRootLoadResult.Loaded>(
            intentStore.loadRoot()
        ).root
        val reclaimed = trackingRoot.copy(phase = SemanticShardOrphanIntentPhase.RECLAIMED)
        assertTrue(intentStore.writeRoot(reclaimed))

        assertIs<AndroidOfflineSemanticShardStorageDeleteResult.Deleted>(
            storage.delete(
                AndroidOfflineSemanticShardStorageKey.forOrphanIntentSegment(
                    reclaimed.publicationId,
                    0L
                )
            )
        )

        val shardStore = shardStore(storage)
        val gc = SemanticShardOrphanIntentGarbageCollector(
            storage = storage,
            intentStore = intentStore,
            manifestStore = SemanticShardManifestV3Store(storage),
            shardStore = shardStore
        )
        assertIs<SemanticShardOrphanIntentGcResult.Completed>(gc.resumeIfSafe())
        assertIs<SemanticShardOrphanIntentRootLoadResult.Missing>(intentStore.loadRoot())
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            storage.read(
                AndroidOfflineSemanticShardStorageKey.forOrphanIntentSegment(
                    reclaimed.publicationId,
                    1L
                )
            )
        )
    }

    private fun shardStore(storage: AndroidOfflineSemanticShardStorage) =
        SemanticShardStore(storage, SemanticModelProfileV01.PROFILE_GENERATION)

    private fun writeShard(
        shardStore: SemanticShardStore,
        tracker: SemanticShardOrphanIntentTracker,
        generation: Long,
        value: Float
    ): SemanticShardDescriptor =
        requireNotNull(
            shardStore.writeShard(
                checkpoint(generation, value),
                beforeWrite = tracker::record
            )
        )

    private fun writeShardWithoutIntent(
        shardStore: SemanticShardStore,
        generation: Long,
        value: Float
    ): SemanticShardDescriptor =
        requireNotNull(shardStore.writeShard(checkpoint(generation, value)))

    private fun checkpoint(generation: Long, value: Float): SemanticShardCheckpoint {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[((value.toInt() - 1).coerceAtLeast(0)) % values.size] = 1f
        val vector = SemanticEmbeddingVector(values)
        values.fill(0f)
        return SemanticShardCheckpoint(
            version = SemanticShardCheckpoint.CURRENT_VERSION,
            shardId = SemanticShardId(SemanticIndexDomain.MEMORY, 0L),
            seeds = listOf(
                SemanticIndexSeed(
                    SemanticIndexSourceReference.Memory(
                        MemoryRecordId("memory-" + generation),
                        MemoryGeneration(generation)
                    ),
                    vector
                )
            )
        )
    }

    private fun publish(
        store: SemanticShardManifestV3Store,
        publicationId: String,
        descriptors: List<SemanticShardDescriptor>
    ): SemanticShardManifestRootV3 {
        val writer = SemanticShardManifestV3PublicationWriter(
            store = store,
            publicationId = publicationId
        )
        descriptors.forEach { check(writer.append(it)) }
        return requireNotNull(
            writer.finish(
                SemanticAuthoritativeMetadataCheckpoint(
                    memory = PersistentBackendMetadata(
                        revision = descriptors.size.toLong() + 1L,
                        highWatermark = descriptors.size.toLong(),
                        entryCount = descriptors.sumOf { it.entryCount.toLong() }
                    ),
                    knowledge = PersistentBackendMetadata(1L, 0L, 0L)
                )
            )
        )
    }

    private fun descriptor(
        ordinal: Long,
        seed: String
    ): SemanticShardDescriptor = SemanticShardDescriptor(
        shardId = SemanticShardId(SemanticIndexDomain.MEMORY, ordinal),
        entryCount = 1,
        blobSha256 = seed.repeat(64).take(64)
    )

    private fun key(descriptor: SemanticShardDescriptor) =
        AndroidOfflineSemanticShardStorageKey.forShard(
            descriptor.shardId,
            descriptor.blobSha256
        )

    private class RecordingDeletableStorage : AndroidOfflineSemanticShardStorage {
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
