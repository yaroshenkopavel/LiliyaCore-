package pro.liliya.app

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure

class ProductionAndroidFirstRunConfigurationOwnerContractTest {
    @After
    fun clearOwner() {
        ProductionAndroidFirstRunConfigurationOwner.clearForTests()
    }

    @Test
    fun install_once_preserves_first_explicit_configuration() {
        val first = configuration(
            ProductionAndroidFirstRunLicenseAcquisitionPort {
                error("not executed")
            }
        )
        val second = configuration(
            ProductionAndroidFirstRunLicenseAcquisitionPort {
                error("not executed")
            }
        )

        assertTrue(ProductionAndroidFirstRunConfigurationOwner.install(first))
        assertEquals(false, ProductionAndroidFirstRunConfigurationOwner.install(second))
        assertSame(first, ProductionAndroidFirstRunConfigurationOwner.current())
    }

    @Test
    fun clear_for_tests_removes_process_local_configuration() {
        val first = configuration(
            ProductionAndroidFirstRunLicenseAcquisitionPort {
                error("not executed")
            }
        )
        val second = configuration(
            ProductionAndroidFirstRunLicenseAcquisitionPort {
                error("not executed")
            }
        )

        assertTrue(ProductionAndroidFirstRunConfigurationOwner.install(first))
        ProductionAndroidFirstRunConfigurationOwner.clearForTests()
        assertNull(ProductionAndroidFirstRunConfigurationOwner.current())
        assertTrue(ProductionAndroidFirstRunConfigurationOwner.install(second))
        assertSame(second, ProductionAndroidFirstRunConfigurationOwner.current())
    }

    @Test
    fun missing_host_configuration_is_distinct_from_model_or_license_failure() {
        val result = ProductionAndroidFirstRunConfiguredAcquisition.prepareAndInstall(
            localModelFile = null
        )

        assertIs<ProductionAndroidFirstRunAcquisitionResult.HostConfigurationRequired>(result)
    }

    @Test
    fun installed_configuration_still_requires_exact_selected_model_before_acquisition() {
        var acquisitionCalls = 0
        val configuration = configuration(
            ProductionAndroidFirstRunLicenseAcquisitionPort {
                acquisitionCalls += 1
                error("must not acquire without exact model")
            }
        )
        assertTrue(ProductionAndroidFirstRunConfigurationOwner.install(configuration))

        val result = ProductionAndroidFirstRunConfiguredAcquisition.prepareAndInstall(
            localModelFile = null
        )

        assertIs<ProductionAndroidFirstRunAcquisitionResult.LocalModelRequired>(result)
        assertEquals(0, acquisitionCalls)
    }

    @Test
    fun configured_acquisition_uses_installed_port_and_preserves_service_rejection() {
        var acquisitionCalls = 0
        var productInputCalls = 0
        val configuration = ProductionAndroidFirstRunConfiguration(
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                acquisitionCalls += 1
                ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected(
                    LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED
                )
            },
            productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
                productInputCalls += 1
                error("product input must not be assembled after service rejection")
            }
        )
        assertTrue(ProductionAndroidFirstRunConfigurationOwner.install(configuration))
        val model = File.createTempFile("liliya-configured-first-run-", ".bin")
        try {
            val result = ProductionAndroidFirstRunConfiguredAcquisition.prepareAndInstall(model)

            val rejected = assertIs<
                ProductionAndroidFirstRunAcquisitionResult.LicenseServiceRejected
            >(result)
            assertEquals(LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED, rejected.reason)
            assertEquals(1, acquisitionCalls)
            assertEquals(0, productInputCalls)
        } finally {
            model.delete()
        }
    }

    private fun configuration(
        acquisition: ProductionAndroidFirstRunLicenseAcquisitionPort
    ) = ProductionAndroidFirstRunConfiguration(
        licenseAcquisition = acquisition,
        productInput = ProductionAndroidFirstRunProductInputPort { _, _ ->
            error("not executed")
        }
    )
}
