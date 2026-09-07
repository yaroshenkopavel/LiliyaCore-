package pro.liliya.core.licensetransport

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject

class LicenseTransportWireCodecContractTest {
    @Test
    fun request_encoding_matches_backend_s6_1_field_shape() {
        val request = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("subject-private"),
            requestId = LicenseServiceRequestId("attempt-001")
        )

        val encoded = LicenseTransportWireCodec.encodeRequest(request)
            .decodeToString()

        assertEquals(
            """{"wireVersion":1,"kind":"request","protocolVersion":1,"operation":"ISSUE","productId":"liliya-pro","subjectReference":"subject-private","requestId":"attempt-001"}""",
            encoded
        )
    }

    @Test
    fun signed_success_decoding_preserves_exact_payload_and_signature_bytes() {
        val payload = byteArrayOf(0, 1, 2, 3, 127, -1)
        val signature = byteArrayOf(9, 8, 7, 6, -2)
        val wire = """
            {
              "wireVersion":1,
              "kind":"success",
              "schemaVersion":1,
              "algorithm":"ECDSA-P256-SHA256",
              "keyReference":"openbao-prod-v2",
              "payloadBase64":"${Base64.getEncoder().encodeToString(payload)}",
              "signatureBase64":"${Base64.getEncoder().encodeToString(signature)}"
            }
        """.trimIndent().encodeToByteArray()

        val decoded = assertIs<LicenseTransportWireDecodeResult.Decoded<LicenseClientTransportResult>>(
            LicenseTransportWireCodec.decodeResponse(wire)
        ).value
        val signed = assertIs<LicenseClientTransportResult.Signed>(decoded)

        assertEquals("ECDSA-P256-SHA256", signed.envelope.algorithm.value)
        assertEquals("openbao-prod-v2", signed.envelope.signingKeyId.value)
        assertContentEquals(payload, signed.envelope.payload.copyBytes())
        assertContentEquals(signature, signed.envelope.signature.copyBytes())
    }

    @Test
    fun unknown_remote_failure_is_protocol_failure() {
        val wire = """
            {"wireVersion":1,"kind":"rejected","reason":"NEW_UNKNOWN_FAILURE"}
        """.trimIndent().encodeToByteArray()

        assertIs<LicenseTransportWireDecodeResult.ProtocolFailure>(
            LicenseTransportWireCodec.decodeResponse(wire)
        )
    }

    @Test
    fun request_rendering_redacts_private_scope() {
        val rendered = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.REFRESH,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("PRIVATE-SUBJECT"),
            requestId = LicenseServiceRequestId("PRIVATE-REQUEST")
        ).toString()

        assertEquals(false, "PRIVATE-SUBJECT" in rendered)
        assertEquals(false, "PRIVATE-REQUEST" in rendered)
    }
}
