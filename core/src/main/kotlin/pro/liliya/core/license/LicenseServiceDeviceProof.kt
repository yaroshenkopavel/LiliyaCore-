package pro.liliya.core.license

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Arrays
import pro.liliya.core.devicekey.DeviceKeyChallenge
import pro.liliya.core.devicekey.DeviceKeyFailureCategory
import pro.liliya.core.devicekey.DeviceKeyOperationResult
import pro.liliya.core.devicekey.DeviceKeyPossessionProof
import pro.liliya.core.devicekey.DeviceKeyProofRequest
import pro.liliya.core.devicekey.DeviceKeyProofService
import pro.liliya.core.devicekey.DeviceKeyReference

/** Unpredictable service-issued nonce. Raw bytes are never rendered. */
class LicenseServiceDeviceProofNonce private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LicenseServiceDeviceProofNonce && Arrays.equals(bytes, other.bytes)

    override fun hashCode(): Int = Arrays.hashCode(bytes)

    override fun toString(): String =
        "LicenseServiceDeviceProofNonce(size=${bytes.size}, content=<redacted>)"

    companion object {
        private const val MAX_BYTES = 256

        fun of(bytes: ByteArray): LicenseServiceDeviceProofNonce {
            require(bytes.isNotEmpty()) { "license service device proof nonce must not be empty" }
            require(bytes.size <= MAX_BYTES) {
                "license service device proof nonce must not exceed $MAX_BYTES bytes"
            }
            return LicenseServiceDeviceProofNonce(bytes.copyOf())
        }
    }
}

/**
 * Structural service challenge for proof-of-possession.
 *
 * Signing this challenge proves only possession of [key] for this exact service transcript.
 * It is not enrollment acceptance, License entitlement, Authority or Execution permission.
 */
class LicenseServiceDeviceProofChallenge(
    val protocolVersion: LicenseServiceProtocolVersion,
    val requestId: LicenseServiceRequestId,
    val operation: LicenseServiceOperation,
    val productId: LicenseProductId,
    val subject: LicenseSubject,
    val enrollmentId: LicenseServiceEnrollmentId?,
    val key: DeviceKeyReference,
    val nonce: LicenseServiceDeviceProofNonce,
    val validFrom: Instant,
    val validUntil: Instant
) {
    init {
        require(validFrom.isBefore(validUntil)) {
            "license service device proof validity window must be non-empty"
        }
    }

    override fun toString(): String =
        "LicenseServiceDeviceProofChallenge(protocolVersion=$protocolVersion, requestId=$requestId, " +
            "operation=$operation, productId=$productId, subject=<redacted>, " +
            "enrollmentPresent=${enrollmentId != null}, key=$key, nonce=$nonce, " +
            "validFrom=$validFrom, validUntil=$validUntil)"
}

sealed interface LicenseServiceDeviceProofResult {
    class Produced internal constructor(
        val proof: DeviceKeyPossessionProof
    ) : LicenseServiceDeviceProofResult {
        override fun toString(): String =
            "LicenseServiceDeviceProofResult.Produced(proof=$proof)"
    }

    data class Rejected(val reason: LicenseServiceDeviceProofRejection) :
        LicenseServiceDeviceProofResult

    data class DeviceKeyRejected(val category: DeviceKeyFailureCategory) :
        LicenseServiceDeviceProofResult

    data class DeviceKeyFailed(
        val category: DeviceKeyFailureCategory,
        val throwableClass: String?
    ) : LicenseServiceDeviceProofResult
}

enum class LicenseServiceDeviceProofRejection {
    CHALLENGE_NOT_YET_VALID,
    CHALLENGE_EXPIRED,
    TRANSCRIPT_TOO_LARGE
}

/**
 * Android-free adapter from a service-scoped challenge into the frozen signing-only Device Key.
 * No raw signing-key material, platform key export, enrollment acceptance or License mutation occurs here.
 */
class LicenseServiceDeviceProofComposition(
    private val proofService: DeviceKeyProofService,
    private val maxTranscriptBytes: Int = 4096
) {
    init {
        require(maxTranscriptBytes > 0) {
            "license service device proof max transcript bytes must be positive"
        }
    }

    fun prove(
        challenge: LicenseServiceDeviceProofChallenge,
        now: Instant
    ): LicenseServiceDeviceProofResult {
        if (now.isBefore(challenge.validFrom)) {
            return LicenseServiceDeviceProofResult.Rejected(
                LicenseServiceDeviceProofRejection.CHALLENGE_NOT_YET_VALID
            )
        }
        if (!now.isBefore(challenge.validUntil)) {
            return LicenseServiceDeviceProofResult.Rejected(
                LicenseServiceDeviceProofRejection.CHALLENGE_EXPIRED
            )
        }

        val transcript = encodeTranscript(challenge)
        if (transcript.size > maxTranscriptBytes) {
            return LicenseServiceDeviceProofResult.Rejected(
                LicenseServiceDeviceProofRejection.TRANSCRIPT_TOO_LARGE
            )
        }

        return when (
            val result = proofService.provePossession(
                DeviceKeyProofRequest(
                    key = challenge.key,
                    challenge = DeviceKeyChallenge(transcript)
                )
            )
        ) {
            is DeviceKeyOperationResult.Success ->
                LicenseServiceDeviceProofResult.Produced(result.value)

            is DeviceKeyOperationResult.Rejected ->
                LicenseServiceDeviceProofResult.DeviceKeyRejected(result.category)

            is DeviceKeyOperationResult.Failed ->
                LicenseServiceDeviceProofResult.DeviceKeyFailed(
                    category = result.category,
                    throwableClass = result.throwable?.javaClass?.name
                )
        }
    }

    internal fun transcriptForTest(
        challenge: LicenseServiceDeviceProofChallenge
    ): DeviceKeyChallenge = DeviceKeyChallenge(encodeTranscript(challenge))

    private fun encodeTranscript(challenge: LicenseServiceDeviceProofChallenge): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(TRANSCRIPT_MAGIC)
                data.writeString(DOMAIN)
                data.writeLong(challenge.protocolVersion.value)
                data.writeString(challenge.requestId.value)
                data.writeString(challenge.operation.name)
                data.writeString(challenge.productId.value)
                data.writeString(challenge.subject.value)
                data.writeBoolean(challenge.enrollmentId != null)
                challenge.enrollmentId?.let { data.writeString(it.value) }
                data.writeString(challenge.key.id.value)
                data.writeLong(challenge.key.generation.value)
                val nonceBytes = challenge.nonce.copyBytes()
                data.writeInt(nonceBytes.size)
                data.write(nonceBytes)
                data.writeLong(challenge.validFrom.epochSecond)
                data.writeInt(challenge.validFrom.nano)
                data.writeLong(challenge.validUntil.epochSecond)
                data.writeInt(challenge.validUntil.nano)
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        const val TRANSCRIPT_MAGIC = 0x4C534450
        const val DOMAIN = "LILIYA-LICENSE-SERVICE-DEVICE-PROOF-V1"
    }
}
