package pro.liliya.core.encryption

enum class CognitiveKeyProtectorSecurityLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    SOFTWARE,
    UNKNOWN
}

data class CognitiveKeyProtectorCreationRequest(
    val id: CognitiveKeyProtectorId,
    val generation: CognitiveKeyProtectorGeneration,
    val requestedSecurityLevel: CognitiveKeyProtectorSecurityLevel,
    val purpose: CognitiveKeyPurpose = CognitiveKeyPurpose.COGNITIVE_STORAGE
) {
    init {
        require(requestedSecurityLevel == CognitiveKeyProtectorSecurityLevel.STRONGBOX ||
            requestedSecurityLevel == CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT) {
            "cognitive key protector must request a hardware-backed security level"
        }
    }
}

data class CognitiveKeyProtectorDescriptor(
    val reference: CognitiveKeyProtectorReference,
    val securityLevel: CognitiveKeyProtectorSecurityLevel,
    val purpose: CognitiveKeyPurpose
) {
    init {
        require(reference.platformReference != null) { "protector platform reference is required" }
    }
}

/**
 * Purpose-specific cognitive DEK protector. This is intentionally not a generic crypto executor,
 * entitlement decision, Authority grant, or Device Key capability surface.
 */
interface CognitiveKeyProtector {
    fun create(
        request: CognitiveKeyProtectorCreationRequest
    ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor>

    fun inspect(
        reference: CognitiveKeyProtectorReference
    ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor>

    fun wrap(
        expected: CognitiveKeyProtectorDescriptor,
        dek: CognitiveDekReference,
        material: CognitiveDekMaterial
    ): CognitiveEncryptionResult<WrappedCognitiveDekEnvelope>

    fun unwrap(
        expected: CognitiveKeyProtectorDescriptor,
        envelope: WrappedCognitiveDekEnvelope
    ): CognitiveEncryptionResult<CognitiveDekMaterial>

    fun retire(
        expected: CognitiveKeyProtectorDescriptor
    ): CognitiveEncryptionResult<Unit>
}
