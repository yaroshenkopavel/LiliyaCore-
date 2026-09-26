package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal data class SemanticShardManifestV3PublicationIntent(
    val version: Int = CURRENT_VERSION,
    val publicationId: String,
    val manifestSegmentCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
        require(manifestSegmentCount > 0L)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal sealed interface SemanticShardManifestV3PublicationIntentReadResult {
    data object Missing : SemanticShardManifestV3PublicationIntentReadResult
    data class Loaded(val intent: SemanticShardManifestV3PublicationIntent) :
        SemanticShardManifestV3PublicationIntentReadResult
    data object Corrupt : SemanticShardManifestV3PublicationIntentReadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardManifestV3PublicationIntentReadResult
}

internal object SemanticShardManifestV3PublicationIntentCodec {
    private const val MAGIC = 0x4C535033 // LSP3
    private const val DIGEST_BYTES = 32

    fun encode(
        intent: SemanticShardManifestV3PublicationIntent
    ): AndroidOfflineSemanticCheckpointBlob {
        val body = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(intent.version)
                val publication = intent.publicationId.encodeToByteArray()
                out.writeInt(publication.size)
                out.write(publication)
                publication.fill(0)
                out.writeLong(intent.manifestSegmentCount)
            }
            buffer.toByteArray()
        }
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

    fun decode(
        blob: AndroidOfflineSemanticCheckpointBlob
    ): SemanticShardManifestV3PublicationIntent? {
        val all = blob.copyBytes()
        try {
            if (all.size <= DIGEST_BYTES) return null
            val bodySize = all.size - DIGEST_BYTES
            val body = all.copyOfRange(0, bodySize)
            val expected = all.copyOfRange(bodySize, all.size)
            val actual = MessageDigest.getInstance("SHA-256").digest(body)
            if (!MessageDigest.isEqual(expected, actual)) {
                body.fill(0)
                expected.fill(0)
                actual.fill(0)
                return null
            }
            expected.fill(0)
            actual.fill(0)

            return try {
                val input = DataInputStream(ByteArrayInputStream(body))
                if (input.readInt() != MAGIC) return null
                val version = input.readInt()
                if (version != SemanticShardManifestV3PublicationIntent.CURRENT_VERSION) {
                    return null
                }
                val length = input.readInt()
                if (length != 32) return null
                val publicationBytes = ByteArray(length)
                input.readFully(publicationBytes)
                val publicationId = try {
                    publicationBytes.decodeToString(throwOnInvalidSequence = true)
                } finally {
                    publicationBytes.fill(0)
                }
                val intent = SemanticShardManifestV3PublicationIntent(
                    version = version,
                    publicationId = publicationId,
                    manifestSegmentCount = input.readLong()
                )
                if (input.read() != -1) return null
                intent
            } catch (_: Exception) {
                null
            } finally {
                body.fill(0)
            }
        } finally {
            all.fill(0)
        }
    }
}

internal class SemanticShardManifestV3PublicationIntentStore(
    private val storage: AndroidOfflineSemanticShardStorage
) {
    fun read(): SemanticShardManifestV3PublicationIntentReadResult =
        when (val loaded = storage.read(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_PUBLICATION_INTENT)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                SemanticShardManifestV3PublicationIntentReadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                SemanticShardManifestV3PublicationIntentReadResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> {
                val decoded = SemanticShardManifestV3PublicationIntentCodec.decode(loaded.blob)
                if (decoded == null) {
                    SemanticShardManifestV3PublicationIntentReadResult.Corrupt
                } else {
                    SemanticShardManifestV3PublicationIntentReadResult.Loaded(decoded)
                }
            }
        }

    fun recordSegmentBeforeWrite(
        publicationId: String,
        ordinal: Long
    ): Boolean {
        require(SemanticShardManifestRootV3.PUBLICATION_ID.matches(publicationId))
        require(ordinal >= 0L)

        val requiredCount = ordinal + 1L
        val next = when (val current = read()) {
            SemanticShardManifestV3PublicationIntentReadResult.Missing ->
                SemanticShardManifestV3PublicationIntent(
                    publicationId = publicationId,
                    manifestSegmentCount = requiredCount
                )
            SemanticShardManifestV3PublicationIntentReadResult.Corrupt,
            is SemanticShardManifestV3PublicationIntentReadResult.Failed -> return false
            is SemanticShardManifestV3PublicationIntentReadResult.Loaded -> {
                val existing = current.intent
                if (
                    existing.publicationId != publicationId ||
                    requiredCount < existing.manifestSegmentCount ||
                    requiredCount > existing.manifestSegmentCount + 1L
                ) {
                    return false
                }
                if (requiredCount == existing.manifestSegmentCount) {
                    return true
                }
                existing.copy(manifestSegmentCount = requiredCount)
            }
        }

        return storage.write(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_PUBLICATION_INTENT,
            SemanticShardManifestV3PublicationIntentCodec.encode(next)
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written
    }

    fun clear(): Boolean =
        when (storage.delete(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_PUBLICATION_INTENT)) {
            AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
            AndroidOfflineSemanticShardStorageDeleteResult.Missing -> true
            AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
            is AndroidOfflineSemanticShardStorageDeleteResult.Failed -> false
        }
}

internal sealed interface SemanticShardManifestV3PublicationIntentGcResult {
    data object Completed : SemanticShardManifestV3PublicationIntentGcResult
    data object NothingToDo : SemanticShardManifestV3PublicationIntentGcResult
    data object Deferred : SemanticShardManifestV3PublicationIntentGcResult
    data object CorruptIntent : SemanticShardManifestV3PublicationIntentGcResult
}

internal class SemanticShardManifestV3PublicationIntentGarbageCollector(
    private val storage: AndroidOfflineSemanticShardStorage,
    private val manifestStore: SemanticShardManifestV3Store =
        SemanticShardManifestV3Store(storage),
    private val intentStore: SemanticShardManifestV3PublicationIntentStore =
        SemanticShardManifestV3PublicationIntentStore(storage)
) {
    fun resumeIfSafe(): SemanticShardManifestV3PublicationIntentGcResult {
        val intent = when (val loaded = intentStore.read()) {
            SemanticShardManifestV3PublicationIntentReadResult.Missing ->
                return SemanticShardManifestV3PublicationIntentGcResult.NothingToDo
            SemanticShardManifestV3PublicationIntentReadResult.Corrupt ->
                return SemanticShardManifestV3PublicationIntentGcResult.CorruptIntent
            is SemanticShardManifestV3PublicationIntentReadResult.Failed ->
                return SemanticShardManifestV3PublicationIntentGcResult.Deferred
            is SemanticShardManifestV3PublicationIntentReadResult.Loaded -> loaded.intent
        }

        val currentPublication = when (val root = manifestStore.loadRoot()) {
            is SemanticShardManifestRootV3LoadResult.Loaded -> root.root.publicationId
            SemanticShardManifestRootV3LoadResult.Missing -> null
            SemanticShardManifestRootV3LoadResult.Corrupt,
            is SemanticShardManifestRootV3LoadResult.Incompatible,
            is SemanticShardManifestRootV3LoadResult.Failed ->
                return SemanticShardManifestV3PublicationIntentGcResult.Deferred
        }

        if (currentPublication != intent.publicationId) {
            for (ordinal in 0 until intent.manifestSegmentCount) {
                when (
                    storage.delete(
                        AndroidOfflineSemanticShardStorageKey.forManifestSegment(
                            intent.publicationId,
                            ordinal
                        )
                    )
                ) {
                    AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
                    AndroidOfflineSemanticShardStorageDeleteResult.Missing -> Unit
                    AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
                    is AndroidOfflineSemanticShardStorageDeleteResult.Failed ->
                        return SemanticShardManifestV3PublicationIntentGcResult.Deferred
                }
            }
        }

        return if (intentStore.clear()) {
            SemanticShardManifestV3PublicationIntentGcResult.Completed
        } else {
            SemanticShardManifestV3PublicationIntentGcResult.Deferred
        }
    }
}
