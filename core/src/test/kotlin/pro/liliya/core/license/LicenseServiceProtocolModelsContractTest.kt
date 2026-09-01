package pro.liliya.core.license

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LicenseServiceProtocolModelsContractTest {
    @Test
    fun protocol_identity_models_fail_closed_on_invalid_values() {
        assertFailsWith<IllegalArgumentException> { LicenseServiceProtocolVersion(0) }
        assertFailsWith<IllegalArgumentException> { LicenseServiceProtocolVersion(-1) }
        assertFailsWith<IllegalArgumentException> { LicenseServiceRequestId("   ") }
        assertFailsWith<IllegalArgumentException> { LicenseServiceEnrollmentId("") }
    }

    @Test
    fun opaque_payload_and_authentication_proof_are_defensively_copied() {
        val payloadSource = byteArrayOf(1, 2, 3)
        val proofSource = byteArrayOf(4, 5, 6)

        val payload = LicenseServiceOpaquePayload.of(payloadSource)
        val proof = LicenseServiceAuthenticationProof.of(proofSource)

        payloadSource[0] = 9
        proofSource[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), payload.copyBytes())
        assertContentEquals(byteArrayOf(4, 5, 6), proof.copyBytes())

        val payloadCopy = payload.copyBytes()
        val proofCopy = proof.copyBytes()
        payloadCopy[1] = 9
        proofCopy[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), payload.copyBytes())
        assertContentEquals(byteArrayOf(4, 5, 6), proof.copyBytes())
    }

    @Test
    fun empty_opaque_security_evidence_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            LicenseServiceOpaquePayload.of(byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            LicenseServiceAuthenticationProof.of(byteArrayOf())
        }
    }

    @Test
    fun auxiliary_service_state_is_structurally_separate_from_entitlement_evidence() {
        val state = LicenseServiceStateEnvelope(
            protocolVersion = LicenseServiceProtocolVersion(1),
            purpose = LicenseServiceEvidencePurpose.SECURITY_STATE,
            signingKeyId = LicenseKeyId("service-state-key"),
            payload = LicenseServiceOpaquePayload.of(byteArrayOf(1)),
            proof = LicenseServiceAuthenticationProof.of(byteArrayOf(2))
        )

        val entitlementPayload = LicenseCanonicalPayload.of(byteArrayOf(3))
        val entitlement = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("TEST-SHA256"),
            signingKeyId = LicenseKeyId("license-key"),
            payload = entitlementPayload,
            signature = LicenseSignature.of(byteArrayOf(4))
        )

        val response = LicenseServiceEntitlementResponse(
            protocolVersion = LicenseServiceProtocolVersion(1),
            requestId = LicenseServiceRequestId("request-1"),
            operation = LicenseServiceOperation.REFRESH,
            entitlementEnvelope = entitlement,
            serviceStateEnvelope = state
        )

        assertEquals(LicenseKeyId("license-key"), response.entitlementEnvelope.signingKeyId)
        assertEquals(LicenseKeyId("service-state-key"), response.serviceStateEnvelope?.signingKeyId)
        assertNotEquals(
            response.entitlementEnvelope.signingKeyId,
            response.serviceStateEnvelope?.signingKeyId
        )
    }

    @Test
    fun request_and_response_rendering_do_not_expose_opaque_evidence_bytes() {
        val secretPayload = "PRIVATE-SERVICE-STATE-PAYLOAD".encodeToByteArray()
        val secretProof = "PRIVATE-SERVICE-STATE-PROOF".encodeToByteArray()
        val secretEntitlementPayload = "PRIVATE-LICENSE-PAYLOAD".encodeToByteArray()
        val secretEntitlementSignature = "PRIVATE-LICENSE-SIGNATURE".encodeToByteArray()

        val state = LicenseServiceStateEnvelope(
            protocolVersion = LicenseServiceProtocolVersion(1),
            purpose = LicenseServiceEvidencePurpose.SECURITY_STATE,
            signingKeyId = LicenseKeyId("service-state-key"),
            payload = LicenseServiceOpaquePayload.of(secretPayload),
            proof = LicenseServiceAuthenticationProof.of(secretProof)
        )
        val entitlement = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("TEST-SHA256"),
            signingKeyId = LicenseKeyId("license-key"),
            payload = LicenseCanonicalPayload.of(secretEntitlementPayload),
            signature = LicenseSignature.of(secretEntitlementSignature)
        )
        val response = LicenseServiceEntitlementResponse(
            protocolVersion = LicenseServiceProtocolVersion(1),
            requestId = LicenseServiceRequestId("request-2"),
            operation = LicenseServiceOperation.ISSUE,
            entitlementEnvelope = entitlement,
            serviceStateEnvelope = state
        )

        val rendered = buildString {
            append(state)
            append(state.payload)
            append(state.proof)
            append(response)
        }

        assertFalse("PRIVATE-SERVICE-STATE-PAYLOAD" in rendered)
        assertFalse("PRIVATE-SERVICE-STATE-PROOF" in rendered)
        assertFalse("PRIVATE-LICENSE-PAYLOAD" in rendered)
        assertFalse("PRIVATE-LICENSE-SIGNATURE" in rendered)
        assertTrue("<redacted>" in rendered)
    }

    @Test
    fun protocol_models_do_not_claim_authentication_or_entitlement() {
        val request = LicenseServiceRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            requestId = LicenseServiceRequestId("request-3"),
            operation = LicenseServiceOperation.REFRESH,
            enrollmentId = LicenseServiceEnrollmentId("enrollment-1")
        )

        assertEquals(LicenseServiceOperation.REFRESH, request.operation)
        assertEquals(LicenseServiceEnrollmentId("enrollment-1"), request.enrollmentId)
        assertFalse(request.toString().contains("authorized", ignoreCase = true))
        assertFalse(request.toString().contains("entitled", ignoreCase = true))
    }
}
