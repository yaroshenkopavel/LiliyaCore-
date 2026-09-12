package pro.liliya.core.licensetransport

/**
 * Caller-owned bearer credential for authenticating one licensing HTTP request.
 *
 * Authentication Credential != License Entitlement.
 * Authentication Credential != License Verification Trust.
 * Authentication Credential != Capability Authority.
 *
 * The credential remains outside LicenseServiceTransportRequest so it never becomes part of the
 * frozen licensing wire DTO. Callers must close it when the external credential is no longer used.
 */
class LicenseHttpBearerCredential private constructor(
    value: ByteArray
) : AutoCloseable {
    private val bytes = value.copyOf()

    @Volatile
    private var closed = false

    init {
        require(bytes.isNotEmpty()) { "license bearer credential must not be empty" }
        val decoded = bytes.toString(Charsets.UTF_8)
        require(decoded.isNotBlank()) { "license bearer credential must not be blank" }
        require(decoded.encodeToByteArray().contentEquals(bytes)) {
            "license bearer credential must be valid UTF-8"
        }
        require('\r' !in decoded && '\n' !in decoded) {
            "license bearer credential must not contain HTTP line breaks"
        }
    }

    internal fun copyBytes(): ByteArray {
        check(!closed) { "license bearer credential is closed" }
        return bytes.copyOf()
    }

    override fun close() {
        if (!closed) {
            bytes.fill(0)
            closed = true
        }
    }

    override fun toString(): String = "LicenseHttpBearerCredential(<redacted>)"

    companion object {
        fun of(value: ByteArray): LicenseHttpBearerCredential =
            LicenseHttpBearerCredential(value)
    }
}
