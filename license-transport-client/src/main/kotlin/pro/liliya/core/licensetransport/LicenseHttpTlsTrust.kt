package pro.liliya.core.licensetransport

/**
 * Explicit caller-owned TLS trust material for one licensing transport configuration.
 *
 * TLS Trust != Product Auth.
 * TLS Trust != License Verification Trust.
 * TLS Trust != Authority.
 *
 * Certificate bytes are defensively copied. This contract does not discover, persist,
 * broaden, or replace Android system trust. When supplied, it creates an isolated trust
 * store for the licensing HTTPS connection only.
 */
class LicenseHttpTlsTrust private constructor(
    certificates: List<ByteArray>
) {
    private val certificates: List<ByteArray> =
        certificates.map { it.copyOf() }

    val certificateCount: Int
        get() = certificates.size

    fun copyCertificates(): List<ByteArray> =
        certificates.map { it.copyOf() }

    override fun toString(): String =
        "LicenseHttpTlsTrust(certificateCount=$certificateCount,material=<redacted>)"

    companion object {
        fun ofCertificates(
            certificates: Collection<ByteArray>
        ): LicenseHttpTlsTrust {
            require(certificates.isNotEmpty()) {
                "license HTTP TLS trust requires at least one certificate"
            }
            require(certificates.all { it.isNotEmpty() }) {
                "license HTTP TLS trust certificate material must not be empty"
            }
            return LicenseHttpTlsTrust(certificates.toList())
        }

        fun ofCertificate(
            certificate: ByteArray
        ): LicenseHttpTlsTrust =
            ofCertificates(listOf(certificate))
    }
}
