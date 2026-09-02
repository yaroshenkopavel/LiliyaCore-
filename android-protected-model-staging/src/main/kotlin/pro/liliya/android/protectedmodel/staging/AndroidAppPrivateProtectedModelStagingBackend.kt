package pro.liliya.android.protectedmodel.staging

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import pro.liliya.core.protectedmodel.LargeProtectedModelOpaqueArtifactId
import pro.liliya.core.protectedmodel.LargeProtectedModelSealedArtifactCandidate
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackend
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackendFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackendId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDurabilityLevel
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelWorkingArtifactHandle

data class AndroidProtectedModelStagingPolicy(
    val freeSpaceReserveBytes: Long
) {
    init {
        require(freeSpaceReserveBytes >= 0L) {
            "free-space reserve must not be negative"
        }
    }
}

/**
 * Android app-private implementation of the frozen Core protected-model staging backend.
 *
 * Physical paths never cross this adapter boundary. A successful seal means the current
 * file was flushed, file-data sync succeeded, the stream closed, a fresh final name was
 * exclusively reserved, and a same-root ATOMIC_MOVE succeeded over that adapter-owned
 * reservation. Directory metadata sync and power-loss durability are deliberately not claimed.
 */
class AndroidAppPrivateProtectedModelStagingBackend private constructor(
    context: Context,
    private val policy: AndroidProtectedModelStagingPolicy,
    private val tokenSource: TokenSource
) : LargeProtectedModelStagingBackend {

    constructor(
        context: Context,
        policy: AndroidProtectedModelStagingPolicy
    ) : this(
        context = context.applicationContext,
        policy = policy,
        tokenSource = SecureTokenSource()
    )

    internal constructor(
        context: Context,
        policy: AndroidProtectedModelStagingPolicy,
        tokenSource: () -> String
    ) : this(
        context = context.applicationContext,
        policy = policy,
        tokenSource = LambdaTokenSource(tokenSource)
    )

    override val backendId: LargeProtectedModelStagingBackendId =
        LargeProtectedModelStagingBackendId(BACKEND_ID)

    private val lock = Any()
    private val appFilesRoot = context.filesDir.toPath().toAbsolutePath().normalize()
    private val adapterRoot = appFilesRoot.resolve(ROOT_DIRECTORY).normalize()
    private val workingRoot = adapterRoot.resolve(WORKING_DIRECTORY).normalize()
    private val sealedRoot = adapterRoot.resolve(SEALED_DIRECTORY).normalize()
    private val records = mutableMapOf<LargeProtectedModelOpaqueArtifactId, PhysicalRecord>()

    override fun prepare(
        attempt: LargeProtectedModelStagingAttemptReference,
        expectedPlaintextBytes: Long
    ): LargeProtectedModelStagingPrepareResult = synchronized(lock) {
        if (expectedPlaintextBytes <= 0L) {
            return@synchronized LargeProtectedModelStagingPrepareResult.Rejected()
        }

        val requiredBytes = try {
            Math.addExact(expectedPlaintextBytes, policy.freeSpaceReserveBytes)
        } catch (_: ArithmeticException) {
            return@synchronized LargeProtectedModelStagingPrepareResult.Rejected()
        }

        if (appFilesRoot.toFile().usableSpace < requiredBytes) {
            return@synchronized LargeProtectedModelStagingPrepareResult.Rejected()
        }

        var createdWorking: java.nio.file.Path? = null
        try {
            ensurePrivateRoots()
            val token = tokenSource.next()
            if (!isValidToken(token)) {
                return@synchronized LargeProtectedModelStagingPrepareResult.Rejected()
            }

            val artifactId = LargeProtectedModelOpaqueArtifactId(token)
            if (records.containsKey(artifactId)) {
                return@synchronized LargeProtectedModelStagingPrepareResult.Rejected()
            }

            val working = childOf(workingRoot, token)
            val sealed = childOf(sealedRoot, token)
            if (
                Files.exists(working, LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(sealed, LinkOption.NOFOLLOW_LINKS)
            ) {
                return@synchronized LargeProtectedModelStagingPrepareResult.Rejected()
            }

            Files.createFile(working)
            createdWorking = working
            val handle = LargeProtectedModelWorkingArtifactHandle(
                backendId = backendId,
                attempt = attempt,
                artifactId = artifactId
            )
            records[artifactId] = PhysicalRecord(
                handle = handle,
                path = working.toFile(),
                expectedPlaintextBytes = expectedPlaintextBytes,
                appendedBytes = 0L,
                state = PhysicalState.WORKING
            )
            LargeProtectedModelStagingPrepareResult.Prepared(handle)
        } catch (throwable: Throwable) {
            createdWorking?.let { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: Throwable) {
                    // The original provider failure remains authoritative. No success is reported.
                }
            }
            LargeProtectedModelStagingPrepareResult.Failed(
                reason = LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
                throwable = throwable
            )
        }
    }

    override fun append(
        handle: LargeProtectedModelWorkingArtifactHandle,
        segmentIndex: Int,
        plaintext: ByteArray
    ): LargeProtectedModelStagingAppendBackendResult = synchronized(lock) {
        val record = records[handle.artifactId]
            ?: return@synchronized LargeProtectedModelStagingAppendBackendResult.Rejected()
        if (
            record.handle != handle ||
            record.state != PhysicalState.WORKING ||
            plaintext.isEmpty() ||
            segmentIndex < 0
        ) {
            return@synchronized LargeProtectedModelStagingAppendBackendResult.Rejected()
        }

        val path = record.path.toPath().toAbsolutePath().normalize()
        if (path.parent != workingRoot || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            record.state = PhysicalState.POISONED
            return@synchronized LargeProtectedModelStagingAppendBackendResult.Rejected()
        }

        val expectedAfter = try {
            Math.addExact(record.appendedBytes, plaintext.size.toLong())
        } catch (_: ArithmeticException) {
            record.state = PhysicalState.POISONED
            return@synchronized LargeProtectedModelStagingAppendBackendResult.Rejected()
        }
        if (expectedAfter > record.expectedPlaintextBytes) {
            record.state = PhysicalState.POISONED
            return@synchronized LargeProtectedModelStagingAppendBackendResult.Rejected()
        }

        return@synchronized try {
            if (Files.size(path) != record.appendedBytes) {
                record.state = PhysicalState.POISONED
                LargeProtectedModelStagingAppendBackendResult.Rejected()
            } else {
                FileOutputStream(record.path, true).use { stream ->
                    stream.write(plaintext)
                    stream.flush()
                }
                if (Files.size(path) != expectedAfter) {
                    record.state = PhysicalState.POISONED
                    LargeProtectedModelStagingAppendBackendResult.Rejected()
                } else {
                    record.appendedBytes = expectedAfter
                    LargeProtectedModelStagingAppendBackendResult.Appended
                }
            }
        } catch (throwable: Throwable) {
            record.state = PhysicalState.POISONED
            LargeProtectedModelStagingAppendBackendResult.Failed(
                reason = LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
                throwable = throwable
            )
        }
    }

    override fun seal(
        handle: LargeProtectedModelWorkingArtifactHandle
    ): LargeProtectedModelStagingSealResult = synchronized(lock) {
        val record = records[handle.artifactId]
            ?: return@synchronized LargeProtectedModelStagingSealResult.Rejected()
        if (record.handle != handle || record.state != PhysicalState.WORKING) {
            return@synchronized LargeProtectedModelStagingSealResult.Rejected()
        }
        if (record.appendedBytes != record.expectedPlaintextBytes) {
            return@synchronized LargeProtectedModelStagingSealResult.Rejected()
        }

        try {
            ensurePrivateRoots()
            val source = record.path.toPath().toAbsolutePath().normalize()
            if (
                source.parent != workingRoot ||
                !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(source) != record.appendedBytes
            ) {
                record.state = PhysicalState.POISONED
                return@synchronized LargeProtectedModelStagingSealResult.Rejected()
            }

            val target = childOf(sealedRoot, handle.artifactId.value)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return@synchronized LargeProtectedModelStagingSealResult.Rejected()
            }

            FileOutputStream(record.path, true).use { stream ->
                stream.flush()
                stream.fd.sync()
            }

            try {
                Files.createFile(target)
            } catch (_: FileAlreadyExistsException) {
                return@synchronized LargeProtectedModelStagingSealResult.Rejected()
            }

            return@synchronized try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                record.path = target.toFile()
                record.state = PhysicalState.SEALED
                LargeProtectedModelStagingSealResult.Sealed(
                    LargeProtectedModelSealedArtifactCandidate(
                        backendId = backendId,
                        attempt = handle.attempt,
                        sourceId = handle.artifactId,
                        plaintextBytes = record.appendedBytes,
                        durabilityLevel = LargeProtectedModelStagingDurabilityLevel.ATOMIC_VISIBILITY_RENAMED
                    )
                )
            } catch (throwable: Throwable) {
                cleanupOwnedReservationAfterMoveFailure(source, target)
                LargeProtectedModelStagingSealResult.Failed(
                    reason = LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
                    throwable = throwable
                )
            }
        } catch (throwable: Throwable) {
            return@synchronized LargeProtectedModelStagingSealResult.Failed(
                reason = LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
                throwable = throwable
            )
        }
    }

    override fun delete(
        artifactId: LargeProtectedModelOpaqueArtifactId
    ): LargeProtectedModelStagingDeleteResult = synchronized(lock) {
        if (!isValidToken(artifactId.value)) {
            return@synchronized LargeProtectedModelStagingDeleteResult.Rejected()
        }
        val record = records[artifactId]
            ?: return@synchronized LargeProtectedModelStagingDeleteResult.Rejected()
        val expectedParent = when (record.state) {
            PhysicalState.WORKING, PhysicalState.POISONED -> workingRoot
            PhysicalState.SEALED -> sealedRoot
        }
        val path = record.path.toPath().toAbsolutePath().normalize()
        if (path.parent != expectedParent) {
            return@synchronized LargeProtectedModelStagingDeleteResult.Rejected()
        }

        return@synchronized try {
            ensurePrivateRoots()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                LargeProtectedModelStagingDeleteResult.Rejected()
            } else {
                Files.delete(path)
                records.remove(artifactId)
                LargeProtectedModelStagingDeleteResult.Deleted
            }
        } catch (throwable: Throwable) {
            LargeProtectedModelStagingDeleteResult.Failed(
                reason = LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
                throwable = throwable
            )
        }
    }

    internal fun physicalFileForTesting(
        artifactId: LargeProtectedModelOpaqueArtifactId
    ): File? = synchronized(lock) {
        records[artifactId]?.path
    }

    internal fun finalFileForTesting(
        artifactId: LargeProtectedModelOpaqueArtifactId
    ): File = synchronized(lock) {
        ensurePrivateRoots()
        childOf(sealedRoot, artifactId.value).toFile()
    }

    internal fun adapterRootForTesting(): File = adapterRoot.toFile()

    private fun cleanupOwnedReservationAfterMoveFailure(
        source: java.nio.file.Path,
        target: java.nio.file.Path
    ) {
        try {
            if (
                Files.exists(source, LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(target) == 0L
            ) {
                Files.deleteIfExists(target)
            }
        } catch (_: Throwable) {
            // Move failure remains authoritative. An uncertain reservation is left for explicit handling.
        }
    }

    private fun ensurePrivateRoots() {
        Files.createDirectories(workingRoot)
        Files.createDirectories(sealedRoot)
        require(workingRoot.startsWith(adapterRoot) && sealedRoot.startsWith(adapterRoot)) {
            "staging roots escaped adapter root"
        }
        require(adapterRoot.startsWith(appFilesRoot)) {
            "staging root escaped application files directory"
        }

        val realAppRoot = appFilesRoot.toRealPath()
        val realAdapterRoot = adapterRoot.toRealPath()
        val realWorkingRoot = workingRoot.toRealPath()
        val realSealedRoot = sealedRoot.toRealPath()
        require(realAdapterRoot.startsWith(realAppRoot)) {
            "real staging root escaped application files directory"
        }
        require(
            realWorkingRoot.startsWith(realAdapterRoot) &&
                realSealedRoot.startsWith(realAdapterRoot)
        ) {
            "real staging child root escaped adapter root"
        }
    }

    private fun childOf(parent: java.nio.file.Path, token: String): java.nio.file.Path {
        require(isValidToken(token)) { "invalid opaque staging token" }
        val child = parent.resolve(token).normalize().toAbsolutePath()
        require(child.parent == parent && child.startsWith(adapterRoot)) {
            "staging artifact escaped adapter root"
        }
        return child
    }

    private fun isValidToken(value: String): Boolean = TOKEN_REGEX.matches(value)

    private data class PhysicalRecord(
        val handle: LargeProtectedModelWorkingArtifactHandle,
        var path: File,
        val expectedPlaintextBytes: Long,
        var appendedBytes: Long,
        var state: PhysicalState
    )

    private enum class PhysicalState {
        WORKING,
        SEALED,
        POISONED
    }

    private fun interface TokenSource {
        fun next(): String
    }

    private class LambdaTokenSource(
        private val block: () -> String
    ) : TokenSource {
        override fun next(): String = block()
    }

    private class SecureTokenSource : TokenSource {
        private val random = SecureRandom()

        override fun next(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return buildString(TOKEN_CHARACTERS) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }.also { bytes.fill(0) }
        }
    }

    private companion object {
        const val BACKEND_ID = "android-app-private-staging-v1"
        const val ROOT_DIRECTORY = "large-protected-model-staging-v1"
        const val WORKING_DIRECTORY = "working"
        const val SEALED_DIRECTORY = "sealed"
        const val TOKEN_BYTES = 16
        const val TOKEN_CHARACTERS = TOKEN_BYTES * 2
        const val HEX = "0123456789abcdef"
        val TOKEN_REGEX = Regex("[0-9a-f]{$TOKEN_CHARACTERS}")
    }
}
