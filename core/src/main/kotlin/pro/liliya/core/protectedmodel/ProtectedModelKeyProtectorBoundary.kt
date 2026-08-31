package pro.liliya.core.protectedmodel

@JvmInline
value class ProtectedModelKeyProtectorId(val value: String) {
    init { require(value.isNotBlank()) { "protected model key protector id must not be blank" } }
    override fun toString(): String = "ProtectedModelKeyProtectorId([redacted])"
}

@JvmInline
value class ProtectedModelKeyProtectorGeneration(val value: Long) {
    init { require(value > 0L) { "protected model key protector generation must be positive" } }
}

@JvmInline
value class ProtectedModelKeyProtectorPlatformReference(val value: String) {
    init { require(value.isNotBlank()) { "platform reference must not be blank" } }
    override fun toString(): String = "ProtectedModelKeyProtectorPlatformReference([redacted])"
}

data class ProtectedModelKeyProtectorReference(
    val id: ProtectedModelKeyProtectorId,
    val generation: ProtectedModelKeyProtectorGeneration,
    val platformReference: ProtectedModelKeyProtectorPlatformReference
)

enum class ProtectedModelKeyProtectorSecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }

data class ProtectedModelKeyProtectorCreationRequest(
    val id: ProtectedModelKeyProtectorId,
    val generation: ProtectedModelKeyProtectorGeneration,
    val requestedSecurityLevel: ProtectedModelKeyProtectorSecurityLevel
) {
    init { require(requestedSecurityLevel != ProtectedModelKeyProtectorSecurityLevel.UNKNOWN) }
}

data class ProtectedModelKeyProtectorDescriptor(
    val reference: ProtectedModelKeyProtectorReference,
    val securityLevel: ProtectedModelKeyProtectorSecurityLevel
)

enum class ProtectedModelKeyProtectorFailure {
    INVALID_REQUEST,
    PROTECTOR_MISSING,
    PROTECTOR_INVALIDATED,
    STALE_PROTECTOR_OWNERSHIP,
    REQUIRED_SECURITY_LEVEL_UNAVAILABLE,
    WRAP_FAILED,
    UNWRAP_FAILED,
    CLEANUP_FAILED,
    PROVIDER_FAILED
}

sealed interface ProtectedModelKeyProtectorResult<out T> {
    data class Success<T>(val value: T) : ProtectedModelKeyProtectorResult<T>
    data class Rejected(val reason: ProtectedModelKeyProtectorFailure) : ProtectedModelKeyProtectorResult<Nothing>
    data class Failed(
        val reason: ProtectedModelKeyProtectorFailure,
        val throwable: Throwable? = null
    ) : ProtectedModelKeyProtectorResult<Nothing> {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

class ProtectedModelDekMaterial(bytes: ByteArray) {
    private val value = bytes.copyOf()
    init { require(value.size == 32) { "model DEK must be exactly 256 bits" } }
    fun copyBytes(): ByteArray = value.copyOf()
    override fun toString(): String = "ProtectedModelDekMaterial([redacted:${value.size} bytes])"
}

class WrappedProtectedModelDek(
    val dek: ModelDekReference,
    val protector: ProtectedModelKeyProtectorReference,
    wrapped: ByteArray,
    nonce: ByteArray,
    authenticationTag: ByteArray
) {
    private val wrappedBytes = wrapped.copyOf()
    private val nonceBytes = nonce.copyOf()
    private val tagBytes = authenticationTag.copyOf()
    init {
        require(wrappedBytes.isNotEmpty())
        require(nonceBytes.size == 12)
        require(tagBytes.size == 16)
    }
    fun copyWrapped(): ByteArray = wrappedBytes.copyOf()
    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyAuthenticationTag(): ByteArray = tagBytes.copyOf()
    override fun toString(): String =
        "WrappedProtectedModelDek(dek=$dek, protector=$protector, wrapped=<redacted:${wrappedBytes.size} bytes>)"
}

/** Purpose-specific protected-model key protector. Not License, Authority, Device Key or a generic crypto executor. */
interface ProtectedModelKeyProtector {
    fun create(request: ProtectedModelKeyProtectorCreationRequest): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor>
    fun inspect(reference: ProtectedModelKeyProtectorReference): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor>
    fun wrap(expected: ProtectedModelKeyProtectorDescriptor, dek: ModelDekReference, material: ProtectedModelDekMaterial): ProtectedModelKeyProtectorResult<WrappedProtectedModelDek>
    fun unwrap(expected: ProtectedModelKeyProtectorDescriptor, envelope: WrappedProtectedModelDek): ProtectedModelKeyProtectorResult<ProtectedModelDekMaterial>
    fun retire(expected: ProtectedModelKeyProtectorDescriptor): ProtectedModelKeyProtectorResult<Unit>
}
