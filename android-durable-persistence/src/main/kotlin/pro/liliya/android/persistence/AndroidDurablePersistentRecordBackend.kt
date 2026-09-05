package pro.liliya.android.persistence

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

/**
 * App-private, crash-durable Android backend for the frozen Core PersistentRecordBackend contract.
 *
 * Record payload confidentiality remains owned by the existing Cognitive encryption layer.
 * This backend does not claim full metadata-at-rest encryption.
 */
class AndroidDurablePersistentRecordBackend private constructor(
    private val root: File
) : PersistentRecordBackend {

    override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
        synchronized(this) {
            when (val loaded = readPublished(storeId)) {
                PublishedState.Missing -> PersistentBackendLoadResult.Missing
                is PublishedState.Ready -> PersistentBackendLoadResult.Loaded(
                    revision = loaded.revision,
                    state = loaded.state
                )
                PublishedState.Corrupt -> PersistentBackendLoadResult.Corrupt
                PublishedState.Incompatible -> PersistentBackendLoadResult.Incompatible(
                    "unsupported durable persistence format"
                )
                is PublishedState.Failed -> PersistentBackendLoadResult.Failed(
                    "durable persistence load failed",
                    loaded.throwable
                )
            }
        }

    override fun commit(
        storeId: PersistentStoreId,
        expectedRevision: Long,
        state: PersistentBackendState
    ): PersistentBackendCommitResult = synchronized(this) {
        if (expectedRevision < 0L || state.storeId != storeId) {
            return@synchronized PersistentBackendCommitResult.Failed(
                "durable persistence commit rejected"
            )
        }

        val currentRevision = when (val current = readPublished(storeId)) {
            PublishedState.Missing -> 0L
            is PublishedState.Ready -> current.revision
            PublishedState.Corrupt,
            PublishedState.Incompatible -> return@synchronized PersistentBackendCommitResult.Failed(
                "durable persistence current state is not writable"
            )
            is PublishedState.Failed -> return@synchronized PersistentBackendCommitResult.Failed(
                "durable persistence current state could not be read",
                current.throwable
            )
        }
        if (currentRevision != expectedRevision) {
            return@synchronized PersistentBackendCommitResult.Conflict
        }
        if (currentRevision == Long.MAX_VALUE) {
            return@synchronized PersistentBackendCommitResult.Failed(
                "durable persistence revision overflow"
            )
        }

        val nextRevision = currentRevision + 1L
        val encoded = try {
            AndroidPersistentStateCodec.encode(nextRevision, state)
        } catch (e: IllegalArgumentException) {
            return@synchronized PersistentBackendCommitResult.Failed(
                "durable persistence state rejected",
                e
            )
        }

        val target = publishedFile(storeId)
        val temp = File(root, target.name + TEMP_SUFFIX)
        return@synchronized try {
            writeSynced(temp, encoded)
            atomicReplace(temp, target)
            syncDirectoryBestEffort()
            PersistentBackendCommitResult.Committed(nextRevision)
        } catch (e: IOException) {
            temp.delete()
            PersistentBackendCommitResult.Failed(
                "durable persistence commit failed",
                e
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun readPublished(storeId: PersistentStoreId): PublishedState {
        val target = publishedFile(storeId)
        if (!target.exists()) return PublishedState.Missing
        if (!target.isFile) return PublishedState.Corrupt
        if (target.length() <= 0L || target.length() > AndroidPersistentStateCodec.MAX_FILE_BYTES) {
            return PublishedState.Corrupt
        }

        return try {
            when (val decoded = AndroidPersistentStateCodec.decode(target.readBytes())) {
                is AndroidPersistentStateCodec.DecodeResult.Decoded -> {
                    if (decoded.state.storeId != storeId) PublishedState.Corrupt
                    else PublishedState.Ready(decoded.revision, decoded.state)
                }
                AndroidPersistentStateCodec.DecodeResult.Corrupt -> PublishedState.Corrupt
                AndroidPersistentStateCodec.DecodeResult.Incompatible -> PublishedState.Incompatible
            }
        } catch (e: IOException) {
            PublishedState.Failed(e)
        } catch (_: SecurityException) {
            PublishedState.Corrupt
        }
    }

    private fun publishedFile(storeId: PersistentStoreId): File =
        File(root, namespaceDigest(storeId.value) + FILE_SUFFIX)

    private fun namespaceDigest(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("atomic durable persistence publication is unavailable", e)
        }
    }

    private fun syncDirectoryBestEffort() {
        try {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (_: IOException) {
            // Android/filesystem combinations may not expose directory fsync through java.nio.
            // The file itself has already been fsynced and publication was atomic.
        } catch (_: UnsupportedOperationException) {
            // Same bounded best-effort rule as above.
        }
    }

    private sealed interface PublishedState {
        data object Missing : PublishedState
        data class Ready(
            val revision: Long,
            val state: PersistentBackendState
        ) : PublishedState
        data object Corrupt : PublishedState
        data object Incompatible : PublishedState
        data class Failed(val throwable: Throwable) : PublishedState
    }

    companion object {
        private const val DEFAULT_DIRECTORY = "liliya-durable-persistence-v1"
        private const val FILE_SUFFIX = ".lpr"
        private const val TEMP_SUFFIX = ".tmp"

        fun create(
            context: Context,
            directoryName: String = DEFAULT_DIRECTORY
        ): AndroidDurablePersistentRecordBackend {
            require(directoryName.isNotBlank()) { "durable persistence directory name must not be blank" }
            require(!directoryName.contains('/') && !directoryName.contains('\\')) {
                "durable persistence directory name must be a single app-private segment"
            }

            val appRoot = context.applicationContext.filesDir.canonicalFile
            val root = File(appRoot, directoryName).canonicalFile
            require(root.parentFile == appRoot) {
                "durable persistence root must be directly inside app-private files"
            }
            if (!root.exists()) check(root.mkdirs()) {
                "durable persistence app-private root could not be created"
            }
            check(root.isDirectory) {
                "durable persistence app-private root is not a directory"
            }
            return AndroidDurablePersistentRecordBackend(root)
        }
    }
}

internal object AndroidPersistentStateCodec {
    const val MAX_FILE_BYTES = 72L * 1024L * 1024L
    private const val MAGIC = 0x4C445031 // LDP1
    private const val FORMAT_VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val MAX_ENTRIES = 25_000
    private const val MAX_IDENTIFIER_BYTES = 1_024
    private const val MAX_PAYLOAD_BYTES = 1 * 1024 * 1024
    private const val MAX_TOTAL_PAYLOAD_BYTES = 64L * 1024L * 1024L

    sealed interface DecodeResult {
        data class Decoded(
            val revision: Long,
            val state: PersistentBackendState
        ) : DecodeResult
        data object Corrupt : DecodeResult
        data object Incompatible : DecodeResult
    }

    fun encode(revision: Long, state: PersistentBackendState): ByteArray {
        require(revision > 0L) { "durable persistence revision must be positive" }
        require(state.highWatermark >= 0L) { "durable persistence high watermark must not be negative" }
        require(state.entries.size <= MAX_ENTRIES) { "durable persistence entry count exceeds limit" }

        var totalPayload = 0L
        val bodyBuffer = ByteArrayOutputStream()
        DataOutputStream(bodyBuffer).use { out ->
            writeString(out, state.storeId.value)
            out.writeLong(state.highWatermark)
            out.writeInt(state.entries.size)
            state.entries.entries
                .sortedBy { it.key.value }
                .forEach { (entityId, backendEntry) ->
                    val record = backendEntry.record
                    require(record.id == entityId) {
                        "durable persistence map key and record id differ"
                    }
                    writeString(out, entityId.value)
                    out.writeLong(backendEntry.generation.value)
                    writeString(out, record.schemaId.value)
                    out.writeInt(record.schemaVersion.value)
                    out.writeLong(record.createdAt.epochSecond)
                    out.writeInt(record.createdAt.nano)
                    val payload = record.payload.copyBytes()
                    try {
                        require(payload.size <= MAX_PAYLOAD_BYTES) {
                            "durable persistence payload exceeds per-entry limit"
                        }
                        totalPayload += payload.size.toLong()
                        require(totalPayload <= MAX_TOTAL_PAYLOAD_BYTES) {
                            "durable persistence total payload exceeds limit"
                        }
                        out.writeInt(payload.size)
                        out.write(payload)
                    } finally {
                        payload.fill(0)
                    }
                }
        }
        val body = bodyBuffer.toByteArray()
        require(body.size.toLong() + HEADER_BYTES + DIGEST_BYTES <= MAX_FILE_BYTES) {
            "durable persistence encoded state exceeds file limit"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return ByteArrayOutputStream().use { whole ->
            DataOutputStream(whole).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeLong(revision)
                out.writeInt(body.size)
                out.write(body)
                out.write(digest)
            }
            body.fill(0)
            digest.fill(0)
            whole.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_FILE_BYTES) return DecodeResult.Corrupt
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) return DecodeResult.Corrupt
            val version = input.readInt()
            if (version != FORMAT_VERSION) return DecodeResult.Incompatible
            val revision = input.readLong()
            if (revision <= 0L) return DecodeResult.Corrupt
            val bodyLength = input.readInt()
            if (bodyLength <= 0 || bodyLength > bytes.size - HEADER_BYTES - DIGEST_BYTES) {
                return DecodeResult.Corrupt
            }
            if (bytes.size != HEADER_BYTES + bodyLength + DIGEST_BYTES) {
                return DecodeResult.Corrupt
            }

            val body = ByteArray(bodyLength)
            input.readFully(body)
            val expectedDigest = ByteArray(DIGEST_BYTES)
            input.readFully(expectedDigest)
            if (input.read() != -1) return DecodeResult.Corrupt
            val actualDigest = MessageDigest.getInstance("SHA-256").digest(body)
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                body.fill(0)
                expectedDigest.fill(0)
                actualDigest.fill(0)
                return DecodeResult.Corrupt
            }
            expectedDigest.fill(0)
            actualDigest.fill(0)

            val state = decodeBody(body) ?: return DecodeResult.Corrupt
            body.fill(0)
            DecodeResult.Decoded(revision, state)
        } catch (_: EOFException) {
            DecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            DecodeResult.Corrupt
        } catch (_: IOException) {
            DecodeResult.Corrupt
        }
    }

    private fun decodeBody(body: ByteArray): PersistentBackendState? {
        val input = DataInputStream(ByteArrayInputStream(body))
        val storeId = PersistentStoreId(readString(input) ?: return null)
        val highWatermark = input.readLong()
        if (highWatermark < 0L) return null
        val count = input.readInt()
        if (count < 0 || count > MAX_ENTRIES) return null

        val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>(count)
        var totalPayload = 0L
        repeat(count) {
            val entityId = PersistentEntityId(readString(input) ?: return null)
            if (entries.containsKey(entityId)) return null
            val generationValue = input.readLong()
            if (generationValue <= 0L) return null
            val schemaId = PersistentSchemaId(readString(input) ?: return null)
            val schemaVersionValue = input.readInt()
            if (schemaVersionValue <= 0) return null
            val epochSecond = input.readLong()
            val nano = input.readInt()
            val createdAt = try {
                Instant.ofEpochSecond(epochSecond, nano.toLong())
            } catch (_: RuntimeException) {
                return null
            }
            val payloadSize = input.readInt()
            if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_BYTES) return null
            totalPayload += payloadSize.toLong()
            if (totalPayload > MAX_TOTAL_PAYLOAD_BYTES) return null
            val payload = ByteArray(payloadSize)
            input.readFully(payload)

            val record = PersistentRecord(
                id = entityId,
                schemaId = schemaId,
                schemaVersion = PersistentSchemaVersion(schemaVersionValue),
                payload = PersistentPayload(payload),
                createdAt = createdAt
            )
            payload.fill(0)
            entries[entityId] = PersistentBackendEntry(
                generation = PersistentGeneration(generationValue),
                record = record
            )
        }
        if (input.read() != -1) return null
        if (entries.values.any { it.generation.value > highWatermark }) return null
        return PersistentBackendState(
            storeId = storeId,
            highWatermark = highWatermark,
            entries = entries.toMap()
        )
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_IDENTIFIER_BYTES) {
            "durable persistence identifier exceeds limit"
        }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String? {
        val size = input.readInt()
        if (size <= 0 || size > MAX_IDENTIFIER_BYTES) return null
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val value = bytes.toString(StandardCharsets.UTF_8)
        bytes.fill(0)
        if (value.isBlank()) return null
        return value
    }

    private const val HEADER_BYTES = Int.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES
}
