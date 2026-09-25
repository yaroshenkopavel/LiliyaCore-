package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class SemanticShardManifestV3GarbageCollectorContractTest {
    @Test
    fun committed_replacement_reclaims_old_blob_and_old_manifest_segment() {
        val fixture = Fixture()
        val oldDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 1f)
        val oldRoot = fixture.publish("1".repeat(32), listOf(oldDescriptor))

        val newDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 2f)
        val gc = fixture.gc()
        val newRoot = fixture.publish(
            "2".repeat(32),
            listOf(newDescriptor)
        ) { candidate -> gc.prepare(oldRoot, candidate) }

        assertIs<SemanticShardManifestV3GcResult.Completed>(
            gc.reclaimCommitted(oldRoot, newRoot)
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(key(oldDescriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(key(newDescriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(
                AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                    oldRoot.publicationId,
                    0L
                )
            )
        )
        assertIs<SemanticShardManifestV3GcJournalReadResult.Missing>(
            fixture.journalStore.read()
        )
        assertEquals(
            newRoot.publicationId,
            assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
                fixture.manifestStore.loadRoot()
            ).root.publicationId
        )
    }

    @Test
    fun shared_content_addressed_blob_is_preserved_when_new_root_still_references_it() {
        val fixture = Fixture()
        val descriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 1f)
        val oldRoot = fixture.publish("3".repeat(32), listOf(descriptor))

        val gc = fixture.gc()
        val newRoot = fixture.publish(
            "4".repeat(32),
            listOf(descriptor)
        ) { candidate -> gc.prepare(oldRoot, candidate) }

        assertIs<SemanticShardManifestV3GcResult.Completed>(
            gc.reclaimCommitted(oldRoot, newRoot)
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(key(descriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(
                AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                    oldRoot.publicationId,
                    0L
                )
            )
        )
    }

    @Test
    fun interrupted_before_root_commit_keeps_previous_root_and_all_previous_blobs() {
        val fixture = Fixture()
        val oldDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 1f)
        val oldRoot = fixture.publish("5".repeat(32), listOf(oldDescriptor))
        val newDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 2f)
        val gc = fixture.gc()
        val abandonedRoot = fixture.stageWithoutCommit(
            "6".repeat(32),
            listOf(newDescriptor)
        ) { candidate -> gc.prepare(oldRoot, candidate) }

        assertIs<SemanticShardManifestV3GcResult.Completed>(gc.resumeIfSafe())
        assertEquals(
            oldRoot.publicationId,
            assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
                fixture.manifestStore.loadRoot()
            ).root.publicationId
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(key(oldDescriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(key(newDescriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(
                AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                    abandonedRoot.publicationId,
                    0L
                )
            )
        )
        assertIs<SemanticShardManifestV3GcJournalReadResult.Missing>(
            fixture.journalStore.read()
        )
    }

    @Test
    fun interrupted_after_root_commit_resumes_cleanup_from_journal() {
        val fixture = Fixture()
        val oldDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 1f)
        val oldRoot = fixture.publish("7".repeat(32), listOf(oldDescriptor))
        val newDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 3f)

        val preparingGc = fixture.gc()
        val newRoot = fixture.publish(
            "8".repeat(32),
            listOf(newDescriptor)
        ) { candidate -> preparingGc.prepare(oldRoot, candidate) }

        val restartedGc = fixture.gc()
        assertIs<SemanticShardManifestV3GcResult.Completed>(restartedGc.resumeIfSafe())
        assertEquals(
            newRoot.publicationId,
            assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
                fixture.manifestStore.loadRoot()
            ).root.publicationId
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(key(oldDescriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(key(newDescriptor))
        )
    }

    @Test
    fun restart_resumes_after_shards_reclaimed_before_manifest_segment_cleanup() {
        val fixture = Fixture()
        val oldDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 1f)
        val oldRoot = fixture.publish("b".repeat(32), listOf(oldDescriptor))
        val newDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 2f)
        val gc = fixture.gc()
        val newRoot = fixture.publish(
            "c".repeat(32),
            listOf(newDescriptor)
        ) { candidate -> gc.prepare(oldRoot, candidate) }

        val oldSegmentKey = AndroidOfflineSemanticShardStorageKey.forManifestSegment(
            oldRoot.publicationId,
            0L
        )
        fixture.storage.failNextDelete(oldSegmentKey)

        assertIs<SemanticShardManifestV3GcResult.Deferred>(
            gc.reclaimCommitted(oldRoot, newRoot)
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(key(oldDescriptor))
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(oldSegmentKey)
        )
        val journal = assertIs<SemanticShardManifestV3GcJournalReadResult.Loaded>(
            fixture.journalStore.read()
        ).journal
        assertEquals(SemanticShardManifestV3GcPhase.SHARDS_RECLAIMED, journal.phase)

        assertIs<SemanticShardManifestV3GcResult.Completed>(fixture.gc().resumeIfSafe())
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Missing>(
            fixture.storage.read(oldSegmentKey)
        )
        assertIs<SemanticShardManifestV3GcJournalReadResult.Missing>(
            fixture.journalStore.read()
        )
        assertEquals(
            newRoot.publicationId,
            assertIs<SemanticShardManifestRootV3LoadResult.Loaded>(
                fixture.manifestStore.loadRoot()
            ).root.publicationId
        )
    }

    @Test
    fun tampered_current_segment_blocks_all_reclamation() {
        val fixture = Fixture()
        val oldDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 1f)
        val oldRoot = fixture.publish("9".repeat(32), listOf(oldDescriptor))
        val newDescriptor = fixture.writeShard(ordinal = 0L, generation = 1L, value = 4f)
        val gc = fixture.gc()

        val newRoot = fixture.publish(
            "a".repeat(32),
            listOf(newDescriptor)
        ) { candidate -> gc.prepare(oldRoot, candidate) }
        val newSegmentKey = AndroidOfflineSemanticShardStorageKey.forManifestSegment(
            newRoot.publicationId,
            0L
        )
        val original = assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(newSegmentKey)
        ).blob.copyBytes()
        original[0] = (original[0].toInt() xor 0x01).toByte()
        fixture.storage.forcePut(
            newSegmentKey,
            AndroidOfflineSemanticCheckpointBlob(original)
        )
        original.fill(0)

        assertIs<SemanticShardManifestV3GcResult.CorruptManifest>(
            gc.reclaimCommitted(oldRoot, newRoot)
        )
        assertIs<AndroidOfflineSemanticShardStorageReadResult.Loaded>(
            fixture.storage.read(key(oldDescriptor))
        )
    }

    private class Fixture {
        val storage = DeletableStorage()
        val manifestStore = SemanticShardManifestV3Store(storage)
        val journalStore = SemanticShardManifestV3GcJournalStore(storage)
        private val shardStore = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )

        fun gc() = SemanticShardManifestV3GarbageCollector(
            storage = storage,
            manifestStore = manifestStore,
            journalStore = journalStore
        )

        fun writeShard(
            ordinal: Long,
            generation: Long,
            value: Float
        ): SemanticShardDescriptor {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            val axis = ((value.toInt() - 1).coerceAtLeast(0)) % values.size
            values[axis] = 1f
            val vector = SemanticEmbeddingVector(values)
            values.fill(0f)
            return try {
                requireNotNull(
                    shardStore.writeShard(
                        SemanticShardCheckpoint(
                            version = SemanticShardCheckpoint.CURRENT_VERSION,
                            shardId = SemanticShardId(
                                SemanticIndexDomain.MEMORY,
                                ordinal
                            ),
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
                    )
                )
            } finally {
                vector.clear()
            }
        }

        fun publish(
            publicationId: String,
            descriptors: List<SemanticShardDescriptor>,
            beforeCommit: (SemanticShardManifestRootV3) -> Boolean = { true }
        ): SemanticShardManifestRootV3 {
            val writer = SemanticShardManifestV3PublicationWriter(
                store = manifestStore,
                publicationId = publicationId
            )
            descriptors.forEach { check(writer.append(it)) }
            return requireNotNull(
                writer.finish(
                    metadata(descriptors.sumOf { it.entryCount.toLong() }),
                    beforeCommit
                )
            )
        }

        fun stageWithoutCommit(
            publicationId: String,
            descriptors: List<SemanticShardDescriptor>,
            prepare: (SemanticShardManifestRootV3) -> Boolean
        ): SemanticShardManifestRootV3 {
            val writer = SemanticShardManifestV3PublicationWriter(
                store = manifestStore,
                publicationId = publicationId
            )
            descriptors.forEach { check(writer.append(it)) }
            var staged: SemanticShardManifestRootV3? = null
            val committed = writer.finish(
                metadata(descriptors.sumOf { it.entryCount.toLong() })
            ) { candidate ->
                staged = candidate
                check(prepare(candidate))
                false
            }
            check(committed == null)
            return requireNotNull(staged)
        }

        private fun metadata(entries: Long) = SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(
                revision = entries + 1L,
                highWatermark = entries,
                entryCount = entries
            ),
            knowledge = PersistentBackendMetadata(
                revision = 1L,
                highWatermark = 0L,
                entryCount = 0L
            )
        )
    }

    private class DeletableStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()
        private var failDeleteKey: String? = null

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
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }

        override fun delete(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageDeleteResult {
            if (failDeleteKey == key.value) {
                failDeleteKey = null
                return AndroidOfflineSemanticShardStorageDeleteResult.Failed(
                    "injected delete failure"
                )
            }
            return if (blobs.remove(key.value) != null) {
                AndroidOfflineSemanticShardStorageDeleteResult.Deleted
            } else {
                AndroidOfflineSemanticShardStorageDeleteResult.Missing
            }
        }

        fun failNextDelete(key: AndroidOfflineSemanticShardStorageKey) {
            failDeleteKey = key.value
        }

        fun forcePut(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ) {
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
        }
    }

    private companion object {
        fun key(descriptor: SemanticShardDescriptor) =
            AndroidOfflineSemanticShardStorageKey.forShard(
                descriptor.shardId,
                descriptor.blobSha256
            )
    }
}
