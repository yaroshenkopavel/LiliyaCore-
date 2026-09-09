package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionFailure
import pro.liliya.android.runtime.AndroidProductRuntimeAdmissionResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupProvisioningResult

class ProductionAndroidRuntimeStartupInstallContractTest {
    @After
    fun cleanup() {
        ProductionAndroidRuntimeConfiguration.clearForTests()
        ProductionAndroidAppTrustedWiring.clearForTests()
    }

    @Test
    fun rejected_provisioning_does_not_publish_runtime_configuration() {
        val result = ProductionAndroidRuntimeStartupInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupPreparePort {
                AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected(
                    AndroidProductRuntimeAdmissionResult.Rejected(
                        AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                    )
                )
            }
        )

        assertIs<ProductionAndroidRuntimeStartupInstallResult.Rejected>(result)
        assertEquals(null, ProductionAndroidRuntimeConfiguration.current())
    }

    @Test
    fun exact_prepared_sources_are_published_once_without_replacement() {
        val first = ProductionAndroidRuntimeWiringSources(
            admission = {
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                )
            },
            preparedInputs = { error("must not execute") }
        )
        val second = ProductionAndroidRuntimeWiringSources(
            admission = {
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.INTERNAL_FAILURE
                )
            },
            preparedInputs = { error("must not execute") }
        )

        assertIs<ProductionAndroidRuntimeStartupInstallResult.Installed>(
            ProductionAndroidRuntimeStartupInstall.installPreparedSources(first)
        )
        assertIs<ProductionAndroidRuntimeStartupInstallResult.AlreadyConfigured>(
            ProductionAndroidRuntimeStartupInstall.installPreparedSources(second)
        )
        assertTrue(ProductionAndroidRuntimeConfiguration.current() === first)
    }

    @Test
    fun existing_configuration_blocks_reprovisioning_before_any_stage_runs() {
        val exact = ProductionAndroidRuntimeWiringSources(
            admission = {
                AndroidProductRuntimeAdmissionResult.Rejected(
                    AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                )
            },
            preparedInputs = { error("must not execute") }
        )
        assertTrue(ProductionAndroidRuntimeConfiguration.install(exact))
        var prepareCalls = 0

        val result = ProductionAndroidRuntimeStartupInstall.prepareAndInstall(
            ProductionAndroidRuntimeStartupPreparePort {
                prepareCalls += 1
                AndroidProductRuntimeStartupProvisioningResult.AdmissionRejected(
                    AndroidProductRuntimeAdmissionResult.Rejected(
                        AndroidProductRuntimeAdmissionFailure.AUTHORITY_DENIED
                    )
                )
            }
        )

        assertIs<ProductionAndroidRuntimeStartupInstallResult.AlreadyConfigured>(result)
        assertEquals(0, prepareCalls)
        assertTrue(ProductionAndroidRuntimeConfiguration.current() === exact)
    }
}
