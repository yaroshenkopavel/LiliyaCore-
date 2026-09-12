package pro.liliya.app

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

class ProductionAndroidFirstRunProductProfileProvisioningContractTest {
    @Test
    fun `profile provisioning preserves exact caller-owned inputs and remains credential-inert`() {
        val template = allocateWithoutConstructor(ProductionAndroidFirstRunProductInputTemplate::class.java)
        val transport = LicenseHttpTransportConfig(
            endpoint = URL("https://license.example.test/v1/issue"),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 2_000
        )
        val request = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya"),
            subjectReference = LicenseSubject("subject-1"),
            requestId = LicenseServiceRequestId("request-1")
        )
        val profile = ProductionAndroidFirstRunProductProfile(
            transport = transport,
            licenseRequest = request,
            productInputTemplate = template
        )
        var sourceLoads = 0
        var secretOpens = 0
        var captured: ProductionAndroidFirstRunAuthenticatedProductConfigurationInput? = null

        val result = ProductionAndroidFirstRunProductProfileProvisioning.prepareAndInstall(
            source = ProductionAndroidFirstRunProductProfileSource {
                sourceLoads += 1
                profile
            },
            credentialSource = ProductionAndroidProductAuthCredentialSource {
                secretOpens += 1
                "credential".encodeToByteArray()
            },
            clientFactory = { LicenseHttpTransportClient(it) },
            install = {
                captured = it
                ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed
            }
        )

        assertEquals(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed,
            result
        )
        assertEquals(1, sourceLoads)
        assertEquals(0, secretOpens)
        assertSame(request, captured!!.licenseRequest)
        assertSame(template, captured!!.productInputTemplate)

        captured!!.bearerCredentialFactory.create().use { credential ->
            assertEquals("LicenseHttpBearerCredential(<redacted>)", credential.toString())
        }
        assertEquals(1, secretOpens)
    }

    @Test
    fun `profile source failure is fail closed and never reaches credential source or install`() {
        var secretOpens = 0
        var installs = 0

        val result = ProductionAndroidFirstRunProductProfileProvisioning.prepareAndInstall(
            source = ProductionAndroidFirstRunProductProfileSource {
                throw IllegalStateException("profile unavailable")
            },
            credentialSource = ProductionAndroidProductAuthCredentialSource {
                secretOpens += 1
                byteArrayOf(1)
            },
            clientFactory = { LicenseHttpTransportClient(it) },
            install = {
                installs += 1
                ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed
            }
        )

        assertEquals(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed,
            result
        )
        assertEquals(0, secretOpens)
        assertEquals(0, installs)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(type) as T
    }
}
