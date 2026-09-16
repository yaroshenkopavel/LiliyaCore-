package pro.liliya.android.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * App-private crash-durable activation journal.
 *
 * Publication is file-fsynced and atomic. Corrupt/incompatible state fails closed through the
 * journal contract. The file contains only activation lifecycle state, never Authority or secrets.
 *
 * FileChannel locks coordinate separate processes, while the canonical-path process lock prevents
 * two journal instances in the same JVM from racing into OverlappingFileLockException or both
 * observing CLEAN before publication. The compare-and-set contract therefore has one serialization
 * boundary across all journal instances that target the same app-private journal directory.
 */
class AndroidProductRuntimeLearningActivationFileJournal private constructor(
    private val root: File
) : AndroidProductRuntimeLearningActivationJournal {
    private val stateFile = File(root, STATE_FILE)
    private val lockFile = File(root, LOCK_FILE)
    private val processLock = processLockFor(lockFile)

    override fun load(): AndroidProductRuntimeLearningActivationJournalLoadResult =
        withLock {
            when (val decoded = readState()) {
                ReadState.Missing -> AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                    AndroidProductRuntimeLearningActivationJournalState.CLEAN
                )
                is ReadState.Ready ->
                    AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(decoded.state)
                ReadState.Invalid -> AndroidProductRuntimeLearningActivationJournalLoadResult.Failed
            }
        } ?: AndroidProductRuntimeLearningActivationJournalLoadResult.Failed

    override fun compareAndSet(
        expected: AndroidProductRuntimeLearningActivationJournalState,
        next: AndroidProductRuntimeLearningActivationJournalState
    ): AndroidProductRuntimeLearningActivationJournalTransitionResult =
        withLock {
            val current = when (val decoded = readState()) {
                ReadState.Missing -> AndroidProductRuntimeLearningActivationJournalState.CLEAN
                is ReadState.Ready -> decoded.state
                ReadState.Invalid ->
                    return@withLock AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed
            }
            if (current != expected) {
                return@withLock AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict(
                    current
                )
            }
            if (!publish(next)) {
                return@withLock AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed
            }
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated
        } ?: AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed

    private fun readState(): ReadState {
        if (!stateFile.exists()) return ReadState.Missing
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_STATE_BYTES) return ReadState.Invalid
        return try {
            decode(stateFile.readText(StandardCharsets.UTF_8))
        } catch (_: IOException) {
            ReadState.Invalid
        } catch (_: SecurityException) {
            ReadState.Invalid
        }
    }

    private fun publish(state: AndroidProductRuntimeLearningActivationJournalState): Boolean {
        val temp = File(root, STATE_FILE + TEMP_SUFFIX)
        val bytes = encode(state).toByteArray(StandardCharsets.UTF_8)
        return try {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                temp.delete()
                return false
            }
            syncDirectoryBestEffort()
            true
        } catch (_: IOException) {
            temp.delete()
            false
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> withLock(block: () -> T): T? =
        synchronized(processLock) {
            try {
                if (!lockFile.exists()) lockFile.createNewFile()
                FileChannel.open(
                    lockFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
                ).use { channel ->
                    channel.lock().use { block() }
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: OverlappingFileLockException) {
                // The process lock should prevent this for matching canonical paths. Fail closed if
                // a platform-specific lock implementation still reports an overlap.
                null
            }
        }

    private fun syncDirectoryBestEffort() {
        try {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // State file is already fsynced and atomically published.
        } catch (_: UnsupportedOperationException) {
            // Some Android filesystems do not expose directory fsync through java.nio.
        }
    }

    private sealed interface ReadState {
        data object Missing : ReadState
        data class Ready(val state: AndroidProductRuntimeLearningActivationJournalState) : ReadState
        data object Invalid : ReadState
    }

    companion object {
        private const val DEFAULT_DIRECTORY = "liliya-learning-activation-v1"
        private const val STATE_FILE = "activation.state"
        private const val LOCK_FILE = "activation.lock"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAGIC = "LILA1"
        private const val MAX_STATE_BYTES = 256L
        private val processLocks = ConcurrentHashMap<String, Any>()

        fun create(
            context: Context,
            directoryName: String = DEFAULT_DIRECTORY
        ): AndroidProductRuntimeLearningActivationFileJournal {
            require(directoryName.isNotBlank()) { "activation journal directory must not be blank" }
            require(!directoryName.contains('/') && !directoryName.contains('\\')) {
                "activation journal directory must be a single app-private segment"
            }
            val appRoot = context.applicationContext.filesDir.canonicalFile
            val root = File(appRoot, directoryName).canonicalFile
            require(root.parentFile == appRoot) {
                "activation journal root must be directly inside app-private files"
            }
            ensureDirectory(root)
            return AndroidProductRuntimeLearningActivationFileJournal(root)
        }

        internal fun createForDirectory(
            directory: File
        ): AndroidProductRuntimeLearningActivationFileJournal {
            val root = directory.canonicalFile
            ensureDirectory(root)
            return AndroidProductRuntimeLearningActivationFileJournal(root)
        }

        private fun processLockFor(lockFile: File): Any =
            processLocks.computeIfAbsent(lockFile.canonicalPath) { Any() }

        private fun ensureDirectory(root: File) {
            if (!root.exists()) check(root.mkdirs()) {
                "activation journal directory could not be created"
            }
            check(root.isDirectory) { "activation journal root is not a directory" }
        }

        private fun encode(state: AndroidProductRuntimeLearningActivationJournalState): String {
            val body = "$MAGIC|${state.name}"
            return "$body|${digest(body)}\n"
        }

        private fun decode(encoded: String): ReadState {
            val line = encoded.trimEnd('\n', '\r')
            val parts = line.split('|')
            if (parts.size != 3 || parts[0] != MAGIC) return ReadState.Invalid
            val state = try {
                AndroidProductRuntimeLearningActivationJournalState.valueOf(parts[1])
            } catch (_: IllegalArgumentException) {
                return ReadState.Invalid
            }
            val body = "${parts[0]}|${parts[1]}"
            if (!MessageDigest.isEqual(
                    parts[2].toByteArray(StandardCharsets.US_ASCII),
                    digest(body).toByteArray(StandardCharsets.US_ASCII)
                )
            ) {
                return ReadState.Invalid
            }
            return ReadState.Ready(state)
        }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
