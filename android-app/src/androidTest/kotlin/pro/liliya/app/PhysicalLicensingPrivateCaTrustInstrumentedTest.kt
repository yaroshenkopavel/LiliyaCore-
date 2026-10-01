package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * Physical acceptance for #573 only.
 *
 * Uses the public deployment CA test asset and intentionally sends no Product Auth bearer.
 * Reaching AUTHENTICATION_REQUIRED proves that Android completed TCP/TLS/hostname verification
 * through the patched production LicenseHttpTransportClient. It does not grant entitlement,
 * Authority, or Execution and it does not retain any secret.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalLicensingPrivateCaTrustInstrumentedTest {
    @Test
    fun explicit_private_ca_reaches_laptop_licensing_endpoint_without_tls_bypass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ca = instrumentation.context.assets.open("licensing-ca.crt").use {
            it.readBytes()
        }
        val client = LicenseHttpTransportClient(
            LicenseHttpTransportConfig(
                endpoint = URL("https://10.124.119.130:8443/v1/license"),
                connectTimeoutMillis = 10_000,
                readTimeoutMillis = 10_000,
                developmentAllowInsecureHttp = false,
                tlsTrust = LicenseHttpTlsTrust.ofCertificate(ca)
            )
        )
        ca.fill(0)

        val result = client.execute(
            LicenseServiceTransportRequest(
                protocolVersion = LicenseServiceProtocolVersion(1),
                operation = LicenseServiceOperation.ISSUE,
                productId = LicenseProductId("liliya-pro"),
                subjectReference = LicenseSubject("rc573-physical-tls-probe"),
                requestId = LicenseServiceRequestId("rc573-no-secret")
            )
        )

        assertEquals(
            LicenseRemoteServiceFailure.AUTHENTICATION_REQUIRED,
            assertIs<LicenseClientTransportResult.ServiceRejected>(result).reason
        )
    }
}
