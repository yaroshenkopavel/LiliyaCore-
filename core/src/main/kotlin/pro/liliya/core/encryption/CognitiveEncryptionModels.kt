package pro.liliya.core.encryption

import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

@JvmInline
value class CognitiveDekId(val value: String) {
    init { require(value.isNotBlank()) { "cognitive DEK id must not be blank" } }
    override fun toString(): String = "CognitiveDekId([redacted])"
}

@JvmInline
value class CognitiveDekGeneration(val value: Long) {
    init { require(value > 0L) { "cognitive DEK generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class CognitiveDekReference(
    val id: CognitiveDekId,
    val generation: CognitiveDekGeneration
)

enum class CognitiveEncryptionAlgorithm {
    AES_256_GCM
}

data class CognitiveEncryptionProfile(
    val algorithm: CognitiveEncryptionAlgorithm,
    val keySizeBits: Int,
    val nonceSizeBytes: Int,
    val authenticationTagSizeBits: Int
) {
    init {
        require(
            algorithm == CognitiveEncryptionAlgorithm.AES_256_GCM &&
                keySizeBits == 256 &&
                nonceSizeBytes == 12 &&
                authenticationTagSizeBits == 128
        ) { "unsupported cognitive encryption profile" }
    }

    companion object {
        val AES_256_GCM = CognitiveEncryptionProfile(
            algorithm = CognitiveEncryptionAlgorithm.AES_256_GCM,
            keySizeBits = 256,
            nonceSizeBytes = 12,
            authenticationTagSizeBits = 128
        )
    }
}

@JvmInline
value class CognitiveEnvelopeVersion(val value: Int) {
    init { require(value > 0) { "cognitive envelope version must be positive" } }
}

data class CognitivePayloadBinding(
    val storeId: PersistentStoreId,
    val entityId: PersistentEntityId,
    val entityGeneration: PersistentGeneration,
    val schemaId: PersistentSchemaId,
    val schemaVersion: PersistentSchemaVersion,
    val dek: CognitiveDekReference
)

class EncryptedCognitivePayloadEnvelope(
    val version: CognitiveEnvelopeVersion,
    val profile: CognitiveEncryptionProfile,
    val binding: CognitivePayloadBinding,
    nonce: ByteArray,
    ciphertext: ByteArray,
    authenticationTag: ByteArray
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()
    private val authenticationTagBytes = authenticationTag.copyOf()

    init {
        require(nonceBytes.size == profile.nonceSizeBytes) { "invalid cognitive encryption nonce size" }
        require(authenticationTagBytes.size * 8 == profile.authenticationTagSizeBits) {
            "invalid cognitive encryption authentication tag size"
        }
    }

    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyCiphertext(): ByteArray = ciphertextBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = authenticationTagBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is EncryptedCognitivePayloadEnvelope &&
            version == other.version &&
            profile == other.profile &&
            binding == other.binding &&
            nonceBytes.contentEquals(other.nonceBytes) &&
            ciphertextBytes.contentEquals(other.ciphertextBytes) &&
            authenticationTagBytes.contentEquals(other.authenticationTagBytes)

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + profile.hashCode()
        result = 31 * result + binding.hashCode()
        result = 31 * result + nonceBytes.contentHashCode()
        result = 31 * result + ciphertextBytes.contentHashCode()
        result = 31 * result + authenticationTagBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EncryptedCognitivePayloadEnvelope(version=$version, profile=$profile, binding=$binding, " +
            "nonce=<redacted:${nonceBytes.size} bytes>, ciphertext=<redacted:${ciphertextBytes.size} bytes>, " +
            "authenticationTag=<redacted:${authenticationTagBytes.size} bytes>)"
}

@JvmInline
value class CognitiveKeyProtectorId(val value: String) {
    init { require(value.isNotBlank()) { "cognitive key protector id must not be blank" } }
    override fun toString(): String = "CognitiveKeyProtectorId([redacted])"
}

@JvmInline
value class CognitiveKeyProtectorGeneration(val value: Long) {
    init { require(value > 0L) { "cognitive key protector generation must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class CognitiveKeyProtectorPlatformReference(val value: String) {
    init { require(value.isNotBlank()) { "cognitive key protector platform reference must not be blank" } }
    override fun toString(): String = "CognitiveKeyProtectorPlatformReference([redacted])"
}

data class CognitiveKeyProtectorReference(
    val id: CognitiveKeyProtectorId,
    val generation: CognitiveKeyProtectorGeneration,
    val platformReference: CognitiveKeyProtectorPlatformReference? = null
)

enum class CognitiveDekWrappingAlgorithm {
    AES_256_GCM
}

class WrappedCognitiveDekEnvelope(
    val version: CognitiveEnvelopeVersion,
    val dek: CognitiveDekReference,
    val protector: CognitiveKeyProtectorReference,
    val wrappingAlgorithm: CognitiveDekWrappingAlgorithm,
    val purpose: String,
    wrappedDek: ByteArray,
    nonce: ByteArray,
    authenticationTag: ByteArray
) {
    private val wrappedDekBytes = wrappedDek.copyOf()
    private val nonceBytes = nonce.copyOf()
    private val authenticationTagBytes = authenticationTag.copyOf()

    init {
        require(purpose.isNotBlank()) { "wrapped DEK purpose must not be blank" }
        require(wrappedDekBytes.isNotEmpty()) { "wrapped DEK bytes must not be empty" }
        require(nonceBytes.size == 12) { "wrapped DEK nonce must be 12 bytes" }
        require(authenticationTagBytes.size == 16) { "wrapped DEK authentication tag must be 16 bytes" }
    }

    fun copyWrappedDek(): ByteArray = wrappedDekBytes.copyOf()
    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = authenticationTagBytes.copyOf()

    override fun toString(): String =
        "WrappedCognitiveDekEnvelope(version=$version, dek=$dek, protector=$protector, " +
            "wrappingAlgorithm=$wrappingAlgorithm, purpose=$purpose, wrappedDek=<redacted:${wrappedDekBytes.size} bytes>, " +
            "nonce=<redacted:${nonceBytes.size} bytes>, authenticationTag=<redacted:${authenticationTagBytes.size} bytes>)"
}

enum class CognitiveEncryptionFailureCategory {
    INVALID_REQUEST,
    MALFORMED_ENVELOPE,
    UNSUPPORTED_PROFILE,
    DEK_MISSING,
    STALE_DEK_OWNERSHIP,
    PROTECTOR_MISSING,
    PROTECTOR_INVALIDATED,
    STALE_PROTECTOR_OWNERSHIP,
    REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
    WRAP_REJECTED,
    WRAP_FAILED,
    UNWRAP_REJECTED,
    UNWRAP_FAILED,
    NONCE_VALIDATION_FAILED,
    CIPHERTEXT_AUTHENTICATION_FAILED,
    PROVIDER_FAILED,
    PERSISTENCE_CONFLICT,
    PERSISTENCE_FAILED,
    MIGRATION_CONFLICT,
    MIGRATION_INCOMPLETE,
    CLEANUP_FAILED
}

sealed interface CognitiveEncryptionResult<out T> {
    data class Success<T>(val value: T) : CognitiveEncryptionResult<T>
    data class Rejected(val category: CognitiveEncryptionFailureCategory) : CognitiveEncryptionResult<Nothing>
    data class Failed(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : CognitiveEncryptionResult<Nothing> {
        override fun toString(): String =
            "Failed(category=$category, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}
