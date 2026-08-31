package pro.liliya.core.protectedmodel

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
            ProtectedModelEncryptionAlgorithm.AES_256_GCM,
            256,
            12,
            128
        )
    }
}

@JvmInline
value class ProtectedModelPackageFormatVersion(val value: Int) {
    init { require(value > 0) { "protected model package format version must be positive" } }
}

data class ProtectedModelManifest(
    val formatVersion: ProtectedModelPackageFormatVersion,
    val model: ProtectedModelReference,
    val modelFormat: String,
    val expectedPlaintextSizeBytes: Long,
    val modelDek: ModelDekReference,
    val encryptionProfile: ProtectedModelEncryptionProfile,
    val signatureAlgorithm: ProtectedModelSignatureAlgorithm,
    val signerId: ProtectedModelSignerId
) {
    init {
        require(modelFormat.isNotBlank()) { "model format must not be blank" }
        require(expectedPlaintextSizeBytes > 0L) { "expected model plaintext size must be positive" }
    }

    override fun toString(): String =
        "ProtectedModelManifest(formatVersion=${formatVersion.value}, model=$model, modelFormat=$modelFormat, " +
            "expectedPlaintextSizeBytes=$expectedPlaintextSizeBytes, modelDek=$modelDek, " +
            "encryptionProfile=$encryptionProfile, signatureAlgorithm=$signatureAlgorithm, signerId=$signerId)"
}

class ProtectedModelSignature(
    val algorithm: ProtectedModelSignatureAlgorithm,
    val signerId: ProtectedModelSignerId,
    signature: ByteArray
) {
    private val signatureBytes = signature.copyOf()

    init { require(signatureBytes.isNotEmpty()) { "protected model signature must not be empty" } }

    fun copyBytes(): ByteArray = signatureBytes.copyOf()

    override fun toString(): String =
        "ProtectedModelSignature(algorithm=$algorithm, signerId=$signerId, signature=<redacted:${signatureBytes.size} bytes>)"
}

class ProtectedModelEncryptedPayload(
    val model: ProtectedModelReference,
    val modelDek: ModelDekReference,
    val profile: ProtectedModelEncryptionProfile,
    nonce: ByteArray,
    ciphertext: ByteArray,
    authenticationTag: ByteArray
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()
    private val authenticationTagBytes = authenticationTag.copyOf()

    init {
        require(nonceBytes.size == profile.nonceSizeBytes) { "invalid protected model nonce size" }
        require(ciphertextBytes.isNotEmpty()) { "protected model ciphertext must not be empty" }
        require(authenticationTagBytes.size * 8 == profile.authenticationTagSizeBits) {
            "invalid protected model authentication tag size"
        }
    }

    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyCiphertext(): ByteArray = ciphertextBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = authenticationTagBytes.copyOf()

    override fun toString(): String =
        "ProtectedModelEncryptedPayload(model=$model, modelDek=$modelDek, profile=$profile, " +
            "nonce=<redacted:${nonceBytes.size} bytes>, ciphertext=<redacted:${ciphertextBytes.size} bytes>, " +
            "authenticationTag=<redacted:${authenticationTagBytes.size} bytes>)"
}

class ProtectedModelPackage(
    val manifest: ProtectedModelManifest,
    val payload: ProtectedModelEncryptedPayload,
    val signature: ProtectedModelSignature
) {
    init {
        require(payload.model == manifest.model) { "protected model payload identity mismatch" }
        require(payload.modelDek == manifest.modelDek) { "protected model DEK reference mismatch" }
        require(payload.profile == manifest.encryptionProfile) { "protected model encryption profile mismatch" }
        require(signature.algorithm == manifest.signatureAlgorithm) { "protected model signature algorithm mismatch" }
        require(signature.signerId == manifest.signerId) { "protected model signer mismatch" }
    }

    override fun toString(): String =
        "ProtectedModelPackage(manifest=$manifest, payload=$payload, signature=$signature)"
}
