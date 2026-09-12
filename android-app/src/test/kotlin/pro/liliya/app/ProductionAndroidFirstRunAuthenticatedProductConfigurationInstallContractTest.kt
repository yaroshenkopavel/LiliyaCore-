package pro.liliya.app

import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
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

class ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallContractTest {
    @After
    fun cleanup() {
        ProductionAndroidFirstRunConfigurationOwner.clearForTests()
    }

    @Test
    fun install_is_inert_and_installs_exact_composed_configuration_once() {
        var compositionCalls = 0
        val expected = ProductionAndroidFirstRunConfiguration(
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                error("acquisition must not execute during install")
            },
            productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
                error("product input must not execute during install")
            }
        )

        val first = ProductionAndroidFirstRunAuthenticatedProductConfigurationInstall.prepareAndInstall(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationCompositionPort {
                compositionCalls += 1
                expected
            }
        )
        val second = ProductionAndroidFirstRunAuthenticatedProductConfigurationInstall.prepareAndInstall(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationCompositionPort {
                compositionCalls += 1
                error("already-configured install must not compose again")
            }
        )

        assertIs<ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed>(first)
        assertIs<ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.AlreadyConfigured>(second)
        assertEquals(1, compositionCalls)
        assertSame(expected, ProductionAndroidFirstRunConfigurationOwner.current())
    }

    @Test
    fun composition_failure_is_bounded_and_does_not_install_partial_configuration() {
        val result = ProductionAndroidFirstRunAuthenticatedProductConfigurationInstall.prepareAndInstall(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationCompositionPort {
                error("private product composition failure")
            }
        )

        assertIs<ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed>(result)
        assertEquals(null, ProductionAndroidFirstRunConfigurationOwner.current())
    }

    @Test
    fun explicit_input_path_does_not_create_bearer_or_execute_transport_during_install() {
        var credentialCalls = 0
        var engineCalls = 0
        val template = allocateWithoutConstructor<ProductionAndroidFirstRunProductInputTemplate>()

        // The template conversion itself is intentionally exercised through a controlled composition
        // seam below; this direct input test focuses on the transport/authentication inactivity contract.
        val client = LicenseHttpTransportClient(
            config = LicenseHttpTransportConfig(
                endpoint = URL("https://license.example/v1/license"),
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000
            ),
            engine = LicenseHttpEngine { _, _ ->
                engineCalls += 1
                error("transport must not execute during install")
            }
        )
        val request = LicenseServiceTransportRequest(
            protocolVersion = LicenseServiceProtocolVersion(1),
            operation = LicenseServiceOperation.ISSUE,
            productId = LicenseProductId("liliya-pro"),
            subjectReference = LicenseSubject("first-run-subject"),
            requestId = LicenseServiceRequestId("first-run-product-config-install")
        )
        val bearerFactory = ProductionAndroidLicenseBearerCredentialFactory {
            credentialCalls += 1
            LicenseHttpBearerCredential.of("attempt-secret".encodeToByteArray())
        }

        val input = ProductionAndroidFirstRunAuthenticatedProductConfigurationInput(
            licenseClient = client,
            licenseRequest = request,
            bearerCredentialFactory = bearerFactory,
            productInputTemplate = template
        )

        val result = ProductionAndroidFirstRunAuthenticatedProductConfigurationInstall.prepareAndInstall(input)

        assertIs<ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed>(result)
        assertSame(client, input.licenseClient)
        assertSame(request, input.licenseRequest)
        assertSame(bearerFactory, input.bearerCredentialFactory)
        assertSame(template, input.productInputTemplate)
        assertEquals(0, credentialCalls)
        assertEquals(0, engineCalls)
        assertIs<ProductionAndroidFirstRunConfiguration>(ProductionAndroidFirstRunConfigurationOwner.current())
    }

    private inline fun <reified T> allocateWithoutConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }
}
