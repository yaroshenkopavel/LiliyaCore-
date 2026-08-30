package pro.liliya.core.devicekey

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class DeviceKeyProofContractTest {
    private val createdAt = Instant.parse("2026-08-30T21:10:00Z")
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "device-key-proof-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun state(
        id: String = "device-main",
        capabilities: Set<DeviceKeyCapability> = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
    ) = DeviceKeyState(
        id = DeviceKeyId(id),
        algorithm = algorithm,
        securityLevel = DeviceKeySecurityLevel.STRONGBOX,
        capabilities = capabilities,
        createdAt = createdAt
    )

    private class RecordingSigner(
        private val result: DeviceKeyOperationResult<DeviceKeyProofSignature>
    ) : DeviceKeyProofSigner {
        var calls = 0
        var lastState: DeviceKeyState? = null

        override fun signChallenge(
            expectedState: DeviceKeyState,
            challenge: DeviceKeyChallenge
        ): DeviceKeyOperationResult<DeviceKeyProofSignature> {
            calls += 1
            lastState = expectedState
            return result
        }
    }

    @Test
    fun proof_requires_exact_live_generation_before_signing() {
        val composition = DeviceKeyComposition(foundation())
        val first = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(state())
        ).ownership
        assertTrue(first.remove())
        val replacement = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(state())
        ).ownership
        val signer = RecordingSigner(
            DeviceKeyOperationResult.Success(DeviceKeyProofSignature(byteArrayOf(1, 2, 3)))
        )
        val service = DeviceKeyProofService(composition, signer)

        val result = service.provePossession(
            DeviceKeyProofRequest(
                key = DeviceKeyReference(first.state.id, first.generation),
                challenge = DeviceKeyChallenge(byteArrayOf(9))
            )
        )

        assertEquals(
            DeviceKeyFailureCategory.STALE_OWNERSHIP,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(0, signer.calls)
        assertEquals(replacement.generation, composition.inspect(replacement.state.id)?.generation)
    }

    @Test
    fun proof_uses_exact_live_state_and_returns_structural_evidence() {
        val composition = DeviceKeyComposition(foundation())
        val ownership = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(state())
        ).ownership
        val signatureBytes = byteArrayOf(5, 6, 7, 8)
        val signer = RecordingSigner(
            DeviceKeyOperationResult.Success(DeviceKeyProofSignature(signatureBytes))
        )
        val service = DeviceKeyProofService(composition, signer)

        val proof = assertIs<DeviceKeyOperationResult.Success<DeviceKeyPossessionProof>>(
            service.provePossession(
                DeviceKeyProofRequest(
                    key = DeviceKeyReference(ownership.state.id, ownership.generation),
                    challenge = DeviceKeyChallenge(byteArrayOf(1, 2, 3))
                )
            )
        ).value

        assertEquals(1, signer.calls)
        assertEquals(ownership.state, signer.lastState)
        assertEquals(DeviceKeyReference(ownership.state.id, ownership.generation), proof.key)
        assertEquals(algorithm, proof.algorithm)
        assertEquals(DeviceKeySecurityLevel.STRONGBOX, proof.securityLevel)
        assertContentEquals(signatureBytes, proof.signature.copyBytes())
    }

    @Test
    fun missing_sign_capability_fails_before_signing() {
        val composition = DeviceKeyComposition(foundation())
        val ownership = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(state(capabilities = setOf(DeviceKeyCapability.UNWRAP_WRAPPED_KEY)))
        ).ownership
        val signer = RecordingSigner(
            DeviceKeyOperationResult.Success(DeviceKeyProofSignature(byteArrayOf(1)))
        )
        val service = DeviceKeyProofService(composition, signer)

        val result = service.provePossession(
            DeviceKeyProofRequest(
                key = DeviceKeyReference(ownership.state.id, ownership.generation),
                challenge = DeviceKeyChallenge(byteArrayOf(2))
            )
        )

        assertEquals(
            DeviceKeyFailureCategory.UNSUPPORTED_PROFILE,
            assertIs<DeviceKeyOperationResult.Rejected>(result).category
        )
        assertEquals(0, signer.calls)
    }

    @Test
    fun challenge_signature_and_enrollment_rendering_do_not_expose_raw_material() {
        val challengeSecret = "CHALLENGE-PRIVATE"
        val signatureSecret = "SIGNATURE-PRIVATE"
        val enrollmentSecret = "ENROLLMENT-OPAQUE-PRIVATE"
        val challenge = DeviceKeyChallenge(challengeSecret.encodeToByteArray())
        val signature = DeviceKeyProofSignature(signatureSecret.encodeToByteArray())
        val enrollment = DeviceEnrollmentReference(enrollmentSecret)

        assertFalse(challengeSecret in challenge.toString())
        assertFalse(signatureSecret in signature.toString())
        assertFalse(enrollmentSecret in enrollment.toString())
        assertTrue("bytes=" in challenge.toString())
        assertTrue("bytes=" in signature.toString())

        val challengeCopy = challenge.copyBytes()
        val signatureCopy = signature.copyBytes()
        challengeCopy.fill(0)
        signatureCopy.fill(0)
        assertContentEquals(challengeSecret.encodeToByteArray(), challenge.copyBytes())
        assertContentEquals(signatureSecret.encodeToByteArray(), signature.copyBytes())
    }

    @Test
    fun enrollment_binding_is_structural_and_exact_generation_scoped() {
        val reference = DeviceKeyReference(DeviceKeyId("device-main"), DeviceKeyGeneration(7))
        val binding = DeviceEnrollmentBinding(
            enrollment = DeviceEnrollmentReference("enrollment-ref-7"),
            key = reference
        )

        assertEquals(reference, binding.key)
        assertEquals("enrollment-ref-7", binding.enrollment.value)
    }
}
