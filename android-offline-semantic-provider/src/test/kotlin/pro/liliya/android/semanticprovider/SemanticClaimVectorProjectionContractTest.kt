package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.semantic.SemanticClaimId
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVersion
import pro.liliya.core.semantic.SemanticClaimVersionReference

class SemanticClaimVectorProjectionContractTest {
    @Test
    fun manifest_round_trip_preserves_unbounded_lifetime_counts_and_exact_source_binding() {
        val manifest = SemanticClaimVectorProjectionManifest(
            profileId = SemanticModelProfileV01.PROFILE_ID,
            profileGeneration = 1,
            indexGeneration = 41,
            source = SemanticClaimSourceCheckpoint(
                revision = 99,
                highWatermark = 4_000_000_000L,
                entryCount = 3_000_000_000L
            ),
            state = SemanticClaimVectorProjectionState.COMPLETE,
            shardEntryLimit = 128,
            shardCount = 23_437_500L,
            indexedEntryCount = 3_000_000_000L
        )

        val decoded = assertIs<SemanticClaimVectorProjectionManifestDecodeResult.Decoded>(
            SemanticClaimVectorProjectionCodec.decodeManifest(
                SemanticClaimVectorProjectionCodec.encodeManifest(manifest)
            )
        )
        assertEquals(manifest, decoded.manifest)
    }

    @Test
    fun shard_round_trip_preserves_exact_claim_versions_and_vectors() {
        val first = vector(0)
        val second = vector(1)
        try {
            val shard = SemanticClaimVectorProjectionShard(
                indexGeneration = 7,
                ordinal = 19,
                entries = listOf(
                    SemanticClaimVectorProjectionEntry(reference("a", 1), first),
                    SemanticClaimVectorProjectionEntry(reference("b", 8), second)
                )
            )
            val blob = SemanticClaimVectorProjectionCodec.encodeShard(shard)
            val digest = SemanticClaimVectorProjectionCodec.digest(blob)
            assertEquals(64, digest.length)

            val decoded = assertIs<SemanticClaimVectorProjectionShardDecodeResult.Decoded>(
                SemanticClaimVectorProjectionCodec.decodeShard(blob)
            ).shard
            try {
                assertEquals(7L, decoded.indexGeneration)
                assertEquals(19L, decoded.ordinal)
                assertEquals(
                    listOf(reference("a", 1), reference("b", 8)),
                    decoded.entries.map { it.reference }
                )
                assertEquals(1.0, decoded.entries[0].vector.dot(first), 1e-9)
                assertEquals(1.0, decoded.entries[1].vector.dot(second), 1e-9)
            } finally {
                decoded.entries.forEach { it.vector.clear() }
            }
        } finally {
            first.clear()
            second.clear()
        }
    }

    @Test
    fun store_uses_claim_only_keys_and_content_addressed_shards() {
        val storage = MemoryShardStorage()
        val store = SemanticClaimVectorProjectionStore(storage)
        val manifest = SemanticClaimVectorProjectionManifest(
            profileId = SemanticModelProfileV01.PROFILE_ID,
            profileGeneration = 1,
            indexGeneration = 5,
            source = SemanticClaimSourceCheckpoint(3, 9, 1),
            state = SemanticClaimVectorProjectionState.INCOMPLETE,
            shardEntryLimit = 128,
            shardCount = 1,
            indexedEntryCount = 1
        )
        assertTrue(store.writeManifest(manifest))
        assertIs<SemanticClaimVectorProjectionManifestLoadResult.Loaded>(
            store.loadManifest()
        )

        val vector = vector(2)
        try {
            val shard = SemanticClaimVectorProjectionShard(
                indexGeneration = 5,
                ordinal = 0,
                entries = listOf(
                    SemanticClaimVectorProjectionEntry(reference("one", 4), vector)
                )
            )
            val digest = store.writeShard(shard) ?: error("shard write failed")
            val shardKey = storage.keys.single { it.value.startsWith("claim-vector-v1-shard-") }
            assertTrue(shardKey.value.contains(digest))
            assertTrue(storage.keys.contains(AndroidOfflineSemanticShardStorageKey.CLAIM_VECTOR_V1_MANIFEST))
            assertTrue(storage.keys.none { it.value == "manifest-v2" })

            val loaded = assertIs<SemanticClaimVectorProjectionShardLoadResult.Loaded>(
                store.readShard(5, 0, digest)
            ).shard
            try {
                assertEquals(reference("one", 4), loaded.entries.single().reference)
            } finally {
                loaded.entries.forEach { it.vector.clear() }
            }
        } finally {
            vector.clear()
        }
    }

    @Test
    fun wrong_content_digest_is_detected_before_decode_is_trusted() {
        val storage = MemoryShardStorage()
        val store = SemanticClaimVectorProjectionStore(storage)
        val vector = vector(3)
        try {
            val shard = SemanticClaimVectorProjectionShard(
                indexGeneration = 6,
                ordinal = 2,
                entries = listOf(
                    SemanticClaimVectorProjectionEntry(reference("digest", 1), vector)
                )
            )
            val digest = store.writeShard(shard) ?: error("write failed")
            val key = storage.keys.single { it.value.startsWith("claim-vector-v1-shard-") }
            val original = storage.entries[key] ?: error("missing stored shard")
            val bytes = original.copyBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            storage.entries[key] = AndroidOfflineSemanticCheckpointBlob(bytes)
            bytes.fill(0)

            assertIs<SemanticClaimVectorProjectionShardLoadResult.Corrupt>(
                store.readShard(6, 2, digest)
            )
        } finally {
            vector.clear()
        }
    }

    @Test
    fun content_address_changes_when_vector_payload_changes() {
        val a = vector(4)
        val b = vector(5)
        try {
            val first = SemanticClaimVectorProjectionCodec.encodeShard(
                SemanticClaimVectorProjectionShard(
                    indexGeneration = 1,
                    ordinal = 0,
                    entries = listOf(
                        SemanticClaimVectorProjectionEntry(reference("same", 1), a)
                    )
                )
            )
            val second = SemanticClaimVectorProjectionCodec.encodeShard(
                SemanticClaimVectorProjectionShard(
                    indexGeneration = 1,
                    ordinal = 0,
                    entries = listOf(
                        SemanticClaimVectorProjectionEntry(reference("same", 1), b)
                    )
                )
            )
            assertNotEquals(
                SemanticClaimVectorProjectionCodec.digest(first),
                SemanticClaimVectorProjectionCodec.digest(second)
            )
        } finally {
            a.clear()
            b.clear()
        }
    }

    private fun reference(id: String, version: Long) =
        SemanticClaimVersionReference(
            SemanticClaimId("claim-" + id),
            SemanticClaimVersion(version)
        )

    private fun vector(index: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[index] = 1f
        return SemanticEmbeddingVector(values)
    }

    private class MemoryShardStorage : AndroidOfflineSemanticShardStorage {
        val entries = LinkedHashMap<
            AndroidOfflineSemanticShardStorageKey,
            AndroidOfflineSemanticCheckpointBlob
        >()
        val keys: Set<AndroidOfflineSemanticShardStorageKey> get() = entries.keys

        override fun read(
            key: AndroidOfflineSemanticShardStorageKey
        ): AndroidOfflineSemanticShardStorageReadResult {
            val blob = entries[key] ?: return AndroidOfflineSemanticShardStorageReadResult.Missing
            return AndroidOfflineSemanticShardStorageReadResult.Loaded(
                AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            )
        }

        override fun write(
            key: AndroidOfflineSemanticShardStorageKey,
            blob: AndroidOfflineSemanticCheckpointBlob
        ): AndroidOfflineSemanticShardStorageWriteResult {
            entries[key] = AndroidOfflineSemanticCheckpointBlob(blob.copyBytes())
            return AndroidOfflineSemanticShardStorageWriteResult.Written
        }
    }
}