package pro.liliya.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

sealed interface ProductionAndroidLocalModelSelectionResult {
    data class Selected(
        val file: File
    ) : ProductionAndroidLocalModelSelectionResult

    data object EmptyDocument : ProductionAndroidLocalModelSelectionResult

    data object ResourceLimitRejected : ProductionAndroidLocalModelSelectionResult

    data object Failed : ProductionAndroidLocalModelSelectionResult
}

/**
 * Imports only the exact document explicitly selected by the user.
 *
 * Local Model Selection != Model Discovery.
 * Local Model Selection != Model Download.
 * Local Model Selection != Model Compatibility Policy.
 * Durable Selection Receipt != Model Trust or Compatibility Acceptance.
 */
object ProductionAndroidLocalModelSelection {
    @Volatile
    private var selectedModel: File? = null

    @Synchronized
    fun importSelected(
        directory: File,
        maxImportBytes: Long,
        openInput: () -> InputStream?
    ): ProductionAndroidLocalModelSelectionResult {
        require(maxImportBytes > 0L) { "local model import budget must be positive" }

        val input = try {
            openInput()
        } catch (_: Exception) {
            return ProductionAndroidLocalModelSelectionResult.Failed
        } ?: return ProductionAndroidLocalModelSelectionResult.Failed

        if (!directory.exists() && !directory.mkdirs()) {
            runCatching { input.close() }
            return ProductionAndroidLocalModelSelectionResult.Failed
        }
        if (!directory.isDirectory) {
            runCatching { input.close() }
            return ProductionAndroidLocalModelSelectionResult.Failed
        }
        cleanupStaleTemps(directory)

        val temp = try {
            File.createTempFile("model-import-", ".tmp", directory)
        } catch (_: Exception) {
            runCatching { input.close() }
            return ProductionAndroidLocalModelSelectionResult.Failed
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L

            input.use { source ->
                FileOutputStream(temp, false).use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) {
                            val next = source.read()
                            if (next < 0) break
                            if (byteCount >= maxImportBytes) throw ImportLimitExceeded()
                            val byte = next.toByte()
                            digest.update(byte)
                            destination.write(next)
                            byteCount += 1L
                            continue
                        }

                        val nextCount = try {
                            Math.addExact(byteCount, read.toLong())
                        } catch (_: ArithmeticException) {
                            throw ImportLimitExceeded()
                        }
                        if (nextCount > maxImportBytes) {
                            throw ImportLimitExceeded()
                        }

                        digest.update(buffer, 0, read)
                        destination.write(buffer, 0, read)
                        byteCount = nextCount
                    }
                    destination.flush()
                    destination.fd.sync()
                }
            }

            if (byteCount == 0L) {
                temp.delete()
                return ProductionAndroidLocalModelSelectionResult.EmptyDocument
            }

            val sha256 = digest.digest().toHex()
            val exact = File(directory, "model-$sha256.bin")

            publishFile(temp, exact)
            if (!matchesDigest(exact, sha256)) {
                exact.delete()
                selectedModel = null
                return ProductionAndroidLocalModelSelectionResult.Failed
            }
            syncDirectoryBestEffort(directory)

            if (!persistSelectionReceipt(directory, exact)) {
                selectedModel = null
                return ProductionAndroidLocalModelSelectionResult.Failed
            }

            File(directory, LEGACY_SELECTION_POINTER_FILE).delete()
            syncDirectoryBestEffort(directory)
            selectedModel = exact
            ProductionAndroidLocalModelSelectionResult.Selected(exact)
        } catch (_: ImportLimitExceeded) {
            temp.delete()
            selectedModel = null
            ProductionAndroidLocalModelSelectionResult.ResourceLimitRejected
        } catch (_: Exception) {
            temp.delete()
            selectedModel = null
            ProductionAndroidLocalModelSelectionResult.Failed
        }
    }

    internal fun current(): File? = selectedModel

    internal fun importBudgetForAllocatableBytes(allocatableBytes: Long): Long? {
        if (allocatableBytes <= IMPORT_STORAGE_SAFETY_RESERVE_BYTES) return null
        return allocatableBytes - IMPORT_STORAGE_SAFETY_RESERVE_BYTES
    }

    /**
     * Restores only the exact previously committed user selection.
     *
     * New imports use an O(1) durable verification receipt so cold startup does not re-read a
     * multi-gigabyte model. A legacy v1 pointer is SHA-256 verified once and upgraded to the
     * receipt format before becoming current.
     */
    @Synchronized
    internal fun restore(directory: File): File? {
        selectedModel = null
        cleanupStaleTemps(directory)

        val receipt = File(directory, SELECTION_RECEIPT_FILE)
        if (receipt.isFile) {
            return restoreFromReceipt(directory, receipt)
        }

        val legacyPointer = File(directory, LEGACY_SELECTION_POINTER_FILE)
        if (!legacyPointer.isFile || legacyPointer.length() !in 1L..MAX_POINTER_BYTES) return null
        val legacyFileName = try {
            legacyPointer.readText(Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }
        val match = MODEL_FILE_NAME.matchEntire(legacyFileName) ?: return null
        val expectedSha256 = match.groupValues[1]
        val restored = resolveExactModel(directory, legacyFileName) ?: return null

        if (!matchesDigest(restored, expectedSha256)) return null
        if (!persistSelectionReceipt(directory, restored)) return null

        legacyPointer.delete()
        syncDirectoryBestEffort(directory)
        selectedModel = restored
        return restored
    }

    @Synchronized
    internal fun clearForTests() {
        selectedModel = null
    }

    private fun restoreFromReceipt(directory: File, receipt: File): File? {
        if (receipt.length() !in 1L..MAX_RECEIPT_BYTES) return null
        val lines = try {
            receipt.readLines(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (lines.size != RECEIPT_LINE_COUNT || lines[0] != RECEIPT_MAGIC) return null

        val fileName = lines[1]
        if (!MODEL_FILE_NAME.matches(fileName)) return null
        val expectedLength = lines[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val expectedLastModified = lines[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null

        val restored = resolveExactModel(directory, fileName) ?: return null
        if (restored.length() != expectedLength) return null
        if (restored.lastModified() != expectedLastModified) return null

        selectedModel = restored
        return restored
    }

    private fun resolveExactModel(directory: File, fileName: String): File? {
        val root = try {
            directory.canonicalFile
        } catch (_: Exception) {
            return null
        }
        val restored = try {
            File(root, fileName).canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (restored.parentFile != root || !restored.isFile) return null
        return restored
    }

    private fun persistSelectionReceipt(directory: File, model: File): Boolean {
        if (!MODEL_FILE_NAME.matches(model.name) || !model.isFile || model.length() <= 0L) return false

        val payload = buildString {
            appendLine(RECEIPT_MAGIC)
            appendLine(model.name)
            appendLine(model.length())
            append(model.lastModified())
        }
        val temp = try {
            File.createTempFile("selected-model-", ".tmp", directory)
        } catch (_: Exception) {
            return false
        }

        return try {
            FileOutputStream(temp, false).use { output ->
                output.write(payload.encodeToByteArray())
                output.flush()
                output.fd.sync()
            }
            publishFile(temp, File(directory, SELECTION_RECEIPT_FILE))
            syncDirectoryBestEffort(directory)
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun publishFile(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun matchesDigest(file: File, expectedSha256: String): Boolean = try {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    val next = input.read()
                    if (next < 0) break
                    digest.update(next.toByte())
                    continue
                }
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex() == expectedSha256
    } catch (_: Exception) {
        false
    }

    private fun cleanupStaleTemps(directory: File) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (MODEL_IMPORT_TEMP_FILE.matches(file.name) ||
                SELECTION_TEMP_FILE.matches(file.name)
            ) {
                runCatching { file.delete() }
            }
        }
    }

    private fun syncDirectoryBestEffort(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            Unit
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class ImportLimitExceeded : Exception()

    private const val IMPORT_STORAGE_SAFETY_RESERVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_POINTER_BYTES = 256L
    private const val MAX_RECEIPT_BYTES = 512L
    private const val RECEIPT_MAGIC = "LILIYA_LOCAL_MODEL_SELECTION_V2"
    private const val RECEIPT_LINE_COUNT = 4
    private const val SELECTION_RECEIPT_FILE = "selected-model-v2"
    private const val LEGACY_SELECTION_POINTER_FILE = "selected-model-v1"
    private val MODEL_FILE_NAME = Regex("^model-([0-9a-f]{64})\\.bin$")
    private val MODEL_IMPORT_TEMP_FILE = Regex("^model-import-[A-Za-z0-9._-]+\\.tmp$")
    private val SELECTION_TEMP_FILE = Regex("^selected-model-[A-Za-z0-9._-]+\\.tmp$")
}
