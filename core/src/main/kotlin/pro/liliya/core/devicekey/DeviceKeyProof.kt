package pro.liliya.core.devicekey

import java.util.Arrays

/** Exact local device-key reference. It is ownership evidence, never permission. */
data class DeviceKeyReference(
    val id: DeviceKeyId,
    val generation: DeviceKeyGeneration
)

/**
 * Challenge bytes used only for a typed proof-of-possession operation.
 *
 * The backing bytes are copied on ingress/egress and are never rendered by toString().
 */
class DeviceKeyChallenge(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.isNotEmpty()) { "device key challenge must not be empty" }
    }

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DeviceKeyChallenge && Arrays.equals(value, other.value)

    override fun hashCode(): Int = Arrays.hashCode(value)

    override fun toString(): String = "DeviceKeyChallenge(bytes=${value.size})"
}

/**
 * Signature evidence returned by the device-key proof boundary.
 *
 * This is proof material for an enrollment transcript. It is not a License, capability,
 * Authority receipt or general bearer token. Raw bytes are intentionally absent from rendering.
 */
class DeviceKeyProofSignature(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.isNotEmpty()) { "device key proof signature must not be empty" }
    }

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is DeviceKeyProofSignature && Arrays.equals(value, other.value)

    override fun hashCode(): Int = Arrays.hashCode(value)

    override fun toString(): String = "DeviceKeyProofSignature(bytes=${value.size})"
}

data class DeviceKeyProofRequest(
    val key: DeviceKeyReference,
    val challenge: DeviceKeyChallenge
)

data class DeviceKeyPossessionProof(
    val key: DeviceKeyReference,
    val algorithm: DeviceKeyAlgorithm,
    val securityLevel: DeviceKeySecurityLevel,
    val signature: DeviceKeyProofSignature
) {
    init {
        require(securityLevel != DeviceKeySecurityLevel.UNKNOWN) {
            "device key possession proof security level must be known"
        }
    }

    override fun toString(): String =
        "DeviceKeyPossessionProof(key=$key, algorithm=$algorithm, " +
            "securityLevel=$securityLevel, signature=$signature)"
}

/** Opaque structural enrollment reference. It is evidence, not entitlement or Authority. */
@JvmInline
value class DeviceEnrollmentReference(val value: String) {
    init {
        require(value.isNotBlank()) { "device enrollment reference must not be blank" }
    }

    override fun toString(): String = "DeviceEnrollmentReference([redacted])"
}

data class DeviceEnrollmentBinding(
    val enrollment: DeviceEnrollmentReference,
    val key: DeviceKeyReference
)

interface DeviceKeyProofSigner {
    fun signChallenge(
        expectedState: DeviceKeyState,
        challenge: DeviceKeyChallenge
    ): DeviceKeyOperationResult<DeviceKeyProofSignature>
}

class DeviceKeyProofService(
    private val composition: DeviceKeyComposition,
    private val signer: DeviceKeyProofSigner
) {
    fun provePossession(request: DeviceKeyProofRequest): DeviceKeyOperationResult<DeviceKeyPossessionProof> {
        val snapshot = composition.inspect(request.key.id)
            ?: return DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)

        if (snapshot.generation != request.key.generation) {
            return DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.STALE_OWNERSHIP)
        }

        val state = snapshot.state
        if (DeviceKeyCapability.SIGN_CHALLENGE !in state.capabilities) {
            return DeviceKeyOperationResult.Rejected(DeviceKeyFailureCategory.UNSUPPORTED_PROFILE)
        }

        return when (val signed = signer.signChallenge(state, request.challenge)) {
            is DeviceKeyOperationResult.Success -> DeviceKeyOperationResult.Success(
                DeviceKeyPossessionProof(
                    key = request.key,
                    algorithm = state.algorithm,
                    securityLevel = state.securityLevel,
                    signature = signed.value
                )
            )

            is DeviceKeyOperationResult.Rejected -> signed
            is DeviceKeyOperationResult.Failed -> signed
        }
    }
}
