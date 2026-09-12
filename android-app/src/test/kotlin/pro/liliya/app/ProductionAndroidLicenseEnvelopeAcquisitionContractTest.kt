package pro.liliya.app

import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseClientTransportResult
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential
import pro.liliya.core.licensetransport.LicenseHttpEngine
import pro.liliya.core.licensetransport.LicenseHttpEngineResponse
import pro.liliya.core.licensetransport.LicenseHttpEngineResult
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

class ProductionAndroidLicenseEnvelopeAcquisitionContractTest {
    @Test
    fun signed_envelope_is_preserved_exactly() {
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            signingKeyId = LicenseKeyId("primary"),
            payload = LicenseCanonicalPayload.of(byteArrayOf(1, 2, 3)),
            signature = LicenseSignature.of(byteArrayOf(4, 5, 6))
        )

        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                LicenseClientTransportResult.Signed(envelope)
            }
        )

        val signed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(result)
        assertEquals(envelope, signed.envelope)
    }

    @Test
    fun service_rejection_is_preserved() {
        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                LicenseClientTransportResult.ServiceRejected(
                    LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED
                )
            }
        )

        val rejected =
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected>(result)
        assertEquals(LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED, rejected.reason)
    }

    @Test
    fun transport_failure_is_preserved() {
        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.TLS_FAILURE
                )
            }
        )

        val failed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result)
        assertEquals(LicenseClientTransportFailure.TLS_FAILURE, failed.reason)
    }

    @Test
    fun unexpected_transport_exception_is_bounded() {
        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                error("private transport failure")
            }
        )

        val failed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result)
        assertEquals(LicenseClientTransportFailure.PROTOCOL_FAILURE, failed.reason)
    }

    @Test
    fun authenticated_attempt_uses_exact_bearer_and_closes_credential_before_return() {
        var engineCalls = 0
        val credential = LicenseHttpBearerCredential.of("first-run-auth".encodeToByteArray())
        val client = client(
            LicenseHttpEngine { request, _ ->
                engineCalls++
                assertEquals(
                    "first-run-auth",
                    request.authorizationBearer?.toString(Charsets.UTF_8)
                )
                LicenseHttpEngineResult.Response(
                    LicenseHttpEngineResponse(
                        status = 401,
                        body = authenticationRejectedBody()
                    )
                )
            }
        )

        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = client,
            request = transportRequest(),
            authentication = ProductionAndroidLicenseBearerCredentialFactory { credential }
        )

        assertEquals(
            LicenseRemoteServiceFailure.AUTHENTICATION_REQUIRED,
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected>(result).reason
        )
        assertEquals(1, engineCalls)

        val reuse = client.execute(transportRequest(), credential)
        assertEquals(
            LicenseClientTransportFailure.INVALID_LOCAL_REQUEST,
            assertIs<LicenseClientTransportResult.Failed>(reuse).reason
        )
        assertEquals(1, engineCalls)
    }

    @Test
    fun credential_factory_failure_fails_locally_before_transport() {
        var engineCalls = 0
        val client = client(
            LicenseHttpEngine { _, _ ->
                engineCalls++
                error("credential factory failure must not reach transport")
            }
        )

        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = client,
            request = transportRequest(),
            authentication = ProductionAndroidLicenseBearerCredentialFactory {
                error("private credential provider failure")
            }
        )

        assertEquals(0, engineCalls)
        assertEquals(
            LicenseClientTransportFailure.INVALID_LOCAL_REQUEST,
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result).reason
        )
    }

    @Test
    fun transport_exception_still_closes_attempt_credential() {
        var engineCalls = 0
        val credential = LicenseHttpBearerCredential.of("throwing-auth".encodeToByteArray())
        val client = client(
            LicenseHttpEngine { _, _ ->
                engineCalls++
                error("private engine failure")
            }
        )

        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            client = client,
            request = transportRequest(),
            authentication = ProductionAndroidLicenseBearerCredentialFactory { credential }
        )

        assertEquals(
            LicenseClientTransportFailure.PROTOCOL_FAILURE,
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result).reason
        )
        assertEquals(1, engineCalls)

        val reuse = client.execute(transportRequest(), credential)
        assertEquals(
            LicenseClientTransportFailure.INVALID_LOCAL_REQUEST,
            assertIs<LicenseClientTransportResult.Failed>(reuse).reason
        )
        assertEquals(1, engineCalls)
    }

    private fun client(engine: LicenseHttpEngine) =
        LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000
            ),
            engine = engine
        )

    private fun transportRequest() =
        LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("first-run-subject"),
            requestId = LicenseServiceRequestId("first-run-authenticated-acquisition")
        )

    private fun authenticationRejectedBody(): ByteArray =
        """
            {"wireVersion":1,"kind":"rejected","reason":"AUTHENTICATION_REQUIRED"}
        """.trimIndent().encodeToByteArray()
}
