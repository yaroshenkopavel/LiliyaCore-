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

            selectedModel = exact
            ProductionAndroidLocalModelSelectionResult.Selected(exact)
        } catch (_: Exception) {
            temp.delete()
            ProductionAndroidLocalModelSelectionResult.Failed
        }
    }

    internal fun current(): File? = selectedModel

    @Synchronized
    internal fun clearForTests() {
        selectedModel = null
    }
}
