package pro.liliya.android.licensestate

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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import pro.liliya.core.license.LicenseServiceDurableBackend
import pro.liliya.core.license.LicenseServiceDurableBackendCommitResult
import pro.liliya.core.license.LicenseServiceDurableBackendLoadResult
import pro.liliya.core.license.LicenseServiceDurableBackendRevision
import pro.liliya.core.license.LicenseServiceDurableExpectedRevision
import pro.liliya.core.license.LicenseServiceDurableStateCodecRejection
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeCanonicalCodec
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeDecodeResult
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopePayload
import pro.liliya.core.license.LicenseServiceDurableStoreId

/**
 * App-private, single-process crash-durable backend for Licensing Service security state.
 *
 * It owns exact revision CAS and atomic publication for one logical [storeId]. It deliberately does
 * not claim multi-process writer safety. All instances for the same canonical published file share
 * one process-local lock.
 */
class AndroidLicenseServiceDurableBackend private constructor(
    private val root: File,
    private val storeId: LicenseServiceDurableStoreId
) : LicenseServiceDurableBackend {
    private val target: File = File(root, fileName(storeId))
    private val lock: Any = processLock(target)

    override fun load(): LicenseServiceDurableBackendLoadResult = synchronized(lock) {
        when (val state = readPublished()) {
            PublishedState.Missing -> LicenseServiceDurableBackendLoadResult.Missing
            is PublishedState.Ready -> LicenseServiceDurableBackendLoadResult.Loaded(
                revision = state.revision,
                envelope = state.payload
            )
            PublishedState.Corrupt -> LicenseServiceDurableBackendLoadResult.Corrupt
            PublishedState.Incompatible -> LicenseServiceDurableBackendLoadResult.Incompatible
            PublishedState.Failed -> LicenseServiceDurableBackendLoadResult.Failed
        }
    }

    override fun commit(
        expectedRevision: LicenseServiceDurableExpectedRevision,
        envelope: LicenseServiceDurableStateEnvelopePayload
    ): LicenseServiceDurableBackendCommitResult = synchronized(lock) {
        val currentRevision = when (val current = readPublished()) {
            PublishedState.Missing -> 0L
            is PublishedState.Ready -> current.revision.value
            PublishedState.Corrupt,
            PublishedState.Incompatible,
            PublishedState.Failed -> return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }

        if (currentRevision != expectedRevision.value) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Conflict
        }
        if (currentRevision == Long.MAX_VALUE) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }

        val nextRevision = currentRevision + 1L
        val decoded = when (val result = LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(envelope)) {
            is LicenseServiceDurableStateEnvelopeDecodeResult.Decoded -> result.envelope
            is LicenseServiceDurableStateEnvelopeDecodeResult.Rejected ->
                return@synchronized if (
                    result.reason == LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION
                ) {
                    LicenseServiceDurableBackendCommitResult.Failed
                } else {
                    LicenseServiceDurableBackendCommitResult.Failed
                }
        }
        if (
            decoded.binding.storeId != storeId ||
            decoded.binding.backendRevision.value != nextRevision
        ) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }

        val encoded = try {
            AndroidLicenseServiceDurableFileCodec.encode(
                revision = nextRevision,
                payload = envelope
            )
        } catch (_: IllegalArgumentException) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }

        val temp = File(root, target.name + TEMP_SUFFIX)
        try {
            try {
                writeSynced(temp, encoded)
            } catch (_: IOException) {
                temp.delete()
                return@synchronized LicenseServiceDurableBackendCommitResult.Failed
            }

            try {
                atomicReplace(temp, target)
            } catch (_: IOException) {
                temp.delete()
                return@synchronized LicenseServiceDurableBackendCommitResult.Uncertain
            }

            syncDirectoryBestEffort()
            LicenseServiceDurableBackendCommitResult.Committed(
                LicenseServiceDurableBackendRevision(nextRevision)
            )
        } finally {
            encoded.fill(0)
            temp.delete()
        }
    }

    private fun readPublished(): PublishedState {
        if (!target.exists()) return PublishedState.Missing
        if (!target.isFile) return PublishedState.Corrupt
        if (target.length() <= 0L || target.length() > AndroidLicenseServiceDurableFileCodec.MAX_FILE_BYTES) {
            return PublishedState.Corrupt
        }

        val bytes = try {
            target.readBytes()
        } catch (_: IOException) {
            return PublishedState.Failed
        } catch (_: SecurityException) {
            return PublishedState.Failed
        }

        return try {
            when (val decoded = AndroidLicenseServiceDurableFileCodec.decode(bytes)) {
                is AndroidLicenseServiceDurableFileCodec.DecodeResult.Decoded -> {
                    val envelope = when (
                        val canonical = LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(decoded.payload)
                    ) {
                        is LicenseServiceDurableStateEnvelopeDecodeResult.Decoded -> canonical.envelope
                        is LicenseServiceDurableStateEnvelopeDecodeResult.Rejected -> {
                            return if (
                                canonical.reason == LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION
                            ) {
                                PublishedState.Incompatible
                            } else {
                                PublishedState.Corrupt
                            }
                        }
                    }
                    if (
                        envelope.binding.storeId != storeId ||
                        envelope.binding.backendRevision != decoded.revision
                    ) {
                        PublishedState.Corrupt
                    } else {
                        PublishedState.Ready(decoded.revision, decoded.payload)
                    }
                }
                AndroidLicenseServiceDurableFileCodec.DecodeResult.Corrupt -> PublishedState.Corrupt
                AndroidLicenseServiceDurableFileCodec.DecodeResult.Incompatible -> PublishedState.Incompatible
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("atomic licensing-state publication unavailable", e)
        }
    }

    private fun syncDirectoryBestEffort() {
        try {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (_: IOException) {
            // File bytes were fsynced before atomic publication.
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is not exposed uniformly across Android/filesystem combinations.
        }
    }

    internal fun publishedFileForTest(): File = target

    private sealed interface PublishedState {
        data object Missing : PublishedState
        data class Ready(
            val revision: LicenseServiceDurableBackendRevision,
            val payload: LicenseServiceDurableStateEnvelopePayload
        ) : PublishedState
        data object Corrupt : PublishedState
        data object Incompatible : PublishedState
        data object Failed : PublishedState
    }

    companion object {
        private const val DEFAULT_DIRECTORY = "liliya-license-service-state-v1"
        private const val FILE_SUFFIX = ".lls"
        private const val TEMP_SUFFIX = ".tmp"
        private val PROCESS_LOCKS = ConcurrentHashMap<String, Any>()

        fun create(
            context: Context,
            storeId: LicenseServiceDurableStoreId,
            directoryName: String = DEFAULT_DIRECTORY
        ): AndroidLicenseServiceDurableBackend {
            require(directoryName.isNotBlank()) { "licensing state directory name must not be blank" }
            require(!directoryName.contains('/') && !directoryName.contains('\\')) {
                "licensing state directory must be one app-private segment"
            }
            val appRoot = context.applicationContext.filesDir.canonicalFile
            val root = File(appRoot, directoryName).canonicalFile
            require(root.parentFile == appRoot) {
                "licensing state root must be directly inside app-private files"
            }
            if (!root.exists()) check(root.mkdirs()) {
                "licensing state root could not be created"
            }
            check(root.isDirectory) { "licensing state root is not a directory" }
            return AndroidLicenseServiceDurableBackend(root, storeId)
        }

        private fun fileName(storeId: LicenseServiceDurableStoreId): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(storeId.value.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { "%02x".format(it) } + FILE_SUFFIX
        }

        private fun processLock(target: File): Any =
            PROCESS_LOCKS.computeIfAbsent(target.canonicalPath) { Any() }
    }
}

internal object AndroidLicenseServiceDurableFileCodec {
    const val MAX_FILE_BYTES = 1_081_344L
    private const val MAGIC = 0x4C4C5331 // LLS1
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val HEADER_BYTES = Int.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES

    sealed interface DecodeResult {
        data class Decoded(
            val revision: LicenseServiceDurableBackendRevision,
            val payload: LicenseServiceDurableStateEnvelopePayload
        ) : DecodeResult
        data object Corrupt : DecodeResult
        data object Incompatible : DecodeResult
    }

    fun encode(
        revision: Long,
        payload: LicenseServiceDurableStateEnvelopePayload
    ): ByteArray {
        require(revision > 0L)
        val body = payload.copyBytes()
        try {
            require(body.isNotEmpty())
            require(HEADER_BYTES.toLong() + body.size + DIGEST_BYTES <= MAX_FILE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256").digest(body)
            return ByteArrayOutputStream(HEADER_BYTES + body.size + DIGEST_BYTES).use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(MAGIC)
                    data.writeInt(VERSION)
                    data.writeLong(revision)
                    data.writeInt(body.size)
                    data.write(body)
                    data.write(digest)
                }
                digest.fill(0)
                output.toByteArray()
            }
        } finally {
            body.fill(0)
        }
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_FILE_BYTES) return DecodeResult.Corrupt
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) return DecodeResult.Corrupt
            if (input.readInt() != VERSION) return DecodeResult.Incompatible
            val revisionValue = input.readLong()
            if (revisionValue <= 0L) return DecodeResult.Corrupt
            val size = input.readInt()
            if (size <= 0 || size > bytes.size - HEADER_BYTES - DIGEST_BYTES) {
                return DecodeResult.Corrupt
            }
            if (bytes.size != HEADER_BYTES + size + DIGEST_BYTES) return DecodeResult.Corrupt
            val body = ByteArray(size)
            input.readFully(body)
            val expected = ByteArray(DIGEST_BYTES)
            input.readFully(expected)
            if (input.read() != -1) return DecodeResult.Corrupt
            val actual = MessageDigest.getInstance("SHA-256").digest(body)
            if (!MessageDigest.isEqual(expected, actual)) {
                body.fill(0)
                expected.fill(0)
                actual.fill(0)
                return DecodeResult.Corrupt
            }
            expected.fill(0)
            actual.fill(0)
            val payload = LicenseServiceDurableStateEnvelopePayload.of(body)
            body.fill(0)
            DecodeResult.Decoded(
                LicenseServiceDurableBackendRevision(revisionValue),
                payload
            )
        } catch (_: EOFException) {
            DecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            DecodeResult.Corrupt
        } catch (_: IOException) {
            DecodeResult.Corrupt
        }
    }
}
