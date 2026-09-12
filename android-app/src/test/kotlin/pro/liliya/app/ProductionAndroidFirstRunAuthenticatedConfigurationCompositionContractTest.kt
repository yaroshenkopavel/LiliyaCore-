package pro.liliya.app

import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential
import pro.liliya.core.licensetransport.LicenseHttpEngine
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

class ProductionAndroidFirstRunAuthenticatedConfigurationCompositionContractTest {
    @Test
    fun composition_is_inert_and_preserves_exact_product_input_owner() {
        var credentialCalls = 0
        var engineCalls = 0
        var productInputCalls = 0
        val productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
            productInputCalls++
            error("product input must not execute during configuration composition")
        }
        val configuration = ProductionAndroidFirstRunAuthenticatedConfigurationComposition.create(
            ProductionAndroidFirstRunAuthenticatedConfigurationInput(
                licenseClient = client(
                    LicenseHttpEngine { _, _ ->
                        engineCalls++
                        error("transport must not execute during configuration composition")
                    }
                ),
                licenseRequest = transportRequest(),
                bearerCredentialFactory = ProductionAndroidLicenseBearerCredentialFactory {
                    credentialCalls++
                    LicenseHttpBearerCredential.of("attempt-secret".encodeToByteArray())
                },
                productInput = productInput
            )
        )

        assertEquals(0, credentialCalls)
        assertEquals(0, engineCalls)
        assertEquals(0, productInputCalls)
        assertSame(productInput, configuration.productInput)
    }

    @Test
    fun each_license_acquisition_attempt_requests_fresh_bearer_without_touching_product_input() {
        var credentialCalls = 0
        var engineCalls = 0
        var productInputCalls = 0
        val configuration = ProductionAndroidFirstRunAuthenticatedConfigurationComposition.create(
            ProductionAndroidFirstRunAuthenticatedConfigurationInput(
                licenseClient = client(
                    LicenseHttpEngine { request, _ ->
                        engineCalls++
                        assertEquals(
                            "attempt-$engineCalls",
                            request.authorizationBearer?.toString(Charsets.UTF_8)
                        )
                        error("bounded transport failure")
                    }
                ),
                licenseRequest = transportRequest(),
                bearerCredentialFactory = ProductionAndroidLicenseBearerCredentialFactory {
                    credentialCalls++
                    LicenseHttpBearerCredential.of(
                        "attempt-$credentialCalls".encodeToByteArray()
                    )
                },
                productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
                    productInputCalls++
                    error("failed license acquisition must not reach product input")
                }
            )
        )

        repeat(2) {
            val result = configuration.licenseAcquisition.acquire()
            assertEquals(
                LicenseClientTransportFailure.PROTOCOL_FAILURE,
                assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result).reason
            )
        }

        assertEquals(2, credentialCalls)
        assertEquals(2, engineCalls)
        assertEquals(0, productInputCalls)
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
            requestId = LicenseServiceRequestId("first-run-authenticated-composition")
        )
}
