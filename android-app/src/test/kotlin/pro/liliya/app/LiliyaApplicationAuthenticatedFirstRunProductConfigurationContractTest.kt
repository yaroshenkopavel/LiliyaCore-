package pro.liliya.app

import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.core.license.LicenseProductId
import pro.liliya.core.license.LicenseServiceOperation
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseSubject
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential
import pro.liliya.core.licensetransport.LicenseHttpEngine
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest
import sun.misc.Unsafe

class LiliyaApplicationAuthenticatedFirstRunProductConfigurationContractTest {
    @After
    fun cleanup() {
        ProductionAndroidFirstRunConfigurationOwner.clearForTests()
        ProductionAndroidFirstRunProductProfileSourceOwner.clearForTests()
    }

    @Test
    fun application_bridge_installs_authenticated_product_configuration_without_executing_it() {
        var credentialCalls = 0
        var engineCalls = 0
        val application = allocateWithoutConstructor<LiliyaApplication>()
        val template = allocateWithoutConstructor<ProductionAndroidFirstRunProductInputTemplate>()
        val client = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000
            ),
            engine = LicenseHttpEngine { _, _ ->
                engineCalls += 1
                error("transport must not execute during application configuration")
            }
        )
        val request = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("application-first-run-subject"),
            requestId = LicenseServiceRequestId("application-first-run-configuration")
        )
        val bearerFactory = ProductionAndroidLicenseBearerCredentialFactory {
            credentialCalls += 1
            LicenseHttpBearerCredential.of("attempt-secret".encodeToByteArray())
        }

        val result = application.configureAuthenticatedFirstRunProduct(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInput(
                licenseClient = client,
                licenseRequest = request,
                bearerCredentialFactory = bearerFactory,
                productInputTemplate = template
            )
        )

        assertIs<ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed>(result)
        assertIs<ProductionAndroidFirstRunConfiguration>(ProductionAndroidFirstRunConfigurationOwner.current())
        assertEquals(0, credentialCalls)
        assertEquals(0, engineCalls)
    }

    @Test
    fun application_bridge_installs_explicit_product_profile_source_once_without_loading_it() {
        var loads = 0
        val application = allocateWithoutConstructor<LiliyaApplication>()
        val source = ProductionAndroidFirstRunProductProfileSource {
            loads += 1
            error("profile source must not load during installation")
        }
        val replacement = ProductionAndroidFirstRunProductProfileSource {
            error("replacement source must not be installed or loaded")
        }

        assertTrue(application.configureFirstRunProductProfile(source))
        assertSame(source, ProductionAndroidFirstRunProductProfileSourceOwner.current())
        assertEquals(0, loads)

        assertFalse(application.configureFirstRunProductProfile(replacement))
        assertSame(source, ProductionAndroidFirstRunProductProfileSourceOwner.current())
        assertEquals(0, loads)
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
