package pro.liliya.app

import java.security.SecureRandom

internal data class ProductionAndroidInstallCredentialMaterial(
    val installId: String,
    val installSecret: ByteArray
) : AutoCloseable {
    init {
        require(installId.length in 8..128) { "install id length out of range" }
        require(installId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "install id contains unsupported characters"
        }
        require(installSecret.size == SECRET_TEXT_BYTES) { "install secret must encode 256-bit entropy" }
        require(installSecret.all { byte ->
            val c = byte.toInt().toChar()
            c in '0'..'9' || c in 'a'..'f'
        }) { "install secret must be lowercase hexadecimal" }
    }

    fun copySecret(): ByteArray = installSecret.copyOf()

    override fun close() {
        installSecret.fill(0)
    }

    override fun toString(): String =
        "ProductionAndroidInstallCredentialMaterial(installId=<redacted>,installSecret=<redacted>)"

    private companion object {
        const val SECRET_TEXT_BYTES = 64
    }
}

internal class ProductionAndroidInstallCredentialGenerator(
    private val random: SecureRandom = SecureRandom()
) {
    fun generate(): ProductionAndroidInstallCredentialMaterial {
        val idBytes = ByteArray(INSTALL_ID_BYTES)
        val secretEntropy = ByteArray(SECRET_BYTES)
        random.nextBytes(idBytes)
        random.nextBytes(secretEntropy)
        val secretText = secretEntropy.toHex().encodeToByteArray()
        return try {
            ProductionAndroidInstallCredentialMaterial(
                installId = "liliya-" + idBytes.toHex(),
                installSecret = secretText
            )
        } finally {
            idBytes.fill(0)
            secretEntropy.fill(0)
        }
    }

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            forEach { value ->
                append(HEX[(value.toInt() ushr 4) and 0x0f])
                append(HEX[value.toInt() and 0x0f])
            }
        }

    private companion object {
        const val INSTALL_ID_BYTES = 16
        const val SECRET_BYTES = 32
        const val HEX = "0123456789abcdef"
    }
}
