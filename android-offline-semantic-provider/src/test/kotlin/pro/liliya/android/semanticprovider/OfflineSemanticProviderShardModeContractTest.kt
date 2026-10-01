package pro.liliya.android.semanticprovider

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.persistence.PersistentBackendMetadata

class OfflineSemanticProviderShardModeContractTest {
    @Test
    fun shard_mode_discovery_mutations_and_manifest_persist_remain_bounded_and_exact() {
        val session = FakeSession()
        val provider = OfflineSemanticProviderComposition(
            profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
            sessionLoader = SemanticProviderSessionLoader {
                SemanticProviderSessionLoadResult.Loaded(session)
            }
        )
        assertEquals(
            OfflineSemanticProviderLoadResult.Ready,
            provider.load(TestSemanticModelArtifacts.validated(File("/private/test/model.onnx")))
        )

        val storage = InMemoryStorage()
        val store = SemanticShardStore(
            storage,
            SemanticModelProfileV01.PROFILE_GENERATION
        )
        val first = memorySource("shared", 2_048L)
        val initialShard = SemanticShardCheckpoint(
            SemanticShardCheckpoint.CURRENT_VERSION,
            SemanticShardLayout.shardFor(first),
            listOf(SemanticIndexSeed(first, axisVector(0)))
        )
        val descriptor = requireNotNull(store.writeShard(initialShard))
        val manifest = SemanticShardManifest(
            SemanticShardManifest.CURRENT_VERSION,
            SemanticCheckpointModelBinding.production(),
            authoritative(2_048L, 1L),
            SemanticShardLayout.ENTRIES_PER_SHARD,
            listOf(descriptor)
        )
        assertEquals(true, store.writeManifest(manifest))

        assertEquals(
            OfflineSemanticRebuildResult.Published(1),
            provider.activateShardIndex(store, manifest)
        )
        assertEquals(
            SemanticCandidates(listOf(first)),
            provider.discover(SemanticIndexDomain.MEMORY, "first", 4)
        )

        val second = memorySource("second", 2_049L)
        assertEquals(
            OfflineSemanticAddResult.Indexed,
            provider.add(SemanticSourceObservation(second, "second content"))
        )
        assertEquals(
            SemanticCandidates(listOf(first, second)),
            provider.discover(SemanticIndexDomain.MEMORY, "after add", 4)
        )

        val replacement = memorySource("shared", 4_097L)
        assertEquals(
            OfflineSemanticReplaceResult.Replaced,
            provider.replace(
                expected = first,
                replacement = SemanticSourceObservation(
                    replacement,
                    "replacement content"
                )
            )
        )
        assertEquals(
            SemanticCandidates(listOf(second, replacement)),
            provider.discover(SemanticIndexDomain.MEMORY, "after replace", 4)
        )

        assertEquals(
            OfflineSemanticRemoveResult.Removed,
            provider.remove(second)
        )
        assertEquals(
            SemanticCandidates(listOf(replacement)),
            provider.discover(SemanticIndexDomain.MEMORY, "after remove", 4)
        )

        val current = authoritative(4_097L, 1L)
        assertEquals(true, provider.persistShardManifest(current))
        val persisted = requireNotNull(storage.manifest())
        assertEquals(current, persisted.authoritative)
        assertEquals(
            listOf(SemanticShardId(SemanticIndexDomain.MEMORY, 2L)),
            persisted.shards.map { it.shardId }
        )
    }

    @Test
    fun shard_storage_failure_never_reports_a_successful_mutation() {
        val session = FakeSession()
        val provider = OfflineSemanticProviderComposition(
            profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
            sessionLoader = SemanticProviderSessionLoader {
                SemanticProviderSessionLoadResult.Loaded(session)
            }
        )
        provider.load(TestSemanticModelArtifacts.validated(File("/private/test/model.onnx")))

        val storage = InMemoryStorage()
        val store = SemanticShardStore(storage, SemanticModelProfileV01.PROFILE_GENERATION)
        val manifest = SemanticShardManifest(
            SemanticShardManifest.CURRENT_VERSION,
            SemanticCheckpointModelBinding.production(),
            authoritative(0L, 0L),
            SemanticShardLayout.ENTRIES_PER_SHARD,
            emptyList()
        )
        assertIs<OfflineSemanticRebuildResult.Published>(
            provider.activateShardIndex(store, manifest)
        )
        storage.failWrites = true

        assertEquals(
            OfflineSemanticAddResult.IndexFailed,
            provider.add(
                SemanticSourceObservation(
                    memorySource("cannot-persist", 1L),
                    "content"
                )
            )
        )
    }

    private class FakeSession : SemanticProviderEmbeddingSession {
        override fun embed(preparedText: String): SemanticEmbeddingResult {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            values[0] = 1f
            return SemanticEmbeddingResult.Embedded(SemanticEmbeddingVector(values))
        }

        override fun close(): SemanticEmbeddingCloseResult =
            SemanticEmbeddingCloseResult.Closed
    }

    private class InMemoryStorage : AndroidOfflineSemanticShardStorage {
        private val blobs = LinkedHashMap<String, AndroidOfflineSemanticCheckpointBlob>()
        var failWrites: Boolean = false

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
            if (failWrites) {
                return AndroidOfflineSemanticShardStorageWriteResult.Failed(
                    "injected storage failure"
                )
            }
            blobs[key.value] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }

        fun manifest(): SemanticShardManifest? {
            val blob = blobs[AndroidOfflineSemanticShardStorageKey.MANIFEST.value]
                ?: return null
            return assertIs<SemanticShardManifestDecodeResult.Decoded>(
                SemanticShardCheckpointCodec.decodeManifest(blob)
            ).manifest
        }
    }

    private fun authoritative(
        highWatermark: Long,
        entryCount: Long
    ): SemanticAuthoritativeMetadataCheckpoint =
        SemanticAuthoritativeMetadataCheckpoint(
            memory = PersistentBackendMetadata(
                revision = maxOf(1L, highWatermark),
                highWatermark = highWatermark,
                entryCount = entryCount
            ),
            knowledge = PersistentBackendMetadata(1L, 0L, 0L)
        )

    private fun memorySource(
        id: String,
        generation: Long
    ): SemanticIndexSourceReference.Memory =
        SemanticIndexSourceReference.Memory(
            MemoryRecordId(id),
            MemoryGeneration(generation)
        )

    private fun axisVector(axis: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[axis] = 1f
        return SemanticEmbeddingVector(values)
    }
}
