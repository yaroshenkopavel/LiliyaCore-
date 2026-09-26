package pro.liliya.android.semanticprovider

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal enum class SemanticShardManifestV3GcPhase {
    PREPARED,
    SHARDS_RECLAIMED
}

internal data class SemanticShardManifestV3GcJournal(
    val previousRoot: SemanticShardManifestRootV3,
    val targetRoot: SemanticShardManifestRootV3,
    val phase: SemanticShardManifestV3GcPhase = SemanticShardManifestV3GcPhase.PREPARED
) {
    init {
        require(previousRoot.publicationId != targetRoot.publicationId)
    }
}

internal sealed interface SemanticShardManifestV3GcJournalReadResult {
    data object Missing : SemanticShardManifestV3GcJournalReadResult
    data class Loaded(val journal: SemanticShardManifestV3GcJournal) :
        SemanticShardManifestV3GcJournalReadResult
    data object Corrupt : SemanticShardManifestV3GcJournalReadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardManifestV3GcJournalReadResult
}

internal class SemanticShardManifestV3GcJournalStore(
    private val storage: AndroidOfflineSemanticShardStorage
) {
    fun read(): SemanticShardManifestV3GcJournalReadResult {
        val blob = when (val read = storage.read(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_GC)) {
            AndroidOfflineSemanticShardStorageReadResult.Missing ->
                return SemanticShardManifestV3GcJournalReadResult.Missing
            is AndroidOfflineSemanticShardStorageReadResult.Failed ->
                return SemanticShardManifestV3GcJournalReadResult.Failed(read.reason, read.throwable)
            is AndroidOfflineSemanticShardStorageReadResult.Loaded -> read.blob
        }
        return when (val decoded = SemanticShardManifestV3GcJournalCodec.decode(blob)) {
            null -> SemanticShardManifestV3GcJournalReadResult.Corrupt
            else -> SemanticShardManifestV3GcJournalReadResult.Loaded(decoded)
        }
    }

    fun write(journal: SemanticShardManifestV3GcJournal): Boolean =
        storage.write(
            AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_GC,
            SemanticShardManifestV3GcJournalCodec.encode(journal)
        ) == AndroidOfflineSemanticShardStorageWriteResult.Written

    fun clear(): Boolean = when (
        storage.delete(AndroidOfflineSemanticShardStorageKey.MANIFEST_V3_GC)
    ) {
        AndroidOfflineSemanticShardStorageDeleteResult.Deleted,
        AndroidOfflineSemanticShardStorageDeleteResult.Missing -> true
        AndroidOfflineSemanticShardStorageDeleteResult.Unsupported,
        is AndroidOfflineSemanticShardStorageDeleteResult.Failed -> false
    }
}

internal object SemanticShardManifestV3GcJournalCodec {
    private const val MAGIC = 0x4C534A33 // LSJ3
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val MAX_ROOT_BYTES = 16_384

    fun encode(journal: SemanticShardManifestV3GcJournal): AndroidOfflineSemanticCheckpointBlob {
        val rootBlob = SemanticShardManifestV3Codec.encodeRoot(journal.previousRoot)
        val rootBytes = rootBlob.copyBytes()
        require(rootBytes.size in 1..MAX_ROOT_BYTES)
        val body = try {
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    out.writeInt(journal.phase.ordinal)
                    val targetBlob = SemanticShardManifestV3Codec.encodeRoot(journal.targetRoot)
                    val targetBytes = targetBlob.copyBytes()
                    try {
                        require(targetBytes.size in 1..MAX_ROOT_BYTES)
                        out.writeInt(rootBytes.size)
                        out.write(rootBytes)
                        out.writeInt(targetBytes.size)
                        out.write(targetBytes)
                    } finally {
                        targetBytes.fill(0)
                    }
                }
                buffer.toByteArray()
            }
        } finally {
            rootBytes.fill(0)
        }
        return wrap(body)
    }

    fun decode(blob: AndroidOfflineSemanticCheckpointBlob): SemanticShardManifestV3GcJournal? {
        val body = unwrap(blob) ?: return null
        try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            val phaseOrdinal = input.readInt()
            val phase = SemanticShardManifestV3GcPhase.entries.getOrNull(phaseOrdinal)
                ?: return null
            val previousSize = input.readInt()
            if (previousSize !in 1..MAX_ROOT_BYTES) return null
            val previousBytes = ByteArray(previousSize)
            input.readFully(previousBytes)
            val targetSize = input.readInt()
            if (targetSize !in 1..MAX_ROOT_BYTES) {
                previousBytes.fill(0)
                return null
            }
            val targetBytes = ByteArray(targetSize)
            input.readFully(targetBytes)
            if (input.read() != -1) {
                previousBytes.fill(0)
                targetBytes.fill(0)
                return null
            }

            val previousBlob = try {
                AndroidOfflineSemanticCheckpointBlob(previousBytes)
            } finally {
                previousBytes.fill(0)
            }
            val targetBlob = try {
                AndroidOfflineSemanticCheckpointBlob(targetBytes)
            } finally {
                targetBytes.fill(0)
            }
            val previous = when (val decoded = SemanticShardManifestV3Codec.decodeRoot(previousBlob)) {
                is SemanticShardManifestRootV3DecodeResult.Decoded -> decoded.root
                SemanticShardManifestRootV3DecodeResult.Corrupt,
                is SemanticShardManifestRootV3DecodeResult.Incompatible -> return null
            }
            val target = when (val decoded = SemanticShardManifestV3Codec.decodeRoot(targetBlob)) {
                is SemanticShardManifestRootV3DecodeResult.Decoded -> decoded.root
                SemanticShardManifestRootV3DecodeResult.Corrupt,
                is SemanticShardManifestRootV3DecodeResult.Incompatible -> return null
            }
            return try {
                SemanticShardManifestV3GcJournal(previous, target, phase)
            } catch (_: IllegalArgumentException) {
                null
            }
        } catch (_: Exception) {
            return null
        } finally {
            body.fill(0)
        }
    }

    private fun wrap(body: ByteArray): AndroidOfflineSemanticCheckpointBlob {
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

    private fun unwrap(blob: AndroidOfflineSemanticCheckpointBlob): ByteArray? {
        val all = blob.copyBytes()
        try {
            if (all.size <= DIGEST_BYTES) return null
            val bodySize = all.size - DIGEST_BYTES
            val body = all.copyOfRange(0, bodySize)
            val expected = all.copyOfRange(bodySize, all.size)
            val actual = MessageDigest.getInstance("SHA-256").digest(body)
            return try {
                if (MessageDigest.isEqual(expected, actual)) body else {
                    body.fill(0)
                    null
                }
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
        } finally {
            all.fill(0)
        }
    }
}
