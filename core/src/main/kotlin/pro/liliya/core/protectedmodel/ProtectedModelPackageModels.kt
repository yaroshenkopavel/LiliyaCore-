package pro.liliya.core.protectedmodel

import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class ProtectedModelPackageId(val value: String) {
    init { require(value.isNotBlank()) { "protected model package id must not be blank" } }
    override fun toString(): String = "ProtectedModelPackageId([redacted])"
}

@JvmInline
value class ProtectedModelGeneration(val value: Long) {
    init { require(value > 0L) { "protected model generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class ProtectedModelReference(
    val packageId: ProtectedModelPackageId,
    val generation: ProtectedModelGeneration
)

@JvmInline
value class ProtectedModelFormatVersion(val value: Int) {
    init { require(value > 0) { "protected model format version must be positive" } }
}

@JvmInline
value class ProtectedModelProfileId(val value: String) {
    init { require(value.isNotBlank()) { "protected model profile id must not be blank" } }
    override fun toString(): String = "ProtectedModelProfileId([redacted])"
}

@JvmInline
value class ModelDekId(val value: String) {
    init { require(value.isNotBlank()) { "model DEK id must not be blank" } }
    override fun toString(): String = "ModelDekId([redacted])"
}

@JvmInline
value class ModelDekGeneration(val value: Long) {
    init { require(value > 0L) { "model DEK generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class ModelDekReference(
    val id: ModelDekId,
    val generation: ModelDekGeneration
)

@JvmInline
value class ProtectedModelSignerId(val value: String) {
    init { require(value.isNotBlank()) { "protected model signer id must not be blank" } }
    override fun toString(): String = "ProtectedModelSignerId([redacted])"
}

enum class ProtectedModelSignatureAlgorithm {
    ED25519
}

enum class ProtectedModelEncryptionAlgorithm {
    AES_256_GCM
}

data class ProtectedModelEncryptionProfile(
    val algorithm: ProtectedModelEncryptionAlgorithm,
    val keySizeBits: Int,
    val nonceSizeBytes: Int,
    val authenticationTagSizeBits: Int
) {
    init {
        require(
            algorithm == ProtectedModelEncryptionAlgorithm.AES_256_GCM &&
                keySizeBits == 256 &&
                nonceSizeBytes == 12 &&
                authenticationTagSizeBits == 128
        ) { "unsupported protected model encryption profile" }
    }

    companion object {
        val AES_256_GCM = ProtectedModelEncryptionProfile(
            algorithm = ProtectedModelEncryptionAlgorithm.AES_256_GCM,
            keySizeBits = 256,
            nonceSizeBytes = 12,
            authenticationTagSizeBits = 128
        )
    }
}

data class ProtectedModelManifest(
    val formatVersion: ProtectedModelFormatVersion,
    val model: ProtectedModelReference,
    val profileId: ProtectedModelProfileId,
    val plaintextSizeBytes: Long,
    val ciphertextSizeBytes: Long,
    val modelDek: ModelDekReference,
    val encryptionProfile: ProtectedModelEncryptionProfile,
    val signatureAlgorithm: ProtectedModelSignatureAlgorithm,
    val signerId: ProtectedModelSignerId
) {
    init {
        require(plaintextSizeBytes > 0L) { "protected model plaintext size must be positive" }
        require(ciphertextSizeBytes > 0L) { "protected model ciphertext size must be positive" }
    }

    override fun toString(): String =
        "ProtectedModelManifest(formatVersion=${formatVersion.value}, model=$model, " +
            "profileId=$profileId, plaintextSizeBytes=$plaintextSizeBytes, " +
            "ciphertextSizeBytes=$ciphertextSizeBytes, modelDek=$modelDek, " +
            "encryptionProfile=$encryptionProfile, signatureAlgorithm=$signatureAlgorithm, signerId=$signerId)"
}

class ProtectedModelPackageEnvelope(
    val manifest: ProtectedModelManifest,
    payloadDigest: ByteArray,
    nonce: ByteArray,
    authenticationTag: ByteArray,
    signature: ByteArray
) {
    private val payloadDigestBytes = payloadDigest.copyOf()
    private val nonceBytes = nonce.copyOf()
    private val authenticationTagBytes = authenticationTag.copyOf()
    private val signatureBytes = signature.copyOf()

    init {
        require(payloadDigestBytes.isNotEmpty()) { "protected model payload digest must not be empty" }
        require(nonceBytes.size == manifest.encryptionProfile.nonceSizeBytes) {
            "invalid protected model nonce size"
        }
        require(authenticationTagBytes.size * 8 == manifest.encryptionProfile.authenticationTagSizeBits) {
            "invalid protected model authentication tag size"
        }
        require(signatureBytes.isNotEmpty()) { "protected model signature must not be empty" }
    }

    fun copyPayloadDigest(): ByteArray = payloadDigestBytes.copyOf()
    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = authenticationTagBytes.copyOf()
    fun copySignature(): ByteArray = signatureBytes.copyOf()

    override fun toString(): String =
        "ProtectedModelPackageEnvelope(manifest=$manifest, " +
            "payloadDigest=<redacted:${payloadDigestBytes.size} bytes>, " +
            "nonce=<redacted:${nonceBytes.size} bytes>, " +
            "authenticationTag=<redacted:${authenticationTagBytes.size} bytes>, " +
            "signature=<redacted:${signatureBytes.size} bytes>)"
}

interface ProtectedModelPackageOwnership {
    val reference: ProtectedModelReference
    fun retire(): Boolean
}

sealed interface ProtectedModelPackageRegistrationResult {
    data class Registered(
        val ownership: ProtectedModelPackageOwnership
    ) : ProtectedModelPackageRegistrationResult

    data class Rejected(val reason: String) : ProtectedModelPackageRegistrationResult
}

/**
 * Process-local exact ownership for protected model package generations.
 * This is structural ownership only: it is not authenticity, entitlement, Authority or execution permission.
 */
class ProtectedModelPackageStore internal constructor(
    initialGeneration: Long = 0L
) {
    private data class Entry(val reference: ProtectedModelReference)

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private val entries = mutableMapOf<ProtectedModelPackageId, Entry>()

    fun register(id: ProtectedModelPackageId): ProtectedModelPackageRegistrationResult = synchronized(lock) {
        if (entries.containsKey(id)) {
            return@synchronized ProtectedModelPackageRegistrationResult.Rejected(
                "protected model package id is already registered"
            )
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized ProtectedModelPackageRegistrationResult.Rejected(
                "protected model generation overflow"
            )
        }

        val entry = Entry(
            ProtectedModelReference(
                packageId = id,
                generation = ProtectedModelGeneration(nextValue)
            )
        )
        entries[id] = entry
        ProtectedModelPackageRegistrationResult.Registered(ownership(entry))
    }

    fun inspect(id: ProtectedModelPackageId): ProtectedModelReference? = synchronized(lock) {
        entries[id]?.reference
    }

    fun snapshot(): List<ProtectedModelReference> = synchronized(lock) {
        entries.values
            .map { it.reference }
            .sortedWith(
                compareBy<ProtectedModelReference> { it.generation.value }
                    .thenBy { it.packageId.value }
            )
    }

    private fun ownership(entry: Entry): ProtectedModelPackageOwnership =
        object : ProtectedModelPackageOwnership {
            override val reference: ProtectedModelReference = entry.reference

            override fun retire(): Boolean = synchronized(lock) {
                entries[entry.reference.packageId] === entry &&
                    entries.remove(entry.reference.packageId) === entry
            }
        }
}
