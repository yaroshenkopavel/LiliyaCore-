package pro.liliya.app

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
import pro.liliya.core.license.LicenseServiceDurableBackend
import pro.liliya.core.license.LicenseServiceDurableBackendCommitResult
import pro.liliya.core.license.LicenseServiceDurableBackendLoadResult
import pro.liliya.core.license.LicenseServiceDurableBackendRevision
import pro.liliya.core.license.LicenseServiceDurableExpectedRevision
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeDecodeResult
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeCanonicalCodec
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopePayload

/**
 * App-private CAS backend for authenticated Licensing Service security state.
 *
 * Backend != License entitlement.
 * Backend != request authentication.
 * Backend != protector/key ownership.
 *
 * The file is kept under noBackupFilesDir so Android backup/restore cannot transplant an older
 * revocation floor into another installation. Exact state authenticity remains owned by the
 * dedicated Keystore protector and the Core durable coordinator.
 */
internal class ProductionAndroidLicenseServiceDurableBackend private constructor(
    private val root: File
) : LicenseServiceDurableBackend {
    private val target = File(root, FILE_NAME)
    private val lock = Any()

    override fun load(): LicenseServiceDurableBackendLoadResult = synchronized(lock) {
        readPublished()
    }

    override fun commit(
        expectedRevision: LicenseServiceDurableExpectedRevision,
        envelope: LicenseServiceDurableStateEnvelopePayload
    ): LicenseServiceDurableBackendCommitResult = synchronized(lock) {
        val currentRevision = when (val current = readPublished()) {
            LicenseServiceDurableBackendLoadResult.Missing -> 0L
            is LicenseServiceDurableBackendLoadResult.Loaded -> current.revision.value
            LicenseServiceDurableBackendLoadResult.Corrupt,
            LicenseServiceDurableBackendLoadResult.Incompatible,
            LicenseServiceDurableBackendLoadResult.Failed ->
                return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }
        if (currentRevision != expectedRevision.value) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Conflict
        }

        val candidateRevision = when (
            val decoded = LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(envelope)
        ) {
            is LicenseServiceDurableStateEnvelopeDecodeResult.Decoded ->
                decoded.envelope.binding.backendRevision
            is LicenseServiceDurableStateEnvelopeDecodeResult.Rejected ->
                return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }
        if (
            candidateRevision.value <= expectedRevision.value ||
            candidateRevision.value != expectedRevision.value + 1L
        ) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }

        val encoded = try {
            encode(candidateRevision, envelope)
        } catch (_: Throwable) {
            return@synchronized LicenseServiceDurableBackendCommitResult.Failed
        }
        val temp = File(root, TEMP_NAME)
        try {
            writeSynced(temp, encoded)
            atomicReplace(temp, target)
            syncDirectoryBestEffort()
            LicenseServiceDurableBackendCommitResult.Committed(candidateRevision)
        } catch (_: IOException) {
            LicenseServiceDurableBackendCommitResult.Failed
        } catch (_: SecurityException) {
            LicenseServiceDurableBackendCommitResult.Failed
        } finally {
            encoded.fill(0)
            temp.delete()
        }
    }

    private fun readPublished(): LicenseServiceDurableBackendLoadResult {
        if (!target.exists()) return LicenseServiceDurableBackendLoadResult.Missing
        if (!target.isFile) return LicenseServiceDurableBackendLoadResult.Corrupt
        if (target.length() !in MIN_FILE_BYTES.toLong()..MAX_FILE_BYTES.toLong()) {
            return LicenseServiceDurableBackendLoadResult.Corrupt
        }

        val bytes = try {
            target.readBytes()
        } catch (_: IOException) {
            return LicenseServiceDurableBackendLoadResult.Failed
        } catch (_: SecurityException) {
            return LicenseServiceDurableBackendLoadResult.Corrupt
        }
        return try {
            decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun encode(
        revision: LicenseServiceDurableBackendRevision,
        envelope: LicenseServiceDurableStateEnvelopePayload
    ): ByteArray {
        val payload = envelope.copyBytes()
        try {
            require(payload.isNotEmpty() && payload.size <= MAX_ENVELOPE_BYTES)
            return ByteArrayOutputStream(HEADER_BYTES + payload.size).use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(MAGIC)
                    data.writeInt(VERSION)
                    data.writeLong(revision.value)
                    data.writeInt(payload.size)
                    data.write(payload)
                }
                output.toByteArray()
            }
        } finally {
            payload.fill(0)
        }
    }

    private fun decode(bytes: ByteArray): LicenseServiceDurableBackendLoadResult = try {
        val input = ByteArrayInputStream(bytes)
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) {
            return LicenseServiceDurableBackendLoadResult.Corrupt
        }
        if (data.readInt() != VERSION) {
            return LicenseServiceDurableBackendLoadResult.Incompatible
        }
        val revision = LicenseServiceDurableBackendRevision(data.readLong())
        val length = data.readInt()
        if (length !in 1..MAX_ENVELOPE_BYTES || length != input.available()) {
            return LicenseServiceDurableBackendLoadResult.Corrupt
        }
        val payload = ByteArray(length)
        data.readFully(payload)
        if (input.available() != 0) {
            payload.fill(0)
            return LicenseServiceDurableBackendLoadResult.Corrupt
        }
        try {
            LicenseServiceDurableBackendLoadResult.Loaded(
                revision = revision,
                envelope = LicenseServiceDurableStateEnvelopePayload.of(payload)
            )
        } finally {
            payload.fill(0)
        }
    } catch (_: EOFException) {
        LicenseServiceDurableBackendLoadResult.Corrupt
    } catch (_: IllegalArgumentException) {
        LicenseServiceDurableBackendLoadResult.Corrupt
    } catch (_: RuntimeException) {
        LicenseServiceDurableBackendLoadResult.Corrupt
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
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException("atomic license-service state publication unavailable", failure)
        }
    }

    private fun syncDirectoryBestEffort() {
        try {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            Unit
        } catch (_: UnsupportedOperationException) {
            Unit
        }
    }

    companion object {
        private const val DIRECTORY = "liliya-license-service-state-v1"
        private const val FILE_NAME = "state.lss"
        private const val TEMP_NAME = "state.lss.tmp"
        private const val MAGIC = 0x4C535331 // LSS1
        private const val VERSION = 1
        private const val HEADER_BYTES = 20
        private const val MIN_FILE_BYTES = HEADER_BYTES + 1
        private const val MAX_ENVELOPE_BYTES = 1_064_960
        private const val MAX_FILE_BYTES = HEADER_BYTES + MAX_ENVELOPE_BYTES

        fun create(context: Context): ProductionAndroidLicenseServiceDurableBackend {
            val appRoot = context.applicationContext.noBackupFilesDir.canonicalFile
            val root = File(appRoot, DIRECTORY).canonicalFile
            require(root.parentFile == appRoot) {
                "license-service durable root must be directly inside no-backup app storage"
            }
            if (!root.exists()) check(root.mkdirs()) {
                "license-service durable root could not be created"
            }
            check(root.isDirectory) {
                "license-service durable root is not a directory"
            }
            return ProductionAndroidLicenseServiceDurableBackend(root)
        }
    }
}
