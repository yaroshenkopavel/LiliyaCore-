package pro.liliya.app

import java.io.InputStream

internal sealed interface ProductionAndroidProductAuthImportResult {
    data object Imported : ProductionAndroidProductAuthImportResult
    data object AlreadyProvisioned : ProductionAndroidProductAuthImportResult
    data object Rejected : ProductionAndroidProductAuthImportResult
    data object Failed : ProductionAndroidProductAuthImportResult
}

/**
 * Strict one-shot product-auth provisioning artifact importer.
 *
 * Format: exact ASCII `LPAUTH1\n` prefix followed by one bearer credential payload. The selected
 * source is never retained; callers own URI lifecycle. Plaintext exists only in bounded temporary
 * buffers and is zeroized before return.
 */
internal object ProductionAndroidProductAuthCredentialImport {
    private val PREFIX = "LPAUTH1\n".encodeToByteArray()
    private const val MAX_SECRET_BYTES = 4096
    private const val MAX_ARTIFACT_BYTES = MAX_SECRET_BYTES + 8

    fun import(
        openInput: () -> InputStream?,
        provision: (ByteArray) -> ProductionAndroidProductAuthProvisionResult
    ): ProductionAndroidProductAuthImportResult {
        val input = try {
            openInput()
        } catch (_: Throwable) {
            null
        } ?: return ProductionAndroidProductAuthImportResult.Failed

        val artifact = try {
            input.use(::readBounded)
        } catch (_: Throwable) {
            null
        } ?: return ProductionAndroidProductAuthImportResult.Rejected

        try {
            if (artifact.size <= PREFIX.size || !artifact.startsWith(PREFIX)) {
                return ProductionAndroidProductAuthImportResult.Rejected
            }
            val secret = artifact.copyOfRange(PREFIX.size, artifact.size)
            try {
                return when (provision(secret)) {
                    ProductionAndroidProductAuthProvisionResult.Provisioned ->
                        ProductionAndroidProductAuthImportResult.Imported
                    ProductionAndroidProductAuthProvisionResult.AlreadyProvisioned ->
                        ProductionAndroidProductAuthImportResult.AlreadyProvisioned
                    ProductionAndroidProductAuthProvisionResult.Rejected ->
                        ProductionAndroidProductAuthImportResult.Rejected
                    ProductionAndroidProductAuthProvisionResult.Failed ->
                        ProductionAndroidProductAuthImportResult.Failed
                }
            } finally {
                secret.fill(0)
            }
        } finally {
            artifact.fill(0)
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val buffer = ByteArray(MAX_ARTIFACT_BYTES + 1)
        var used = 0
        try {
            while (used < buffer.size) {
                val read = input.read(buffer, used, buffer.size - used)
                if (read < 0) break
                if (read == 0) {
                    val next = input.read()
                    if (next < 0) break
                    buffer[used] = next.toByte()
                    used += 1
                    continue
                }
                used += read
            }
            if (used == 0 || used > MAX_ARTIFACT_BYTES) return null
            return buffer.copyOf(used)
        } finally {
            buffer.fill(0)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
