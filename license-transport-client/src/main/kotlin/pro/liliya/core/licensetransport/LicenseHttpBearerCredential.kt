package pro.liliya.core.licensetransport

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("license bearer credential must be valid UTF-8")
        }
        try {
            var hasNonWhitespace = false
            for (index in 0 until decoded.limit()) {
                val char = decoded.get(index)
                require(char != '\r' && char != '\n') {
                    "license bearer credential must not contain HTTP line breaks"
                }
                if (!char.isWhitespace()) hasNonWhitespace = true
            }
            require(hasNonWhitespace) { "license bearer credential must not be blank" }
        } finally {
            for (index in 0 until decoded.limit()) {
                decoded.put(index, '\u0000')
            }
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
