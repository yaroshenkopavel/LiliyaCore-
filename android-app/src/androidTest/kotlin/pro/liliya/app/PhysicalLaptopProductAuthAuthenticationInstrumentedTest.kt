package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.licensetransport.LicenseClientTransportResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/**
 * #259 acceptance slice proving real encrypted Product Auth authentication.
 *
 * The subject is intentionally non-entitled. Receiving SUBJECT_NOT_ELIGIBLE proves that:
 * - strict HTTPS succeeded;
 * - Product Auth bearer was opened from the production encrypted Android store;
 * - request authentication was accepted by the laptop Licensing Service;
 * - the request then reached entitlement policy and was rejected there.
 *
 * Product Auth != entitlement != Authority != Execution.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLaptopProductAuthAuthenticationInstrumentedTest {

    @Test
    fun encrypted_product_auth_is_accepted_before_non_entitled_subject_is_rejected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val application =
            instrumentation.targetContext.applicationContext as LiliyaApplication

        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try {
            LicenseHttpTlsTrust.ofCertificate(caBytes)
        } finally {
            caBytes.fill(0)
        }

        val store = ProductionAndroidProductAuthEncryptedStore.create(application)
        val client = LicenseHttpTransportClient(
            LicenseHttpTransportConfig(
                endpoint = endpoint,
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 30_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = tlsTrust
            )
        )

        val result = client.execute(
            request = LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = LicenseProductId("liliya-pro"),
                subjectReference = LicenseSubject("rc259-authenticated-non-entitled-probe"),
                requestId = LicenseServiceRequestId("rc259-auth-probe")
            ),
            authorizationBearer = ProductionAndroidProductAuthCredentialAdapter
                .bearerFactory(store)
                .open()
        )

        val rejected = assertIs<LicenseClientTransportResult.ServiceRejected>(result)
        assertEquals(LicenseRemoteServiceFailure.SUBJECT_NOT_ELIGIBLE, rejected.reason)

        println(
            "LILIYA_LAPTOP_PRODUCT_AUTH_AUTHENTICATION=" +
                "{\"https\":true," +
                "\"encryptedStore\":true," +
                "\"requestAuthenticationAccepted\":true," +
                "\"entitlementRejected\":true}"
        )
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing Product Auth acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "liveLicenseEndpoint"
        const val ARG_CA_BASE64 = "liveCaBase64"
    }
}
