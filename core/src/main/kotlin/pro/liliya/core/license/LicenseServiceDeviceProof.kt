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
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

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
        "LicenseServiceDeviceProofChallenge(protocolVersion=$protocolVersion, requestId=<redacted>, " +
            "operation=$operation, productId=<redacted>, subject=<redacted>, " +
            "enrollmentPresent=${enrollmentId != null}, key=$key, nonce=$nonce, " +
            "validFrom=$validFrom, validUntil=$validUntil)"
}

/** Exact structural association between one service challenge and the proof produced for it. */
class LicenseServiceDeviceProofEvidence internal constructor(
    val challenge: LicenseServiceDeviceProofChallenge,
    val proof: DeviceKeyPossessionProof
) {
    init {
        require(proof.key == challenge.key) {
            "license service device proof must reference the exact challenged device key"
        }
    }

    override fun toString(): String =
        "LicenseServiceDeviceProofEvidence(challenge=$challenge, proof=$proof)"
}

sealed interface LicenseServiceDeviceProofResult {
    class Produced internal constructor(
        val evidence: LicenseServiceDeviceProofEvidence
    ) : LicenseServiceDeviceProofResult {
        override fun toString(): String =
            "LicenseServiceDeviceProofResult.Produced(evidence=$evidence)"
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
    private val foundation: FoundationComposition,
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
            return reject(
                challenge,
                LicenseServiceDeviceProofRejection.CHALLENGE_NOT_YET_VALID
            )
        }
        if (!now.isBefore(challenge.validUntil)) {
            return reject(
                challenge,
                LicenseServiceDeviceProofRejection.CHALLENGE_EXPIRED
            )
        }
        if (hasOversizedTextField(challenge)) {
            return reject(
                challenge,
                LicenseServiceDeviceProofRejection.TRANSCRIPT_TOO_LARGE
            )
        }

        val transcript = encodeTranscript(challenge)
        if (transcript.size > maxTranscriptBytes) {
            return reject(
                challenge,
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
            is DeviceKeyOperationResult.Success -> {
                val evidence = LicenseServiceDeviceProofEvidence(
                    challenge = challenge,
                    proof = result.value
                )
                observeProduced(evidence)
                LicenseServiceDeviceProofResult.Produced(evidence)
            }

            is DeviceKeyOperationResult.Rejected -> {
                observeDeviceKeyRejected(challenge, result.category)
                LicenseServiceDeviceProofResult.DeviceKeyRejected(result.category)
            }

            is DeviceKeyOperationResult.Failed -> {
                val throwableClass = result.throwable?.javaClass?.name
                observeDeviceKeyFailed(challenge, result.category, throwableClass)
                LicenseServiceDeviceProofResult.DeviceKeyFailed(
                    category = result.category,
                    throwableClass = throwableClass
                )
            }
        }
    }

    internal fun transcriptForTest(
        challenge: LicenseServiceDeviceProofChallenge
    ): DeviceKeyChallenge = DeviceKeyChallenge(encodeTranscript(challenge))

    private fun hasOversizedTextField(challenge: LicenseServiceDeviceProofChallenge): Boolean {
        val values = buildList {
            add(DOMAIN)
            add(challenge.requestId.value)
            add(challenge.operation.name)
            add(challenge.productId.value)
            add(challenge.subject.value)
            challenge.enrollmentId?.let { add(it.value) }
            add(challenge.key.id.value)
        }
        return values.any { !utf8LengthAtMost(it, maxTranscriptBytes) }
    }

    /**
     * Computes a conservative UTF-8 byte bound without allocating the encoded byte array.
     * Malformed surrogate code units count as three bytes, which may reject early but never
     * understates the memory needed by a normal UTF-8 encoding.
     */
    private fun utf8LengthAtMost(value: String, limit: Int): Boolean {
        var used = 0
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val bytes = when {
                current.code <= 0x7F -> 1
                current.code <= 0x7FF -> 2
                Character.isHighSurrogate(current) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index += 1
                    4
                }
                else -> 3
            }
            if (used > limit - bytes) {
                return false
            }
            used += bytes
            index += 1
        }
        return true
    }

    private fun reject(
        challenge: LicenseServiceDeviceProofChallenge,
        reason: LicenseServiceDeviceProofRejection
    ): LicenseServiceDeviceProofResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LICENSE_SERVICE_DEVICE_PROOF_REJECTED",
            message = "license service device proof rejected",
            context = foundation.rootContext(
                operation = "proveLicenseServiceDevicePossession",
                component = "LicenseService",
                metadata = metadata(challenge)
            ),
            metadata = metadata(challenge) +
                ("licenseServiceDeviceProofRejection" to reason.name.lowercase())
        )
        return LicenseServiceDeviceProofResult.Rejected(reason)
    }

    private fun observeProduced(evidence: LicenseServiceDeviceProofEvidence) {
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LICENSE_SERVICE_DEVICE_PROOF_PRODUCED",
            message = "license service device proof produced",
            context = foundation.rootContext(
                operation = "proveLicenseServiceDevicePossession",
                component = "LicenseService",
                metadata = metadata(evidence.challenge)
            ),
            metadata = metadata(evidence.challenge) + mapOf(
                "deviceKeyAlgorithm" to evidence.proof.algorithm.value,
                "deviceKeySecurityLevel" to evidence.proof.securityLevel.name.lowercase()
            )
        )
    }

    private fun observeDeviceKeyRejected(
        challenge: LicenseServiceDeviceProofChallenge,
        category: DeviceKeyFailureCategory
    ) {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "LICENSE_SERVICE_DEVICE_KEY_PROOF_REJECTED",
            message = "license service device key proof rejected",
            context = foundation.rootContext(
                operation = "proveLicenseServiceDevicePossession",
                component = "LicenseService",
                metadata = metadata(challenge)
            ),
            metadata = metadata(challenge) +
                ("deviceKeyFailureCategory" to category.name.lowercase())
        )
    }

    private fun observeDeviceKeyFailed(
        challenge: LicenseServiceDeviceProofChallenge,
        category: DeviceKeyFailureCategory,
        throwableClass: String?
    ) {
        foundation.observability.record(
            severity = DiagnosticSeverity.ERROR,
            code = "LICENSE_SERVICE_DEVICE_KEY_PROOF_FAILED",
            message = "license service device key proof failed",
            context = foundation.rootContext(
                operation = "proveLicenseServiceDevicePossession",
                component = "LicenseService",
                metadata = metadata(challenge)
            ),
            metadata = metadata(challenge) + buildMap {
                put("deviceKeyFailureCategory", category.name.lowercase())
                throwableClass?.let { put("throwableClass", it) }
            }
        )
    }

    private fun metadata(challenge: LicenseServiceDeviceProofChallenge): Map<String, String> = mapOf(
        "licenseServiceProtocolVersion" to challenge.protocolVersion.value.toString(),
        "licenseServiceOperation" to challenge.operation.name.lowercase(),
        "licenseServiceEnrollmentPresent" to (challenge.enrollmentId != null).toString(),
        "deviceKeyGeneration" to challenge.key.generation.value.toString()
    )

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
