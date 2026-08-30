package pro.liliya.core.license

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class LicenseAlgorithm(val value: String) {
    init { require(value.isNotBlank()) { "license algorithm must not be blank" } }
    override fun toString(): String = value
}

class LicenseCanonicalPayload private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseCanonicalPayload && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "LicenseCanonicalPayload(size=${bytes.size}, content=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): LicenseCanonicalPayload {
            require(bytes.isNotEmpty()) { "license canonical payload must not be empty" }
            return LicenseCanonicalPayload(bytes.copyOf())
        }
    }
}

class LicenseSignature private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseSignature && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "LicenseSignature(size=${bytes.size}, value=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): LicenseSignature {
            require(bytes.isNotEmpty()) { "license signature must not be empty" }
            return LicenseSignature(bytes.copyOf())
        }
    }
}

class LicenseSignedEnvelope(
    val schemaVersion: LicenseVersion,
    val algorithm: LicenseAlgorithm,
    val signingKeyId: LicenseKeyId,
    val payload: LicenseCanonicalPayload,
    val signature: LicenseSignature
) {
    override fun toString(): String =
        "LicenseSignedEnvelope(schemaVersion=$schemaVersion, algorithm=$algorithm, " +
            "signingKeyId=$signingKeyId, payload=<redacted>, signature=<redacted>)"
}

class LicenseTrustedVerificationKey private constructor(
    val keyId: LicenseKeyId,
    val algorithm: LicenseAlgorithm,
    private val material: ByteArray
) {
    fun copyMaterial(): ByteArray = material.copyOf()

    override fun toString(): String =
        "LicenseTrustedVerificationKey(keyId=$keyId, algorithm=$algorithm, material=<redacted>)"

    companion object {
        fun of(
            keyId: LicenseKeyId,
            algorithm: LicenseAlgorithm,
            material: ByteArray
        ): LicenseTrustedVerificationKey {
            require(material.isNotEmpty()) { "license verification key material must not be empty" }
            return LicenseTrustedVerificationKey(keyId, algorithm, material.copyOf())
        }
    }
}

fun interface LicenseTrustedKeyResolver {
    fun resolve(keyId: LicenseKeyId): LicenseTrustedVerificationKey?
}

fun interface LicenseSignatureVerifier {
    fun verify(
        algorithm: LicenseAlgorithm,
        key: LicenseTrustedVerificationKey,
        payload: LicenseCanonicalPayload,
        signature: LicenseSignature
    ): Boolean
}

sealed interface LicenseVerificationResult {
    class Verified internal constructor(
        val entitlement: LicenseEntitlement,
        val envelope: LicenseSignedEnvelope
    ) : LicenseVerificationResult {
        override fun toString(): String =
            "LicenseVerificationResult.Verified(licenseId=${entitlement.id}, " +
                "productId=${entitlement.productId}, signingKeyId=${envelope.signingKeyId})"
    }

    data class Rejected(val reason: LicenseVerificationRejection) : LicenseVerificationResult
}

enum class LicenseVerificationRejection {
    UNSUPPORTED_SCHEMA_VERSION,
    UNKNOWN_KEY_ID,
    UNSUPPORTED_ALGORITHM,
    TRUSTED_KEY_MISMATCH,
    INVALID_SIGNATURE,
    INVALID_CANONICAL_PAYLOAD,
    SIGNING_KEY_ID_MISMATCH
}

class LicenseVerifier(
    private val supportedSchemaVersion: LicenseVersion,
    supportedAlgorithms: Set<LicenseAlgorithm>,
    private val trustedKeys: LicenseTrustedKeyResolver,
    private val signatureVerifier: LicenseSignatureVerifier
) {
    private val supportedAlgorithms: Set<LicenseAlgorithm> = supportedAlgorithms.toSet()

    init {
        require(this.supportedAlgorithms.isNotEmpty()) {
            "license verifier must support at least one algorithm"
        }
    }

    fun verify(envelope: LicenseSignedEnvelope): LicenseVerificationResult {
        if (envelope.schemaVersion != supportedSchemaVersion) {
            return rejected(LicenseVerificationRejection.UNSUPPORTED_SCHEMA_VERSION)
        }
        if (envelope.algorithm !in supportedAlgorithms) {
            return rejected(LicenseVerificationRejection.UNSUPPORTED_ALGORITHM)
        }
        val key = trustedKeys.resolve(envelope.signingKeyId)
            ?: return rejected(LicenseVerificationRejection.UNKNOWN_KEY_ID)
        if (key.keyId != envelope.signingKeyId || key.algorithm != envelope.algorithm) {
            return rejected(LicenseVerificationRejection.TRUSTED_KEY_MISMATCH)
        }
        if (
            !signatureVerifier.verify(
                algorithm = envelope.algorithm,
                key = key,
                payload = envelope.payload,
                signature = envelope.signature
            )
        ) {
            return rejected(LicenseVerificationRejection.INVALID_SIGNATURE)
        }

        val entitlement = when (val decoded = LicenseEntitlementCanonicalCodec.decode(envelope.payload)) {
            is LicenseEntitlementDecodeResult.Decoded -> decoded.entitlement
            LicenseEntitlementDecodeResult.Corrupt ->
                return rejected(LicenseVerificationRejection.INVALID_CANONICAL_PAYLOAD)
        }
        if (entitlement.signingKeyId != envelope.signingKeyId) {
            return rejected(LicenseVerificationRejection.SIGNING_KEY_ID_MISMATCH)
        }
        return LicenseVerificationResult.Verified(entitlement, envelope)
    }

    private fun rejected(reason: LicenseVerificationRejection) =
        LicenseVerificationResult.Rejected(reason)
}

/** Deterministic test/dev verifier only; not a production cryptographic primitive. */
object LicenseDigestTestVerifier : LicenseSignatureVerifier {
    override fun verify(
        algorithm: LicenseAlgorithm,
        key: LicenseTrustedVerificationKey,
        payload: LicenseCanonicalPayload,
        signature: LicenseSignature
    ): Boolean {
        if (algorithm.value != "TEST-SHA256") return false
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key.copyMaterial())
        digest.update(byteArrayOf(0))
        digest.update(payload.copyBytes())
        return MessageDigest.isEqual(digest.digest(), signature.copyBytes())
    }

    fun signForTest(
        key: LicenseTrustedVerificationKey,
        payload: LicenseCanonicalPayload
    ): LicenseSignature {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key.copyMaterial())
        digest.update(byteArrayOf(0))
        digest.update(payload.copyBytes())
        return LicenseSignature.of(digest.digest())
    }
}

internal fun String.licenseCanonicalPayload(): LicenseCanonicalPayload =
    LicenseCanonicalPayload.of(toByteArray(StandardCharsets.UTF_8))
