package pro.liliya.core.license

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.devicekey.DeviceKeyAlgorithm
import pro.liliya.core.devicekey.DeviceKeyCapability
import pro.liliya.core.devicekey.DeviceKeyComposition
import pro.liliya.core.devicekey.DeviceKeyFailureCategory
import pro.liliya.core.devicekey.DeviceKeyGeneration
import pro.liliya.core.devicekey.DeviceKeyId
import pro.liliya.core.devicekey.DeviceKeyOperationResult
import pro.liliya.core.devicekey.DeviceKeyPlatformReference
import pro.liliya.core.devicekey.DeviceKeyProofService
import pro.liliya.core.devicekey.DeviceKeyProofSignature
import pro.liliya.core.devicekey.DeviceKeyProofSigner
import pro.liliya.core.devicekey.DeviceKeyReference
import pro.liliya.core.devicekey.DeviceKeyRegisterResult
import pro.liliya.core.devicekey.DeviceKeySecurityLevel
import pro.liliya.core.devicekey.DeviceKeyState
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseServiceDeviceProofContractTest {
    private val now = Instant.parse("2026-09-01T09:00:00Z")
    private val protocol = LicenseServiceProtocolVersion(1)
    private val algorithm = DeviceKeyAlgorithm("EC-P256-SHA256")

    private data class ObservedFoundation(
        val foundation: FoundationComposition,
        val diagnostics: InMemoryDiagnosticSink,
        val logs: InMemoryLogWriter
    )

    private fun observedFoundation(): ObservedFoundation {
        val diagnostics = InMemoryDiagnosticSink()
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return ObservedFoundation(
            foundation = FoundationComposition(
                diagnostics = DiagnosticRecorder(diagnostics),
                loggerProvider = LoggerProvider { context ->
                    StructuredLogger(context, logs)
                },
                correlationIds = CorrelationIdGenerator {
                    "license-service-device-proof-${sequence.incrementAndGet()}"
                }
            ),
            diagnostics = diagnostics,
            logs = logs
        )
    }

    private fun state(
        id: String = "PRIVATE-DEVICE-KEY-ID",
        platformReference: String = "PRIVATE-PLATFORM-REFERENCE"
    ) = DeviceKeyState(
        id = DeviceKeyId(id),
        algorithm = algorithm,
        securityLevel = DeviceKeySecurityLevel.STRONGBOX,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
        createdAt = now.minusSeconds(300),
        platformReference = DeviceKeyPlatformReference(platformReference)
    )

    private class RecordingSigner(
        private val result: DeviceKeyOperationResult<DeviceKeyProofSignature> =
            DeviceKeyOperationResult.Success(
                DeviceKeyProofSignature("PRIVATE-DEVICE-SIGNATURE".encodeToByteArray())
            )
    ) : DeviceKeyProofSigner {
        var calls = 0
        var lastChallengeBytes: ByteArray? = null

        override fun signChallenge(
            expectedState: DeviceKeyState,
            challenge: pro.liliya.core.devicekey.DeviceKeyChallenge
        ): DeviceKeyOperationResult<DeviceKeyProofSignature> {
            calls += 1
            lastChallengeBytes = challenge.copyBytes()
            return result
        }
    }

    private data class Fixture(
        val observed: ObservedFoundation,
        val keyComposition: DeviceKeyComposition,
        val signer: RecordingSigner,
        val service: LicenseServiceDeviceProofComposition,
        val key: DeviceKeyReference
    )

    private fun fixture(
        maxTranscriptBytes: Int = 4096,
        signer: RecordingSigner = RecordingSigner()
    ): Fixture {
        val observed = observedFoundation()
        val keyComposition = DeviceKeyComposition(observed.foundation)
        val ownership = assertIs<DeviceKeyRegisterResult.Registered>(
            keyComposition.register(state())
        ).ownership
        val proofService = DeviceKeyProofService(keyComposition, signer)
        return Fixture(
            observed = observed,
            keyComposition = keyComposition,
            signer = signer,
            service = LicenseServiceDeviceProofComposition(
                foundation = observed.foundation,
                proofService = proofService,
                maxTranscriptBytes = maxTranscriptBytes
            ),
            key = DeviceKeyReference(ownership.state.id, ownership.generation)
        )
    }

    private fun challenge(
        key: DeviceKeyReference,
        requestId: String = "request-1",
        operation: LicenseServiceOperation = LicenseServiceOperation.ISSUE,
        productId: String = "liliya-pro",
        subject: String = "PRIVATE-LICENSE-SUBJECT",
        enrollmentId: String? = "PRIVATE-ENROLLMENT-ID",
        nonce: ByteArray = byteArrayOf(1, 2, 3, 4),
        validFrom: Instant = now.minusSeconds(1),
        validUntil: Instant = now.plusSeconds(60),
        protocolVersion: LicenseServiceProtocolVersion = protocol
    ) = LicenseServiceDeviceProofChallenge(
        protocolVersion = protocolVersion,
        requestId = LicenseServiceRequestId(requestId),
        operation = operation,
        productId = LicenseProductId(productId),
        subject = LicenseSubject(subject),
        enrollmentId = enrollmentId?.let(::LicenseServiceEnrollmentId),
        key = key,
        nonce = LicenseServiceDeviceProofNonce.of(nonce),
        validFrom = validFrom,
        validUntil = validUntil
    )

    @Test
    fun proof_signs_the_exact_service_scoped_transcript_with_frozen_device_key() {
        val fixture = fixture()
        val challenge = challenge(fixture.key)
        val expectedTranscript = fixture.service.transcriptForTest(challenge).copyBytes()

        val produced = assertIs<LicenseServiceDeviceProofResult.Produced>(
            fixture.service.prove(challenge, now)
        )

        assertTrue(produced.evidence.challenge === challenge)
        assertEquals(fixture.key, produced.evidence.proof.key)
        assertEquals(algorithm, produced.evidence.proof.algorithm)
        assertEquals(DeviceKeySecurityLevel.STRONGBOX, produced.evidence.proof.securityLevel)
        assertEquals(1, fixture.signer.calls)
        assertContentEquals(expectedTranscript, fixture.signer.lastChallengeBytes)
        assertFalse("PRIVATE-LICENSE-SUBJECT" in challenge.toString())
        assertFalse("PRIVATE-ENROLLMENT-ID" in challenge.toString())
        assertFalse("PRIVATE-DEVICE-SIGNATURE" in produced.toString())
    }

    @Test
    fun every_security_relevant_service_or_key_field_changes_the_signed_transcript() {
        val fixture = fixture()
        val base = challenge(fixture.key)
        val baseBytes = fixture.service.transcriptForTest(base).copyBytes()

        val variants = listOf(
            challenge(fixture.key, requestId = "request-2"),
            challenge(fixture.key, operation = LicenseServiceOperation.REFRESH),
            challenge(fixture.key, productId = "other-product"),
            challenge(fixture.key, subject = "PRIVATE-OTHER-SUBJECT"),
            challenge(fixture.key, enrollmentId = null),
            challenge(fixture.key, enrollmentId = "PRIVATE-OTHER-ENROLLMENT"),
            challenge(
                DeviceKeyReference(fixture.key.id, DeviceKeyGeneration(fixture.key.generation.value + 1))
            ),
            challenge(
                DeviceKeyReference(DeviceKeyId("PRIVATE-OTHER-KEY-ID"), fixture.key.generation)
            ),
            challenge(fixture.key, nonce = byteArrayOf(9, 9, 9)),
            challenge(fixture.key, validFrom = now.minusSeconds(30)),
            challenge(fixture.key, validUntil = now.plusSeconds(120)),
            challenge(
                fixture.key,
                protocolVersion = LicenseServiceProtocolVersion(protocol.value + 1)
            )
        )

        variants.forEach { variant ->
            assertFalse(
                baseBytes.contentEquals(fixture.service.transcriptForTest(variant).copyBytes()),
                "security-relevant transcript field must be domain-bound: $variant"
            )
        }
    }

    @Test
    fun malformed_or_outside_validity_window_challenge_is_rejected_before_device_key_signing() {
        val fixture = fixture()

        assertFailsWith<IllegalArgumentException> {
            challenge(
                key = fixture.key,
                validFrom = now.plusSeconds(10),
                validUntil = now.plusSeconds(10)
            )
        }

        val notYetValid = fixture.service.prove(
            challenge(
                key = fixture.key,
                validFrom = now.plusSeconds(1),
                validUntil = now.plusSeconds(60)
            ),
            now
        )
        assertEquals(
            LicenseServiceDeviceProofRejection.CHALLENGE_NOT_YET_VALID,
            assertIs<LicenseServiceDeviceProofResult.Rejected>(notYetValid).reason
        )

        val expired = fixture.service.prove(
            challenge(
                key = fixture.key,
                validFrom = now.minusSeconds(60),
                validUntil = now
            ),
            now
        )
        assertEquals(
            LicenseServiceDeviceProofRejection.CHALLENGE_EXPIRED,
            assertIs<LicenseServiceDeviceProofResult.Rejected>(expired).reason
        )
        assertEquals(0, fixture.signer.calls)
    }

    @Test
    fun stale_device_key_generation_is_rejected_by_the_frozen_exact_ownership_boundary() {
        val fixture = fixture()
        val staleReference = fixture.key
        val current = fixture.keyComposition.inspect(staleReference.id)!!
        assertTrue(
            assertIs<DeviceKeyRegisterResult.Registered>(
                fixture.keyComposition.register(state(id = "other-key"))
            ).ownership.generation.value > 0
        )
        val stale = DeviceKeyReference(
            staleReference.id,
            DeviceKeyGeneration(current.generation.value + 1)
        )

        val result = fixture.service.prove(challenge(stale), now)

        assertEquals(
            DeviceKeyFailureCategory.STALE_OWNERSHIP,
            assertIs<LicenseServiceDeviceProofResult.DeviceKeyRejected>(result).category
        )
        assertEquals(0, fixture.signer.calls)
    }

    @Test
    fun nonce_and_transcript_are_bounded_before_signing() {
        assertFailsWith<IllegalArgumentException> {
            LicenseServiceDeviceProofNonce.of(ByteArray(257))
        }

        val fixture = fixture(maxTranscriptBytes = 32)
        val result = fixture.service.prove(challenge(fixture.key), now)

        assertEquals(
            LicenseServiceDeviceProofRejection.TRANSCRIPT_TOO_LARGE,
            assertIs<LicenseServiceDeviceProofResult.Rejected>(result).reason
        )
        assertEquals(0, fixture.signer.calls)
    }

    @Test
    fun nonce_is_defensively_copied_and_private_material_is_not_rendered() {
        val source = "PRIVATE-NONCE-CONTENT".encodeToByteArray()
        val nonce = LicenseServiceDeviceProofNonce.of(source)
        source.fill(0)

        assertContentEquals("PRIVATE-NONCE-CONTENT".encodeToByteArray(), nonce.copyBytes())
        val copy = nonce.copyBytes()
        copy.fill(0)
        assertContentEquals("PRIVATE-NONCE-CONTENT".encodeToByteArray(), nonce.copyBytes())
        assertFalse("PRIVATE-NONCE-CONTENT" in nonce.toString())
        assertNotEquals("PRIVATE-NONCE-CONTENT", nonce.toString())
    }

    @Test
    fun normal_observability_and_failure_rendering_do_not_expose_private_proof_material() {
        val secretSubject = "PRIVATE-SUBJECT-DO-NOT-LOG"
        val secretEnrollment = "PRIVATE-ENROLLMENT-DO-NOT-LOG"
        val secretNonce = "PRIVATE-NONCE-DO-NOT-LOG"
        val fixture = fixture()
        val challenge = challenge(
            key = fixture.key,
            subject = secretSubject,
            enrollmentId = secretEnrollment,
            nonce = secretNonce.encodeToByteArray()
        )

        val produced = assertIs<LicenseServiceDeviceProofResult.Produced>(
            fixture.service.prove(challenge, now)
        )

        val rendered = buildString {
            fixture.observed.logs.snapshot().forEach { append(it) }
            fixture.observed.diagnostics.snapshot().forEach { append(it) }
            append(produced)
        }
        assertFalse(secretSubject in rendered)
        assertFalse(secretEnrollment in rendered)
        assertFalse(secretNonce in rendered)
        assertFalse("PRIVATE-DEVICE-SIGNATURE" in rendered)
        assertTrue("LICENSE_SERVICE_DEVICE_PROOF_PRODUCED" in rendered)
    }

    @Test
    fun device_key_failure_exposes_only_structural_throwable_class() {
        val secretMessage = "PRIVATE-DEVICE-FAILURE-MESSAGE"
        val signer = RecordingSigner(
            DeviceKeyOperationResult.Failed(
                category = DeviceKeyFailureCategory.PLATFORM_REJECTED,
                throwable = IllegalStateException(secretMessage)
            )
        )
        val fixture = fixture(signer = signer)

        val failed = assertIs<LicenseServiceDeviceProofResult.DeviceKeyFailed>(
            fixture.service.prove(challenge(fixture.key), now)
        )

        assertEquals(DeviceKeyFailureCategory.PLATFORM_REJECTED, failed.category)
        assertEquals(IllegalStateException::class.java.name, failed.throwableClass)
        val rendered = buildString {
            append(failed)
            fixture.observed.logs.snapshot().forEach { append(it) }
            fixture.observed.diagnostics.snapshot().forEach { append(it) }
        }
        assertFalse(secretMessage in rendered)
    }
}
