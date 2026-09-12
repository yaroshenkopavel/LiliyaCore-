package pro.liliya.app

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface ProductionAndroidLocalModelSelectionResult {
    data class Selected(
        val file: File
    ) : ProductionAndroidLocalModelSelectionResult

    data object EmptyDocument : ProductionAndroidLocalModelSelectionResult

    data object Failed : ProductionAndroidLocalModelSelectionResult
}

/**
 * Imports only the exact document explicitly selected by the user.
 *
 * Local Model Selection != Model Discovery.
 * Local Model Selection != Model Download.
 * Local Model Selection != Model Compatibility Policy.
 * Durable Selection Pointer != Model Trust or Compatibility Acceptance.
 */
object ProductionAndroidLocalModelSelection {
    @Volatile
    private var selectedModel: File? = null

    @Synchronized
    fun importSelected(
        directory: File,
        openInput: () -> InputStream?
    ): ProductionAndroidLocalModelSelectionResult {
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
                temp.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        destination.write(buffer, 0, read)
                        byteCount += read
                    }
                    destination.flush()
                }
            }

            if (byteCount == 0L) {
                temp.delete()
                return ProductionAndroidLocalModelSelectionResult.EmptyDocument
            }

            val sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            val exact = File(directory, "model-$sha256.bin")

            try {
                Files.move(
                    temp.toPath(),
                    exact.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    exact.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            if (!persistSelectionPointer(directory, exact.name)) {
                selectedModel = null
                return ProductionAndroidLocalModelSelectionResult.Failed
            }

            selectedModel = exact
            ProductionAndroidLocalModelSelectionResult.Selected(exact)
        } catch (_: Exception) {
            temp.delete()
            selectedModel = null
            ProductionAndroidLocalModelSelectionResult.Failed
        }
    }

    internal fun current(): File? = selectedModel

    /**
     * Restores only the exact previously committed user selection.
     *
     * This intentionally does not scan the directory or select another model when the durable
     * pointer is missing, malformed, stale, or escapes the exact model directory.
     */
    @Synchronized
    internal fun restore(directory: File): File? {
        selectedModel = null
        val pointer = File(directory, SELECTION_POINTER_FILE)
        if (!pointer.isFile) return null

        val fileName = try {
            pointer.readText(Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }
        if (!MODEL_FILE_NAME.matches(fileName)) return null

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

        selectedModel = restored
        return restored
    }

    @Synchronized
    internal fun clearForTests() {
        selectedModel = null
    }

    private fun persistSelectionPointer(directory: File, fileName: String): Boolean {
        if (!MODEL_FILE_NAME.matches(fileName)) return false
        val temp = try {
            File.createTempFile("selected-model-", ".tmp", directory)
        } catch (_: Exception) {
            return false
        }
        return try {
            temp.writeText(fileName, Charsets.UTF_8)
            val pointer = File(directory, SELECTION_POINTER_FILE)
            try {
                Files.move(
                    temp.toPath(),
                    pointer.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    pointer.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private const val SELECTION_POINTER_FILE = "selected-model-v1"
    private val MODEL_FILE_NAME = Regex("^model-[0-9a-f]{64}\\.bin$")
}
